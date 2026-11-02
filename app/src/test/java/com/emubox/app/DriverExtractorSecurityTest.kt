package com.emubox.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 驱动包解压的本地安全测试。
 *
 * 全部在 JVM 上跑（Robolectric 模拟 Android 环境），不需要真机、不需要安装 APK。
 * 覆盖三件事：正常包能提出来、路径穿越被挡住、超大元数据被截断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverExtractorSecurityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 把一段字节包装成 content:// URI 喂给被测代码 */
    private fun uriOf(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.emubox.test/driver.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    /** 构造一个 zip：[entries] 是「条目名 to 内容」 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun driversRoot(): File = File(context.filesDir, "drivers")

    // ── 1. 正常包 ────────────────────────────────────────────────

    @Test
    fun `正常驱动包能提取出 so 并落在驱动目录内`() {
        val bytes = zipOf(
            "meta.json" to """{"libraryName":"libvulkan_freedreno.so"}""".toByteArray(),
            "lib/libvulkan_freedreno.so" to ByteArray(1024) { 0x7f }
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "test-driver")

        assertNotNull("正常包应该提取成功", result)
        assertEquals("libvulkan_freedreno.so", result!!.name)
        assertTrue("文件应落在驱动目录内", result.absolutePath.startsWith(driversRoot().absolutePath))
        assertTrue("文件应真实存在", result.exists())
    }

    // ── 2. meta.json 里写路径穿越 ────────────────────────────────

    @Test
    fun `meta 里的 libraryName 带路径穿越时会被压成单层文件名`() {
        val bytes = zipOf(
            "meta.json" to """{"libraryName":"../../shared_prefs/evil.so"}""".toByteArray(),
            "libreal.so" to ByteArray(512) { 0x01 }
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "evil-meta")

        assertNotNull(result)
        // 关键断言：解析后只剩文件名，不能带任何目录层级
        assertEquals("evil.so", result!!.name)
        assertTrue("绝不能写到驱动目录之外", result.parentFile!!.absolutePath.contains("/drivers/"))
        // 确认没有文件被写到 filesDir 外面去
        assertTrue(File(context.filesDir.parentFile, "shared_prefs/evil.so").let { !it.exists() })
    }

    // ── 3. zip 条目名本身带路径穿越 ──────────────────────────────

    @Test
    fun `zip 条目名带路径穿越时同样被压成单层文件名`() {
        val bytes = zipOf(
            "../../../../pwned.so" to ByteArray(256) { 0x02 }
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "evil-entry")

        assertNotNull(result)
        assertEquals("pwned.so", result!!.name)
        assertTrue(
            "不得写到驱动目录外",
            result.absolutePath.startsWith(driversRoot().absolutePath)
        )
    }

    // ── 4. 超大 meta.json 被截断 ─────────────────────────────────

    @Test
    fun `超大的元数据文件会被限长截断而不是撑爆内存`() {
        val huge = """{"libraryName":"libx.so","junk":"""" + "A".repeat(120 * 1024) + """"}"""
        val bytes = zipOf(
            "meta.json" to huge.toByteArray(),
            "libx.so" to ByteArray(64) { 0x03 }
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "huge-meta")

        // 不崩溃即可，且 meta.json 落盘大小不超过 64KB 上限
        val metaOnDisk = File(DriverExtractor.driverDir(context, "huge-meta"), "meta.json")
        assertTrue("meta.json 不应存在或不超过上限", !metaOnDisk.exists() || metaOnDisk.length() <= 64 * 1024)
        assertNotNull("so 仍应提取成功", result)
    }

    // ── 5. 驱动 ID 净化 ─────────────────────────────────────────

    @Test
    fun `驱动 ID 含路径符号时不会把目录建到外面`() {
        val dir = DriverExtractor.driverDir(context, "../../escape")

        assertTrue(
            "驱动目录必须位于 drivers 之下，实际为 ${dir.absolutePath}",
            dir.absolutePath.startsWith(driversRoot().absolutePath)
        )
    }

    // ── 6. 空包不崩溃 ───────────────────────────────────────────

    @Test
    fun `空压缩包返回 null 而不是抛异常`() {
        val bytes = zipOf()

        val result = DriverExtractor.extract(context, uriOf(bytes), "empty-pack")

        assertNull(result)
    }
}
