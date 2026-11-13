package com.emubox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 仓库曾用名 EmuHub-CN，2026 年更名为 Emubox（GitHub 301 重定向）。
// 统一指向现名 Emubox，旧路径仅作兜底。
// 首屏只拉索引（约 340KB）。完整 registry 3.2MB，91% 是 releases，
// 那是点进详情页才需要的东西，拆到 emulator-details-N.json 按需取。
const val DEFAULT_EMULATOR_INDEX_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/emulator-index.json"

const val DEFAULT_EMULATOR_DETAILS_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/emulator-details-%d.json"

/** 完整注册表 URL：仅作兼容保留（老版本客户端、外部脚本仍可能引用） */
const val DEFAULT_EMULATOR_REGISTRY_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/emulator-registry.json"

const val DEFAULT_EMULATOR_RANKING_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/emulator-ranking.json"

private const val OFFLINE_ASSET = "emulator-offline.json"

/** 自建/第三方代码托管站，命中则直接使用 repoUrl */
private val REPO_HOST = Regex(
    "^https://(github\\.com|gitlab\\.com|gitee\\.com|codeberg\\.org|notabug\\.org|" +
        "git\\.ryujinx\\.app|git\\.eden-emu\\.dev)/", RegexOption.IGNORE_CASE
)

/** 模拟器条目：官方 registry（版本/APK 直链）+ 社区排行（下载量/更新日志）合并 */
data class EmulatorAsset(
    val name: String = "",
    val size: Long = 0L,
    val url: String = ""
)

data class EmulatorRelease(
    val tag: String = "",
    val date: String = "",
    val body: String = "",
    val assets: List<EmulatorAsset> = emptyList()
)

/**
 * 一个项目在最新发布里「实际给了什么」。五类分开，是因为用户关心的问题不同：
 * 安卓用户只问 apk/androidZip 有没有，桌面用户问 desktop，开发者问 source。
 */
data class EmulatorArtifacts(
    val apk: Int = 0,
    val androidZip: Int = 0,
    val ipa: Int = 0,
    val libretroCore: Int = 0,
    val desktop: Int = 0,
    val console: Int = 0,
    val source: Int = 0,
    val total: Int = 0
) {
    /** 手机上能直接装的（安卓 apk / 安卓 zip / iOS ipa） */
    val installableOnPhone: Int get() = apk + androidZip + ipa

    /**
     * 归好类的产物数。`total` 是发布页的资产总数，含 sha256sums、.sig、说明图片等
     * 校验/说明文件——354 条里有 131 条两者对不上（Termux：total 48、装包 40）。
     * 界面上一律用这个值，避免「共 48 个」下面只列出 40 个的自相矛盾。
     */
    val recognized: Int get() = apk + androidZip + ipa + libretroCore + desktop + console + source

    /** 界面上报的「共 N 个」：优先归好类的数，全没认出来才退回资产总数 */
    val displayTotal: Int get() = if (recognized > 0) recognized else total

    val isEmpty: Boolean get() = total == 0

    /** 有没有「不是手机包」的产物：只有桌面或源码时必须提前告知，别让用户白下 */
    val onlyOtherPlatforms: Boolean get() = installableOnPhone == 0 && total > 0
}

