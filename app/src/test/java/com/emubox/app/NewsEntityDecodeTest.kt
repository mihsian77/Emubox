package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新闻文本 HTML 实体解码。
 *
 * 用例取自 news_offline.json 里真实存在的 6 条标题（2026-11 抓取），
 * 这些条目的实体没有被脚本替换，未解码时会原样显示成「&#183;」。
 */
class NewsEntityDecodeTest {

    @Test
    fun 数字实体解码为正常字符() {
        assertEquals(
            "酷狗音乐 22 周年 · 鸿蒙特别版 App 官宣“即将上线”，为 HarmonyOS 7 全新设计",
            decodeHtmlEntities("酷狗音乐 22 周年 &#183; 鸿蒙特别版 App 官宣“即将上线”，为 HarmonyOS 7 全新设计")
        )
        assertEquals(
            "《戴帽子的猫：雨天大乱斗》Steam 发售，国区售价 ¥136",
            decodeHtmlEntities("《戴帽子的猫：雨天大乱斗》Steam 发售，国区售价 &#165;136")
        )
        assertEquals(
            "标配 ±12° 后轮转向",
            decodeHtmlEntities("标配 &#177;12&#176; 后轮转向")
        )
    }

    @Test
    fun 命名实体与十六进制实体都能解码() {
        assertEquals("a \"b\" & c", decodeHtmlEntities("a &quot;b&quot; &amp; c"))
        assertEquals("中间 · 点", decodeHtmlEntities("中间 &middot; 点"))
        assertEquals("·", decodeHtmlEntities("&#xB7;"))
        assertEquals("·", decodeHtmlEntities("&#xb7;"))
    }

    @Test
    fun 未知实体与残缺实体原样保留() {
        // 没有分号、不认识的名字、越界码点都不应被吞掉
        assertEquals("100 &#", decodeHtmlEntities("100 &#"))
        assertEquals("&unknownentity; 保留", decodeHtmlEntities("&unknownentity; 保留"))
        assertEquals("&#0;", decodeHtmlEntities("&#0;"))
        assertEquals("&#x110000;", decodeHtmlEntities("&#x110000;"))
    }

    @Test
    fun 无实体时不做多余处理() {
        val plain = "普通标题 · 没有实体"
        assertTrue(decodeHtmlEntities(plain) === plain) // 直接返回原对象，不新建字符串
        assertEquals("", decodeHtmlEntities(""))
    }

    @Test
    fun 解析新闻时标题摘要正文都会解码() {
        val json = """
            {"items":[{"id":"t1","title":"A &#183; B","url":"u","published_at":1,
            "category":"device","summary":"售价 &#165;99","body":"正文 &amp; 说明"}]}
        """.trimIndent()
        val item = NewsRepository.parseNews(json)!!.single()
        assertEquals("A · B", item.title)
        assertEquals("售价 ¥99", item.summary)
        assertEquals("正文 & 说明", item.body)
    }
}
