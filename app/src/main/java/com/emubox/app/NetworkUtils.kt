package com.emubox.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class GithubRelease(
    val tagName: String,
    val name: String,
    val assets: List<GithubAsset>,
    val publishedAt: String = "",
    val body: String = "",
    val prerelease: Boolean = false
)

data class GithubAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

/**
 * 组件文件大小（字节）缓存：url -> size。
 * contents.json 清单不含大小，通过 HEAD 请求跟随 GitHub 重定向取 Content-Length。
 * 只在版本列表可见时按需查询，结果缓存避免重复请求。
 */
private val componentSizeCache = ConcurrentHashMap<String, Long>()

/** 查询远程文件大小（字节），失败或不可得返回 null。结果按 URL 缓存。 */
suspend fun fetchRemoteSizeBytes(remoteUrl: String): Long? = withContext(Dispatchers.IO) {
    componentSizeCache[remoteUrl]?.let { return@withContext it }
    try {
        // GitHub release 直链会 302 到 release-assets CDN，HEAD 跟随重定向后才有 Content-Length
        val headClient = githubClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder()
            .url(Accelerator.rewriteUrl(remoteUrl))
            .head()
            .build()
        headClient.newCall(request).execute().use { resp ->
            val len = resp.header("Content-Length")?.toLongOrNull()
            if (resp.isSuccessful && len != null && len > 0) {
                componentSizeCache[remoteUrl] = len
                len
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
data class Component(
    val type: String,
    val verName: String,
    val verCode: String,
    val remoteUrl: String,
    /** 文件字节数；contents.json 清单不含大小，需要时通过 HEAD 懒加载获取 */
    val sizeBytes: Long? = null
)

/** wrapper 清单条目（winlator-contents wrappers.json，作者维护的目录，不依赖 release 标签顺序） */
data class WrapperItem(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val license: String,
    val url: String,
    val fileSizeBytes: Long,
    val version: Int,
    val gpuTargets: List<String>
)

private val githubClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

// In-memory cache to avoid re-fetching the same GitHub releases / component
// manifest on every source switch. TTL is 3 minutes; stale entries are
// returned immediately and silently re-fetched in background (SWR),
// so data self-refreshes without a manual pull-to-refresh.
private const val CACHE_TTL_MS = 3 * 60 * 1000L
private data class CacheEntry<T>(val data: T, val timestamp: Long)
private val releaseCache = ConcurrentHashMap<String, CacheEntry<List<GithubRelease>>>()
private val componentCache = ConcurrentHashMap<String, CacheEntry<Map<String, List<Component>>>>()
private val repoMetaCache = ConcurrentHashMap<String, CacheEntry<RepoMeta>>()

/** 后台静默刷新去重：同一 URL 同时只允许一个后台刷新任务，避免并发重复请求。 */
private val inFlightReleases = ConcurrentHashMap.newKeySet<String>()
private val inFlightComponents = ConcurrentHashMap.newKeySet<String>()

/** 仓库元数据：最近推送时间 + 星标数（一次 /repos 请求带回，来源卡片共用） */
data class RepoMeta(val pushedAt: Long?, val stars: Long?)

// GitHub 未认证 API 限额 60 次/小时：驱动源 35+ 个并发拉元数据会瞬间打爆限额，
// 全部返回 null 导致更新时间不显示。用信号量限制并发为 4，配合 6h 缓存分摊配额。
private val repoMetaSemaphore = java.util.concurrent.Semaphore(4, true)

private fun <T> ConcurrentHashMap<String, CacheEntry<T>>.getValid(key: String): T? {
    val entry = this[key] ?: return null
    return if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) entry.data else null
}

suspend fun fetchGithubReleasesFromUrl(apiUrl: String): List<GithubRelease> = withContext(Dispatchers.IO) {
    val cached = releaseCache[apiUrl]
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
        return@withContext cached.data
    }
    if (cached != null) {
        // 过期缓存先返回（立即渲染），后台静默刷新到新数据，避免白屏等待。
        refreshReleasesInBackground(apiUrl)
        return@withContext cached.data
    }
    refreshReleases(apiUrl)
}

private fun refreshReleasesInBackground(apiUrl: String) {
    if (!inFlightReleases.add(apiUrl)) return
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scope.launch {
        try {
            refreshReleases(apiUrl)
        } catch (_: Exception) {
        } finally {
            inFlightReleases.remove(apiUrl)
            scope.cancel()
        }
    }
}

/** 拉取并解析 release 列表；API 节点失败时轮换下一个支持 API 的加速节点，最后直连兜底。 */
private suspend fun refreshReleases(apiUrl: String): List<GithubRelease> {
    var json: JSONArray? = null
    // API 请求只走支持 API 流量的节点（ghfast 等实测不支持，返回 Invalid input）；
    // 失败则轮换下一个节点，避免 AUTO 选中不支持 API 的节点导致列表永远拉不到。
    val triedIds = mutableSetOf<String>()
    for (i in 0..Accelerator.ALL_NODES.size) {
        val node = Accelerator.ALL_NODES.firstOrNull {
            it.id !in triedIds && it.id != Accelerator.DIRECT_NODE.id &&
                (it.trafficKinds.isEmpty() || TrafficKind.API in it.trafficKinds)
        }
        if (node != null) {
            triedIds.add(node.id)
            json = executeReleasesRequest(Accelerator.rewriteWith(apiUrl, node))
        } else {
            // 所有加速节点试完：直连 GitHub 最后兜底
            json = executeReleasesRequest(apiUrl)
            break
        }
        if (json != null) break
    }
    if (json == null) return emptyList()
    val jsonArray = json // 稳定引用：var 被 lambda 捕获后无法 smart-cast

    val releases = (0 until jsonArray.length()).mapNotNull { idx ->
        val obj = jsonArray.getJSONObject(idx)
        if (obj.optBoolean("draft", false)) return@mapNotNull null

        val tagName = obj.optString("tag_name")
        if (tagName.isBlank()) return@mapNotNull null

        val name = obj.optString("name").ifBlank { tagName }
        val prerelease = obj.optBoolean("prerelease", false)
        val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsArray.length()).map { assetIdx ->
            val asset = assetsArray.getJSONObject(assetIdx)
            GithubAsset(
                name = asset.optString("name"),
                downloadUrl = asset.optString("browser_download_url"),
                sizeBytes = asset.optLong("size", 0L)
            )
        }.filter { it.name.isNotBlank() && it.downloadUrl.isNotBlank() }

        GithubRelease(
            tagName = tagName,
            name = name,
            assets = assets,
            publishedAt = obj.optString("published_at", obj.optString("created_at", "")),
            body = obj.optString("body", ""),
            prerelease = prerelease
        )
    }.sortGithubReleasesNewestFirst()

    releaseCache[apiUrl] = CacheEntry(releases, System.currentTimeMillis())
    return releases
}

private fun executeReleasesRequest(url: String): JSONArray? = try {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()
    githubClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        JSONArray(body)
    }
} catch (_: Exception) {
    null
}

