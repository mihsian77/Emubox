package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载项目归属解析。
 *
 * 这块的输入全是外部 URL，格式杂（GitHub 直链、jsdelivr 改写、raw 文件、
 * 加速层包过的 URL），所以按形态逐条钉死，避免以后改下载链路时静默失效——
 * 解析不出来不会报错，只会让下载库多一个「其他文件」组，很难被发现。
 */
class DownloadOriginTest {

    private fun parse(url: String?) = DownloadOrigins.parse(url)

    @Test
    fun `release 直链解析出 owner repo 与 tag`() {
        val o = parse("https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v26.1/Turnip-26.1.zip")
        assertEquals("StevenMXZ", o?.owner)
        assertEquals("Adreno-Tools-Drivers", o?.repo)
        assertEquals("v26.1", o?.version)
        assertEquals("stevenmxz/adreno-tools-drivers", o?.key)
    }

    @Test
    fun `latest 直链按 latest 作为版本`() {
        val o = parse("https://github.com/K11MCH1/AdrenoToolsDrivers/releases/latest/download/driver.zip")
        assertEquals("K11MCH1", o?.owner)
        assertEquals("latest", o?.version)
    }

    @Test
    fun `jsdelivr 改写后仍能解析`() {
        val o = parse("https://cdn.jsdelivr.net/gh/The412Banner/winlator-contents@main/contents.json")
        assertEquals("The412Banner", o?.owner)
        assertEquals("winlator-contents", o?.repo)
        assertEquals("main", o?.version)
    }

    @Test
    fun `raw githubusercontent 能解析`() {
        val o = parse("https://raw.githubusercontent.com/mihsian77/Emubox/main/driver-registry.json")
        assertEquals("mihsian77", o?.owner)
        assertEquals("Emubox", o?.repo)
        assertEquals("main", o?.version)
    }

    @Test
    fun `仓库名带 git 后缀时去掉`() {
        val o = parse("https://github.com/owner/project.git/releases/download/v1/a.zip")
        assertEquals("project", o?.repo)
    }

    @Test
    fun `非 github 直链不猜测`() {
        assertNull(parse("https://objects.githubusercontent.com/github-production-release-asset/1/2?X-Amz-Signature=x"))
        assertNull(parse("https://d.apkpure.com/b/APK/com.example.app?version=latest"))
        assertNull(parse(""))
        assertNull(parse(null))
    }

    @Test
    fun `解析不出仓库时用下载目录分类兜底`() {
        assertEquals("dir:Drivers", DownloadOrigins.fallbackKey("a.zip", "Drivers/Winlator"))
        assertEquals("Drivers", DownloadOrigins.fallbackLabel("Drivers/Winlator", "a.zip"))
        // 目录也没有时按扩展名分组，至少同类文件挨在一起
        assertEquals("file:zip", DownloadOrigins.fallbackKey("a.zip", ""))
        assertTrue(DownloadOrigins.fallbackLabel("", "a.zip").contains("ZIP"))
    }

    @Test
    fun `同一仓库不同版本的记录归到同一组`() {
        val a = DownloadsManager.CompletedDownload(
            id = "1", fileName = "Turnip-26.0.zip", filePath = "/x/a", sizeBytes = 10,
            timestamp = 1000, subPath = "Drivers",
            sourceUrl = "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v26.0/Turnip-26.0.zip"
        )
        val b = a.copy(
            id = "2", fileName = "Turnip-26.1.zip", timestamp = 2000,
            sourceUrl = "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v26.1/Turnip-26.1.zip"
        )
        assertEquals(a.projectKey, b.projectKey)
        assertEquals("Adreno-Tools-Drivers", a.projectLabel)
    }
}
