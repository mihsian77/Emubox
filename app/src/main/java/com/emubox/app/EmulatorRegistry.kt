package com.emubox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val DEFAULT_EMULATOR_REGISTRY_URL =
    "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/emulator-registry.json"

/** 模拟器条目：内置清单 + CI 每日刷新最新版本/活跃度 */
data class EmulatorEntry(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val owner: String,
    val repo: String,
    val homepage: String = "",
    val latestTag: String = "",
    val latestApkUrl: String = "",
    val pushedAt: String = "",
    val stars: Long = 0L
) {
    val githubUrl: String
        get() = if (owner.isNotBlank() && repo.isNotBlank()) "https://github.com/$owner/$repo" else ""
}

object EmulatorRegistryRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 内置清单（与 emulator-registry.json 根文件保持一致，CI 每日刷新该文件） */
    fun builtInRegistry(): List<EmulatorEntry> = listOf(
        EmulatorEntry(
            id = "winlator", name = "Winlator", category = "windows",
            description = "安卓端 Windows x86 转译运行环境，通过 Wine/Box64 运行 Windows 游戏与软件，EmuBox 驱动/组件的主要使用场景",
            owner = "brunodev85", repo = "winlator"
        ),
        EmulatorEntry(
            id = "gamehub", name = "GameHub", category = "windows",
            description = "面向安卓的 Windows 游戏整合平台，内置 Wine/Box64 兼容层，可安装 DXVK、Turnip 等组件",
            owner = "", repo = "", homepage = "https://gamehub.com"
        ),
        EmulatorEntry(
            id = "eden", name = "Eden", category = "windows",
            description = "Winlator 衍生分支，部分驱动仓库（如 s1mptom）专门针对其在 Adreno 830/840 上的运行适配",
            owner = "", repo = ""
        ),
        EmulatorEntry(
            id = "termux", name = "Termux", category = "terminal",
            description = "安卓终端模拟器与 Linux 环境，可安装 Box64 等转译层，是驱动/组件的进阶使用场景",
            owner = "termux", repo = "termux-app"
        ),
        EmulatorEntry(
            id = "ppsspp", name = "PPSSPP", category = "console",
            description = "PSP 模拟器，支持 Vulkan/OpenGL 渲染，安卓端成熟的掌机模拟方案",
            owner = "hrydgard", repo = "ppsspp"
        ),
        EmulatorEntry(
            id = "retroarch", name = "RetroArch", category = "multi",
            description = "多机种聚合模拟器前端，内置大量核心（核心通过在线下载），支持 Vulkan 渲染",
            owner = "libretro", repo = "RetroArch"
        ),
        EmulatorEntry(
            id = "dolphin", name = "Dolphin", category = "console",
            description = "GameCube/Wii 模拟器，安卓端 Vulkan 支持成熟",
            owner = "dolphin-emu", repo = "dolphin"
        ),
        EmulatorEntry(
            id = "duckstation", name = "DuckStation", category = "console",
            description = "PS1 模拟器，兼容性与画质增强优秀，支持 Vulkan",
            owner = "stenzek", repo = "duckstation"
        ),
        EmulatorEntry(
            id = "vita3k", name = "Vita3K", category = "console",
            description = "PSVita 模拟器，安卓端实验性支持 Vulkan",
            owner = "Vita3K", repo = "Vita3K"
        )
    )

    /** 拉取远程清单（加速重写 + 失败回退内置） */
    suspend fun fetchRemoteRegistry(): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val acceleratedUrl = Accelerator.rewriteUrl(DEFAULT_EMULATOR_REGISTRY_URL)
        val request = Request.Builder().url(acceleratedUrl).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                val entries = parseEmulators(json)
                if (entries.isEmpty()) null else entries
            }
        }.getOrNull() ?: builtInRegistry()
    }

    private fun parseEmulators(json: JSONObject): List<EmulatorEntry> {
        val arr: JSONArray = json.optJSONArray("emulators") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    EmulatorEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        category = obj.optString("category"),
                        description = obj.optString("description"),
                        owner = obj.optString("owner"),
                        repo = obj.optString("repo"),
                        homepage = obj.optString("homepage"),
                        latestTag = obj.optString("latest_tag"),
                        latestApkUrl = obj.optString("latest_apk_url"),
                        pushedAt = obj.optString("pushed_at"),
                        stars = obj.optLong("stars", 0L)
                    )
                )
            }
        }.filter { it.id.isNotBlank() }
    }
}