data class EmulatorEntry(
    val id: String,
    val name: String,
    /**
     * 中文名 / 中文作品名。
     * 原生移植项目的原名是英文（Zelda64 Recompiled、Ship of Harkinian），
     * 中文用户看不出这是哪个游戏，所以补一列人工核对的译名，空值表示原名本身可读。
     */
    val nameZh: String = "",
    val category: String,
    /** 用途分区：console-emulation / windows-compat / native-port / frontend-tool / vn-engine */
    val section: String = "",
    /** 模拟的目标平台：ps2 / switch / 3ds / windows-pc …（用于二级筛选） */
    val platforms: List<String> = emptyList(),
    /**
     * 平台家族（8 个硬件谱系：sony / nintendo / microsoft / sega /
     * arcade-handheld / retro-computer / mobile-legacy / pc）。
     * 由 registry 的 `platform_families` 派生，供「分区 → 家族 → 机型」三级筛选的中间层。
     * 空值表示该条目描述的是形态（multi）或题材（visual-novel），不属任何硬件谱系。
     */
    val platformFamilies: List<String> = emptyList(),
    /**
     * 二级分类的另一种轴。只有两个分区用：
     * windows-compat -> winlator / gamehub / bannerhub / other-container / launcher-tool / desktop
     * frontend-tool  -> frontend / tool / emulator
     * 这两个分区套平台家族会失效（前者 49/50 都在 pc 一族，后者 22 条无家族），
     * 由 scripts/derive_subcategory.py 从 id/name/kind 派生。
     */
    val subcategory: String = "",
    /**
     * 发布产物构成。按「解析资产文件名」统计，不是数 release 条数——
     * 安卓包常打成 zip（EX-Emulators.zip / FivePocket-*-android.zip），
     * 只看扩展名会把 100 多条判成「没有安卓产物」。
     */
    val artifacts: EmulatorArtifacts = EmulatorArtifacts(),
    /** 是否有能直接装到安卓手机上的产物（apk / 安卓 zip / ipa 之外的都不算） */
    val androidReady: Boolean = false,
    val description: String,
    /**
     * 中文一句话摘要：优先用 README 首段，没有 README 时用仓库 About，
     * 再没有就从最近发布说明里提炼。description 保留作者原文。
     */
    val summary: String = "",
    val owner: String,
    val repo: String,
    val homepage: String = "",
    val repoUrl: String = "",
    val official: Boolean = false,
    val channel: String = "stable",
    val latestTag: String = "",
    val latestApkUrl: String = "",
    val latestApkName: String = "",
    val latestApkSize: Long = 0L,
    val pushedAt: String = "",
    val stars: Long = 0L,
    val forks: Long = 0L,
    val watchers: Long = 0L,
    val downloads: Long = 0L,
    val latestDate: String = "",
    val latestBody: String = "",
    val releases: List<EmulatorRelease> = emptyList(),
    val logoUrl: String = "",
    /** 无下载条目标注（GitHub 移除/未发布），UI 展示"暂无可用下载" */
    val note: String = "",
    /** 来源分级：github/gitea/gitlab/notabug/official-web/official-play/community-archive */
    val source: String = "",
    /** DMCA/停更等失效仓库：UI 不跳转、隐藏下载 */
    val dead: Boolean = false,
    val deadReason: String = "",
    /**
     * 非 GitHub 分发条目的下载入口：官网直链 / SourceForge 文件页 / Google Play。
     * registry 里 40 条只有它没有 APK，此前 UI 完全没读这个字段，
     * 于是「有官方下载页」被渲染成按钮变灰的「无下载」。
     */
    val downloadUrl: String = "",
    /**
     * 运行宿主：android / windows / macos / linux。
     * 库里 105 条是桌面模拟器，安卓端装不了，列表要标出来，
     * 否则用户点进去才发现「没有安装包」。
     */
    val hosts: List<String> = emptyList(),
    /** 详情分片号（-1 表示当前对象已自带 releases，无需补拉） */
    val detailShard: Int = -1,
    val license: String = "",
    val topics: List<String> = emptyList(),
    /**
     * 社区下载量名次（1 起，0 = 无排名）。
     * 只有进榜（有累计下载统计）的项目才有值，列表挂「#N」勋章；
     * 其余留 0 而不是硬排一个号——没有下载数据的排名是假数据。
     */
    val rank: Int = 0
) {
    /**
     * 源码仓库地址。
     * 只有能确定是代码托管站时才返回，避免给 GitLab/Gitea/NotABug 源拼出 github.com 死链。
     * 优先级：repoUrl（若指向代码托管站）> GitHub 源拼装 > 空。
     */
    val repoLink: String
        get() {
            if (repoUrl.isNotBlank() && REPO_HOST.containsMatchIn(repoUrl)) return repoUrl
            if (owner.isBlank() || repo.isBlank()) return ""
            return if (source.isBlank() || source == "github") "https://github.com/$owner/$repo" else ""
        }

    /** 兼容旧字段名，语义同 [repoLink] */
    @Deprecated("改用 repoLink", ReplaceWith("repoLink"))
    val githubUrl: String get() = repoLink

    /** 社区排行条目（无官方 APK 直链，走 GitHub Release / 官网） */
    val hasApk: Boolean get() = latestApkUrl.isNotBlank()
}