suspend fun fetchTurnipReleases(source: TurnipSource, adrenoSeries: String): List<GithubRelease> {
    val allReleases = fetchGithubReleasesFromUrl(source.apiUrl)
    if (allReleases.isEmpty()) return emptyList()

    val patterns = source.filters[adrenoSeries]
        ?: source.filters["default"]
        ?: emptyList()

    // A source can intentionally omit filters when every release in the repo is relevant.
    val filtered = if (patterns.isEmpty()) {
        allReleases
    } else {
        allReleases.filter { release ->
            val searchable = buildString {
                append(release.name)
                append(' ')
                append(release.tagName)
                release.assets.forEach { asset ->
                    append(' ')
                    append(asset.name)
                }
            }
            patterns.any { pattern -> searchable.contains(pattern, ignoreCase = true) }
        }
    }

    // 严格过滤（wrapper 混装仓库专用）：只返回 filters 命中的 release，
    // 禁 fallback 兜底与全量兜底——winlator-contents/WinXclipse 等仓库混装
    // 驱动/运行库/桌面包，fallback 会把 Rutabaga、linuxfs、steamdeck 等误判为 wrapper。
    if (source.strictFilter) return filtered

    // If an upstream maintainer changes naming, prefer showing Turnip-looking releases
    // rather than presenting an empty page. This keeps remote catalogs resilient.
    val fallback = allReleases.filter { release ->
        val text = "${release.name} ${release.tagName} ${release.assets.joinToString { it.name }}"
        text.contains("turnip", ignoreCase = true) ||
            text.contains("mesa", ignoreCase = true) ||
            text.contains("adreno", ignoreCase = true)
    }

    val includeAssetPatterns = source.assetIncludes[adrenoSeries]
        ?: source.assetIncludes["default"]
        ?: emptyList()
    val excludeAssetPatterns = source.assetExcludes[adrenoSeries]
        ?: source.assetExcludes["default"]
        ?: emptyList()

    // 精确匹配非空时，命名变体（如 Gamesir Rutabaga）可能被 patterns 遗漏，
    // 将 fallback 中不在精确集内的条目并入，避免变体被整体丢弃。以 tagName 去重。
    val merged = if (patterns.isEmpty()) {
        allReleases
    } else {
        val filteredTags = filtered.mapTo(mutableSetOf()) { it.tagName }
        filtered + fallback.filter { it.tagName !in filteredTags }
    }

    return (merged.ifEmpty { allReleases })
        .map { release ->
            // 驱动包约定为 zip/adpkg（含驱动本体与 meta.json）：
            // 先按源 include/exclude 规则匹配，再硬性只保留压缩驱动包，
            // 源码 tar.gz / txt / json 等非驱动文件不进入版本卡与下载。
            val compatibleAssets = release.assets.filter { asset ->
                val included = includeAssetPatterns.isEmpty() ||
                    includeAssetPatterns.any { asset.name.contains(it, ignoreCase = true) }
                val excluded = excludeAssetPatterns.any { asset.name.contains(it, ignoreCase = true) }
                included && !excluded
            }.filter { asset ->
                // 驱动包约定为 zip/adpkg，wrapper 包装层为 tzst——按来源扩展名白名单保留
                source.assetExts.any { asset.name.endsWith(it, ignoreCase = true) }
            }
            release.copy(assets = compatibleAssets)
        }
        .filter { it.assets.isNotEmpty() }
        .sortGithubReleasesNewestFirst()
}

