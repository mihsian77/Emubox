package com.emubox.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 新闻条目（news-data.json 由 CI 每日刷新；官方组件/运行库条目带 type 字段） */
data class NewsItem(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Long,   // epoch 秒
    val category: String,    // emulator / driver / component / runtime / eco / general
    val summary: String,
    val type: String = "",   // 官方组件/运行库类型：DXVK / VKD3D-Proton / Box64 / Proton-GE 等
    val body: String = "",   // 全文（资讯正文 / 官方 release 完整日志），详情弹窗阅读用
    val tag: String = "",    // 资讯具体主题标签（脚本按标题提取：系统/芯片/手机/游戏/AI 等）
    val image: String = "",  // 缩略图 URL（IT之家 img.ithome.com，卡片列表用）
    val commentCount: Int = 0, // 评论数（官方接口 commentcount / 栏目页 review-num）
    val section: String = "",  // 分区（emulator / device / tech），App 分区过滤用
    val channel: String = ""   // 一级栏目（软件之家 / Linux / Windows / 数码之家 等）
) {
    /** 分类展示名：资讯条目优先显示具体主题标签（tag），无 tag（官方条目）回退大类名 */
    val categoryLabel: String
        get() = tag.ifBlank { when (category) {
            "emulator" -> "模拟器"
            "driver" -> "驱动组件"
            "component" -> "组件"
            "runtime" -> "运行库"
            "device" -> "数码"
            "eco" -> "生态"
            else -> "泛科技"
        } }
}

/** 新闻仓库：远程加载 news-data.json（GitHub raw + jsDelivr），失败兜底内置离线副本。
 *  离线副本由 scripts/refresh-news.py 与 CI 同步，App 无网也能展示最近一期。 */
object NewsRepository {

    private const val NEWS_URL_PRIMARY =
        "https://raw.githubusercontent.com/mihsian77/Emubox/main/news-data.json"
    private const val OFFLINE_ASSET = "news_offline.json"

    @Volatile
    private var cached: List<NewsItem>? = null

    @Volatile
    private var cachedSource = "assets"

    /** 最近一次加载来源（供 UI 标注：remote / jsdelivr / assets） */
    fun lastSource(): String = cachedSource

    fun parseNews(body: String): List<NewsItem>? {
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        NewsItem(
                            id = o.optString("id"),
                            title = truncateSafely(
                                stripInlineMarkdown(decodeHtmlEntities(o.optString("title", ""))).trim(), 140
                            ),
                            url = o.optString("url"),
                            publishedAt = o.optLong("published_at", 0L),
                            category = o.optString("category", "general"),
                            summary = truncateSafely(
                                stripInlineMarkdown(decodeHtmlEntities(o.optString("summary", ""))).trim(), 160
                            ),
                            type = o.optString("type", ""),
                            body = decodeHtmlEntities(o.optString("body", "")),
                            tag = o.optString("tag", ""),
                            image = o.optString("image", ""),
                            commentCount = o.optInt("commentcount", 0),
                            section = o.optString("section", ""),
                            channel = o.optString("channel", "")
                        )
                    )
                }
            }.sortedByDescending { it.publishedAt }
        } catch (_: Exception) {
            null
        }
    }

    fun loadFromAssets(context: Context?): List<NewsItem>? {
        if (context == null) return null
        cached?.let { return it }
        return try {
            val body = context.assets.open(OFFLINE_ASSET).bufferedReader().readText()
            parseNews(body)?.also {
                cached = it
                cachedSource = "assets"
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 远程拉取（raw → jsDelivr），失败回退内置离线副本 */
    suspend fun loadNews(context: Context): List<NewsItem> {
        cached?.let { return it }
        // 多通道竞速取数：以前固定先试 raw 直连，8 秒超时后才回退 jsDelivr，
        // 国内网络下 raw 基本不通，等于每次白等 8 秒。
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                RemoteData.fetchText(NEWS_URL_PRIMARY)?.let { parseNews(it) }
            }.getOrNull()
        }
        if (fetched != null && fetched.isNotEmpty()) {
            cached = fetched
            cachedSource = RemoteData.lastChannel
            return fetched
        }
        return loadFromAssets(context) ?: emptyList()
    }

    fun clearCache() {
        cached = null
    }
}

/**
 * 解码新闻文本里的 HTML 实体。
 *
 * IT之家接口与栏目页会直接返回 `&#183;`（·）、`&#165;`（¥）、`&quot;` 这类实体；
 * 脚本侧只替换了 6 个常见实体，其余的会原样落库，而 Compose 的 Text 不做 HTML 解析，
 * 界面上就会直接看见「酷狗音乐 22 周年 &#183; 鸿蒙特别版」。这里统一兜底。
 */
private val HTML_ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,9});")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "middot" to "·", "hellip" to "…", "mdash" to "—", "ndash" to "–",
    "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "times" to "×"
)

/**
 * 摘要/标题来自各项目 README 与 release 正文，常带 `**粗体**`、`[文字](链接)`。
 * 客户端必须自己再过一遍：数据每天重生成，源站格式不受我们控制。
 */
internal fun stripInlineMarkdown(raw: String): String {
    if (raw.isEmpty()) return raw
    var t = raw
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    // 截断经常把成对标记切成半个，剩下的标记符号直接清掉
    t = t.replace("**", "")
    return t.trim()
}

/** 按 UTF-16 长度截断，但不切断代理对（否则 emoji 会变成两个乱码方块） */
internal fun truncateSafely(s: String, max: Int): String {
    if (max <= 0) return ""
    if (s.length <= max) return s
    val cut = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
    return s.substring(0, cut)
}

internal fun decodeHtmlEntities(raw: String): String {
    if (raw.isEmpty() || '&' !in raw) return raw
    return HTML_ENTITY.replace(raw) { m ->
        val token = m.groupValues[1]
        if (token.startsWith("#")) {
            val code = if (token.length > 1 && (token[1] == 'x' || token[1] == 'X')) {
                token.substring(2).toIntOrNull(16)
            } else {
                token.substring(1).toIntOrNull()
            }
            // 过滤代理区（0xD800–0xDFFF）与越界码点，避免造出非法字符
            code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: m.value
        } else {
            NAMED_ENTITIES[token.lowercase()] ?: m.value
        }
    }
}