/**
 * APK 内置图标：`assets/icons/<id>.webp`（见 scripts/build_icon_assets.py）。
 * 命中就用本地文件，省一次网络请求，弱网/离线也不会整片字母牌。
 */
private object LocalIcons {
    @Volatile
    private var ids: Set<String>? = null

    fun load(context: android.content.Context): Set<String> {
        ids?.let { return it }
        val loaded = runCatching {
            val body = context.assets.open("icon-manifest.json").bufferedReader().use { it.readText() }
            JSONArray(body).let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
            }
        }.getOrElse { emptySet() }
        ids = loaded
        return loaded
    }

    fun apply(context: android.content.Context, entries: List<EmulatorEntry>): List<EmulatorEntry> {
        val have = load(context)
        if (have.isEmpty()) return entries
        return entries.map { e ->
            if (e.id in have) e.copy(logoUrl = "file:///android_asset/icons/${e.id}.webp") else e
        }
    }
}

object EmulatorRegistryRepository {
    /** 静默刷新用：不随界面销毁，缓存写完下次启动直接生效 */
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 离线兜底：首启无网、或远程三次都失败时用。
     * 内容来自 assets/emulator-offline.json（由 scripts/build_index.py 从 registry 生成），
     * 不再在 Kotlin 里手写 130 行字面量——那份副本早就和 registry 不同步了
     * （有把 .png 当 APK 的条目、缺 summary 字段）。
     */
    fun builtInRegistry(context: android.content.Context): List<EmulatorEntry> = runCatching {
        val body = context.assets.open(OFFLINE_ASSET).bufferedReader().use { it.readText() }
        parseEmulators(JSONObject(body))
    }.getOrElse { emptyList() }