suspend fun fetchQualcommReleases(source: QualcommSource): List<GithubRelease> {
    val releases = fetchGithubReleasesFromUrl(source.apiUrl)
    // 与 Turnip 一致：只保留 zip/adpkg 驱动包资产，其余文件不进入下载
    val zipOnly = releases
        .map { release ->
            release.copy(assets = release.assets.filter { asset ->
                asset.name.endsWith(".zip", ignoreCase = true) ||
                    asset.name.endsWith(".adpkg", ignoreCase = true)
            })
        }
        .filter { it.assets.isNotEmpty() }
    if (source.filters.isEmpty()) return zipOnly.sortGithubReleasesNewestFirst()

    return zipOnly.filter { release ->
        val searchable = buildString {
            append(release.name)
            append(' ')
            append(release.tagName)
            release.assets.forEach { asset ->
                append(' ')
                append(asset.name)
            }
        }
        source.filters.any { filter -> searchable.contains(filter, ignoreCase = true) }
    }.sortGithubReleasesNewestFirst()
}

/** Backwards-compatible default used by older call sites/tests. */
suspend fun loadQualcommDriver(): GithubRelease? {
    val source = SourceCatalogRepository.builtInCatalog().qualcommSources.first()
    return fetchQualcommReleases(source).firstOrNull()
}

/** wrapper 清单缓存（wrappers.json，SWR 策略与 release/component 一致） */
private val wrapperCache = ConcurrentHashMap<String, CacheEntry<List<WrapperItem>>>()

/**
 * 读取 wrapper 清单（winlator-contents wrappers.json）：
 * 作者维护的 wrapper 目录（id/name/description/url/checksum），
 * 不依赖 release 标签顺序——标签变动不会导致内容错读。
 */
suspend fun fetchWrappersCatalog(manifestUrl: String): List<WrapperItem> = withContext(Dispatchers.IO) {
    val cached = wrapperCache[manifestUrl]
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
        return@withContext cached.data
    }
    if (cached != null) {
        if (inFlightWrappers.add(manifestUrl)) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try { refreshWrappers(manifestUrl) } catch (_: Exception) {
                } finally {
                    inFlightWrappers.remove(manifestUrl)
                    scope.cancel()
                }
            }
        }
        return@withContext cached.data
    }
    refreshWrappers(manifestUrl)
}

private val inFlightWrappers = ConcurrentHashMap.newKeySet<String>()

private suspend fun refreshWrappers(manifestUrl: String): List<WrapperItem> {
    val acceleratedUrl = Accelerator.rewriteUrl(manifestUrl)
    var json = executeWrappersRequest(acceleratedUrl)
    if (json == null && acceleratedUrl != manifestUrl) {
        json = executeWrappersRequest(manifestUrl)
    }
    if (json == null) return emptyList()

    val items = buildList {
        val wrappersArr = json.optJSONArray("wrappers") ?: JSONArray()
        for (i in 0 until wrappersArr.length()) {
            val obj = wrappersArr.optJSONObject(i) ?: continue
            val url = obj.optString("url").trim()
            val id = obj.optString("id").trim()
            if (url.isBlank() || id.isBlank()) continue
            val gpuTargets = obj.optJSONArray("gpuTargets")?.let { ja ->
                (0 until ja.length()).map { ja.getString(it) }
            } ?: emptyList()
            add(
                WrapperItem(
                    id = id,
                    name = obj.optString("name").trim().ifBlank { id },
                    description = obj.optString("description").trim(),
                    author = obj.optString("author").trim(),
                    license = obj.optString("license").trim(),
                    url = url,
                    fileSizeBytes = obj.optLong("file_size", 0L),
                    version = obj.optInt("version", 1),
                    gpuTargets = gpuTargets
                )
            )
        }
    }
    wrapperCache[manifestUrl] = CacheEntry(items, System.currentTimeMillis())
    return items
}

