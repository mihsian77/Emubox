package com.emubox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线翻译引擎：把英文更新日志 / 新闻正文译成简体中文。
 *
 * 旧实现的两个硬伤（用户反馈"超 500 字符就翻译错误"的根因）：
 *  1. 只按换行分块。一整段没有换行的长日志（GitHub release 极常见）被当成"短文本"
 *     走单次请求，直接撞上 MyMemory 的 500 字符上限，返回警告串——而旧代码只比对
 *     `translatedText == "MYMEMORY WARNING"`，实际警告后面还跟着一长句说明，
 *     于是**把警告原文当译文显示**。
 *  2. `translateText` 直接 `take(1500)` 截断，长正文后 2/3 永远没有译文。
 *
 * 现在的做法：
 *  - 四层语义切块（换行 → 句末标点 → 空格 → 硬切），任何长度的文本都能切成 ≤450 字符；
 *  - 明确识别 MyMemory 的限流/非法参数警告，当成失败而不是译文；
 *  - 单块失败重试一次，仍失败就保留该块原文（中英混排好过整段丢失）；
 *  - 引擎可切换：默认 MyMemory（免费、国内直连），填了 DeepSeek Key 自动改走 DeepSeek。
 */
object TranslationEngine {

    /** 单块上限：MyMemory 单请求 500 字符，留 50 给 URL 编码膨胀 */
    const val CHUNK_LIMIT = 450

