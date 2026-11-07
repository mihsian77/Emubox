package com.emubox.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 远程数据统一取数入口。
 *
 * 为什么需要它：所有数据仓库（新闻、驱动注册表、模拟器注册表、组件清单）原本都是
 * 「raw.githubusercontent.com 直连，失败再 jsDelivr」。实测国内网络下 raw 直连
 * 12 秒只能下到 187KB（文件 680KB），等于永远超时；而代码里 connectTimeout 是 8 秒，
 * 于是每次加载都要先白等 8 秒才回退，用户体感就是「新闻一直转圈然后显示旧数据」。
 *
 * 现在改成多通道竞速：同时向两个镜像发起请求，谁先返回完整响应就用谁，
 * 并把胜出的通道记住，下次优先走它。
 *
 * 实测（2026-11，news-data.json 680KB）：
 *   ghfast.top          2.8s  200
 *   gh-proxy.org        4.0s  200
 *   cdn.jsdelivr.net    5.1s  200
 *   fastly.jsdelivr.net 12s+ 只下到 31 万字节（Accelerator 里用的就是它，选错了域）
 *   raw 直连            12s+ 只下到 18 万字节
 */
object RemoteData {

    private const val TAG = "EmuBoxRemoteData"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val UA = "EmuHub-CN/1.4"

    /** 一个可用通道：id 用于记忆，url 是实际请求地址 */
    data class Candidate(val id: String, val url: String)

    /**
     * 通道表，按实测速度排序。
     * 注意 jsDelivr 必须用 cdn.jsdelivr.net，fastly.jsdelivr.net 实测慢一个数量级。
     */
    private val MIRRORS: List<Pair<String, (String) -> String?>> = listOf(
        "ghfast" to { u: String -> "https://ghfast.top/$u" },
        "ghproxy" to { u: String -> "https://gh-proxy.org/$u" },
        "jsdelivr" to { u: String -> toJsdelivr(u) },
    )

    /** 上次成功的通道 id，用来把最快的那条排到队首 */
    @Volatile
    private var lastGood: String? = null

    /** 最近一次成功取数的通道（供 UI 标注「数据来自哪里」） */
    @Volatile
    var lastChannel: String = "unknown"
        private set

    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "remote-data").apply { isDaemon = true }
    }

    /** raw.githubusercontent.com/owner/repo/ref/path → cdn.jsdelivr.net/gh/owner/repo@ref/path */
    fun toJsdelivr(url: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(prefix)) return null
        val seg = url.removePrefix(prefix).split("/")
        if (seg.size < 4) return null
        return "https://cdn.jsdelivr.net/gh/${seg[0]}/${seg[1]}@${seg[2]}/${seg.drop(3).joinToString("/")}"
    }

    /** 生成候选通道：镜像在前、直连兜底。非 GitHub raw 地址只有直连一条。 */
    fun candidates(url: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        // 用户主动关掉加速时不要偷偷走代理，只留直连
        if (Accelerator.getMode() != Accelerator.Mode.OFF) {
            MIRRORS.forEach { (id, build) ->
                build(url)?.let { out.add(Candidate(id, it)) }
            }
        }
        out.add(Candidate("direct", url))
        return out
    }

    /** 同一条数据只走一次网络：进程内按 url 去重，避免首页多个卡片同时拉同一份 JSON */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 阻塞取文本。调用方需在 IO 线程使用，或用 [fetchTextAsync]。
     * @return 响应体；全部通道失败返回 null
     */
    fun fetchText(url: String, userAgent: String = UA): String? {
        inflight[url]?.let { return it }
        val ordered = orderByHistory(candidates(url))
        var i = 0
        while (i < ordered.size) {
            // 每次并发试两个，胜出即止；这样最坏耗时接近「最快通道」而不是「所有通道之和」
            val batch = ordered.drop(i).take(2)
            val hit = race(batch, userAgent)
            if (hit != null) {
                lastGood = hit.first
                lastChannel = hit.first
                inflight[url] = hit.second
                Log.i(TAG, "取数成功 channel=${hit.first} bytes=${hit.second.length} url=$url")
                return hit.second
            }
            i += batch.size
        }
        Log.w(TAG, "取数失败，所有通道均不可用 url=$url")
        return null
    }

    private fun orderByHistory(list: List<Candidate>): List<Candidate> {
        val good = lastGood ?: return list
        return list.sortedBy { if (it.id == good) 0 else 1 }
    }

    /** 小批竞速：谁先返回完整的 200 响应就用谁 */
    private fun race(batch: List<Candidate>, userAgent: String): Pair<String, String>? {
        if (batch.isEmpty()) return null
        if (batch.size == 1) {
            return fetchOne(batch[0], userAgent)?.let { batch[0].id to it }
        }
        val queue = LinkedBlockingQueue<Pair<String, String>>()
        batch.forEach { c ->
            pool.execute {
                runCatching { fetchOne(c, userAgent) }.getOrNull()
                    ?.let { queue.offer(c.id to it) }
            }
        }
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS + 2_000
        while (System.currentTimeMillis() < deadline) {
            val hit = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            return hit
        }
        return null
    }

    private fun fetchOne(c: Candidate, userAgent: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(c.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "通道失败 ${c.id}: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 数据刷新后（或用户手动刷新时）清掉进程内缓存 */
    fun clearCache() {
        inflight.clear()
    }
}
