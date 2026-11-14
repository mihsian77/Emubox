package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 资讯标题/摘要的出口清洗。
 *
 * 用例取自 news_offline.json 里真实存在的条目：PanVK 的 release 正文带 `**`，
 * 摘要在 160 字处截断后常常只剩半个标记，必须由客户端兜底清掉。
 */
class NewsSummaryCleanTest {

    @Test
    fun 成对粗体标记被清掉() {
        assertEquals(
            "Brings back the full speed of the older Mali GPUs",
            stripInlineMarkdown("**Brings back the full speed of the older Mali GPUs**")
        )
    }

    @Test
    fun 截断造成的半个标记也被清掉() {
        assertEquals(
            "Fixes the gray screen on MediaTek phones",
            stripInlineMarkdown("**Fixes the gray screen on MediaTek phones")
        )
    }

    @Test
    fun 链接只保留文字() {
        assertEquals(
            "发布说明 详见",
            stripInlineMarkdown("[发布说明](https://github.com/x/y/releases) 详见")
        )
    }

    @Test
    fun 用户名里的双下划线保留() {
        assertEquals("感谢网友 Air__ 的线索投递", stripInlineMarkdown("感谢网友 Air__ 的线索投递"))
    }

    @Test
    fun 标题前面的井号被清掉() {
        assertEquals("更新内容", stripInlineMarkdown("## 更新内容"))
    }

    @Test
    fun 截断不切断代理对() {
        assertEquals("😀", truncateSafely("😀😀", 3))
        assertEquals("😀😀", truncateSafely("😀😀", 4))
    }
}
