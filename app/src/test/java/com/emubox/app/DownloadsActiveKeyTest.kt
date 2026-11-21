package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动下载表的键。
 *
 * 背景：文件名只在同一子目录内唯一。turnip 与 adreno 两个驱动可以都叫
 * libvulkan.so，分别落在 Drivers/Turnip 与 Drivers/Adreno 下。以前整张活动表
 * 拿裸 fileName 当键，后启动的那个直接顶掉前一条记录，于是：
 *
 * - 前者的进度回写到后者身上（界面进度来回跳）；
 * - 前者下载完成时按同一个键删记录，删掉的是后者；
 * - pause / cancel 也打在后者身上，前者想停停不下来。
 *
 * 这几条断言钉住的就是「同名不同目录必须是两条独立记录」。
 */
class DownloadsActiveKeyTest {

    private fun active(fileName: String, subPath: String) = DownloadsManager.ActiveDownload(
        fileName = fileName,
        progress = 0,
        totalBytes = 100L,
        downloadedBytes = 0L,
        url = "https://example.com/$fileName",
        outputUri = "file:///tmp/$subPath/$fileName",
        subPath = subPath
    )

    @Test
    fun `同名文件在不同子目录下键不同`() {
        val turnip = activeDownloadKey("libvulkan.so", "Drivers/Turnip")
        val adreno = activeDownloadKey("libvulkan.so", "Drivers/Adreno")
        assertNotEquals(turnip, adreno)
        assertEquals("Drivers/Turnip/libvulkan.so", turnip)
    }

    @Test
    fun `键的最后一段就是文件名`() {
        val key = activeDownloadKey("v37.zip", "Components/Bundle-REF4IK")
        assertEquals("v37.zip", key.substringAfterLast('/'))
    }

    @Test
    fun `根目录不拼前缀且空路径与斜杠等价`() {
        assertEquals("v37.zip", activeDownloadKey("v37.zip", ""))
        assertEquals("v37.zip", activeDownloadKey("v37.zip", "/"))
    }

    @Test
    fun `两条同名不同目录的活动下载互不覆盖`() {
        DownloadsManager.replaceActiveForTest(
            listOf(
                active("libvulkan.so", "Drivers/Turnip"),
                active("libvulkan.so", "Drivers/Adreno")
            )
        )
        // 旧实现这里只有 1 条：后写入的把前一条顶掉了
        assertEquals(2, DownloadsManager.activeDownloads.size)

        val turnipKey = activeDownloadKey("libvulkan.so", "Drivers/Turnip")
        val adrenoKey = activeDownloadKey("libvulkan.so", "Drivers/Adreno")
        DownloadsManager.updateProgress(turnipKey, 60L)

        assertEquals(60L, DownloadsManager.activeDownloads[turnipKey]!!.downloadedBytes)
        assertEquals(0L, DownloadsManager.activeDownloads[adrenoKey]!!.downloadedBytes)
    }

    @Test
    fun `完成一条只清掉自己并只记一条记录`() {
        DownloadsManager.replaceCompletedForTest(emptyList())
        DownloadsManager.replaceActiveForTest(
            listOf(
                active("libvulkan.so", "Drivers/Turnip"),
                active("libvulkan.so", "Drivers/Adreno")
            )
        )

        DownloadsManager.completeDownload(
            activeDownloadKey("libvulkan.so", "Drivers/Turnip"),
            "/tmp/Drivers/Turnip/libvulkan.so",
            100L,
            "Drivers/Turnip"
        )

        // 另一条必须原地不动
        assertEquals(1, DownloadsManager.activeDownloads.size)
        assertNotNull(DownloadsManager.activeDownloads[activeDownloadKey("libvulkan.so", "Drivers/Adreno")])

        assertEquals(1, DownloadsManager.completedDownloads.size)
        assertEquals("Drivers/Turnip", DownloadsManager.completedDownloads[0].subPath)
        assertEquals("libvulkan.so", DownloadsManager.completedDownloads[0].fileName)

        assertFalse(DownloadsManager.isActive("libvulkan.so", "Drivers/Turnip"))
        assertTrue(DownloadsManager.isActive("libvulkan.so", "Drivers/Adreno"))
    }
}