private fun executeWrappersRequest(url: String): JSONObject? = try {
    val request = Request.Builder().url(url).build()
    githubClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        JSONObject(body)
    }
} catch (_: Exception) {
    null
}

suspend fun fetchComponentsFromUrl(manifestUrl: String): Map<String, List<Component>> =
    withContext(Dispatchers.IO) {
        val cached = componentCache[manifestUrl]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return@withContext cached.data
        }
        if (cached != null) {
            // 过期缓存先返回，后台静默刷新（与 release 一致的 SWR 策略）
            if (inFlightComponents.add(manifestUrl)) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                    try { refreshComponents(manifestUrl) } catch (_: Exception) {
                    } finally {
                        inFlightComponents.remove(manifestUrl)
                        scope.cancel()
                    }
                }
            }
            return@withContext cached.data
        }
        refreshComponents(manifestUrl)
    }

private suspend fun refreshComponents(manifestUrl: String): Map<String, List<Component>> {
    val acceleratedUrl = Accelerator.rewriteUrl(manifestUrl)
    var json = executeComponentsRequest(acceleratedUrl)
    if (json == null && acceleratedUrl != manifestUrl) {
        // 加速节点（jsdelivr 等）失败/缓存异常：回退 raw.githubusercontent 直连
        json = executeComponentsRequest(manifestUrl)
    }
    if (json == null) return emptyMap()
    val jsonArray = json // 稳定引用：var 被 lambda 捕获后无法 smart-cast

    val map = mutableMapOf<String, MutableList<Component>>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        val type = obj.optString("type").trim()
        val verName = obj.optString("verName").trim()
        val remoteUrl = obj.optString("remoteUrl").trim()
        if (type.isBlank() || verName.isBlank() || remoteUrl.isBlank()) continue

        val component = Component(
            type = type,
            verName = verName,
            verCode = obj.opt("verCode")?.toString().orEmpty(),
            remoteUrl = remoteUrl
        )
        map.getOrPut(component.type) { mutableListOf() }.add(component)
    }
    map.forEach { (_, list) ->
        list.sortWith { a, b -> naturalVersionCompare(b.verName, a.verName) }
    }
    componentCache[manifestUrl] = CacheEntry(map, System.currentTimeMillis())
    return map
}

private fun executeComponentsRequest(url: String): JSONArray? = try {
    val request = Request.Builder().url(url).build()
    githubClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        JSONArray(body)
    }
} catch (_: Exception) {
    null
}

/** Backwards-compatible default used by older call sites/tests. */
suspend fun fetchComponentsFromUrl(): Map<String, List<Component>> =
    fetchComponentsFromUrl(SourceCatalogRepository.builtInCatalog().componentSources.first().manifestUrl)

/**
 * 从独立 GitHub release 仓库加载组件，用于 panDXVK 这类不走 contents.json
 * 清单、直接在 release 附件里分发 .wcp/.zip 的组件。
 *
 * 每个 release 取一个首选资产（默认 .wcp 优先、其次 .zip），映射到指定
 * 组件分类（如 DXVK）。返回列表已按 release 从新到旧排序。
 */
suspend fun fetchGithubComponents(
    apiUrl: String,
    componentType: String,
    preferredExtensions: List<String> = listOf(
        ".wcp", ".zip", ".tar.gz", ".7z", ".so", ".dll", ".exe", ".msi",
        ".patch", ".diff", ".txt", ".json", ".apk", ".aab"
    )
): List<Component> {
    val releases = fetchGithubReleasesFromUrl(apiUrl)
    return releases.mapNotNull { release ->
        // 优先级：主组件包（wcp/zip/so）→ 补丁/配置等特殊文件 → 任意可下载资产
        val primary = release.assets.firstOrNull { a ->
            val n = a.name.lowercase()
            n.endsWith(".wcp") || n.endsWith(".zip") || n.endsWith(".tar.gz") ||
                n.endsWith(".7z") || n.endsWith(".so")
        }
        val special = release.assets.firstOrNull { a ->
            val n = a.name.lowercase()
            n.endsWith(".patch") || n.endsWith(".diff") || n.endsWith(".txt") ||
                n.endsWith(".json") || n.endsWith(".dll") || n.endsWith(".exe") ||
                n.endsWith(".msi") || n.endsWith(".apk") || n.endsWith(".aab")
        }
        val asset = primary ?: special ?: release.assets.firstOrNull { it.downloadUrl.isNotBlank() }
            ?: return@mapNotNull null
        Component(
            type = componentType,
            verName = release.tagName,
            verCode = "",
            remoteUrl = asset.downloadUrl,
            sizeBytes = asset.sizeBytes.takeIf { it > 0 }
        )
    }
}

