package com.emuhub.cn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 驱动注册表中的单个仓库条目 */
data class DriverRegistryEntry(
    val id: String,
    val name: String,
    val owner: String,
    val repo: String,
    val apiUrl: String,
    val gpuVendor: String,          // adreno / mali / xclipse
    val gpuArch: List<String>,      // 6xx,7xx,8xx / valhall-v9,valhall-v10,bifrost / rdna
    val gpuModels: List<String>,    // 具体型号，空=通用
    val driverType: String,         // turnip / panvk / radv / qualcomm / component
    val componentType: String? = null, // 组件分类（DXVK/VKD3D…），仅 driver_type=component
    val frontend: String?,          // Mali: CSF / JM，其他 null
    val targetEmulator: String,     // generic / winlator / eden
    val packageFormat: String,      // adpkg / zip / so / magisk / wcp
    val maturity: String,           // stable / beta / alpha / ci / experimental
    val stars: Int,
    val description: String,
    val notes: String,
    val companion: List<String>,    // 配套组件 id（指向 component 类型条目）
    val filters: Map<String, List<String>>
)

/** 匹配结果 */
data class DriverMatchResult(
    val entry: DriverRegistryEntry,
    val score: Int,
    val recommended: Boolean,
    val matchReason: String
)

/**
 * 驱动注册表仓库：远程加载 driver-registry.json，根据设备 GPU 自动匹配。
 *
 * 修改原因：上游硬编码驱动源且只有 Adreno，新增 30+ 仓库（含 Mali PanVK 全系列），
 *           需要结构化注册表 + 自动匹配，用户不用自己判断该下哪个驱动。
 * 影响范围：仅新增文件，不改动现有 SourceCatalog 逻辑；UI 层后续接入。
 * 回滚方法：删除本文件，DriverHubScreen 继续使用原有 SourceCatalog。
 */
object DriverRegistryRepository {

    private const val REGISTRY_URL_PRIMARY =
        "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/driver-registry.json"
    private const val REGISTRY_URL_JSDELIVR =
        "https://cdn.jsdelivr.net/gh/mihsian77/EmuHub-CN@main/driver-registry.json"

    /** 成熟度排序权重，越小越优先 */
    private val MATURITY_ORDER = mapOf(
        "stable" to 0, "beta" to 1, "ci" to 2, "alpha" to 3, "experimental" to 4
    )

    /** 非驱动类型：这些条目归入组件专区，不参与驱动匹配 */
    val COMPONENT_TYPES = setOf("component", "companion")

    /** Mali 型号 → (架构, frontend) */
    private val MALI_ARCH_MAP = mapOf(
        // Bifrost, JM
        "G51" to ("bifrost" to "JM"),
        "G52" to ("bifrost" to "JM"),
        "G71" to ("bifrost" to "JM"),
        "G72" to ("bifrost" to "JM"),
        "G76" to ("bifrost" to "JM"),
        // Valhall v9, JM
        "G57" to ("valhall-v9" to "JM"),
        "G68" to ("valhall-v9" to "JM"),
        "G77" to ("valhall-v9" to "JM"),
        "G78" to ("valhall-v9" to "JM"),
        // Valhall v10+, CSF
        "G610" to ("valhall-v10" to "CSF"),
        "G615" to ("valhall-v10" to "CSF"),
        "G710" to ("valhall-v10" to "CSF"),
        "G715" to ("valhall-v10" to "CSF"),
        "G720" to ("valhall-v10" to "CSF"),
        "G725" to ("valhall-v10" to "CSF"),
        "G920" to ("valhall-v10" to "CSF"),
        "G925" to ("valhall-v10" to "CSF")
    )

    @Volatile
    private var cachedEntries: List<DriverRegistryEntry>? = null

    /** 加载注册表，优先 GitHub Raw，失败走 jsDelivr，再失败用内置精简表 */
    suspend fun load(forceRefresh: Boolean = false): List<DriverRegistryEntry> {
        cachedEntries?.let { if (!forceRefresh) return it }
        return withContext(Dispatchers.IO) {
            val entries = fetchFromUrl(REGISTRY_URL_PRIMARY)
                ?: fetchFromUrl(REGISTRY_URL_JSDELIVR)
                ?: builtinFallback()
            cachedEntries = entries
            entries
        }
    }

