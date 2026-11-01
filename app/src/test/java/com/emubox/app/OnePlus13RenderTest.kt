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
        repeat(44) {
            Thread.sleep(500)
            compose.mainClock.advanceTimeByFrame()
        }
        save("11-oneplus13-device.png")
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
        repeat(44) {
            Thread.sleep(500)
            compose.mainClock.advanceTimeByFrame()
        }
        save("12-oneplus13-device-tall.png")
    }
}
