package com.emuhub.cn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

private const val TAG = "EmuHubAccelerator"

/**
 * 流量类型：区分 Release 附件和 Raw 文件，
 * 因为 jsdelivr 等镜像只支持 Raw 文件，不支持 Release 附件。
 */
enum class TrafficKind { RELEASE_ASSET, RAW_FILE }

/**
 * 国内下载加速节点。
 * 所有节点均为公益 GitHub 代理，格式为 https://<domain>/https://github.com/...
 * trafficKinds 为空表示支持所有类型。
 */
data class ProxyNode(
    val id: String,
    val displayName: String,
    val domain: String,
    val trafficKinds: Set<TrafficKind> = emptySet()
) {
    /** 将 GitHub URL 改写为经过此代理的 URL */
    fun rewrite(url: String): String {
        val githubDomains = listOf(
            "https://github.com/", "http://github.com/",
            "https://raw.githubusercontent.com/", "http://raw.githubusercontent.com/",
            "https://objects.githubusercontent.com/",
            "https://release-assets.githubusercontent.com/",
            "https://api.github.com/", "http://api.github.com/"
        )
        for (prefix in githubDomains) {
            if (url.startsWith(prefix)) {
                return "https://$domain/$url"
            }
        }
        return url
    }

    /** 判断此节点是否支持指定流量类型 */
    fun supports(kind: TrafficKind): Boolean = trafficKinds.isEmpty() || kind in trafficKinds
}

object Accelerator {

    /** 内置加速节点列表（按推荐度排序，2026-09 验证可用） */
    val BUILTIN_NODES = listOf(
        ProxyNode("gh-proxy-org", "gh-proxy 官方", "gh-proxy.org"),
        ProxyNode("gh-proxy-com", "gh-proxy 旧域", "gh-proxy.com"),
        ProxyNode("ghfast", "ghfast 多线", "ghfast.top"),
        ProxyNode("gh-con-sh", "con.sh 公益", "gh.con.sh"),
        ProxyNode("gh-idayer", "idayer 公益", "gh.idayer.com"),
        ProxyNode("gh-proxy-net", "gh-proxy 镜像", "gh-proxy.net")
    )

    /**
     * jsdelivr CDN（Raw 文件专用，不支持 Release 附件）。
     * 格式：https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}
     */
    val JSDELIVR_NODE = ProxyNode(
        id = "jsdelivr",
        displayName = "jsdelivr CDN",
        domain = "fastly.jsdelivr.net",
        trafficKinds = setOf(TrafficKind.RAW_FILE)
    )

    /** 延迟测试用的小文件（必须是实际存在的 GitHub Raw 文件，不能用根路径） */
    private const val LATENCY_TEST_FILE =
        "https://raw.githubusercontent.com/Rodrig02005/EmuHub-APP/main/sources.json"

    /** 节点动态更新的远程列表 URL（GitHub Raw，可随时更新无需发版） */
    private const val REMOTE_NODES_URL =
        "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/accelerator-nodes.json"

    /** 直连（不加速） */
    val DIRECT_NODE = ProxyNode("direct", "直连 GitHub", "github.com")

    /** 所有可选节点 = 直连 + 内置节点 + jsdelivr */
    val ALL_NODES: List<ProxyNode>
        get() = listOf(DIRECT_NODE) + effectiveNodes + JSDELIVR_NODE

    /** 远程更新后的节点列表（null 表示使用内置列表） */
    @Volatile
    private var remoteNodes: List<ProxyNode>? = null

    /** 实际生效的节点列表（远程优先，回退内置） */
    private val effectiveNodes: List<ProxyNode>
        get() = remoteNodes ?: BUILTIN_NODES

    private val latencyClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val updateClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 加速模式 */
    enum class Mode { AUTO, MANUAL, OFF }

    /** 获取当前加速模式 */
    fun getMode(): Mode = SettingsManager.getAcceleratorMode()

    /** 设置加速模式 */
    fun setMode(mode: Mode) = SettingsManager.setAcceleratorMode(mode)

    /** 获取手动选择的节点 */
    fun getManualNode(): ProxyNode {
        val id = SettingsManager.getAcceleratorNodeId()
        return ALL_NODES.firstOrNull { it.id == id } ?: effectiveNodes.first()
    }

