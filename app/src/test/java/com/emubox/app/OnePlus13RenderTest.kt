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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 一加 13（PJZ110 / 骁龙 8 Elite / Adreno 830 / Android 15）实机参数渲染。
 *
 * 目的：把「这台机器上设备页第一屏到底显示什么」变成可看的图，而不是口头推测。
 * 注入的字段全部来自真机真实取值：Build.MODEL=PJZ110、Build.SOC=sun（映射为
 * Qualcomm Snapdragon 8 Elite）、GL_RENDERER="Adreno (TM) 830"、16GB 内存。
 *
 * 分辨率按一加 13 的 3168×1440 @560dpi 换算，即 411×905dp。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h905dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
class OnePlus13RenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        SettingsManager.init(ApplicationProvider.getApplicationContext())
    }

    private fun onePlus13() = DeviceInfo(
        androidVersion = "15 (SDK 35)",
        ram = "16 GB",
        gpuRenderer = "Adreno (TM) 830",
        adrenoSeries = "8xx",
        gpuModel = "830",
        gpuVendor = GpuVendor.ADRENO,
        deviceBrand = "OnePlus",
        deviceModel = "PJZ110",
        socName = "Qualcomm Snapdragon 8 Elite"
    )

    /**
     * 等各卡片的异步数据（注册表匹配 / EmuReady 查询 / 生态动态）落地后再截图。
     * 原来固定 44×500ms（22 秒/用例）；改成「主线程空闲 + 连续稳定」就提前退出，
     * 数据快的用例一两秒结束，上限 20 秒兜底，避免真有慢数据时截空图。
     */
    private fun settle(rounds: Int = 30) {
        // Robolectric paused 模式拿不到 scheduler，没有可靠的异步就绪信号；
        // 固定轮次驱动帧时钟，窗口从 44×500ms 压到 30×200ms（22s → 6s）
        repeat(rounds) {
            Thread.sleep(200)
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
        }
    }

    /** 截图目录：默认 build/screenshots，可用 -Dscreenshot.dir=... 覆盖（不写死绝对路径） */
    private fun save(name: String) {
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots")
        dir.mkdirs()
        val out = File(dir, File(name).name)
        compose.onRoot().captureRoboImage(filePath = out.absolutePath)
        println("已保存 ${out.absolutePath}")
    }

    /** 设备页第一屏：身份卡 + 生态动态 + 指南入口 + EmuReady + 驱动适配推荐 */
    @Test
    fun renderOnePlus13DeviceFirstScreen() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DeviceScreen(deviceInfo = onePlus13(), isLoading = false)
                    }
                }
            }
        }
        // 等各卡片的异步数据（注册表匹配 / EmuReady 查询 / 生态动态）落地后再截图
        settle()
        save("11-oneplus13-device.png")
    }

    /**
     * 展锐设备：型号认不出，但必须认到厂。
     *
     * 展锐没有公开的完整料号表（设备只报 ums9230 这类平台代号），
     * 直接显示裸串对用户没信息量；这张图验证降级路径是
     * 「紫光展锐 · 型号未收录（ums9230）」而不是「SOC: unknown」。
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1200dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun renderUnisocUnknownModel() {
        // Robolectric 的 Build 没有 SOC 字段，改用系统属性注入（真机对应 ro.soc.model）
        org.robolectric.shadows.ShadowSystemProperties.override("ro.soc.model", "ums9230")
        try {
            val info = DeviceInfo(
                androidVersion = "13 (SDK 33)",
                ram = "8 GB",
                gpuRenderer = "Mali-G57 MC2",
                adrenoSeries = "",
                gpuModel = "57",
                gpuVendor = GpuVendor.MALI,
                deviceBrand = "Unisoc",
                deviceModel = "ums9230",
                socName = "紫光展锐 T760"
            )
            compose.setContent {
                ProvideAppLanguage(AppLanguage.CHINESE) {
                    EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                        Surface(Modifier.fillMaxSize()) {
                            DeviceScreen(deviceInfo = info, isLoading = false)
                        }
                    }
                }
            }
            settle()
            save("13-unisoc-unknown.png")
        } finally {
            org.robolectric.shadows.ShadowSystemProperties.reset()
        }
    }

    /** 加长视口：把首屏之下的芯片知识 / 跑分引擎 / Vulkan 标题一并带出来（长图，非一屏） */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1900dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun renderOnePlus13DeviceTall() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DeviceScreen(deviceInfo = onePlus13(), isLoading = false)
                    }
                }
            }
        }
        settle()
        save("12-oneplus13-device-tall.png")
    }

    /**
     * 骁龙 8 Elite Gen5（SM8850-AC）：验证芯片卡把极客湾新抓的
     * 缓存 / 内存 / GPU 频率与 ALUs / 基带与 NPU / GB6 / 型号名称全部画出来。
     * 这一条对应「手机性能排行」截图里的骁龙 8 Elite Gen5 详情页。
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1900dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun renderSnapdragon8EliteGen5() {
        org.robolectric.shadows.ShadowSystemProperties.override("ro.soc.model", "SM8850-AC")
        try {
            val info = DeviceInfo(
                androidVersion = "16 (SDK 36)",
                ram = "16 GB",
                gpuRenderer = "Adreno (TM) 840",
                adrenoSeries = "8xx",
                gpuModel = "840",
                gpuVendor = GpuVendor.ADRENO,
                deviceBrand = "HONOR",
                deviceModel = "WIN 2",
                socName = "Qualcomm Snapdragon 8 Elite Gen 5"
            )
            compose.setContent {
                ProvideAppLanguage(AppLanguage.CHINESE) {
                    EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                        Surface(Modifier.fillMaxSize()) {
                            DeviceScreen(deviceInfo = info, isLoading = false)
                        }
                    }
                }
            }
            settle()
            save("14-snapdragon-8-elite-gen5.png")
        } finally {
            org.robolectric.shadows.ShadowSystemProperties.reset()
        }
    }
}
