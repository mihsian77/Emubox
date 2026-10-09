package com.emuhub.cn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_SOURCE_CATALOG_URL =
    "https://raw.githubusercontent.com/Rodrig02005/EmuHub-APP/main/sources.json"

data class TurnipSource(
    val id: String,
    val name: String,
    val apiUrl: String,
    val description: String,
    val experimental: Boolean,
    val supportedSeries: Set<String>,
    val supportedModels: Set<String> = emptySet(),
    /**
     * 要求的 GPU 厂商（小写："adreno" / "mali"）。null 表示不限制。
     * 用于 Mali 等非 Adreno GPU 的专用驱动源过滤。
     */
    val requiredVendor: String? = null,
    val filters: Map<String, List<String>>,
    val assetIncludes: Map<String, List<String>> = emptyMap(),
    val assetExcludes: Map<String, List<String>> = emptyMap(),
    /** 目标运行环境：eden / winlator / termux / 空=通用（来源选择预览用） */
    val targetEmulator: String = "",
    /** 仓库星标数，来源选择预览展示活跃度 */
    val stars: Long = 0L
)

data class QualcommSource(
    val id: String,
    val name: String,
    val apiUrl: String,
    val description: String,
    val experimental: Boolean,
    val filters: List<String>,
    /** 仓库星标数，来源选择预览展示活跃度 */
    val stars: Long = 0L
)

data class ComponentSource(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val description: String,
    val experimental: Boolean
)

data class SourceCatalog(
    val turnipSources: List<TurnipSource>,
    val qualcommSources: List<QualcommSource>,
    val componentSources: List<ComponentSource>,
    val isRemote: Boolean = false
) {
    /**
     * Returns Turnip sources compatible with the detected GPU.
     * Filters by requiredVendor (Mali/Adreno) and supportedSeries (6xx/7xx/8xx).
     * 具体型号不再过滤（型号差异由排序表达，避免二次过滤把已匹配源排除）。
     */
    fun compatibleTurnipSources(
        adrenoSeries: String?,
        gpuModel: String? = null,
        gpuVendor: String? = null
    ): List<TurnipSource> {
        // 第一步：按 GPU 厂商过滤
        val vendorFiltered = if (gpuVendor.isNullOrBlank()) {
            turnipSources
        } else {
            turnipSources.filter { source ->
                source.requiredVendor == null || source.requiredVendor.equals(gpuVendor, ignoreCase = true)
            }
        }

        // 第二步：按 Adreno series 过滤（非 Adreno GPU 的 series 为 unknown，跳过）
        if (adrenoSeries.isNullOrBlank() || adrenoSeries == "unknown") return vendorFiltered
        return vendorFiltered.filter { source ->
            source.supportedSeries.isEmpty() || adrenoSeries in source.supportedSeries
        }.ifEmpty { vendorFiltered }
    }
}

private val sourceCatalogClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