    /** 单次翻译的原文上限，超过则只译前面的部分（防止一次点开跑几十个请求） */
    const val MAX_INPUT = 6000

    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    private data class CacheEntry(val text: String, val at: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // ---------------------------------------------------------------- 切块

    /**
     * 把文本切成每块 ≤ limit 的片段，拼接结果与原文完全一致（不丢字、不加字）。
     *
     * 切分优先级：换行 > 句末标点 > 空格 > 硬切。先切成"不超过 limit 的语义单元"，
     * 再把相邻单元贪心合并到接近 limit，既减少请求次数，又不会把句子拦腰截断。
     */
    fun splitChunks(text: String, limit: Int = CHUNK_LIMIT): List<String> {
        if (text.isEmpty()) return emptyList()
        if (limit <= 0) return listOf(text)
        if (text.length <= limit) return listOf(text)

        val units = semanticUnits(text, limit)
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (u in units) {
            if (buf.isNotEmpty() && buf.length + u.length > limit) {
                out += buf.toString()
                buf.setLength(0)
            }
            buf.append(u)
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /** 语义单元：每个长度 ≤ limit，拼接后等于入参。 */
    private fun semanticUnits(s: String, limit: Int): List<String> {
        if (s.length <= limit) return listOf(s)
        // ① 换行
        splitKeep(s, setOf('\n')).let { if (it.size > 1) return it.flatMap { p -> semanticUnits(p, limit) } }
        // ② 句末标点（英文句号后紧跟空格才算断句，避免把 3.5 拆开）
        splitSentences(s).let { if (it.size > 1) return it.flatMap { p -> semanticUnits(p, limit) } }
        // ③ 空格
        splitKeep(s, setOf(' ')).let { if (it.size > 1) return it.flatMap { p -> semanticUnits(p, limit) } }
        // ④ 实在没有断点（超长 URL / 哈希串）按长度硬切
        return s.chunked(limit)
    }

    private fun splitKeep(s: String, seps: Set<Char>): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] in seps) {
                out += s.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** 按句末标点切分，标点留在前一句末尾；英文句号要求后接空白或行尾。 */
    private fun splitSentences(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in s.indices) {
            val c = s[i]
            val isBreak = when (c) {
                '。', '！', '？', '；' -> true
                '.', '!', '?', ';' -> i == s.lastIndex || s[i + 1].isWhitespace() || s[i + 1] == '\n'
                else -> false
            }
            if (isBreak) {
                // 把紧随其后的空白一起带上，保持段落间距
                var end = i + 1
                while (end < s.length && (s[end] == ' ' || s[end] == '\t')) end++
                out += s.substring(start, end)
                start = end
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    // ---------------------------------------------------------------- 提供商

    /** MyMemory 的"假译文"：限流、超长、语言对非法等都会塞进 translatedText 返回。 */
    fun isProviderWarning(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // org.json 的 optString 会把 JSON null 变成字符串 "null"，直接当译文显示就露馅了
        if (t.equals("null", ignoreCase = true)) return true
        val u = t.uppercase()
        return u.startsWith("MYMEMORY WARNING") ||
            u.contains("QUERY LENGTH LIMIT EXCEEDED") ||
            u.contains("INVALID LANGUAGE PAIR") ||
            u.contains("NO QUERY SPECIFIED") ||
            u.contains("YOU USED ALL AVAILABLE FREE TRANSLATIONS") ||
            u.contains("PLEASE SELECT TWO DISTINCT LANGUAGES")
    }

    /** 已经是中文（汉字占比够高）就不必译，省额度。 */
    fun isMostlyChinese(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        val han = text.count { it.code in 0x4E00..0x9FFF }
        return han * 100 / letters >= 45
    }

    private fun buildUrl(q: String, sl: String): String {
        val base = "https://api.mymemory.translated.net/get?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&langpair=$sl|zh-CN"
        // 填了邮箱（设置里可选）→ 每日额度 5000 → 50000 字符，官方建议做法
        val email = runCatching { SettingsManager.getTranslateEmail() }.getOrDefault("")
        return if (email.isNotBlank()) base + "&de=" + java.net.URLEncoder.encode(email, "UTF-8") else base
    }

    /** 上一次请求是否已把当日免费额度用尽（串行调用，加锁无必要） */
    @Volatile
    private var lastQuotaExhausted = false

    /** 单次请求结果。quotaExhausted=true 表示当日免费额度已用完，调用方应停止继续请求。 */
    private data class MmResult(val text: String?, val quotaExhausted: Boolean = false)

    /**
     * 调一次 MyMemory。成功返回译文，失败/警告返回 text=null。
     * 实测：同一 IP 下偶发 translatedText 为 null（HTTP 仍是 200），换源语言重试能救回来。
     */
    private fun myMemoryOnce(q: String, sl: String): MmResult {
        return try {
            val req = Request.Builder().url(buildUrl(q, sl)).header("User-Agent", "EmuHub-CN").build()
            providerClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return MmResult(null)
                val root = JSONObject(resp.body?.string().orEmpty())
                val quota = root.optBoolean("quotaFinished", false)
                if (quota) lastQuotaExhausted = true
                // MyMemory 会把换行输出成 &#10; 之类的实体，不解码会原样显示在译文里
                val t = decodeHtmlEntities(
                    root.optJSONObject("responseData")?.optString("translatedText").orEmpty()
                ).trim()
                MmResult(if (t.isEmpty() || isProviderWarning(t)) null else t, quota)
            }
        } catch (_: Exception) {
            MmResult(null)
        }
    }

    /** DeepSeek：填了 Key 才走这条路，按段落批量送，一次请求译完整段（质量远高于免费接口）。 */
    private fun deepSeekTranslate(text: String, key: String): String? {
        return try {
            val sys = "你是技术文档译者。把用户给出的软件更新日志翻译成简体中文：" +
                "保留 Markdown 结构、代码块、命令、版本号、链接和专有名词原文；" +
                "不要添加解释、不要总结、不要输出原文；只输出译文。"
            val body = JSONObject()
                .put("model", "deepseek-chat")
                .put("temperature", 0.2)
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", text)))
                .toString()
            val req = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            providerClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val arr = root.optJSONArray("choices") ?: return null
                if (arr.length() == 0) return null
                arr.getJSONObject(0).optJSONObject("message")?.optString("content")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 译一块：块级失败后按句拆开逐句重试。
     *
     * 实测 MyMemory 对同一段文本会随机返回 translatedText=null（HTTP 200、额度未耗尽），
     * 整块重试有时仍拿不到；拆成短句后多数能命中它的翻译记忆库，成功率高不少。
     * @return 译文；彻底失败返回 null，调用方保留原文
     */
    private suspend fun translateChunk(chunk: String, sl: String): String? {
        var r = myMemoryOnce(chunk, sl)
        if (r.text == null && !r.quotaExhausted) {
            delay(500)
            r = myMemoryOnce(chunk, if (sl == "en") "Autodetect" else "en")
        }
        if (r.text != null) return r.text
        if (r.quotaExhausted) return null

        // 块级两次都空 → 拆句再试（只在块够长时才拆，短块拆了没意义）
        if (chunk.length < 80) return null
        val sentences = splitChunks(chunk, 120)
        if (sentences.size <= 1) return null
        val sb = StringBuilder()
        var anyOk = false
        for ((i, s) in sentences.withIndex()) {
            if (s.isBlank()) { sb.append(s); continue }
            if (i > 0) delay(100)
            val t = myMemoryOnce(s, sl).text
            if (t != null) { sb.append(t); anyOk = true } else sb.append(s)
        }
        return if (anyOk) sb.toString() else null
    }

    // ---------------------------------------------------------------- 对外

    /**
     * 翻译入口。已经是中文、空文本、超限输入都会按明确定义处理。
     * @return 译文；整体失败返回 null（调用方回退原文）。部分块失败时该块保留英文。
     */
    suspend fun translate(text: String, preferAutoDetect: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val raw = text.trim()
            if (raw.isEmpty()) return@withContext null
            if (isMostlyChinese(raw)) return@withContext null

            val input = if (raw.length > MAX_INPUT) raw.take(MAX_INPUT) else raw
            val engineTag = runCatching { SettingsManager.getTranslateEngine() }.getOrDefault("mymemory")

            cache["$engineTag|$input"]?.let {
                if (System.currentTimeMillis() - it.at < CACHE_TTL_MS) return@withContext it.text
            }

            // ① DeepSeek（用户填了 Key 才走）
            if (engineTag == "deepseek") {
                val key = runCatching { SettingsManager.getDeepSeekKey() }.getOrDefault("")
                if (key.isNotBlank()) {
                    // 按段落切成 ≤2000 字符的批次，避免单次请求过大
                    val batches = splitChunks(input, 2000)
                    val sb = StringBuilder()
                    var ok = false
                    for ((i, b) in batches.withIndex()) {
                        if (i > 0) delay(200)
                        val r = deepSeekTranslate(b, key)
                        if (r != null) { sb.append(r); ok = true } else sb.append(b)
                    }
                    val merged = fixTerms(sb.toString().trim())
                    if (ok) {
                        cache["$engineTag|$input"] = CacheEntry(merged, System.currentTimeMillis())
                        return@withContext merged
                    }
                    // DeepSeek 失败则继续用 MyMemory 兜底
                }
            }

            // ② MyMemory（默认；免费额度按 IP 每天 5000 字符，填邮箱 50000）
            val chunks = splitChunks(input)
            val sl = if (preferAutoDetect) "Autodetect" else "en"
            val out = StringBuilder()
            var anySuccess = false
            lastQuotaExhausted = false
            for ((i, chunk) in chunks.withIndex()) {
                // 额度已空就别再发了，剩余块直接保留原文
                if (lastQuotaExhausted) {
                    out.append(chunk)
                    continue
                }
                if (chunk.isBlank()) { out.append(chunk); continue }
                if (i > 0) delay(120)  // 串行 + 轻间隔，降低触发频率限制的概率
                val translated = translateChunk(chunk, sl)
                if (translated != null) {
                    out.append(translated)
                    anySuccess = true
                } else {
                    out.append(chunk)
                }
            }
            if (!anySuccess) return@withContext null
            val merged = fixTerms(out.toString().trim())
            if (merged.isEmpty()) return@withContext null
            cache["$engineTag|$input"] = CacheEntry(merged, System.currentTimeMillis())
            merged
        }

    /**
     * 专名纠错表：机翻会把品牌/产品音译成同音字（Ludashi → 「路大师」），
     * 在业界文本里这些词必须保持原品牌名，否则用户根本认不出说的是什么。
     * 只做「错→对」的定点替换，不改动其他内容。
     */
    private val termFixes = listOf(
        "路大师" to "鲁大师", "鲁大师跑分" to "鲁大师跑分",
        "阿德雷诺" to "Adreno", "阿德里诺" to "Adreno", "阿德雷诺" to "Adreno",
        "马里图形" to "Mali GPU", "马里-G" to "Mali-G", "马里 G" to "Mali G",
        "温拉托" to "Winlator", "温莱特" to "Winlator", "温拉特" to "Winlator",
        "特尼普" to "Turnip", "特纳普" to "Turnip", "特努普" to "Turnip",
        "潘弗罗斯特" to "Panfrost", "帕恩夫罗斯特" to "Panfrost",
        "维恩" to "Wine", "普罗顿" to "Proton",
        "波克斯64" to "Box64", "博克斯64" to "Box64",
        "赛马" to "Theodore", "安兔兔" to "安兔兔",
        "高通骁龙" to "高通骁龙", "联发科天玑" to "联发科天玑",
        "埃克西诺斯" to "Exynos", "埃克斯诺斯" to "Exynos",
        "麒麟" to "麒麟", "德米恩西蒂" to "Dimensity", "迪门西蒂" to "Dimensity"
    )

    private fun fixTerms(text: String): String {
        var t = text
        for ((wrong, right) in termFixes) {
            if (wrong != right) t = t.replace(wrong, right)
        }
        return t
    }

    /** 清空缓存（设置里切换引擎/邮箱后调用，避免读到旧译文）。 */
    fun clearCache() = cache.clear()
}

/** 翻译用的 OkHttp 客户端：连 8s / 读 20s（长块读得慢） */
private val providerClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}
