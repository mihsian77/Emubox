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
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 驱动提取的原子性回归。
 *
 * 三条断言对应三个曾经真实发生的故障：
 * 1. 同 id 重新提取后旧 .so 残留 → 主驱动可能挑到上一版；
 * 2. 提取失败把已装好的驱动删掉 → 之后所有测试都报「未找到 .so」；
 * 3. 多架构包 32 位覆盖 64 位 → 加载 32 位库必失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverExtractorAtomicTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例从干净目录开始，避免用例间互相污染
        File(context.filesDir, "drivers").deleteRecursively()
    }

    private fun uriOf(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.emubox.test/atomic-${System.nanoTime()}.zip")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun uriOfStream(stream: InputStream): Uri {
        val uri = Uri.parse("content://com.emubox.test/atomic-${System.nanoTime()}.zip")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return uri
    }

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

    private fun soNames(driverId: String): List<String> =
        DriverExtractor.driverDir(context, driverId)
            .listFiles { f -> f.extension == "so" }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    // ── 1. 同 id 重复提取，旧 .so 不得残留 ──────────────────────────

    @Test
    fun `同一驱动二次提取后目录里只有新版 so`() {
        val v1 = zipOf("libvulkan_old.so" to ByteArray(64) { 0x01 })
        val v2 = zipOf("libvulkan_new.so" to ByteArray(64) { 0x02 })

        assertNotNull(DriverExtractor.extract(context, uriOf(v1), "repack"))
        assertEquals(listOf("libvulkan_old.so"), soNames("repack"))

        val second = DriverExtractor.extract(context, uriOf(v2), "repack")

        assertNotNull(second)
        assertEquals("新版应该替换掉旧版，而不是叠加", listOf("libvulkan_new.so"), soNames("repack"))
        assertEquals("libvulkan_new.so", second!!.name)
    }

    // ── 2. 提取失败不得动已装驱动 ──────────────────────────────────

    @Test
    fun `提取失败时已装好的驱动仍然可用`() {
        val good = zipOf("libvulkan_good.so" to ByteArray(64) { 0x03 })
        val installed = DriverExtractor.extract(context, uriOf(good), "keepme")
        assertNotNull(installed)
        val pathBefore = installed!!.absolutePath

        // 模拟网络流读到一半断掉
        val broken = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (++served > 8) throw IOException("connection reset")
                return 0x50
            }
        }
        val failed = DriverExtractor.extract(context, uriOfStream(broken), "keepme")

        assertNull("损坏包应返回 null", failed)
        val still = DriverExtractor.getSoFile(context, "keepme")
        assertNotNull("已装驱动不能被失败的重测删掉", still)
        assertEquals(pathBefore, still!!.absolutePath)
        assertTrue(still.exists())
    }

    // ── 3. 多架构包只留 arm64 ─────────────────────────────────────

    @Test
    fun `多架构包只保留 arm64 的 so，不被 32 位覆盖`() {
        val bytes = zipOf(
            "arm64-v8a/libvulkan.so" to "ARM64-CONTENT".toByteArray(),
            "armeabi-v7a/libvulkan.so" to "ARM32-CONTENT".toByteArray(),
            "x86_64/libvulkan.so" to "X64-CONTENT".toByteArray()
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "multi-abi")

        assertNotNull(result)
        assertEquals(listOf("libvulkan.so"), soNames("multi-abi"))
        assertEquals(
            "留下的必须是 arm64 那份",
            "ARM64-CONTENT",
            result!!.readText()
        )
    }

    // ── 4. 中性目录（不带 ABI 名）照收，别过度过滤 ────────────────

    @Test
    fun `不带 ABI 目录的 so 仍会被提取`() {
        val bytes = zipOf(
            "meta.json" to """{"libraryName":"libvulkan_freedreno.so"}""".toByteArray(),
            "lib/libvulkan_freedreno.so" to ByteArray(128) { 0x7f }
        )

        val result = DriverExtractor.extract(context, uriOf(bytes), "neutral-dir")

        assertNotNull("中性目录不能因为不带 arm64 字样就被跳过", result)
        assertEquals("libvulkan_freedreno.so", result!!.name)
    }

    // ── 5. 失败后不留 staging / old 残留 ──────────────────────────

    @Test
    fun `失败路径不留下临时目录`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        DriverExtractor.extract(context, uriOfStream(broken), "cleanup")

        val leftovers = File(context.filesDir, "drivers")
            .listFiles()
            ?.map { it.name }
            .orEmpty()
        assertTrue("不应残留 staging/old：$leftovers", leftovers.none { it.endsWith(".staging") || it.endsWith(".old") })
    }
}