    private fun parseEmulators(json: JSONObject): List<EmulatorEntry> {
        val arr: JSONArray = json.optJSONArray("emulators") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val relArr = obj.optJSONArray("releases")
                val latest = if (relArr != null && relArr.length() > 0) relArr.getJSONObject(0) else null
                val assets = latest?.optJSONArray("assets")
                // 下载/版本以顶层 latest_* 为准（refresh 脚本已做 APK 过滤）；releases 仅作详情展示。
                // 顶层缺失时兜底取 releases 中第一个 .apk 资产，避免 aab/tar.gz/debug 污染下载按钮。
                val firstApk = if (assets != null) {
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").lowercase().endsWith(".apk") }
                } else null
                val repoFull = obj.optString("repo")
                val slash = repoFull.indexOf('/')
                add(
                    EmulatorEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        category = obj.optString("category"),
                        section = obj.optString("section"),
                        platforms = obj.optJSONArray("platforms")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
                        } ?: emptyList(),
                        platformFamilies = obj.optJSONArray("platform_families")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
                        } ?: emptyList(),
                        subcategory = obj.optString("subcategory"),
                        artifacts = obj.optJSONObject("artifacts")?.let { a ->
                            EmulatorArtifacts(
                                apk = a.optInt("apk", 0),
                                androidZip = a.optInt("android_zip", 0),
                                ipa = a.optInt("ipa", 0),
                                libretroCore = a.optInt("libretro_core", 0),
                                desktop = a.optInt("desktop", 0),
                                console = a.optInt("console", 0),
                                source = a.optInt("source", 0),
                                total = a.optInt("total", 0)
                            )
                        } ?: EmulatorArtifacts(),
                        androidReady = obj.optBoolean("android_ready", false),
                        description = obj.optString("description"),
                        summary = obj.optString("summary"),
                        owner = if (slash > 0) repoFull.substring(0, slash) else "",
                        repo = if (slash > 0) repoFull.substring(slash + 1) else "",
                        homepage = obj.optString("homepage"),
                        repoUrl = obj.optString("repo_url"),
                        official = obj.optBoolean("official", false),
                        channel = obj.optString("channel", "stable"),
                        latestTag = obj.optString("latest_tag").ifBlank { latest?.optString("tag").orEmpty() },
                        latestDate = obj.optString("latest_date").ifBlank { latest?.optString("date").orEmpty() },
                        latestBody = obj.optString("latest_body").ifBlank { latest?.optString("body").orEmpty() },
                        latestApkUrl = obj.optString("latest_apk_url").ifBlank { firstApk?.optString("url").orEmpty() },
                        latestApkName = obj.optString("latest_apk_name").ifBlank { firstApk?.optString("name").orEmpty() },
                        latestApkSize = obj.optLong("latest_apk_size", 0L).takeIf { it > 0 } ?: firstApk?.optLong("size", 0L) ?: 0L,
                        pushedAt = obj.optString("pushed_at"),
                        // 字段名统一后只认 logo_url；不再回退作者头像，
                        // 空值交给 UI 的字母牌（见 ProjectAvatar）。
                        logoUrl = obj.optString("logo_url"),
                        nameZh = obj.optString("name_zh"),
                        rank = obj.optInt("rank", 0),
                        topics = obj.optJSONArray("topics")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf(String::isNotBlank)
                            }
                        } ?: emptyList(),
                        note = obj.optString("note"),
                        stars = obj.optLong("stars", 0L),
                        forks = obj.optLong("forks", 0L),
                        watchers = obj.optLong("watchers", 0L),
                        // 累计下载量统一叫 downloads_total；没有累计值的老条目退回 downloads_latest
                        // （语义是「当前版本下载量」，是下限但有总比显示 0 好）。
                        downloads = obj.optLong("downloads_total", 0L)
                            .takeIf { it > 0 } ?: obj.optLong("downloads_latest", 0L),
                        releases = parseReleases(obj),
                        source = obj.optString("source", ""),
                        dead = obj.optBoolean("dead", false),
                        deadReason = obj.optString("dead_reason", ""),
                        downloadUrl = obj.optString("download_url"),
                        hosts = obj.optJSONArray("hosts")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
                        } ?: emptyList(),
                        detailShard = obj.optInt("detail_shard", -1)
                    )
                )
            }
        }.filter { it.id.isNotBlank() }
    }

    /**
     * 首屏索引：stale-while-revalidate。
     *
     * 有缓存就先返回缓存（哪怕已经过期一周），同时在后台刷新，刷到新数据通过
     * [onRefreshed] 回调推给界面。用户不再为「缓存 1 天到期」付一次转圈代价。
     * 只有完全没缓存（首装/清数据）才走阻塞网络。
     */
    suspend fun fetchRemoteRegistry(
        context: android.content.Context,
        onRefreshed: ((List<EmulatorEntry>) -> Unit)? = null
    ): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val cached = DiskRegistryCache.read(context, "emulator_index", maxAgeDays = 30)
        val stale = cached?.let { parseEmulators(JSONObject(it)) }?.takeIf { it.isNotEmpty() }

        if (stale != null) {
            if (DiskRegistryCache.ageDays(context, "emulator_index") >= 1) {
                bgScope.launch {
                    val fresh = runCatching {
                        RemoteData.fetchText(DEFAULT_EMULATOR_INDEX_URL)?.let { body ->
                            parseEmulators(JSONObject(body)).takeIf { it.isNotEmpty() }?.also {
                                DiskRegistryCache.write(context, "emulator_index", body)
                            }
                        }
                    }.getOrNull()
                    if (fresh != null && onRefreshed != null) {
                        withContext(Dispatchers.Main) { onRefreshed(fresh) }
                    }
                }
            }
            return@withContext LocalIcons.apply(context, stale)
        }

        val entries = runCatching {
            val body = RemoteData.fetchText(DEFAULT_EMULATOR_INDEX_URL) ?: return@runCatching null
            val parsed = parseEmulators(JSONObject(body))
            if (parsed.isEmpty()) null else parsed.also { DiskRegistryCache.write(context, "emulator_index", body) }
        }.getOrNull()
        LocalIcons.apply(context, entries ?: builtInRegistry(context))
    }

    /**
     * 详情补全：索引里没有 releases / 更新日志，进详情页时按 detail_shard 拉一片
     * （约 250~390KB，比整包 3.2MB 省 90%）。分片按 shard 号缓存，同一片里的条目共用。
     */
    suspend fun loadDetail(context: android.content.Context, entry: EmulatorEntry): EmulatorEntry =
        withContext(Dispatchers.IO) {
            if (entry.detailShard < 0 || entry.releases.isNotEmpty()) return@withContext entry
            val key = "emulator_details_${entry.detailShard}"
            val url = DEFAULT_EMULATOR_DETAILS_URL.format(entry.detailShard)

            fun pick(body: String): EmulatorEntry? = runCatching {
                val details = JSONObject(body).optJSONObject("details") ?: return null
                val d = details.optJSONObject(entry.id) ?: return null
                entry.copy(
                    releases = parseReleases(d),
                    latestBody = entry.latestBody.ifBlank { d.optString("latest_body") },
                    license = d.optString("license"),
                    topics = d.optJSONArray("topics")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
                    } ?: emptyList()
                )
            }.getOrNull()

            DiskRegistryCache.read(context, key, maxAgeDays = 7)?.let { pick(it)?.let { r -> return@withContext r } }

            runCatching {
                RemoteData.fetchText(url)?.let { body ->
                    pick(body)?.also { DiskRegistryCache.write(context, key, body) }
                }
            }.getOrNull() ?: entry
        }

    /** 拉取远程社区排行（下载量/更新日志），本地缓存优先，失败回退内置 */
    suspend fun fetchRemoteRanking(
        context: android.content.Context,
        onRefreshed: ((List<EmulatorEntry>) -> Unit)? = null
    ): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val cached = DiskRegistryCache.read(context, "emulator_ranking", maxAgeDays = 30)
        cached?.let { parseEmulators(JSONObject(it)) }?.takeIf { it.isNotEmpty() }?.let { stale ->
            if (DiskRegistryCache.ageDays(context, "emulator_ranking") >= 1) {
                bgScope.launch {
                    val fresh = runCatching {
                        RemoteData.fetchText(DEFAULT_EMULATOR_RANKING_URL)?.let { body ->
                            parseEmulators(JSONObject(body)).takeIf { it.isNotEmpty() }?.also {
                                DiskRegistryCache.write(context, "emulator_ranking", body)
                            }
                        }
                    }.getOrNull()
                    if (fresh != null && onRefreshed != null) {
                        withContext(Dispatchers.Main) { onRefreshed(fresh) }
                    }
                }
            }
            return@withContext LocalIcons.apply(context, stale)
        }
        val entries = runCatching {
            val body = RemoteData.fetchText(DEFAULT_EMULATOR_RANKING_URL) ?: return@runCatching null
            val parsed = parseEmulators(JSONObject(body))
            if (parsed.isEmpty()) null else parsed.also { DiskRegistryCache.write(context, "emulator_ranking", body) }
        }.getOrNull()
        val result = entries ?: DiskRegistryCache.read(context, "emulator_ranking", maxAgeDays = 30)?.let {
            parseEmulators(JSONObject(it))?.takeIf { p -> p.isNotEmpty() }
        } ?: builtInRegistry(context)
        LocalIcons.apply(context, result)
    }

    private fun parseReleases(obj: JSONObject): List<EmulatorRelease> {
        val arr = obj.optJSONArray("releases") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                val assetsArr = rel.optJSONArray("assets")
                val assets = buildList {
                    if (assetsArr != null) {
                        for (j in 0 until assetsArr.length()) {
                            val a = assetsArr.getJSONObject(j)
                            add(EmulatorAsset(
                                name = a.optString("name"),
                                size = a.optLong("size", 0L),
                                url = a.optString("url")
                            ))
                        }
                    }
                }
                add(EmulatorRelease(
                    tag = rel.optString("tag"),
                    date = rel.optString("date"),
                    body = rel.optString("body"),
                    assets = assets
                ))
            }
        }
    }
}

