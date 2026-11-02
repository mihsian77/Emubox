package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译切块与提供商警告识别。
 *
 * 用户反馈的"超 500 字符就翻译错误"根因是：旧实现只按换行切块，整段无换行的长日志
 * 被当成短文本走单次请求 → 撞 MyMemory 500 字符上限；返回的警告串又被当成译文显示。
 * 这里锁住：任何形状的长文本都能切成 ≤450 字符、拼接不失真、警告必须被识别为失败。
 */
class TranslationEngineTest {

    private val limit = TranslationEngine.CHUNK_LIMIT

    @Test
    fun 单行超长文本必须切成多块() {
        // GitHub release 里常见：一整段没有换行
        val line = "Fixed a crash when launching games with a custom driver. ".repeat(60)
        val chunks = TranslationEngine.splitChunks(line)
        assertTrue("应切成多块，实际 ${chunks.size}", chunks.size > 1)
        chunks.forEach { assertTrue("块长 ${it.length} 超过上限", it.length <= limit) }
        assertEquals(line, chunks.joinToString(""))
    }

    @Test
    fun 段落边界优先切() {
        val p1 = "Changelog:\n"
        val p2 = "- Improved Vulkan compatibility.\n"
        val p3 = "- Reduced memory usage.\n"
        val chunks = TranslationEngine.splitChunks(p1 + p2 + p3)
        // 总长不足上限时应保持一整块，不无谓拆分
        assertEquals(1, chunks.size)
        assertEquals(p1 + p2 + p3, chunks[0])
    }

    @Test
    fun 超长无断点串硬切且不失真() {
        val blob = "a".repeat(1000)
        val chunks = TranslationEngine.splitChunks(blob)
        assertEquals(3, chunks.size)  // 450 + 450 + 100
        assertEquals(blob, chunks.joinToString(""))
    }

    @Test
    fun 长URL不丢失字符() {
        val url = "https://github.com/xodiosx/mesa-panvk-g57/releases/download/v1.4.0/" + "x".repeat(700) + ".adpkg"
        val chunks = TranslationEngine.splitChunks("见 $url 下载")
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= limit) }
        assertEquals("见 $url 下载", chunks.joinToString(""))
    }

    @Test
    fun 恰好等于上限不切() {
        val exact = "b".repeat(limit)
        assertEquals(1, TranslationEngine.splitChunks(exact).size)
    }

    @Test
    fun 空文本返回空列表() {
        assertTrue(TranslationEngine.splitChunks("").isEmpty())
    }

    @Test
    fun 识别MyMemory的假译文() {
        // 上游把限流说明塞在 translatedText 里返回，旧代码当成译文直接显示
        assertTrue(
            TranslationEngine.isProviderWarning(
                "MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY. NEXT AVAILABLE IN 07 HOURS 59 MINUTES 35 SECONDS"
            )
        )
        assertTrue(
            TranslationEngine.isProviderWarning(
                "QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS"
            )
        )
        assertFalse(TranslationEngine.isProviderWarning("修复了启动崩溃"))
    }

    @Test
    fun 中文无需翻译() {
        assertTrue(TranslationEngine.isMostlyChinese("这款模拟器修复了驱动加载失败的问题，建议所有用户更新"))
        assertFalse(TranslationEngine.isMostlyChinese("Fixed GPU driver loading on Mali devices"))
    }
}
