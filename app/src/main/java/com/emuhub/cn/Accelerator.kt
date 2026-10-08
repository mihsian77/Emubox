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
 * 节点来源：MirrorHub (https://github.com/mihsian77/MirrorHub)，MIT 协议。
 * 远程拉取 MirrorHub 自动维护的在线节点列表（每6小时测速更新），内置节点作为 fallback。
 * 所有节点均为公益 GitHub 代理，格式为 https://<domain>/https://github.com/...
 * trafficKinds 为空表示支持所有类型。
 *
 * 二次分发声明：本应用加速节点由 MirrorHub 提供，遵守 MIT 协议。
 * 如二次分发或修改，请保留此来源声明。
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

    /** 内置加速节点列表（fallback，远程拉取失败时使用） */
    val BUILTIN_NODES = listOf(
        ProxyNode("gh-proxy-org", "gh-proxy 官方", "gh-proxy.org"),
        ProxyNode("gh-proxy-com", "gh-proxy 旧域", "gh-proxy.com"),
        ProxyNode("ghfast", "ghfast 多线", "ghfast.top"),
        ProxyNode("gh-con-sh", "con.sh 公益", "gh.con.sh"),
        ProxyNode("gh-idayer", "idayer 公益", "gh.idayer.com"),
        ProxyNode("gh-proxy-net", "gh-proxy 镜像", "gh-proxy.net")
    )

    /** MirrorHub 来源信息（用于 UI 显示和日志声明） */
    const val MIRRORHUB_SOURCE = "MirrorHub"
    const val MIRRORHUB_URL = "https://github.com/mihsian77/MirrorHub"
    const val MIRRORHUB_LICENSE = "MIT"

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

    /**
     * 下载测速用的固定大小文件（5MB 随机数据，避免代理压缩导致速度虚高）。
     * 放在本仓库 main 分支，raw URL 稳定，所有代理节点均支持 Raw 流量。
     */
    private const val SPEEDTEST_FILE =
        "https://raw.githubusercontent.com/mihsian77/EmuHub-CN/main/speedtest.bin"

    /** 下载测速超时（毫秒），超时则按已下载字节计算速度 */
    private const val SPEEDTEST_TIMEOUT_MS = 15000L

    /** 自动测速的节点数量上限（按延迟最低取前 N，控制流量消耗） */
    private const val SPEEDTEST_AUTO_LIMIT = 5

    /**
     * 远程节点列表 URL（MirrorHub 自动维护，每6小时测速更新）。
     * active-nodes.json 只包含在线节点，按延迟升序排列，最多20个。
     * 格式：{"source":"MirrorHub","license":"MIT","nodes":[{"id","name","domain","category","latency_ms"}]}
     */
    private const val REMOTE_NODES_URL =
        "https://raw.githubusercontent.com/mihsian77/MirrorHub/main/active-nodes.json"

    /** 不加速（直连 GitHub），作为最后 fallback */
    val DIRECT_NODE = ProxyNode("direct", "不加速（直连）", "github.com")

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
     * 从 MirrorHub 拉取最新在线节点列表。
     * active-nodes.json 只包含在线节点，按延迟升序排列。
     * 根据 category 推断 trafficKinds：
     *   - universal_proxy / web_mirror → 支持所有类型
     *   - cdn_raw → 仅 RAW_FILE
     *   - clone_specialized → 跳过（仅用于 git clone，不适合下载）
     * 失败时静默回退到内置列表。
     */
    suspend fun refreshRemoteNodes(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(REMOTE_NODES_URL).get().build()
            updateClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val json = response.body?.string() ?: return@withContext false
                val obj = org.json.JSONObject(json)
                // 验证来源
                val source = obj.optString("source", "")
                if (source != MIRRORHUB_SOURCE) {
                    Log.w(TAG, "远程节点来源不匹配: $source，期望 $MIRRORHUB_SOURCE")
                }
                val license = obj.optString("license", "")
                val updatedAt = obj.optString("updated_at", "未知")
                val array = obj.optJSONArray("nodes") ?: return@withContext false
                val nodes = buildList {
                    for (i in 0 until array.length()) {
                        val nodeObj = array.getJSONObject(i)
                        val id = nodeObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = nodeObj.optString("name", id)
                        val domain = nodeObj.optString("domain").takeIf { it.isNotBlank() } ?: continue
                        val category = nodeObj.optString("category", "universal_proxy")
                        // 根据分类推断支持的流量类型
                        val kinds = when (category) {
                            "cdn_raw" -> setOf(TrafficKind.RAW_FILE)
                            "clone_specialized" -> continue  // 跳过，仅用于 clone
                            else -> emptySet()  // universal_proxy / web_mirror 支持所有
                        }
                        add(ProxyNode(id, name, domain, kinds))
                    }
                }
                if (nodes.isNotEmpty()) {
                    remoteNodes = nodes
                    Log.i(TAG, "远程节点更新成功：${nodes.size} 个在线节点 " +
                            "(来源: $MIRRORHUB_SOURCE, 协议: $license, 更新于: $updatedAt)")
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
     * 测试单个节点的实际下载速度（bytes/s）。
     * 下载 5MB 固定测速文件，测量总耗时与字节数；超时则按已下载量计算。
     * 失败或数据过少返回 null。
     */
    fun testDownloadSpeed(node: ProxyNode, timeoutMs: Long = SPEEDTEST_TIMEOUT_MS): Long? {
        return try {
            val url = when {
                node.id == DIRECT_NODE.id -> SPEEDTEST_FILE
                node.id == JSDELIVR_NODE.id -> rewriteJsdelivr(SPEEDTEST_FILE)
                else -> "https://${node.domain}/$SPEEDTEST_FILE"
            }
            val request = Request.Builder().url(url).get().build()
            val start = System.currentTimeMillis()
            var totalBytes = 0L
            latencyClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val input = response.body?.byteStream() ?: return null
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    if (System.currentTimeMillis() - start >= timeoutMs) break
                }
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed <= 0 || totalBytes < 64 * 1024) return null
            totalBytes * 1000 / elapsed
        } catch (e: Exception) {
            Log.w(TAG, "节点 ${node.displayName} 下载测速失败: ${e.message}")
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
     * 先测所有节点延迟，再对延迟最低的前 [SPEEDTEST_AUTO_LIMIT] 个节点并发下载测速。
     * 返回 (延迟Map, 速度Map)，速度 Map 只包含实际测速的节点。
     * 控制总流量在 ~25MB 以内（5 节点 × 5MB）。
     */
    suspend fun testAllLatenciesAndSpeeds(): Pair<Map<String, Long?>, Map<String, Long?>> =
        withContext(Dispatchers.IO) {
            val latencyResults = testAllLatencies()
            val latencyMap = latencyResults.associate { it.first.id to it.second }
            val candidates = latencyResults
                .filter { it.second != null }
                .take(SPEEDTEST_AUTO_LIMIT)
                .map { it.first }
            val speedMap = candidates.map { node ->
                async { node.id to testDownloadSpeed(node) }
            }.awaitAll().toMap()
            latencyMap to speedMap
        }

    /**
     * 自动选择最优节点并缓存。
     * 综合评分 = 延迟 40% + 下载速度 60%，避免"直连延迟低但网速慢"被误选。
     * 返回选中的节点。
     */
    suspend fun autoSelectBest(): ProxyNode = withContext(Dispatchers.IO) {
        val (latencyMap, speedMap) = testAllLatenciesAndSpeeds()
        val best = selectBestNode(latencyMap, speedMap)
        cachedBestNode = best
        best
    }

    /** 缓存自动选择结果（设置页手动测试后调用） */
    fun cacheBestNode(node: ProxyNode) {
        cachedBestNode = node
    }

    /**
     * 综合评分选择最优节点。
     * 延迟分：0-100（2000ms+ 为 0，0ms 为 100）；速度分：0-100（10MB/s+ 为 100）。
     * 未测速节点速度视为 0（不占优），综合分 = 延迟×0.4 + 速度×0.6。
     */
    fun selectBestNode(
        latencyMap: Map<String, Long?>,
        speedMap: Map<String, Long?>
    ): ProxyNode {
        var best: ProxyNode = DIRECT_NODE
        var bestScore = -1.0
        for (node in ALL_NODES) {
            val latency = latencyMap[node.id] ?: continue
            val score = compositeScore(latency, speedMap[node.id])
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    /** 综合评分：延迟分 40% + 速度分 60% */
    fun compositeScore(latencyMs: Long, speedBps: Long?): Double {
        val latencyScore = (100.0 - latencyMs / 20.0).coerceIn(0.0, 100.0)
        val speedScore = if (speedBps == null || speedBps <= 0) {
            0.0 // 未测速：速度不占优
        } else {
            (speedBps / (10.0 * 1024 * 1024) * 100.0).coerceIn(0.0, 100.0)
        }
        return latencyScore * 0.4 + speedScore * 0.6
    }

    /** 格式化延迟显示 */
    fun formatLatency(context: android.content.Context, ms: Long?): String = when {
        ms == null -> context.getString(R.string.latency_timeout)
        ms < 100 -> context.getString(R.string.latency_very_fast, ms)
        ms < 300 -> context.getString(R.string.latency_fast, ms)
        ms < 800 -> context.getString(R.string.latency_normal, ms)
        ms < 2000 -> context.getString(R.string.latency_slow, ms)
        else -> context.getString(R.string.latency_very_slow, ms)
    }

    /** 格式化下载速度显示 */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "—"
        bytesPerSec < 1024 -> "${bytesPerSec} B/s"
        bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.2f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    }

    /** 估算剩余时间 */
    fun formatEta(context: android.content.Context, remainingBytes: Long, bytesPerSec: Long): String {
        if (bytesPerSec <= 0 || remainingBytes <= 0) return "—"
        val seconds = remainingBytes / bytesPerSec
        return when {
            seconds < 60 -> context.getString(R.string.eta_seconds, seconds)
            seconds < 3600 -> context.getString(R.string.eta_minutes, seconds / 60, seconds % 60)
            else -> context.getString(R.string.eta_hours, seconds / 3600, (seconds % 3600) / 60)
        }
    }
}