    /** 设置手动节点 */
    fun setManualNode(node: ProxyNode) = SettingsManager.setAcceleratorNodeId(node.id)

    /**
     * 根据当前设置获取实际使用的节点。
     * AUTO 模式返回延迟最低的节点（带缓存），MANUAL 返回手动选择，OFF 返回直连。
     */
    fun getActiveNode(): ProxyNode = when (getMode()) {
        Mode.OFF -> DIRECT_NODE
        Mode.MANUAL -> getManualNode()
        Mode.AUTO -> cachedBestNode ?: effectiveNodes.first()
    }

    @Volatile
    private var cachedBestNode: ProxyNode? = null

    /** 清除自动选择缓存（设置变更或用户手动刷新时调用） */
    fun clearCache() {
        cachedBestNode = null
    }

    /**
     * 分类 GitHub URL 的流量类型。
     * Release 附件：objects.githubusercontent.com 或 github.com/.../releases/download/
     * Raw 文件：raw.githubusercontent.com 或 github.com/.../raw/、/blob/
     */
    fun classifyTraffic(url: String): TrafficKind? {
        return when {
            url.contains("objects.githubusercontent.com") -> TrafficKind.RELEASE_ASSET
            url.contains("github.com/") && url.contains("/releases/download/") -> TrafficKind.RELEASE_ASSET
            url.contains("raw.githubusercontent.com") -> TrafficKind.RAW_FILE
            url.contains("github.com/") && (url.contains("/raw/") || url.contains("/blob/")) -> TrafficKind.RAW_FILE
            else -> null
        }
    }

    /**
     * 改写下载 URL。如果是 GitHub 链接且加速已开启，则通过代理节点访问。
     * 会根据流量类型选择支持该类型的节点（jsdelivr 只用于 Raw 文件）。
     * 非 GitHub 链接原样返回。
     */
    fun rewriteUrl(url: String): String {
        if (!isGithubUrl(url)) return url
        val node = getActiveNode()
        if (node.id == DIRECT_NODE.id) return url
        val kind = classifyTraffic(url)
        if (kind != null && !node.supports(kind)) {
            // 当前节点不支持此流量类型（如 jsdelivr 不支持 Release），回退到第一个通用节点
            val fallback = effectiveNodes.firstOrNull { it.supports(kind) } ?: return url
            return fallback.rewrite(url)
        }
        if (node.id == JSDELIVR_NODE.id) {
            return rewriteJsdelivr(url)
        }
        return node.rewrite(url)
    }

