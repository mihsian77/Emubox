package com.emuhub.cn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class GithubRelease(
    val tagName: String,
    val name: String,
    val assets: List<GithubAsset>,
    val publishedAt: String = "",
    val body: String = ""
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

private val githubClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

// In-memory cache to avoid re-fetching the same GitHub releases / component
// manifest on every source switch. TTL is 10 minutes; stale entries are
// transparently re-fetched.
private const val CACHE_TTL_MS = 10 * 60 * 1000L
private data class CacheEntry<T>(val data: T, val timestamp: Long)
private val releaseCache = ConcurrentHashMap<String, CacheEntry<List<GithubRelease>>>()
private val componentCache = ConcurrentHashMap<String, CacheEntry<Map<String, List<Component>>>>()

private fun <T> ConcurrentHashMap<String, CacheEntry<T>>.getValid(key: String): T? {
    val entry = this[key] ?: return null
    return if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) entry.data else null
}

suspend fun fetchGithubReleasesFromUrl(apiUrl: String): List<GithubRelease> = withContext(Dispatchers.IO) {
    releaseCache.getValid(apiUrl)?.let { return@withContext it }

    val acceleratedUrl = Accelerator.rewriteUrl(apiUrl)
    val request = Request.Builder()
        .url(acceleratedUrl)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()

    try {
        githubClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val json = response.body?.string() ?: return@withContext emptyList()
            val releasesArray = JSONArray(json)

            val releases = (0 until releasesArray.length()).mapNotNull { idx ->
                val obj = releasesArray.getJSONObject(idx)
                if (obj.optBoolean("draft", false)) return@mapNotNull null

                val tagName = obj.optString("tag_name")
                if (tagName.isBlank()) return@mapNotNull null

                val name = obj.optString("name").ifBlank { tagName }
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
                    body = obj.optString("body", "")
                )
            }.sortGithubReleasesNewestFirst()

            releaseCache[apiUrl] = CacheEntry(releases, System.currentTimeMillis())
            releases
        }
    } catch (_: Exception) {
        emptyList()
    }
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

    return (filtered.ifEmpty { fallback }.ifEmpty { allReleases })
        .map { release ->
            val compatibleAssets = release.assets.filter { asset ->
                val included = includeAssetPatterns.isEmpty() ||
                    includeAssetPatterns.any { asset.name.contains(it, ignoreCase = true) }
                val excluded = excludeAssetPatterns.any { asset.name.contains(it, ignoreCase = true) }
                included && !excluded
            }
            release.copy(assets = compatibleAssets)
        }
        .filter { it.assets.isNotEmpty() }
        .sortGithubReleasesNewestFirst()
}

suspend fun fetchQualcommReleases(source: QualcommSource): List<GithubRelease> {
    val releases = fetchGithubReleasesFromUrl(source.apiUrl)
    if (source.filters.isEmpty()) return releases

    return releases.filter { release ->
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

suspend fun fetchComponentsFromUrl(manifestUrl: String): Map<String, List<Component>> =
    withContext(Dispatchers.IO) {
        componentCache.getValid(manifestUrl)?.let { return@withContext it }

        val acceleratedUrl = Accelerator.rewriteUrl(manifestUrl)
        val request = Request.Builder().url(acceleratedUrl).build()

        try {
            githubClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyMap()
                val jsonArray = JSONArray(response.body?.string() ?: return@withContext emptyMap())
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
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
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
    preferredExtensions: List<String> = listOf(".wcp", ".zip")
): List<Component> {
    val releases = fetchGithubReleasesFromUrl(apiUrl)
    return releases.mapNotNull { release ->
        val asset = release.assets.firstOrNull { a ->
            preferredExtensions.any { a.name.lowercase().endsWith(it) }
        } ?: release.assets.firstOrNull { it.downloadUrl.isNotBlank() }
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
 * 失败或超时返回 null，调用方回退原文。
 */
suspend fun translateReleaseNotes(text: String): String? = withContext(Dispatchers.IO) {
    if (text.isBlank() || text.length > 4500) return@withContext null
    translationCache[text]?.let {
        if (System.currentTimeMillis() - it.timestamp < TRANSLATION_TTL_MS) return@withContext it.data
    }
    try {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=en|zh-CN"
        val request = Request.Builder().url(url).build()
        githubClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            val root = org.json.JSONObject(body)
            val translated = root.optJSONObject("responseData")?.optString("translatedText")
            if (translated.isNullOrBlank() || translated.equals("MYMEMORY WARNING", ignoreCase = true)) {
                null
            } else {
                translationCache[text] = CacheEntry(translated, System.currentTimeMillis())
                translated
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
