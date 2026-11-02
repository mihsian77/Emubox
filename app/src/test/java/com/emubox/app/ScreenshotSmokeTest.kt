package com.emubox.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emubox.app.ui.theme.EmuBoxTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 界面渲染烟雾测试：验证能否在纯 JVM 环境把 Compose 真实画成图片。
 * 不依赖模拟器、不依赖真机。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    /** 把当前界面存成 PNG（用 Roborazzi，它走 View.draw 而不是 PixelCopy） */
    /** 截图目录：默认 build/screenshots，可用 -Dscreenshot.dir=... 覆盖（不写死绝对路径） */
    private fun savePng(name: String) {
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots")
        dir.mkdirs()
        val out = File(dir, File(name).name)
        compose.onRoot().captureRoboImage(filePath = out.absolutePath)
        println("已保存 ${out.absolutePath}")
    }

    @Test
    fun renderThemeSmoke() {
        compose.setContent {
            EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp)
                ) {
                    Text(
                        text = "EmuBox 界面渲染验证",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        savePng("00-smoke.png")
    }
}
