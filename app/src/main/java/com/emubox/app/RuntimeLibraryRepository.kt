package com.emubox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 运行库目录仓库：从 The412Banner/winlator-contents 拉取 components.json 并解析，
 * 再合并内置"微软官方直链层"（社区清单缺失的最新版本）与 URL 替换表（死链 → 官方可达源）。
 *
 * 官方层全部经下载前 HEAD 校验可达；社区清单作为兜底保留。
 */
object RuntimeLibraryRepository {
    private const val DEFAULT_CATALOG_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** 官方直链条目：微软官方渠道，App 启动前已实测可达（download.visualstudio / builds.dotnet / download.microsoft） */
    private data class OfficialEntry(
        val name: String,
        val displayName: String,
        val description: String,
        val provider: String,
        val url: String,
        val category: RuntimeCategory
    )

    private val officialEntries: List<OfficialEntry> = listOf(
        OfficialEntry(
            name = "dotnet9",
            displayName = ".NET 9.0 桌面运行库",
            description = ".NET 9 桌面运行时（LTS），微软官方直链",
            provider = "Microsoft",
            url = "https://aka.ms/dotnet/9.0/dotnet-runtime-win-x64.zip",
            category = RuntimeCategory.DOTNET
        ),
        OfficialEntry(
            name = "vcredist2026",
            displayName = "VC++ 2026 运行库 (x64)",
            description = "Visual C++ 2015-2026 Redistributable x64，微软官方直链（aka.ms/vc14 最新）",
            provider = "Microsoft",
            url = "https://aka.ms/vc14/vc_redist.x64.exe",
            category = RuntimeCategory.VC_RUNTIME
        ),
        OfficialEntry(
            name = "vcredist2026_x86",
            displayName = "VC++ 2026 运行库 (x86)",
            description = "Visual C++ 2015-2026 Redistributable x86，微软官方直链（aka.ms/vc14 最新）",
            provider = "Microsoft",
            url = "https://aka.ms/vc14/vc_redist.x86.exe",
            category = RuntimeCategory.VC_RUNTIME
        ),
        OfficialEntry(
            name = "dotnet481",
            displayName = ".NET Framework 4.8.1",
            description = ".NET Framework 4.8.1（含 x86/x64），微软官方直链",
            provider = "Microsoft",
            url = "https://download.microsoft.com/download/8/1/8/81877d8b-a9b2-4153-9ad2-63a6441d11dd/NDP481-DevPack-ENU.exe",
            category = RuntimeCategory.DOTNET
        ),
        OfficialEntry(
            name = "wmv9vcm",
            displayName = "WMV9 解码器 (wmv9vcm)",
            description = "Windows Media Video 9 VCM 解码器，解决游戏过场动画/媒体 WMV 无法播放问题，微软官方直链",
            provider = "Microsoft",
            url = "https://download.microsoft.com/download/2/8/D/28DA9C3E-6DA2-456F-BD33-1F937EB6E0FF/WindowsServer2003-WindowsMedia-KB2845142-x86-ENU.exe",
            category = RuntimeCategory.CODEC
        )
    )

    /** URL 替换表：社区清单里的死链/第三方 CDN → 官方可达直链（替换后保持 status=ready） */
    private val urlReplacements: Map<String, String> = mapOf(
        // VB6 SP6：dlo4d.com 第三方 CDN 实测不可达 → 微软官方 MSI（download.microsoft.com 实测 200）
        "https://lon-01.dlo4d.com/files/visual-basic-runtime-files/VB6.0-KB290887-X86.exe" to
            "https://download.microsoft.com/download/5/6/3/5635D6A9-885E-4C80-A2E7-8A7F4488FBF1/VB60SP6-KB2708437-x86-ENU.msi"
    )

    suspend fun load(url: String = DEFAULT_CATALOG_URL): List<RuntimeComponent> =
        withContext(Dispatchers.IO) {
            val community = fetchCommunity(url)
            val merged = mergeWithOfficial(community)
            applyUrlReplacements(merged)
        }

    private suspend fun fetchCommunity(url: String): List<RuntimeComponent> {
        return try {
            val acceleratedUrl = Accelerator.rewriteUrl(url)
            val request = Request.Builder().url(acceleratedUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                RuntimeLibraryParser.parse(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 社区清单 + 官方层：官方条目仅在社区缺失同名组件时补充 */
    private fun mergeWithOfficial(community: List<RuntimeComponent>): List<RuntimeComponent> {
        if (officialEntries.isEmpty()) return community
        val existingNames = community.mapTo(HashSet()) { it.name }
        val extra = officialEntries.filter { it.name !in existingNames }.map { entry ->
            RuntimeComponent(
                name = entry.name,
                description = entry.description,
                provider = entry.provider,
                category = entry.category,
                status = "ready",
                dependencies = emptyList(),
                steps = listOf(
                    RuntimeStep(
                        action = "install_exe",
                        fileName = entry.url.substringAfterLast('/'),
                        url = entry.url,
                        arguments = "",
                        checksum = "",
                        size = 0L,
                        rename = "",
                        dll = "",
                        forArch = ""
                    )
                ),
                primaryFile = RuntimeFile(
                    fileName = entry.url.substringAfterLast('/'),
                    url = entry.url,
                    size = 0L,
                    checksum = "",
                    rename = ""
                )
            )
        }
        return community + extra
    }

    /** 死链替换：把社区清单里指向不可达第三方 CDN 的 URL 换成官方可达直链，并修正状态 */
    private fun applyUrlReplacements(components: List<RuntimeComponent>): List<RuntimeComponent> {
        if (urlReplacements.isEmpty()) return components
        return components.map { comp ->
            val primary = comp.primaryFile ?: return@map comp
            val replacement = urlReplacements[primary.url] ?: return@map comp
            comp.copy(
                status = "ready",
                primaryFile = primary.copy(url = replacement),
                steps = comp.steps.map { step ->
                    if (step.url == primary.url) step.copy(url = replacement) else step
                }
            )
        }
    }

    /** 按分类分组，分类内按 name 排序 */
    fun groupByCategory(components: List<RuntimeComponent>): Map<RuntimeCategory, List<RuntimeComponent>> =
        components.groupBy { it.category }
            .mapValues { (_, list) -> list.sortedBy { it.name } }
            .toSortedMap(compareBy { it.order })
}
