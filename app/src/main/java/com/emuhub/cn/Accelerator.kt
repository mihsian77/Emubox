package com.emuhub.cn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "EmuHubAccelerator"

/**
 * 国内下载加速节点。
 * 所有节点均为公益 GitHub 代理，格式为 https://<domain>/https://github.com/...
 */
data class ProxyNode(
    val id: String,
    val displayName: String,
    val domain: String
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
}

object Accelerator {

    /** 内置加速节点列表（按推荐度排序） */
    val BUILTIN_NODES = listOf(
        ProxyNode("gh-proxy", "gh-proxy 官方", "gh-proxy.com"),
        ProxyNode("ghfast", "ghfast 多线", "ghfast.top"),
        ProxyNode("ghproxy-mirror", "ghproxy 镜像", "mirror.ghproxy.com"),
        ProxyNode("moeyy", "moeyy 公益", "github.moeyy.xyz"),
        ProxyNode("llkk", "llkk 公益", "gh.llkk.cc")
    )

    /** 直连（不加速） */
    val DIRECT_NODE = ProxyNode("direct", "直连 GitHub", "github.com")

    /** 所有可选节点 = 直连 + 内置节点 */
    val ALL_NODES = listOf(DIRECT_NODE) + BUILTIN_NODES

    private val latencyClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
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
        return ALL_NODES.firstOrNull { it.id == id } ?: BUILTIN_NODES.first()
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
        Mode.AUTO -> cachedBestNode ?: BUILTIN_NODES.first()
    }

    @Volatile
    private var cachedBestNode: ProxyNode? = null

    /** 清除自动选择缓存（设置变更或用户手动刷新时调用） */
    fun clearCache() {
        cachedBestNode = null
    }

    /**
     * 改写下载 URL。如果是 GitHub 链接且加速已开启，则通过代理节点访问。
     * 非 GitHub 链接原样返回。
     */
    fun rewriteUrl(url: String): String {
        if (!isGithubUrl(url)) return url
        val node = getActiveNode()
        if (node.id == DIRECT_NODE.id) return url
        return node.rewrite(url)
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
     * 测试单个节点的延迟（毫秒）。失败返回 null。
     * 通过对代理域名做 HEAD 请求测量连接时间。
     */
    fun testLatency(node: ProxyNode, timeoutMs: Long = 5000): Long? {
        return try {
            val url = if (node.id == DIRECT_NODE.id) {
                "https://github.com"
            } else {
                "https://${node.domain}/https://github.com"
            }
            val request = Request.Builder().url(url).head().build()
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
}
