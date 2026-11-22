package com.emubox.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传的内容一致性。
 *
 * 背景：自动重试会在加速节点之间轮换。第 1 轮走节点 A 下到 60% 断流，第 2 轮换
 * 节点 B 只发 `Range: bytes=<60%>-`。若 B 的缓存是另一个版本的同名资产，服务器
 * 会正常返回 206 + 新版本的后 40%，客户端追加后就得到「旧版前段 + 新版后段」的
 * 混合文件：
 *
 * - 有 sha256 的来源（驱动下载）：校验失败，白下一整轮，用户看到"校验失败已删除"；
 * - 没有指纹的来源（组件下载）：直接当成功，坏文件被装进模拟器。
 *
 * 这几条断言钉住三道闸门的判定：If-Range 的值、Content-Range 起点、ETag 比对，
 * 以及"没有指纹就不跨节点续传"这条保守策略。
 */
class DownloadsResumeValidatorTest {

    // ── 解析 ──────────────────────────────────────────────

    @Test
    fun `解析 Content-Range 起点`() {
        assertEquals(1024L, parseContentRangeStart("bytes 1024-2047/4096"))
        assertEquals(0L, parseContentRangeStart("bytes 0-4095/4096"))
        assertEquals(1024L, parseContentRangeStart("BYTES 1024-2047/4096"))
    }

    @Test
    fun `Content-Range 解析不出时返回负一`() {
        assertEquals(-1L, parseContentRangeStart(null))
        assertEquals(-1L, parseContentRangeStart(""))
        // 416 的响应是 `bytes */12345`，没有区间，不能当成起点 0
        assertEquals(-1L, parseContentRangeStart("bytes */12345"))
        assertEquals(-1L, parseContentRangeStart("garbage"))
    }

    // ── 判定 ──────────────────────────────────────────────

    @Test
    fun `没请求续传一律从头写`() {
        assertEquals(
            ResumeVerdict.RESTART_FROM_SERVER,
            judgeResumeResponse(
                requestedOffset = 0L,
                responseCode = 200,
                contentRangeStart = -1L,
                responseEtag = null,
                storedEtag = null
            )
        )
    }

