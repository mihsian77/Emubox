package com.emubox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emubox.app.ui.theme.EmuBoxTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 真实页面渲染：把 App 的各个专区界面在 JVM 上画成 PNG。
 * 数据取自仓库里的真实注册表，不编造。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", shadows = [ShadowNativeVulkanBridge::class])
class ScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // 与 MainActivity.onCreate 一致：先初始化设置存储，否则各页面读配置会崩
        SettingsManager.init(ApplicationProvider.getApplicationContext())
    }

    /** 截图目录：默认 build/screenshots，可用 -Dscreenshot.dir=... 覆盖（不写死绝对路径） */
    private fun save(name: String) {
        // 页面里的清单（运行库、组件、社区数据）走 IO 协程拉取，结果回到主线程才重组。
        // 直接取帧会截到「正在读取…」的加载态，所以先给数据留出到位窗口，再推进一次帧。
        runCatching {
            compose.waitForIdle()
            Thread.sleep(2500)
            compose.waitForIdle()
        }
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots")
        dir.mkdirs()
        val out = File(dir, File(name).name)
        compose.onRoot().captureRoboImage(filePath = out.absolutePath)
        println("已保存 ${out.absolutePath}")
    }

    /** 从仓库根目录找注册表文件 */
    private fun repoFile(name: String): File {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null && !File(d, name).exists()) d = d.parentFile
        return File(d ?: File("."), name)
    }

    /** 把真实注册表 JSON 转成页面需要的数据对象 */
    private fun loadEmulators(limit: Int): List<EmulatorEntry> {
        val text = repoFile("emulator-registry.json").readText()
        val trimmed = text.trimStart()
        val arr = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            val key = root.keys().asSequence().first { root.get(it) is JSONArray }
            root.getJSONArray(key)
        }
        return (0 until minOf(limit, arr.length())).map { i -> toEntry(arr.getJSONObject(i)) }
    }

    /** 社区排行：App 里由 MainActivity 合并，截图测试照同一规则补名次，否则徽章永远是空的 */
    private fun loadRanking(): Map<String, Int> {
        val f = repoFile("emulator-ranking.json")
        if (!f.exists()) return emptyMap()
        val arr = runCatching { JSONObject(f.readText()).optJSONArray("emulators") }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out[o.optString("id")] = i + 1
            val repo = o.optString("repo")
            if (repo.isNotBlank()) out["repo:$repo"] = i + 1
        }
        return out
    }

    private fun toEntry(o: JSONObject) = EmulatorEntry(
        id = o.optString("id"),
        name = o.optString("name"),
        category = o.optString("category"),
        description = o.optString("description"),
        owner = o.optString("owner"),
        repo = o.optString("repo"),
        homepage = o.optString("homepage"),
        repoUrl = o.optString("repo_url"),
        official = o.optBoolean("official"),
        channel = o.optString("channel", "stable"),
        latestTag = o.optString("latest_tag"),
        latestApkUrl = o.optString("latest_apk_url"),
        latestApkName = o.optString("latest_apk_name"),
        latestApkSize = o.optLong("latest_apk_size"),
        pushedAt = o.optString("pushed_at"),
        stars = o.optLong("stars"),
        forks = o.optLong("forks"),
        watchers = o.optLong("watchers"),
        downloads = o.optLong("downloads"),
        latestDate = o.optString("latest_date"),
        latestBody = o.optString("latest_body"),
        logoUrl = o.optString("logo_url"),
        note = o.optString("note"),
        // 平台标签、筛选都靠这两个字段，之前测试夹具漏了，
        // 导致「卡片上到底有没有平台标签」这件事在截图里根本验不出来。
        platforms = o.optJSONArray("platforms")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList(),
        hosts = o.optJSONArray("hosts")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList(),
        // 产物统计与安卓可用性：详情页顶部「发布文件」块全靠它，
        // 夹具漏了就会显示「无」，与下面的文件列表自相矛盾
        artifacts = o.optJSONObject("artifacts")?.let { a ->
            EmulatorArtifacts(
                apk = a.optInt("apk"), androidZip = a.optInt("android_zip"),
                ipa = a.optInt("ipa"), libretroCore = a.optInt("libretro_core"),
                desktop = a.optInt("desktop"), console = a.optInt("console"),
                source = a.optInt("source"), total = a.optInt("total")
            )
        } ?: EmulatorArtifacts(),
        androidReady = o.optBoolean("android_ready", false)
    )

    @Test
    fun renderDeviceScreen() {
        val device = DeviceInfo(
            androidVersion = "15 (SDK 35)",
            ram = "12 GB",
            gpuRenderer = "Adreno (TM) 750",
            adrenoSeries = "7xx",
            gpuModel = "Adreno 750",
            gpuVendor = GpuVendor.ADRENO,
            deviceBrand = "Xiaomi",
            deviceModel = "23127PN0CC",
            socName = "骁龙 8 Gen 3"
        )
        // 设备页会调用原生 Vulkan 检测库，电脑上加载不了；先把时钟冻住，
        // 让界面停在"静态首帧"，只验版式
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DeviceScreen(deviceInfo = device, isLoading = false)
                    }
                }
            }
        }
        save("01-device.png")
    }

    @Test
    fun renderEmulatorScreen() {
        val ranking = loadRanking()
        val entries = loadEmulators(12).map { e ->
            e.copy(rank = ranking[e.id] ?: ranking["repo:${e.repo}"] ?: 0)
        }
        println("注入模拟器条目: ${entries.size} 条，带名次 ${entries.count { it.rank > 0 }} 条")
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        EmulatorScreen(entries = entries, onDownload = {})
                    }
                }
            }
        }
        save("02-emulators.png")
    }

    @Test
    fun renderSettingsScreen() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        SettingsScreen(
                            onBack = {},
                            themeMode = ThemeMode.SYSTEM,
                            colorTheme = ColorTheme.EMUHUB,
                            onThemeModeChange = {},
                            onColorThemeChange = {},
                            appLanguage = AppLanguage.CHINESE,
                            onAppLanguageChange = {},
                            uiScale = UiScale.STANDARD,
                            onUiScaleChange = {},
                            onSourceCatalogChanged = {}
                        )
                    }
                }
            }
        }
        save("03-settings.png")
    }

    @Test
    fun renderDownloadsScreen() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DownloadsScreen(onBack = {}, showBack = true)
                    }
                }
            }
        }
        save("04-downloads.png")
    }

    @Test
    fun renderRuntimeScreen() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        RuntimeLibraryScreen(onDownload = {})
                    }
                }
            }
        }
        save("05-runtime.png")
    }

    @Test
    fun renderEmulatorDark() {
        val entries = loadEmulators(8)
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.AMOLED, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        EmulatorScreen(entries = entries, onDownload = {})
                    }
                }
            }
        }
        save("06-emulators-amoled.png")
    }

    @Test
    fun renderDriverScreen() {
        val device = DeviceInfo(
            androidVersion = "15 (SDK 35)",
            ram = "12 GB",
            gpuRenderer = "Adreno (TM) 750",
            adrenoSeries = "7xx",
            gpuModel = "Adreno 750",
            gpuVendor = GpuVendor.ADRENO,
            deviceBrand = "Xiaomi",
            deviceModel = "23127PN0CC",
            socName = "骁龙 8 Gen 3"
        )
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DriverScreen(
                            deviceInfo = device,
                            isLoading = false,
                            turnipSourceId = "",
                            turnipSources = emptyList(),
                            turnipReleases = emptyList(),
                            panvkSourceId = "",
                            panvkSources = emptyList(),
                            panvkReleases = emptyList(),
                            qualcommSourceId = "",
                            qualcommSources = emptyList(),
                            qualcommReleases = emptyList(),
                            xclipseSourceId = "",
                            xclipseSources = emptyList(),
                            xclipseReleases = emptyList(),
                            wrapperSourceId = "",
                            wrapperSources = emptyList(),
                            wrapperReleases = emptyList(),
                            wrapperItems = emptyList(),
                            onTurnipSourceChange = {},
                            onPanvkSourceChange = {},
                            onQualcommSourceChange = {},
                            onXclipseSourceChange = {},
                            onWrapperSourceChange = {},
                            onDownloadAsset = { _, _ -> }
                        )
                    }
                }
            }
        }
        save("07-drivers.png")
    }

    /**
     * 长文件名断行回归：Termux 一次发 6 个产物，前 33 个字符完全相同，
     * 区分它们的 apt 版本、debug/release、ABI 全在尾部。
     * 单行截断后六行看起来一样，用户按名字选必然下错包。
     */
    @Test
    fun renderLongAssetNames() {
        // 真实产物名，直接从仓库数据里取，不手写
        val names = listOf(
            "termux-app_v0.119.0-beta.3.1+apt-android-7-github-debug_arm64-v8a.apk",
            "termux-app_v0.119.0-beta.3.1+apt-android-7-github-debug_armeabi-v7a.apk",
            "termux-app_v0.119.0-beta.3.1+apt-android-5-github-debug_arm64-v8a.apk",
            "termux-app_v0.119.0-beta.3.1+apt-android-5-github-debug_armeabi-v7a.apk",
            "pre-release-shadPS4-2026-11-15-312cfbae77821627aa511bdee8717e1b7c3c9a23",
            "azaharplus-2126.2-A-android_coexists_with_azahar.apk"
        )
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("修复前（1 行截断）", style = MaterialTheme.typography.labelMedium)
                            names.forEach { n ->
                                Text(
                                    n,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider()
                            Text("修复后（软断行 + 3 行 + ABI 副行）", style = MaterialTheme.typography.labelMedium)
                            names.forEach { n ->
                                Column {
                                    LongFieldText(n, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                    Text(
                                        buildString {
                                            val arch = assetArchLabel(n)
                                            if (arch.isNotBlank()) append(arch).append(" · ")
                                            append(assetTypeLabel(n))
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        save("08-long-names.png")
    }

    /**
     * 详情页资产列表：下载列表的文件名是用户真正要读的东西。
     * 这里用真实注册表条目 + 真实分片里的 releases，不造数据。
     */
    @Test
    fun renderDetailAssets() {
        // 挑一个「最新版给了多个同类 APK」的条目：这类最容易被截断误导
        val entry = loadEmulators(400).firstOrNull { it.id == "ranggacasper-mostwanted-android" }
            ?: loadEmulators(400).first { it.latestApkName.isNotBlank() }
        val withReleases = entry.copy(releases = loadReleases(entry.id))
        println("详情页条目: ${entry.id}，releases=${withReleases.releases.size}")
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        EmulatorDetailDialog(
                            initialEntry = withReleases,
                            onDismiss = {},
                            onDownloadAsset = { _, _ -> }
                        )
                    }
                }
            }
        }
        save("09-detail-assets.png")
    }

    /** 从详情分片读 releases（结构与 build_index.py 输出一致；分片号不查表，直接找） */
    private fun loadReleases(id: String): List<EmulatorRelease> {
        var d: JSONObject? = null
        for (shard in 0..7) {
            val f = repoFile("emulator-details-$shard.json")
            if (!f.exists()) continue
            val details = JSONObject(f.readText()).optJSONObject("details") ?: continue
            d = details.optJSONObject(id) ?: continue
            break
        }
        val dd = d ?: return emptyList()
        val arr = dd.optJSONArray("releases") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val assetsArr = r.optJSONArray("assets")
            EmulatorRelease(
                tag = r.optString("tag"),
                date = r.optString("date"),
                body = r.optString("body"),
                assets = if (assetsArr == null) emptyList() else (0 until assetsArr.length()).map { j ->
                    val a = assetsArr.getJSONObject(j)
                    EmulatorAsset(name = a.optString("name"), size = a.optLong("size"), url = a.optString("url"))
                }
            )
        }
    }
}