    /**
     * jsdelivr 专用重写：将 raw.githubusercontent.com URL 转换为
     * https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}
     */
    private fun rewriteJsdelivr(url: String): String {
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(rawPrefix)) return url
        val path = url.removePrefix(rawPrefix)
        val segments = path.split("/")
        if (segments.size < 4) return url
        val owner = segments[0]
        val repo = segments[1]
        val ref = segments[2]
        val filePath = segments.drop(3).joinToString("/")
        return "https://fastly.jsdelivr.net/gh/$owner/$repo@$ref/$filePath"
    }

    /** 判断是否为 GitHub 域名的 URL */
    fun isGithubUrl(url: String): Boolean {
        return url.startsWith("https://github.com/") ||
                url.startsWith("http://github.com/") ||
                url.startsWith("https://raw.githubusercontent.com/") ||
                url.startsWith("http://raw.githubusercontent.com/") ||
                url.startsWith("https://objects.githubusercontent.com/") ||
                url.startsWith("https://release-assets.githubusercontent.com/") ||
                url.startsWith("https://api.github.com/") ||
                url.startsWith("http://api.github.com/")
    }

    /**
     * 如果 URL 被重写到第三方代理域名，移除 Authorization 头，
     * 防止 GitHub token 泄露给第三方代理。
     * 返回 (最终URL, 是否需要移除Authorization)
     */
    fun shouldStripAuthorization(originalUrl: String, rewrittenUrl: String): Boolean {
        val originalHost = runCatching {
            java.net.URL(originalUrl).host
        }.getOrDefault("")
        val rewrittenHost = runCatching {
            java.net.URL(rewrittenUrl).host
        }.getOrDefault("")
        return originalHost != rewrittenHost && !rewrittenHost.endsWith("github.com") &&
                !rewrittenHost.endsWith("githubusercontent.com")
    }

    /**
     * 从远程 GitHub Raw 拉取最新节点列表，实现节点动态更新。
     * JSON 格式：[{"id":"...","displayName":"...","domain":"...","trafficKinds":["RAW_FILE"]}]
     * 失败时静默回退到内置列表。
     */
    suspend fun refreshRemoteNodes(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(REMOTE_NODES_URL).get().build()
            updateClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val json = response.body?.string() ?: return@withContext false
                val array = JSONArray(json)
                val nodes = buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val displayName = obj.optString("displayName", id)
                        val domain = obj.optString("domain").takeIf { it.isNotBlank() } ?: continue
                        val kindsArray = obj.optJSONArray("trafficKinds")
                        val kinds = buildSet {
                            for (k in 0 until (kindsArray?.length() ?: 0)) {
                                runCatching {
                                    TrafficKind.valueOf(kindsArray!!.getString(k))
                                }.getOrNull()?.let(::add)
                            }
                        }
                        add(ProxyNode(id, displayName, domain, kinds))
                    }
                }
                if (nodes.isNotEmpty()) {
                    remoteNodes = nodes
                    Log.i(TAG, "远程节点更新成功：${nodes.size} 个节点")
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.w(TAG, "远程节点更新失败，使用内置列表：${e.message}")
            false
        }
    }

    /**
     * 测试单个节点的延迟（毫秒）。失败返回 null。
     * 通过 GET 请求一个实际存在的小文件测量总耗时。
     * 注意：不能用代理根路径（如 https://proxy/https://github.com）测试，
     * 因为多数代理根路径返回 404/403，必须用实际文件 URL。
     */
    fun testLatency(node: ProxyNode, timeoutMs: Long = 10000): Long? {
        return try {
            val url = when {
                node.id == DIRECT_NODE.id -> LATENCY_TEST_FILE
                node.id == JSDELIVR_NODE.id -> rewriteJsdelivr(LATENCY_TEST_FILE)
                else -> "https://${node.domain}/$LATENCY_TEST_FILE"
            }
            val request = Request.Builder().url(url).get().build()
            val start = System.currentTimeMillis()
            latencyClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                if (response.isSuccessful || response.code in 300..399) elapsed else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "节点 ${node.displayName} 延迟测试失败: ${e.message}")
            null
        }
    }

    /**
     * 并发测试所有内置节点的延迟，返回按延迟升序排列的结果。
     * 直连也会测试。
     */
    suspend fun testAllLatencies(): List<Pair<ProxyNode, Long?>> = withContext(Dispatchers.IO) {
        ALL_NODES.map { node ->
            async { node to testLatency(node) }
        }.awaitAll().sortedBy { it.second ?: Long.MAX_VALUE }
    }

    /**
     * 自动选择延迟最低的节点并缓存。
     * 返回选中的节点。
     */
    suspend fun autoSelectBest(): ProxyNode = withContext(Dispatchers.IO) {
        val results = testAllLatencies()
        val best = results.firstOrNull { it.second != null }?.first ?: DIRECT_NODE
        cachedBestNode = best
        best
    }

    /** 格式化延迟显示 */
    fun formatLatency(ms: Long?): String = when {
        ms == null -> "超时"
        ms < 100 -> "${ms}ms 极快"
        ms < 300 -> "${ms}ms 快"
        ms < 800 -> "${ms}ms 一般"
        ms < 2000 -> "${ms}ms 较慢"
        else -> "${ms}ms 很慢"
    }

    /** 格式化下载速度显示 */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "—"
        bytesPerSec < 1024 -> "${bytesPerSec} B/s"
        bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.2f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    }

    /** 估算剩余时间 */
    fun formatEta(remainingBytes: Long, bytesPerSec: Long): String {
        if (bytesPerSec <= 0 || remainingBytes <= 0) return "—"
        val seconds = remainingBytes / bytesPerSec
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
            else -> "${seconds / 3600}时${(seconds % 3600) / 60}分"
        }
    }
}