    @Test
    fun `服务端忽略 Range 返回 200 时从头写`() {
        assertEquals(
            ResumeVerdict.RESTART_FROM_SERVER,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 200,
                contentRangeStart = -1L,
                responseEtag = "\"new\"",
                storedEtag = "\"old\""
            )
        )
    }

    @Test
    fun `206 且起点与 ETag 都一致才追加`() {
        assertEquals(
            ResumeVerdict.APPEND,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 206,
                contentRangeStart = 1000L,
                responseEtag = "\"abc\"",
                storedEtag = "\"abc\""
            )
        )
    }

    @Test
    fun `206 起点错位必须丢弃本地片段`() {
        // 代理没照办 Range，但从 0 开始回 206：直接追加会在文件中间插一段重复内容
        assertEquals(
            ResumeVerdict.DISCARD_PARTIAL,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 206,
                contentRangeStart = 0L,
                responseEtag = "\"abc\"",
                storedEtag = "\"abc\""
            )
        )
    }

    @Test
    fun `206 但 ETag 变了说明文件换版，前段作废`() {
        assertEquals(
            ResumeVerdict.DISCARD_PARTIAL,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 206,
                contentRangeStart = 1000L,
                responseEtag = "\"v2\"",
                storedEtag = "\"v1\""
            )
        )
    }

    @Test
    fun `换节点且响应没带 ETag 时不敢续`() {
        // 节点剥掉了 ETag 头：既没法比对，也没法证明仍是同一份文件
        assertEquals(
            ResumeVerdict.DISCARD_PARTIAL,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 206,
                contentRangeStart = 1000L,
                responseEtag = null,
                storedEtag = "\"v1\"",
                nodeChanged = true
            )
        )
    }

    @Test
    fun `同节点且本地无指纹时允许续传`() {
        // 同一节点的同一 URL 内容稳定，缺指纹不影响判断
        assertEquals(
            ResumeVerdict.APPEND,
            judgeResumeResponse(
                requestedOffset = 1000L,
                responseCode = 206,
                contentRangeStart = 1000L,
                responseEtag = null,
                storedEtag = "",
                nodeChanged = false
            )
        )
    }

    // ── ETag 宽松比较 ──────────────────────────────────────

    @Test
    fun `弱标记与引号差异不算换版`() {
        // 代理层常把 "abc" 改写成 abc 或 W/"abc"，值没变
        assertTrue(etagSameContent("\"abc\"", "abc"))
        assertTrue(etagSameContent("W/\"abc\"", "\"abc\""))
        assertFalse(etagSameContent("ABC", "abc")) // 大小写不同就是不同内容
        assertFalse(etagSameContent("\"v1\"", "\"v2\""))
    }

    @Test
    fun `缺一边时不做阻断`() {
        assertTrue(etagSameContent(null, "\"v1\""))
        assertTrue(etagSameContent("\"v1\"", null))
        assertTrue(etagSameContent("", ""))
    }

    // ── If-Range 取值 ─────────────────────────────────────

    @Test
    fun `If-Range 优先用强 ETag`() {
        assertEquals("\"abc\"", ifRangeValue("\"abc\"", "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `弱 ETag 不能用于 If-Range，退回 Last-Modified`() {
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", ifRangeValue("W/\"abc\"", "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `两个都没有时不给 If-Range`() {
        assertNull(ifRangeValue("", ""))
        assertNull(ifRangeValue("W/\"abc\"", ""))
    }

    // ── 有无指纹 ──────────────────────────────────────────

    @Test
    fun `任意一种指纹都算有依据`() {
        assertTrue(hasResumeValidator("\"abc\"", ""))
        assertTrue(hasResumeValidator("", "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertFalse(hasResumeValidator("", ""))
    }

    // ── 活动表持久化 ───────────────────────────────────────

    @Test
    fun `ETag 随活动表持久化并能恢复`() {
        val original = DownloadsManager.ActiveDownload(
            fileName = "libvulkan.so",
            progress = 40,
            totalBytes = 1000L,
            downloadedBytes = 400L,
            url = "https://example.com/libvulkan.so",
            outputUri = "file:///tmp/Drivers/Turnip/libvulkan.so",
            subPath = "Drivers/Turnip",
            etag = "\"abc123\"",
            lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        )
        val restored = DownloadsManager.ActiveDownload.fromJson(JSONObject(original.toJson().toString()))
        assertEquals("\"abc123\"", restored?.etag)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", restored?.lastModified)
    }

    @Test
    fun `老记录没有 ETag 字段时恢复为空串`() {
        val legacy = JSONObject(
            """{"fileName":"a.zip","url":"https://e.com/a.zip","outputUri":"file:///tmp/a.zip",
               "totalBytes":100,"downloadedBytes":50,"status":"DOWNLOADING"}"""
        )
        val restored = DownloadsManager.ActiveDownload.fromJson(legacy)
        assertEquals("", restored?.etag)
        assertEquals("", restored?.lastModified)
    }

    @Test
    fun `空值不清掉已记录的指纹`() {
        DownloadsManager.replaceActiveForTest(
            listOf(
                DownloadsManager.ActiveDownload(
                    fileName = "x.zip",
                    progress = 0,
                    totalBytes = 100L,
                    downloadedBytes = 10L,
                    outputUri = "file:///tmp/x.zip",
                    etag = "\"keep\"",
                    lastModified = "lm-keep"
                )
            )
        )
        val key = activeDownloadKey("x.zip", "")
        // 206 响应里没有这两个头：不能把已有的依据抹掉
        DownloadsManager.updateValidators(key, null, "")
        assertEquals("\"keep\"", DownloadsManager.activeDownloads[key]?.etag)
        assertEquals("lm-keep", DownloadsManager.activeDownloads[key]?.lastModified)

        DownloadsManager.updateValidators(key, "\"new\"", null)
        assertEquals("\"new\"", DownloadsManager.activeDownloads[key]?.etag)
        assertEquals("lm-keep", DownloadsManager.activeDownloads[key]?.lastModified)
    }

    // ── 校验失败不能落成"已完成" ─────────────────────────────

    @Test
    fun `字节写满但校验失败时不得转成已完成`() {
        DownloadsManager.replaceCompletedForTest(emptyList())
        DownloadsManager.replaceActiveForTest(
            listOf(
                DownloadsManager.ActiveDownload(
                    fileName = "bad.zip",
                    progress = 100,
                    totalBytes = 100L,
                    downloadedBytes = 100L,
                    outputUri = "file:///tmp/bad.zip",
                    subPath = "Drivers"
                )
            )
        )
        val key = activeDownloadKey("bad.zip", "Drivers")

        // 不带 forceFail 的老行为：字节达标 → 转 completed（死记录，文件已被删）
        DownloadsManager.failDownload(key)
        assertEquals(1, DownloadsManager.completedDownloads.size)

        // 校验失败路径必须走 forceFail，一条记录都不留
        DownloadsManager.replaceCompletedForTest(emptyList())
        DownloadsManager.replaceActiveForTest(
            listOf(
                DownloadsManager.ActiveDownload(
                    fileName = "bad2.zip",
                    progress = 100,
                    totalBytes = 100L,
                    downloadedBytes = 100L,
                    outputUri = "file:///tmp/bad2.zip",
                    subPath = "Drivers"
                )
            )
        )
        DownloadsManager.failDownload(activeDownloadKey("bad2.zip", "Drivers"), "校验失败", forceFail = true)
        assertEquals(0, DownloadsManager.completedDownloads.size)
        assertNull(DownloadsManager.activeDownloads[activeDownloadKey("bad2.zip", "Drivers")])
    }
}