/**
 * 拉取组件源仓库的最后推送时间（pushed_at，epoch 毫秒）。
 * 组件清单（contents.json）本身没有时间字段，用源仓库活跃度作为"哪个最新"的参考。
 * 支持三种 URL 形态：raw.githubusercontent.com/{owner}/{repo}/...、
 * github.com/{owner}/{repo}/releases/...、api.github.com/repos/{owner}/{repo}/...
 * 带 6 小时缓存，失败返回 null（UI 不显示更新时间，不阻塞加载）。
 */
/**
 * 拉取仓库元数据（pushed_at + stargazers_count），带 6h 缓存 + 4 并发信号量。
 * 失败返回 null（UI 不显示更新时间/星标，不阻塞加载）。
 * 支持三种 URL 形态：raw.githubusercontent.com/{owner}/{repo}/...、
 * github.com/{owner}/{repo}/releases/...、api.github.com/repos/{owner}/{repo}/...
 */
suspend fun fetchRepoMeta(url: String): RepoMeta? =
    withContext(Dispatchers.IO) {
        repoMetaCache.getValid(url)?.let { return@withContext it }

        val ownerRepo = extractOwnerRepo(url) ?: return@withContext null
        val apiUrl = "https://api.github.com/repos/${ownerRepo.first}/${ownerRepo.second}"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        repoMetaSemaphore.acquire()
        try {
            githubClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val obj = JSONObject(response.body?.string() ?: return@withContext null)
                val pushed = obj.optString("pushed_at")
                val pushedTs = if (pushed.isNotBlank()) {
                    runCatching { java.time.Instant.parse(pushed).toEpochMilli() }.getOrNull()
                } else null
                val stars = obj.optLong("stargazers_count", -1L).takeIf { it >= 0 }
                val meta = RepoMeta(pushedAt = pushedTs, stars = stars)
                repoMetaCache[url] = CacheEntry(meta, System.currentTimeMillis())
                meta
            }
        } catch (_: Exception) {
            null
        } finally {
            repoMetaSemaphore.release()
        }
    }

/** 兼容旧调用：只取仓库最近推送时间 */
suspend fun fetchRepositoryPushedAt(manifestUrl: String): Long? =
    fetchRepoMeta(manifestUrl)?.pushedAt

/** 仓库星标数（远程权威值），无数据返回 null */
suspend fun fetchRepositoryStars(url: String): Long? =
    fetchRepoMeta(url)?.stars

/** R5：从组件清单/发布 URL 推导仓库 API 地址（拉取最新 release 全部资产用） */
fun deriveRepoApiUrl(url: String): String? {
    val (owner, repo) = extractOwnerRepo(url) ?: return null
    return "https://api.github.com/repos/$owner/$repo"
}