object SourceCatalogRepository {
    suspend fun load(url: String = SettingsManager.getSourceCatalogUrl()): SourceCatalog =
        withContext(Dispatchers.IO) {
            try {
                val acceleratedUrl = Accelerator.rewriteUrl(url)
                val request = Request.Builder().url(acceleratedUrl).build()
                sourceCatalogClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext builtInCatalog()
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext builtInCatalog()
                    // 远程 catalog 为主，内置中独有的组件源（按 id 去重）追加进去，
                    // 避免远程 sources.json 覆盖我们新增的组件仓库。
                    val remote = parseCatalog(body)
                    val remoteIds = remote.componentSources.map { it.id }.toSet()
                    val extra = builtInCatalog().componentSources.filter { it.id !in remoteIds }
                    remote.copy(componentSources = remote.componentSources + extra, isRemote = true)
                }
            } catch (_: Exception) {
                builtInCatalog()
            }
        }

    private fun parseCatalog(json: String): SourceCatalog {
        val root = JSONObject(json)
        val turnip = root.optJSONArray("turnipSources") ?: JSONArray()
        val qualcomm = root.optJSONArray("qualcommSources") ?: JSONArray()
        val components = root.optJSONArray("componentSources") ?: JSONArray()

        val turnipSources = buildList {
            for (i in 0 until turnip.length()) {
                val obj = turnip.optJSONObject(i) ?: continue
                if (!obj.optBoolean("enabled", true)) continue

                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val apiUrl = obj.optString("apiUrl").trim()
                if (id.isBlank() || name.isBlank() || apiUrl.isBlank()) continue

                val seriesArray = obj.optJSONArray("supportedSeries") ?: JSONArray()
                val supportedSeries = buildSet {
                    for (s in 0 until seriesArray.length()) {
                        seriesArray.optString(s).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }

                val modelsArray = obj.optJSONArray("supportedModels") ?: JSONArray()
                val supportedModels = buildSet {
                    for (m in 0 until modelsArray.length()) {
                        modelsArray.optString(m).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }

                val requiredVendor = obj.optString("requiredVendor").takeIf { it.isNotBlank() }

                val filtersObject = obj.optJSONObject("filters") ?: JSONObject()
                val filters = parseStringListMap(filtersObject)
                val assetIncludes = parseStringListMap(obj.optJSONObject("assetIncludes") ?: JSONObject())
                val assetExcludes = parseStringListMap(obj.optJSONObject("assetExcludes") ?: JSONObject())

                add(
                    TurnipSource(
                        id = id,
                        name = name,
                        apiUrl = apiUrl,
                        description = obj.optString("description"),
                        experimental = obj.optBoolean("experimental", false),
                        supportedSeries = supportedSeries,
                        supportedModels = supportedModels,
                        requiredVendor = requiredVendor,
                        filters = filters,
                        assetIncludes = assetIncludes,
                        assetExcludes = assetExcludes,
                        targetEmulator = obj.optString("targetEmulator"),
                        stars = obj.optLong("stars", 0L)
                    )
                )
            }
        }

        val qualcommSources = buildList {
            for (i in 0 until qualcomm.length()) {
                val obj = qualcomm.optJSONObject(i) ?: continue
                if (!obj.optBoolean("enabled", true)) continue

                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val apiUrl = obj.optString("apiUrl").trim()
                if (id.isBlank() || name.isBlank() || apiUrl.isBlank()) continue

                val filterArray = obj.optJSONArray("filters") ?: JSONArray()
                val filters = buildList {
                    for (f in 0 until filterArray.length()) {
                        filterArray.optString(f).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }

                add(
                    QualcommSource(
                        id = id,
                        name = name,
                        apiUrl = apiUrl,
                        description = obj.optString("description"),
                        experimental = obj.optBoolean("experimental", false),
                        filters = filters,
                        stars = obj.optLong("stars", 0L)
                    )
                )
            }
        }

        val componentSources = buildList {
            for (i in 0 until components.length()) {
                val obj = components.optJSONObject(i) ?: continue
                if (!obj.optBoolean("enabled", true)) continue

                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val manifestUrl = obj.optString("manifestUrl").trim()
                if (id.isBlank() || name.isBlank() || manifestUrl.isBlank()) continue

                add(
                    ComponentSource(
                        id = id,
                        name = name,
                        manifestUrl = manifestUrl,
                        description = obj.optString("description"),
                        experimental = obj.optBoolean("experimental", false)
                    )
                )
            }
        }

        val fallback = builtInCatalog()
        return SourceCatalog(
            turnipSources = turnipSources.ifEmpty { fallback.turnipSources },
            qualcommSources = qualcommSources.ifEmpty { fallback.qualcommSources },
            componentSources = componentSources.ifEmpty { fallback.componentSources }
        )
    }

    private fun parseStringListMap(obj: JSONObject): Map<String, List<String>> = buildMap {
        obj.keys().forEach { key ->
            val values = obj.optJSONArray(key) ?: return@forEach
            put(
                key,
                buildList {
                    for (i in 0 until values.length()) {
                        values.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            )
        }
    }

    fun builtInCatalog(): SourceCatalog = SourceCatalog(
        turnipSources = listOf(
            TurnipSource(
                id = "stevenmxz",
                name = "StevenMXZ",
                apiUrl = "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases",
                description = "稳定版 Turnip 构建，含 Gen8 系列与高通驱动",
                experimental = false,
                supportedSeries = setOf("6xx", "7xx", "8xx"),
                filters = mapOf(
                    "6xx" to listOf("Turnip v26"),
                    "7xx" to listOf("Turnip v26"),
                    "8xx" to listOf("Turnip Gen8")
                )
            ),
            TurnipSource(
                id = "whitebelyash",
                name = "whitebelyash",
                apiUrl = "https://api.github.com/repos/whitebelyash/AdrenoToolsDrivers/releases",
                description = "Mainline/稳定版 Turnip 构建，适配 Adreno 全系列",
                experimental = true,
                supportedSeries = setOf("6xx", "7xx", "8xx"),
                filters = mapOf(
                    "default" to listOf("A8XX", "Mainline Turnip", "Stable Turnip", "tu_", "stu_")
                )
            ),
            TurnipSource(
                id = "k11mch1",
                name = "K11MCH1",
                apiUrl = "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases",
                description = "老牌 AdrenoTools Turnip 驱动仓库，更新稳定",
                experimental = false,
                supportedSeries = setOf("6xx", "7xx"),
                filters = mapOf("default" to listOf("Turnip"))
            ),
            TurnipSource(
                id = "mrpurple",
                name = "MrPurple",
                apiUrl = "https://api.github.com/repos/MrPurple666/purple-turnip/releases",
                description = "A6xx/A7xx/A8xx 定制 Turnip 构建合集",
                experimental = true,
                supportedSeries = setOf("6xx", "7xx", "8xx"),
                filters = mapOf("default" to listOf("Turnip", "vturnip", "turnip_mrpurple"))
            ),
            TurnipSource(
                id = "banner",
                name = "The412Banner",
                apiUrl = "https://api.github.com/repos/The412Banner/Banners-Turnip/releases",
                description = "自动构建的 Mesa Turnip 前沿版本",
                experimental = true,
                supportedSeries = setOf("6xx", "7xx", "8xx"),
                filters = mapOf("default" to listOf("Turnip", "Mesa", "Adreno")),
                assetIncludes = mapOf("8xx" to listOf("A8xx")),
                assetExcludes = mapOf(
                    "6xx" to listOf("A8xx", "710-720-Test"),
                    "7xx" to listOf("A8xx", "710-720-Test")
                )
            ),
            TurnipSource(
                id = "s1mptom",
                name = "s1mptom (A830/A840 eden)",
                apiUrl = "https://api.github.com/repos/s1mptom/freedreno_turnip-CI/releases",
                description = "A830/A840 专用 Mesa 构建，为 Eden 模拟器适配优化",
                experimental = true,
                supportedSeries = setOf("8xx"),
                supportedModels = setOf("830", "840"),
                filters = mapOf("default" to listOf("Turnip", "Mesa"))
            ),
            TurnipSource(
                id = "wintermist010-a810",
                name = "WinterMist010 (A810/A812)",
                apiUrl = "https://api.github.com/repos/WinterMist010/AdrenoToolsDriversA810/releases",
                description = "Adreno A810/A812 实验性 Turnip 构建",
                experimental = true,
                supportedSeries = setOf("8xx"),
                supportedModels = setOf("810", "812"),
                filters = mapOf("default" to listOf("Turnip", "A81"))
            ),
            TurnipSource(
                id = "diskdvd-a8xx",
                name = "DiskDVD (A810/A829)",
                apiUrl = "https://api.github.com/repos/DiskDVD/TurniptoolsA8XX/releases",
                description = "whitebelyash 分支，针对 A810/A829 额外补丁，活跃维护",
                experimental = true,
                supportedSeries = setOf("8xx"),
                supportedModels = setOf("810", "829"),
                filters = mapOf("default" to listOf("A8XX", "Turnip"))
            ),
            TurnipSource(
                id = "vauzi17-710",
                name = "Vauzi-17 (710/720/722)",
                apiUrl = "https://api.github.com/repos/Vauzi-17/710/releases",
                description = "Adreno 710/720/722 成熟 Turnip 构建，含 Winlator glibc 专用版",
                experimental = false,
                supportedSeries = setOf("7xx"),
                supportedModels = setOf("710", "720", "722"),
                filters = mapOf("default" to listOf("Turnip", "710", "720"))
            ),
            TurnipSource(
                id = "panvk-g720",
                name = "PanVK (Mali-G720 实验性)",
                apiUrl = "https://api.github.com/repos/wonderkast02/panvk-g720-kbase-csf/releases",
                description = "PanVK Vulkan 驱动 for Mali-G720 (天玑 9300/9200+)。实验性，上游暂未官方支持 Mali。",
                experimental = true,
                supportedSeries = emptySet(),
                supportedModels = setOf("720"),
                requiredVendor = "mali",
                filters = mapOf("default" to listOf("panvk", "vulkan"))
            )
        ),
        qualcommSources = listOf(
            QualcommSource(
                id = "stevenmxz-qualcomm",
                name = "StevenMXZ",
                apiUrl = "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases",
                description = "高通闭源驱动包，由 StevenMXZ 发布",
                experimental = false,
                filters = listOf("Qualcomm")
            ),
            QualcommSource(
                id = "k11mch1-qualcomm",
                name = "K11MCH1",
                apiUrl = "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases",
                description = "高通设备提取的系统驱动包",
                experimental = false,
                filters = listOf("Qualcomm Driver", "Qualcomm")
            )
        ),
        componentSources = listOf(
            ComponentSource(
                id = "winnative",
                name = "WinNative-Emu",
                manifestUrl = "https://raw.githubusercontent.com/WinNative-Emu/Components/refs/heads/main/contents.json",
                description = "EmuHub 默认组件清单",
                experimental = false
            ),
            ComponentSource(
                id = "xnick",
                name = "Xnick Nightly",
                manifestUrl = "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json",
                description = "WinNative/Winlator 自动构建组件",
                experimental = true
            ),
            ComponentSource(
                id = "banner-components",
                name = "The412Banner",
                manifestUrl = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/contents.json",
                description = "Winlator 夜间构建组件清单",
                experimental = true
            ),
            ComponentSource(
                id = "ref4ik",
                name = "REF4IK",
                manifestUrl = "https://github.com/REF4IK/Components-Adrenotools-/releases/download/1/contents.json",
                description = "Adrenotools 专用组件构建",
                experimental = true
            ),
            ComponentSource(
                id = "arihany",
                name = "Arihany WCP Hub",
                manifestUrl = "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json",
                description = "WCP 组件包合集（DXVK/FEXCore/VKD3D）",
                experimental = true
            )
        )
    )
}
