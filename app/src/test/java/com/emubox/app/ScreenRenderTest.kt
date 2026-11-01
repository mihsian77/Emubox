package com.emubox.app

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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", shadows = [ShadowNativeVulkanBridge::class])
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
        note = o.optString("note")
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
        val entries = loadEmulators(12)
        println("注入模拟器条目: ${entries.size} 条")
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
                            sourceCatalogRemote = false,
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
}