/** 社区数据洞察（t3st31 history 趋势 + 总览统计 + 分类占比 + 食谱） */
data class EmulatorInsights(
    val totalProjects: Int = 0,
    val totalDownloads: Long = 0L,
    val totalStars: Long = 0L,
    val trendBaseDate: String = "",
    val trendLatestDate: String = "",
    val trending14: List<TrendingRepo> = emptyList(),
    val categoryDownloads: List<CategoryDownloads> = emptyList(),
    val recipes: List<EmulatorRecipe> = emptyList(),
    /** 近 7 天全社区发布版本数（412 首页 Releases this week 等价物） */
    val weeklyReleases: Int = 0,
    /** 412 站点访问统计（visitors.json 由上游每小时产出；未产出时为 0，UI 不显示） */
    val totalVisits: Long = 0L,
    val onlineNow: Int = 0,
    /** t3st31 兼容性测试报告（上游填充后自动接入） */
    val compatReports: List<CompatReport> = emptyList()
)

data class CompatReport(
    val project: String = "",
    val status: String = "",
    val game: String = "",
    val note: String = ""
)

data class TrendingRepo(
    val repo: String = "",
    val delta: Long = 0L,
    val date: String = ""
)

data class CategoryDownloads(
    val category: String = "",
    val label: String = "",
    val downloads: Long = 0L
)

