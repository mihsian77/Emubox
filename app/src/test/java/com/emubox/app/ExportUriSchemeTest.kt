package com.emubox.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 锁定下载库"打开 / 分享"按钮构造 Uri 的行为（Screens.kt 中的内联逻辑）。
 *
 * 背景：从 Android 7.0（API 24）起，把 file:// 的 Uri 通过 Intent 交给其他 App
 * 会抛 FileUriExposedException。此测试只验证"当前代码走到哪条分支"，
 * 不验证系统抛异常（那是框架行为，JVM 单元测试无法覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportUriSchemeTest {

    /** 与 Screens.kt:538 / 579 一模一样的构造方式 */
    private fun exportUri(filePath: String): Uri =
        if (filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            Uri.fromFile(File(filePath))
        }

    @Test
    fun `扫描记录的绝对路径生成 file scheme`() {
        val uri = exportUri("/storage/emulated/0/Download/EmuBox/Drivers/v37_turnip.zip")
        assertEquals("file", uri.scheme)
        assertEquals("file:///storage/emulated/0/Download/EmuBox/Drivers/v37_turnip.zip", uri.toString())
    }

    @Test
    fun `MediaStore 记录保持 content scheme`() {
        val uri = exportUri("content://media/external/downloads/123")
        assertEquals("content", uri.scheme)
    }
}
