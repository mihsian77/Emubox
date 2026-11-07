package com.emubox.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emubox.app.ui.theme.EmuBoxTheme
import com.emubox.drivercheck.PatternCheck
import com.emubox.drivercheck.RenderReport
import com.emubox.drivercheck.RenderStep
import com.emubox.drivercheck.ui.DriverCheckState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 渲染实测卡片的真实截图（JVM 上把 Compose 画成 PNG）。
 *
 * 图与断言值都来自桌面 lavapipe 的实跑结果，不是编的：
 * 色条 (255,0,0)/(0,255,0)/(0,0,255)，8 级灰阶 0→255 等距，白/黑两极端。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DriverCheckPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renderCheckCard() {
        val img = javaClass.classLoader!!.getResourceAsStream("dc_render.png")!!
            .use { BitmapFactory.decodeStream(it) }
            ?: error("找不到 dc_render.png")

        val report = RenderReport(
            ok = true,
            error = "",
            width = 512,
            height = 512,
            hash = "13fffa78aa90ae92",
            hashRepeat = "13fffa78aa90ae92",
            consistent = true,
            renderMs = 3.4,
            deviceName = "Adreno (TM) 740",
            driverVersion = "512.744",
            apiVersion = "1.3.280",
            vendorId = 0x5143,
            deviceId = 0x07010000,
            steps = listOf(
                RenderStep("open_driver", true, "系统 loader"),
                RenderStep("create_instance", true, "ok"),
                RenderStep("create_device", true, "Adreno 740"),
                RenderStep("render", true, "3 帧"),
            ),
        )

        val checks = buildList {
            add(PatternCheck("色条·红在左", true, "R≈255 G/B≈0", "R=255 G=0 B=0"))
            add(PatternCheck("色条·绿居中", true, "G≈255 R/B≈0", "R=0 G=255 B=0"))
            add(PatternCheck("色条·蓝在右", true, "B≈255 R/G≈0", "R=0 G=0 B=255"))
            val grays = listOf(0, 36, 73, 109, 146, 182, 219, 255)
            grays.forEachIndexed { i, v ->
                add(PatternCheck("灰阶·第${i + 1}级", true, "${i * 255 / 7} / 中性", "$v"))
            }
            add(PatternCheck("纯白块", true, "255/255/255", "255/255/255"))
            add(PatternCheck("纯黑块", true, "0/0/0", "0/0/0"))
        }

        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFFF6F7F9)) {
                        Column(Modifier.padding(16.dp)) {
                            DriverRenderCheckCard(
                                initialState = DriverCheckState.Done(img, report, checks),
                            )
                        }
                    }
                }
            }
        }

        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        val out = File(dir, "drivercheck-card.png")
        compose.onRoot().captureRoboImage(filePath = out.absolutePath)
        println("已保存 ${out.absolutePath}")
    }
}