/** 从清单/发布 URL 提取 owner/repo（小写化），识别不出返回 null */
private fun extractOwnerRepo(url: String): Pair<String, String>? {
    return try {
        val u = java.net.URI(url)
        val host = u.host ?: return null
        val path = u.path.trim('/').split('/')
        when {
            host.endsWith("raw.githubusercontent.com") && path.size >= 2 ->
                path[0] to path[1]
            host.endsWith("github.com") && path.size >= 2 ->
                path[0] to path[1]
            host.endsWith("api.github.com") && path.size >= 3 && path[0] == "repos" ->
                path[1] to path[2]
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun List<GithubRelease>.sortGithubReleasesNewestFirst(): List<GithubRelease> =
    sortedWith { a, b ->
        val dateResult = b.publishedAt.compareTo(a.publishedAt)
        if (dateResult != 0) dateResult else naturalVersionCompare(b.tagName, a.tagName)
    }

private fun naturalVersionCompare(v1: String, v2: String): Int {
    fun parseVersion(value: String): List<Long> =
        Regex("\\d+").findAll(value).map { it.value.toLongOrNull() ?: 0L }.toList()

    val parts1 = parseVersion(v1)
    val parts2 = parseVersion(v2)
    val maxLen = maxOf(parts1.size, parts2.size)

    for (i in 0 until maxLen) {
        val num1 = parts1.getOrNull(i) ?: 0L
        val num2 = parts2.getOrNull(i) ?: 0L
        if (num1 != num2) return num1.compareTo(num2)
    }

    return v1.compareTo(v2, ignoreCase = true)
}

/** 更新日志在线翻译缓存：原文 → 译文（TTL 24h，避免重复请求） */
private val translationCache = ConcurrentHashMap<String, CacheEntry<String>>()
private const val TRANSLATION_TTL_MS = 24 * 60 * 60 * 1000L

/**
 * 调用 MyMemory 免费翻译 API（无需 key，按文本长度有每日限额）把英文日志译成中文。
 * MyMemory 单请求 q 参数约 500 字符上限：长文本按行分块（每块 ≤450 字符）逐块翻译后合并，
 * 单块失败回退原文，保证整段不丢内容。失败或超时返回 null，调用方回退原文。
 */
suspend fun translateReleaseNotes(text: String): String? = withContext(Dispatchers.IO) {
    if (text.isBlank()) return@withContext null
    translationCache[text]?.let {
        if (System.currentTimeMillis() - it.timestamp < TRANSLATION_TTL_MS) return@withContext it.data
    }
    try {
        // 按行边界分块，每块 ≤ 450 字符（留 50 字符余量给 URL 编码膨胀）
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (line in text.split("\n")) {
            if (buf.isNotEmpty() && buf.length + line.length > 450) {
                chunks += buf.toString()
                buf.setLength(0)
            }
            buf.append(line).append('\n')
        }
        if (buf.isNotEmpty()) chunks += buf.toString()
        if (chunks.size <= 1) {
            // 短文本单次请求
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=en|zh-CN"
            val request = Request.Builder().url(url).build()
            githubClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = org.json.JSONObject(response.body?.string().orEmpty())
                val translated = root.optJSONObject("responseData")?.optString("translatedText")
                if (translated.isNullOrBlank() || translated.equals("MYMEMORY WARNING", ignoreCase = true)) null
                else {
                    translationCache[text] = CacheEntry(translated, System.currentTimeMillis())
                    translated
                }
            }
        } else {
            // 长文本分块翻译合并
            val out = StringBuilder()
            var anySuccess = false
            for (chunk in chunks) {
                val translatedChunk = try {
                    val encoded = java.net.URLEncoder.encode(chunk, "UTF-8")
                    val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=en|zh-CN"
                    val request = Request.Builder().url(url).build()
                    githubClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else {
                            val root = org.json.JSONObject(response.body?.string().orEmpty())
                            val t = root.optJSONObject("responseData")?.optString("translatedText")
                            if (t.isNullOrBlank() || t.equals("MYMEMORY WARNING", ignoreCase = true)) null else t
                        }
                    }
                } catch (_: Exception) {
                    null
                }
                if (translatedChunk != null) {
                    out.append(translatedChunk)
                    anySuccess = true
                } else {
                    out.append(chunk)
                }
                out.append('\n')
            }
            if (!anySuccess) null
            else {
                val merged = out.toString().trim()
                translationCache[text] = CacheEntry(merged, System.currentTimeMillis())
                merged
            }
        }
    } catch (_: Exception) {
        null
    }
}

private val componentLogCache = ConcurrentHashMap<String, CacheEntry<String>>()
private const val COMPONENT_LOG_TTL_MS = 30 * 60 * 1000L

/**
 * 从组件文件 URL 反查 GitHub release 正文（组件官方更新日志）。
 * 仅支持 github.com/{owner}/{repo}/releases/download/{tag}/... 形式；
 * raw/manifest 等非 release 地址返回 null。
 */
suspend fun fetchComponentReleaseNotes(remoteUrl: String): String? = withContext(Dispatchers.IO) {
    val m = Regex("github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/")
        .find(remoteUrl)
        ?: return@withContext null
    val owner = m.groupValues[1]
    val repo = m.groupValues[2]
    val tag = m.groupValues[3]
    val cacheKey = "$owner/$repo/$tag"
    componentLogCache[cacheKey]?.let {
        if (System.currentTimeMillis() - it.timestamp < COMPONENT_LOG_TTL_MS) return@withContext it.data
    }
    try {
        val api = "https://api.github.com/repos/$owner/$repo/releases/tags/${java.net.URLEncoder.encode(tag, "UTF-8")}"
        val request = Request.Builder().url(api).build()
        githubClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            val json = org.json.JSONObject(body)
            val notes = json.optString("body").orEmpty()
            if (notes.isNotBlank()) {
                componentLogCache[cacheKey] = CacheEntry(notes, System.currentTimeMillis())
                notes
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

/** GitHub compare API 提交对比结果 */
data class CompareCommit(val message: String, val author: String, val date: String)

/**
 * GitHub Compare API：取两个版本标签之间的提交列表（匿名 60 次/时，够低频对比用）。
 * 返回 null 表示 API 失败（标签不存在 / 网络 / 限流），调用方回退展示两版本日志全文。
 */
suspend fun fetchCompareCommits(owner: String, repo: String, from: String, to: String): List<CompareCommit>? =
    withContext(Dispatchers.IO) {
        if (owner.isBlank() || repo.isBlank() || from.isBlank() || to.isBlank()) return@withContext null
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/compare/" +
                java.net.URLEncoder.encode(from, "UTF-8") + "..." + java.net.URLEncoder.encode(to, "UTF-8")
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "EmuBox")
                .build()
            githubClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = org.json.JSONObject(resp.body?.string().orEmpty())
                val arr = root.optJSONArray("commits") ?: org.json.JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        val cm = c.optJSONObject("commit")
                        val authorObj = cm?.optJSONObject("author")
                        add(
                            CompareCommit(
                                message = (cm?.optString("message") ?: "").split("\n").first(),
                                author = c.optJSONObject("author")?.optString("login")
                                    ?: authorObj?.optString("name") ?: "",
                                date = (authorObj?.optString("date") ?: "").take(10)
                            )
                        )
                    }
                }
            }
        }.getOrNull()
    }

