package com.emubox.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 下载库「查看信息」读取 meta.json 的限长行为。
 *
 * 背景：DriverExtractor 早有 64KB 上限，但下载库走的是 DriverMetaParser，
 * 原来用 readText() 不限长，异常包可以让点「查看信息」时 OOM。
 * 这里锁定两点：正常偏大的 meta 仍能解析；超大 meta 快速返回 null 而不是吃内存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverMetaParserLimitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun makeDriverZip(metaJson: String): File {
        val dir = File(context.cacheDir, "meta-limit-test").apply { mkdirs() }
        val zip = File(dir, "driver-${metaJson.length}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("meta.json"))
            out.write(metaJson.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("libvulkan_fake.so"))
            out.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun `60KB 的 meta 仍能正常解析`() {
        val pad = "a".repeat(60 * 1024)
        val json = """{"name":"测试驱动","driverVersion":"v1.2.3","libraryName":"libvulkan_fake.so","pad":"$pad"}"""
        val meta = DriverMetaParser.parse(context, makeDriverZip(json).absolutePath)
        assertNotNull(meta)
        assert(meta!!.name == "测试驱动")
        assert(meta.libraryName == "libvulkan_fake.so")
    }

    @Test
    fun `1MB 的 meta 被限长截断后不抛异常也不吃内存`() {
        val pad = "b".repeat(1024 * 1024)
        val json = """{"name":"膨胀包","pad":"$pad"}"""
        val start = System.currentTimeMillis()
        val meta = DriverMetaParser.parse(context, makeDriverZip(json).absolutePath)
        val elapsed = System.currentTimeMillis() - start
        // 截断后 JSON 不完整 → 解析失败返回 null（判定为异常包），关键是快速且不 OOM
        assertNull(meta)
        assert(elapsed < 5000) { "限长读取应快速返回，实际耗时 ${elapsed}ms" }
    }

    @Test
    fun `截断后仍是完整 JSON 的超限 meta 也要拒收`() {
        // 完整对象在前，padding 在后：旧实现只读前 64KB，读到的部分 JSON 可解析，
        // 超限包会被当成正常包；修复后超限即整体拒收，无论截断点在哪儿
        val head = """{"name":"伪装包","driverVersion":"v9.9.9","author":"x"}"""
        val json = head + " ".repeat(70 * 1024) + "END"
        val meta = DriverMetaParser.parse(context, makeDriverZip(json).absolutePath)
        assertNull(meta)
    }
}
