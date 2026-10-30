package com.emubox.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 新闻条目（news-data.json 由 CI 每日刷新；官方组件/运行库条目带 type 字段） */
data class NewsItem(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Long,   // epoch 秒
    val category: String,    // emulator / driver / component / runtime / eco / general
    val summary: String,
    val type: String = "",   // 官方组件/运行库类型：DXVK / VKD3D-Proton / Box64 / Proton-GE 等
    val body: String = ""    // 全文（IT之家正文 / 官方 release 完整日志），详情弹窗阅读用
) {
    /** 分类展示名（随 App 语言由 UI 层映射，此处仅存机器分类） */
    val categoryLabel: String
        get() = when (category) {
            "emulator" -> "模拟器"
            "driver" -> "驱动组件"
            "component" -> "组件"
            "runtime" -> "运行库"
            "eco" -> "生态"
            else -> "泛科技"
        }
}

/** 新闻仓库：远程加载 news-data.json（GitHub raw + jsDelivr），失败兜底内置离线副本。
 *  离线副本由 scripts/refresh-news.py 与 CI 同步，App 无网也能展示最近一期。 */
object NewsRepository {

    private const val NEWS_URL_PRIMARY =
        "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/news-data.json"
    private const val NEWS_URL_JSDELIVR =
        "https://cdn.jsdelivr.net/gh/mihsian77/EmuHub-CN@main/news-data.json"
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
                            title = o.optString("title", "").trim().take(140),
                            url = o.optString("url"),
                            publishedAt = o.optLong("published_at", 0L),
                            category = o.optString("category", "general"),
                            summary = o.optString("summary", "").trim().take(160),
                            type = o.optString("type", ""),
                            body = o.optString("body", "")
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
        val fetched = withContext(Dispatchers.IO) {
            try {
                fetchUrl(NEWS_URL_PRIMARY)?.let { parseNews(it) }
                    ?: fetchUrl(NEWS_URL_JSDELIVR)?.let { parseNews(it) }
            } catch (_: Exception) {
                null
            }
        }
        if (fetched != null && fetched.isNotEmpty()) {
            cached = fetched
            cachedSource = "remote"
            return fetched
        }
        return loadFromAssets(context) ?: emptyList()
    }

    private fun fetchUrl(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "EmuHub-CN/1.3")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        cached = null
    }
}