/** EmuReady 兼容性数据库设备命中（devices.get 模糊匹配品牌+型号） */
data class EmuReadyDevice(
    val modelName: String,
    val brandName: String,
    val socName: String,
    val listingsCount: Int
)

/** 查询 EmuReady 设备兼容性统计：按品牌+型号搜索，未命中返回 null（网络失败静默降级）。 */
suspend fun fetchEmuReadyDevice(brand: String, model: String): EmuReadyDevice? = withContext(Dispatchers.IO) {
    try {
        val query = listOfNotNull(brand.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() })
            .joinToString(" ").trim()
        if (query.isBlank()) return@withContext null
        val input = "{\"json\":{\"search\":\"$query\",\"limit\":1}}"
        val url = "https://www.emuready.com/api/mobile/trpc/devices.get?input=" +
            java.net.URLEncoder.encode(input, "UTF-8")
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "EmuHub-CN")
            .build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val root = org.json.JSONObject(resp.body?.string().orEmpty())
            val data = root.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json") ?: return@withContext null
            val arr = data.optJSONArray("devices") ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val d = arr.getJSONObject(0)
            EmuReadyDevice(
                modelName = d.optString("modelName"),
                brandName = d.optString("brandName"),
                socName = d.optString("socName"),
                listingsCount = d.optInt("listingsCount")
            )
        }
    } catch (_: Exception) { null }
}

/** EmuReady 设备兼容性：设备 + 各模拟器平台的兼容得分（getDeviceCompatibility 结果精简） */
data class EmuReadySysCompat(
    val name: String,
    val score: Int,
    val confidence: String,
    val dataSource: String,
    val totalListings: Int,
    val uniqueGames: Int,
    val totalVotes: Int,
    val bestEmulator: String
)
data class EmuReadyCompat(
    val modelName: String,
    val brandName: String,
    val socName: String,
    val systems: List<EmuReadySysCompat>
)

/** 两步查询 EmuReady 设备兼容得分：先 devices.get 多关键词模糊匹配拿精确型号，
 *  再 getDeviceCompatibility 取各平台得分。未命中/网络失败返回 null。
 *  多关键词顺序：品牌+型号 → 纯型号 → socName（SM8xxx/Dimensity xxxx，国内机型常按
 *  SoC 收录）→ 品牌+SoC；任一命中即用其精确型号，解决"识别不出处理器型号"。 */