data class EmulatorRecipe(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val settings: List<Pair<String, String>> = emptyList(),
    val checks: List<String> = emptyList(),
    val sources: List<String> = emptyList()
)

object EmulatorInsightsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun parse(json: JSONObject): EmulatorInsights {
        val ins = json.optJSONObject("insights") ?: JSONObject()
        val trending = buildList {
            val arr = ins.optJSONArray("trending14")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(TrendingRepo(
                        repo = o.optString("repo"),
                        delta = o.optLong("delta", 0L),
                        date = o.optString("date")
                    ))
                }
            }
        }
        val cats = buildList {
            val arr = ins.optJSONArray("category_downloads")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CategoryDownloads(
                        category = o.optString("category"),
                        label = o.optString("label"),
                        downloads = o.optLong("downloads", 0L)
                    ))
                }
            }
        }
        val recipes = buildList {
            val arr = json.optJSONArray("recipes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val settings = buildList {
                        val sa = o.optJSONArray("settings")
                        if (sa != null) {
                            for (j in 0 until sa.length()) {
                                val so = sa.getJSONObject(j)
                                add(so.optString("key") to so.optString("value"))
                            }
                        }
                    }
                    val checks = buildList {
                        val ca = o.optJSONArray("checks")
                        if (ca != null) {
                            for (j in 0 until ca.length()) {
                                add(ca.getJSONObject(j).optString("title"))
                            }
                        }
                    }
                    val srcs = buildList {
                        val sra = o.optJSONArray("sources")
                        if (sra != null) {
                            for (j in 0 until sra.length()) add(sra.getString(j))
                        }
                    }
                    add(EmulatorRecipe(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        summary = o.optString("summary"),
                        settings = settings,
                        checks = checks,
                        sources = srcs
                    ))
                }
            }
        }
        val compat = buildList {
            val arr = ins.optJSONArray("compat_reports")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CompatReport(
                        project = o.optString("project"),
                        status = o.optString("status"),
                        game = o.optString("game"),
                        note = o.optString("note")
                    ))
                }
            }
        }
        return EmulatorInsights(
            totalProjects = ins.optInt("total_projects", 0),
            totalDownloads = ins.optLong("total_downloads", 0L),
            totalStars = ins.optLong("total_stars", 0L),
            trendBaseDate = ins.optString("trend_base_date"),
            trendLatestDate = ins.optString("trend_latest_date"),
            trending14 = trending,
            categoryDownloads = cats,
            recipes = recipes,
            weeklyReleases = ins.optInt("weekly_releases", 0),
            totalVisits = ins.optLong("total_visits", 0L),
            onlineNow = ins.optInt("online_now", 0),
            compatReports = compat
        )
    }

    /** 从远程 emulator-ranking.json 拉取 insights（与排行同一文件），失败返回空 */
    suspend fun fetchRemoteInsights(): EmulatorInsights = withContext(Dispatchers.IO) {
        runCatching {
            val body = RemoteData.fetchText(DEFAULT_EMULATOR_RANKING_URL) ?: return@runCatching null
            parse(JSONObject(body))
        }.getOrNull() ?: EmulatorInsights()
    }
}