    private fun fetchFromUrl(urlStr: String): List<DriverRegistryEntry>? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "EmuHub-CN")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseRegistry(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRegistry(body: String): List<DriverRegistryEntry>? {
        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("repositories")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val filtersObj = o.optJSONObject("filters")
                val filters = mutableMapOf<String, List<String>>()
                filtersObj?.keys()?.forEach { key ->
                    filters[key] = filtersObj.getJSONArray(key).let { ja ->
                        (0 until ja.length()).map { ja.getString(it) }
                    }
                }
                DriverRegistryEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    owner = o.getString("owner"),
                    repo = o.getString("repo"),
                    apiUrl = o.getString("api_url"),
                    gpuVendor = o.getString("gpu_vendor"),
                    gpuArch = o.getJSONArray("gpu_arch").let { ja ->
                        (0 until ja.length()).map { ja.getString(it) }
                    },
                    gpuModels = o.getJSONArray("gpu_models").let { ja ->
                        (0 until ja.length()).map { ja.getString(it) }
                    },
                    driverType = o.getString("driver_type"),
                    componentType = if (o.isNull("component_type")) null
                        else o.optString("component_type").takeIf { it.isNotBlank() },
                    frontend = if (o.isNull("frontend")) null else o.getString("frontend"),
                    targetEmulator = o.optString("target_emulator", "generic"),
                    packageFormat = o.optString("package_format", "zip"),
                    maturity = o.optString("maturity", "experimental"),
                    stars = o.optInt("stars", 0),
                    description = o.optString("description", ""),
                    notes = o.optString("notes", ""),
                    companion = o.getJSONArray("companion").let { ja ->
                        (0 until ja.length()).map { ja.getString(it) }
                    },
                    filters = filters
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 根据设备 GPU 信息匹配驱动，返回按推荐度排序的结果。
     *
     * 匹配优先级（修正：型号不命中不再排除，避免"整理的仓库不显示"）：
     * 1. GPU 厂商必须匹配
     * 2. 具体型号精确匹配（+100），不匹配不排除（特定型号源仅降排序）
     * 3. 架构系列匹配（+50）；架构不匹配仍显示但排序靠后（-50）
     * 4. 通用驱动（型号和架构都为空）（+20）
     * 5. Mali frontend 必须匹配（不匹配排除，架构差异真实不兼容）
     * 6. 成熟度权重 + star 数加权
     */
    fun match(deviceInfo: DeviceInfo?, entries: List<DriverRegistryEntry>): List<DriverMatchResult> {
        if (deviceInfo == null) return emptyList()

        val vendorKey = when (deviceInfo.gpuVendor) {
            GpuVendor.ADRENO -> "adreno"
            GpuVendor.MALI -> "mali"
            GpuVendor.XCLIPSE -> "xclipse"
            else -> return emptyList()
        }

        // Mali 型号归一化为 G 开头
        val maliModel = if (vendorKey == "mali") normalizeMaliModel(deviceInfo.gpuModel) else null
        val maliArchInfo = maliModel?.let { MALI_ARCH_MAP[it] }
        val adrenoArch = deviceInfo.adrenoSeries.takeIf { it != "unknown" }

        return entries
            .filter { it.gpuVendor == vendorKey && it.driverType !in COMPONENT_TYPES }
            .mapNotNull { entry ->
                var score = 0
                val reasons = mutableListOf<String>()

                // 型号精确匹配：命中加分，不命中不排除（仅降排序）
                if (entry.gpuModels.isNotEmpty()) {
                    val modelMatched = when (vendorKey) {
                        "mali" -> maliModel != null && entry.gpuModels.contains(maliModel)
                        else -> entry.gpuModels.contains(deviceInfo.gpuModel)
                    }
                    if (modelMatched) {
                        score += 100
                        reasons.add("精确匹配 ${entry.gpuModels.joinToString("/")}")
                    } else {
                        score -= 20
                        reasons.add("支持 ${entry.gpuModels.joinToString("/")}（非本机型号）")
                    }
                }

                // 架构匹配：命中加分；不命中不排除，但降分放后面
                if (entry.gpuArch.isNotEmpty()) {
                    val archMatched = when (vendorKey) {
                        "mali" -> maliArchInfo != null && entry.gpuArch.contains(maliArchInfo.first)
                        "adreno" -> adrenoArch != null && entry.gpuArch.contains(adrenoArch)
                        "xclipse" -> entry.gpuArch.contains("rdna")
                        else -> false
                    }
                    if (archMatched) {
                        score += 50
                        reasons.add("兼容 ${entry.gpuArch.joinToString("/")}")
                    } else {
                        score -= 50
                        reasons.add("架构 ${entry.gpuArch.joinToString("/")}（非本机系列）")
                    }
                } else if (entry.gpuModels.isEmpty()) {
                    // 通用驱动
                    score += 20
                    reasons.add("通用兼容")
                }

                // Mali frontend 必须匹配（架构差异真实不兼容，排除）
                if (vendorKey == "mali" && entry.frontend != null) {
                    if (maliArchInfo == null || entry.frontend != maliArchInfo.second) {
                        return@mapNotNull null
                    }
                    score += 30
                    reasons.add("${entry.frontend} 前端")
                }

                // 成熟度加分（stable 最高）
                val maturityScore = 20 - (MATURITY_ORDER[entry.maturity] ?: 4) * 5
                score += maturityScore.coerceAtLeast(0)

                // Termux 专用源：不走容器路径，默认不推荐（降权），但不排除
                if (entry.targetEmulator == "termux") {
                    score -= 50
                    reasons.add("Termux 专用（非容器路径）")
                }

                // star 加权（每 100 star +1，上限 20）
                score += (entry.stars / 100).coerceAtMost(20)

                val recommended = score >= 100 &&
                    (entry.maturity == "stable" || entry.maturity == "beta" || entry.maturity == "ci")

                DriverMatchResult(
                    entry = entry,
                    score = score,
                    recommended = recommended,
                    matchReason = reasons.joinToString(" · ")
                )
            }
            .sortedWith(
                compareByDescending<DriverMatchResult> { it.recommended }
                    .thenByDescending { it.score }
                    .thenByDescending { it.entry.stars }
            )
    }

    /** 获取推荐驱动的配套组件（如 Mali 用户推荐 panDXVK） */
    fun companions(matched: List<DriverMatchResult>, entries: List<DriverRegistryEntry>): List<DriverRegistryEntry> {
        val companionIds = matched
            .filter { it.recommended }
            .flatMap { it.entry.companion }
            .toSet()
        return entries.filter { it.id in companionIds }
    }

    /**
     * 匹配设备专属的组件（归入组件专区对应分类）。
     *
     * 例如 Mali 设备返回 panDXVK（component_type=DXVK），调用方把它的
     * GitHub release 转成 Component 后合并进 DXVK 分类列表。
     * gpuModels/gpuArch 为空表示该 GPU 厂商通用；否则需型号/架构命中。
     */
    fun matchComponents(deviceInfo: DeviceInfo?, entries: List<DriverRegistryEntry>): List<DriverRegistryEntry> {
        if (deviceInfo == null) return emptyList()
        val vendorKey = when (deviceInfo.gpuVendor) {
            GpuVendor.ADRENO -> "adreno"
            GpuVendor.MALI -> "mali"
            GpuVendor.XCLIPSE -> "xclipse"
            else -> return emptyList()
        }

        val maliModel = if (vendorKey == "mali") normalizeMaliModel(deviceInfo.gpuModel) else null
        val maliArchInfo = maliModel?.let { MALI_ARCH_MAP[it] }
        val adrenoArch = deviceInfo.adrenoSeries.takeIf { it != "unknown" }

        return entries.filter { it.driverType == "component" && it.gpuVendor == vendorKey }
            .filter { entry ->
                // 指定了型号则必须命中
                if (entry.gpuModels.isNotEmpty()) {
                    val modelHit = when (vendorKey) {
                        "mali" -> maliModel != null && entry.gpuModels.contains(maliModel)
                        else -> entry.gpuModels.contains(deviceInfo.gpuModel)
                    }
                    if (!modelHit) return@filter false
                }
                // 指定了架构则必须命中
                if (entry.gpuArch.isNotEmpty()) {
                    val archHit = when (vendorKey) {
                        "mali" -> maliArchInfo != null && entry.gpuArch.contains(maliArchInfo.first)
                        "adreno" -> adrenoArch != null && entry.gpuArch.contains(adrenoArch)
                        else -> true
                    }
                    if (!archHit) return@filter false
                }
                true
            }
            .sortedBy { MATURITY_ORDER[it.maturity] ?: 4 }
    }

    /**
     * 将注册表驱动条目转换为现有 TurnipSource，复用 release 拉取与资产过滤逻辑。
     * Mali 型号在注册表中带 G 前缀（G57），detectGpuModel 返回纯数字（57），
     * supportedModels 需去掉 G 前缀才能被 compatibleTurnipSources 命中。
     *
     * filters 返回空：注册表源按类型归类，release 几乎都是驱动包，不过滤避免版本丢失
     * （assetIncludes/assetExcludes 保持默认空，资产也不过滤）。
     */
    fun toTurnipSource(entry: DriverRegistryEntry): TurnipSource {
        val adrenoSeriesNames = setOf("6xx", "7xx", "8xx")
        val series = entry.gpuArch.filter { it in adrenoSeriesNames }.toSet()
        val models = entry.gpuModels.map { it.removePrefix("G") }.toSet()
        val maturityLabel = when (entry.maturity) {
            "stable" -> ""
            "ci" -> "CI 构建"
            "beta" -> "Beta"
            "alpha" -> "Alpha"
            "experimental" -> "实验性"
            else -> ""
        }
        return TurnipSource(
            id = "reg-${entry.id}",
            name = entry.name,
            apiUrl = entry.apiUrl,
            description = buildString {
                if (entry.targetEmulator == "termux") append("[Termux 专用] ")
                append(entry.description)
                if (maturityLabel.isNotEmpty()) append("（$maturityLabel）")
            },
            experimental = entry.maturity != "stable",
            supportedSeries = series,
            supportedModels = models,
            requiredVendor = entry.gpuVendor,
            filters = emptyMap(),
            targetEmulator = entry.targetEmulator,
            stars = entry.stars.toLong()
        )
    }

    /** Mali 型号归一化：detectGpuModel 返回纯数字 "57"，注册表用 "G57" */
    private fun normalizeMaliModel(rawModel: String): String? {
        if (rawModel == "unknown") return null
        if (rawModel.startsWith("G")) return rawModel
        val num = rawModel.toIntOrNull() ?: return null
        // 3 位数（610/720）和 2 位数（52/57）都加 G 前缀
        return "G$num"
    }

    /** 内置精简兜底表，远程不可用时保证基础功能 */
    private fun builtinFallback(): List<DriverRegistryEntry> = listOf(
        DriverRegistryEntry(
            id = "k11mch1", name = "K11MCH1 AdrenoTools",
            owner = "K11MCH1", repo = "AdrenoToolsDrivers",
            apiUrl = "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases",
            gpuVendor = "adreno", gpuArch = listOf("6xx", "7xx"), gpuModels = emptyList(),
            driverType = "turnip", frontend = null, targetEmulator = "generic",
            packageFormat = "adpkg", maturity = "stable", stars = 5130,
            description = "最权威的 Adrenotools 驱动仓库", notes = "",
            companion = emptyList(), filters = mapOf("default" to listOf("Turnip"))
        ),
        DriverRegistryEntry(
            id = "mrpurple", name = "MrPurple666 Purple-Turnip",
            owner = "MrPurple666", repo = "purple-turnip",
            apiUrl = "https://api.github.com/repos/MrPurple666/purple-turnip/releases",
            gpuVendor = "adreno", gpuArch = listOf("6xx", "7xx", "8xx"), gpuModels = emptyList(),
            driverType = "turnip", frontend = null, targetEmulator = "generic",
            packageFormat = "adpkg", maturity = "stable", stars = 824,
            description = "最热门第三方 Turnip 编译", notes = "",
            companion = emptyList(), filters = mapOf("default" to listOf("Turnip"))
        ),
        DriverRegistryEntry(
            id = "fristoner-g57", name = "FristOneRR PanVK (Mali-G57)",
            owner = "FristOneRR", repo = "FristOneRR-Panvk-Driver",
            apiUrl = "https://api.github.com/repos/FristOneRR/FristOneRR-Panvk-Driver/releases",
            gpuVendor = "mali", gpuArch = listOf("valhall-v9"), gpuModels = listOf("G57"),
            driverType = "panvk", frontend = "JM", targetEmulator = "winlator",
            packageFormat = "zip", maturity = "beta", stars = 29,
            description = "Mali-G57 PanVK，DXVK 可用", notes = "",
            companion = listOf("pandxvk"), filters = mapOf("default" to listOf("Panvk"))
        ),
        DriverRegistryEntry(
            id = "pandxvk", name = "panDXVK (Mali 纹理转码)",
            owner = "isygold", repo = "panDXVK",
            apiUrl = "https://api.github.com/repos/isygold/panDXVK/releases",
            gpuVendor = "mali", gpuArch = emptyList(), gpuModels = emptyList(),
            driverType = "component", componentType = "DXVK",
            frontend = null, targetEmulator = "winlator",
            packageFormat = "wcp", maturity = "beta", stars = 12,
            description = "PanVK 专用 DXVK，BC→ASTC 纹理转码", notes = "",
            companion = emptyList(), filters = mapOf("default" to listOf("wcp"))
        ),
        DriverRegistryEntry(
            id = "pandxvk-lloyd262", name = "panDXVK-lloyd262 (PDXVK GPLASYNC)",
            owner = "isygold", repo = "panDXVK-lloyd262",
            apiUrl = "https://api.github.com/repos/isygold/panDXVK-lloyd262/releases",
            gpuVendor = "mali", gpuArch = emptyList(), gpuModels = emptyList(),
            driverType = "component", componentType = "DXVK",
            frontend = null, targetEmulator = "winlator",
            packageFormat = "wcp", maturity = "beta", stars = 0,
            description = "PDXVK 2.4.1 GPLASYNC（Mali 纹理转码，Sarek 变体分支）", notes = "",
            companion = emptyList(), filters = mapOf("default" to listOf("wcp", "pdvxk", "gplasync"))
        )
    )
}