suspend fun fetchEmuReadyCompatibility(
    brand: String,
    model: String,
    socName: String = ""
): EmuReadyCompat? = withContext(Dispatchers.IO) {
    try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        fun getJson(url: String): org.json.JSONObject? {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "EmuHub-CN").build()
            return client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else org.json.JSONObject(resp.body?.string().orEmpty())
            }
        }
        // ① 多关键词依次模糊匹配拿精确型号
        val queries = buildList {
            add(listOfNotNull(brand.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() })
                .joinToString(" ").trim())
            add(model.trim())
            add(socName.trim())
            add(listOfNotNull(brand.takeIf { it.isNotBlank() }, socName.takeIf { it.isNotBlank() })
                .joinToString(" ").trim())
        }.filter { it.isNotBlank() }.distinct()
        var dev: org.json.JSONObject? = null
        for (q in queries) {
            val searchInput = "{\"json\":{\"search\":\"$q\",\"limit\":1}}"
            val searchUrl = "https://www.emuready.com/api/mobile/trpc/devices.get?input=" +
                java.net.URLEncoder.encode(searchInput, "UTF-8")
            val searchRoot = getJson(searchUrl) ?: continue
            val devicesArr = searchRoot.optJSONObject("result")?.optJSONObject("data")
                ?.optJSONObject("json")?.optJSONArray("devices") ?: continue
            if (devicesArr.length() > 0) { dev = devicesArr.getJSONObject(0); break }
        }
        if (dev == null) return@withContext null
        val exactModel = dev!!.optString("modelName")
        val exactBrand = dev!!.optString("brandName")
        // ② 精确型号查兼容得分
        val compatInput = "{\"json\":{\"deviceBrandName\":\"$exactBrand\",\"deviceModelName\":\"$exactModel\"}}"
        val compatUrl = "https://www.emuready.com/api/mobile/trpc/catalog.getDeviceCompatibility?input=" +
            java.net.URLEncoder.encode(compatInput, "UTF-8")
        val compatRoot = getJson(compatUrl) ?: return@withContext null
        val json = compatRoot.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json")
            ?: return@withContext null
        val deviceObj = json.optJSONObject("device")
        val systemsArr = json.optJSONArray("systems") ?: org.json.JSONArray()
        val systems = buildList {
            for (i in 0 until systemsArr.length()) {
                val s = systemsArr.getJSONObject(i)
                val score = s.optInt("compatibilityScore", -1)
                if (score < 0) continue
                val emus = s.optJSONArray("emulators") ?: org.json.JSONArray()
                var best = ""
                var bestScore = -1
                for (j in 0 until emus.length()) {
                    val e = emus.getJSONObject(j)
                    val es = e.optInt("avgCompatibilityScore", -1)
                    if (es > bestScore) { bestScore = es; best = e.optString("name") }
                }
                val metrics = s.optJSONObject("metrics")
                add(
                    EmuReadySysCompat(
                        name = s.optString("name"),
                        score = score,
                        confidence = s.optString("confidence"),
                        dataSource = s.optString("dataSource"),
                        totalListings = metrics?.optInt("totalListings") ?: 0,
                        uniqueGames = metrics?.optInt("uniqueGames") ?: 0,
                        totalVotes = metrics?.optInt("totalVotes") ?: 0,
                        bestEmulator = best
                    )
                )
            }
        }.sortedByDescending { it.score }.take(5)
        if (systems.isEmpty()) return@withContext null
        EmuReadyCompat(
            modelName = deviceObj?.optString("modelName") ?: exactModel,
            brandName = deviceObj?.optString("brandName") ?: exactBrand,
            socName = deviceObj?.optString("socName") ?: dev!!.optString("socName"),
            systems = systems
        )
    } catch (_: Exception) { null }
}

/** EmuReady 模拟器命中（emulators.get 按名称搜索，含社区报告数） */
data class EmuReadyEmulator(
    val name: String,
    val listingsCount: Int
)

/** 查询模拟器社区数据：emulators.get 按名称模糊匹配，失败/未命中返回 null 静默降级。 */
suspend fun fetchEmuReadyEmulator(name: String): EmuReadyEmulator? = withContext(Dispatchers.IO) {
    try {
        val q = name.takeIf { it.isNotBlank() } ?: return@withContext null
        val input = "{\"json\":{\"search\":\"$q\",\"limit\":1}}"
        val url = "https://www.emuready.com/api/mobile/trpc/emulators.get?input=" +
            java.net.URLEncoder.encode(input, "UTF-8")
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "EmuHub-CN").build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val root = org.json.JSONObject(resp.body?.string().orEmpty())
            val arr = root.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("json") ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val e = arr.getJSONObject(0)
            EmuReadyEmulator(
                name = e.optString("name"),
                listingsCount = e.optJSONObject("_count")?.optInt("listings") ?: 0
            )
        }
    } catch (_: Exception) { null }
}

/** 在线翻译（Google 免费端点）：自动检测 → 中文，失败返回 null（UI 兜底显示原文）。
 *  仅对正文前 1500 字符翻译（控制请求体积），适合 release 日志/新闻正文。 */
suspend fun translateText(text: String): String? = withContext(Dispatchers.IO) {
    try {
        val q = text.trim().take(1500)
        if (q.isBlank()) return@withContext null
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "EmuHub-CN").build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val root = org.json.JSONArray(resp.body?.string().orEmpty())
            val sb = StringBuilder()
            for (i in 0 until root.length()) {
                val seg = root.optJSONArray(i) ?: continue
                if (seg.length() > 0) sb.append(seg.optString(0))
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { null }
}
