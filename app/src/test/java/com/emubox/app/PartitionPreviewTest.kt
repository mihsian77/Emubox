package com.emubox.app

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emubox.app.ui.theme.EmuBoxTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 七个分区的实况预览：每个分区一张图，数据全部来自仓库里的真实清单
 * （catalog.json / 各源 contents.json / GitHub releases / emulator-registry.json），
 * 不编造条目。设备参数取一加 13（PJZ110 / 骁龙 8 Elite / Adreno 830 / 16GB / Android 15）。
 *
 * 说明：JVM 上无法加载 Vulkan 原生库，也拿不到真实下载目录与真实网络实时数据，
 * 因此这类依赖外部条件的区块会以「加载中 / 空态 / 离线副本」的真实形态出现，
 * 报告里对每一张图都会注明哪些是环境限制、哪些是产品问题。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h905dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
class PartitionPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        SettingsManager.init(ctx)
        // 预热内置离线新闻副本：否则截图时新闻区块还停在异步加载前的「共 0 条」
        NewsRepository.loadFromAssets(ctx)
    }

    // ── 数据准备 ────────────────────────────────────────────────

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("preview/$name")!!
            .bufferedReader().readText()

    /** 仓库根目录下的数据文件（emulator-registry.json 在仓库根，不在 assets） */
    /** assets/icons/<id>.webp 存在就用本地文件，模拟 App 的「本地图标优先」 */
    private fun localIconOr(id: String, remote: String): String {
        val f = repoFile("app/src/main/assets/icons/$id.webp")
        return if (f.exists()) f.toURI().toString() else remote
    }

    private fun repoFile(name: String): File {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null && !File(d, name).exists()) d = d.parentFile
        return File(d ?: File("."), name)
    }

    private fun onePlus13() = DeviceInfo(
        androidVersion = "15 (SDK 35)",
        ram = "16 GB",
        gpuRenderer = "Adreno (TM) 830",
        adrenoSeries = "8xx",
        gpuModel = "830",
        gpuVendor = GpuVendor.ADRENO,
        deviceBrand = "OnePlus",
        deviceModel = "PJZ110",
        socName = "Qualcomm Snapdragon 8 Elite"
    )

    private fun turnipSources(): List<TurnipSource> {
        val arr = JSONObject(res("catalog.json")).optJSONArray("turnipSources") ?: JSONArray()
        val out = ArrayList<TurnipSource>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seriesArr = o.optJSONArray("supportedSeries") ?: JSONArray()
            val series = HashSet<String>()
            for (j in 0 until seriesArr.length()) series.add(seriesArr.optString(j))
            val filtersObj = o.optJSONObject("filters")
            val filters = LinkedHashMap<String, List<String>>()
            filtersObj?.keys()?.forEach { k ->
                val a = filtersObj.optJSONArray(k) ?: JSONArray()
                val list = ArrayList<String>()
                for (j in 0 until a.length()) list.add(a.optString(j))
                filters[k] = list
            }
            out.add(
                TurnipSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    apiUrl = o.optString("apiUrl"),
                    description = o.optString("description"),
                    experimental = o.optBoolean("experimental"),
                    supportedSeries = series,
                    filters = filters,
                    stars = o.optLong("stars"),
                    updatedAt = o.optLong("updatedAt"),
                    requiredVendor = o.optString("requiredVendor").takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    private fun turnipReleases(): List<GithubRelease> {
        val arr = JSONArray(res("rel_steven.json"))
        val out = ArrayList<GithubRelease>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val assetsArr = o.optJSONArray("assets") ?: JSONArray()
            val assets = ArrayList<GithubAsset>()
            for (j in 0 until assetsArr.length()) {
                val a = assetsArr.optJSONObject(j) ?: continue
                assets.add(
                    GithubAsset(
                        name = a.optString("name"),
                        downloadUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                        digest = a.optString("digest")
                    )
                )
            }
            out.add(
                GithubRelease(
                    tagName = o.optString("tag_name"),
                    name = o.optString("name"),
                    assets = assets,
                    publishedAt = o.optString("published_at"),
                    body = o.optString("body").take(400),
                    prerelease = o.optBoolean("prerelease")
                )
            )
        }
        return out
    }

    /**
     * 按源的 filters 规则筛出「一加 13 上真正会显示的版本」。
     * 规则与 NetworkUtils.fetchTurnipReleases 一致（8xx 命不中就退回 turnip/mesa/adreno 全量），
     * 目的只是让截图等于真机所见，不引入测试专用逻辑。
     */
    private fun releasesFor(source: TurnipSource, series: String): List<GithubRelease> {
        val all = turnipReleases()
        val patterns = source.filters[series] ?: source.filters["default"] ?: emptyList()
        fun searchable(r: GithubRelease) =
            r.name + " " + r.tagName + " " + r.assets.joinToString(" ") { it.name }
        val filtered = if (patterns.isEmpty()) all
        else all.filter { r -> patterns.any { searchable(r).contains(it, ignoreCase = true) } }
        return filtered
            .map { r -> r.copy(assets = r.assets.filter { a -> DRIVER_ASSET_EXTS.any { a.name.endsWith(it, true) } }) }
            .filter { it.assets.isNotEmpty() }
    }

    private fun componentSources(): List<ComponentSource> {
        val arr = JSONObject(res("catalog.json")).optJSONArray("componentSources") ?: JSONArray()
        val out = ArrayList<ComponentSource>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ComponentSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    manifestUrl = o.optString("manifestUrl"),
                    description = o.optString("description"),
                    experimental = o.optBoolean("experimental"),
                    updatedAt = o.optLong("updatedAt")
                )
            )
        }
        return out
    }

    /** 每个组件源的真实 contents.json 解析成 type → 版本列表 */
    private fun componentCatalogs(): Map<String, Map<String, List<Component>>> {
        val out = LinkedHashMap<String, Map<String, List<Component>>>()
        for (s in componentSources()) {
            val body = try {
                res("comp_${s.id}.json")
            } catch (_: Exception) {
                continue // 本机没抓到该源的清单，跳过（页面会显示该源为空，属真实情况）
            }
            val arr = JSONArray(body)
            val byType = LinkedHashMap<String, MutableList<Component>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type")
                if (type.isBlank()) continue
                byType.getOrPut(type) { ArrayList() }.add(
                    Component(
                        type = type,
                        verName = o.optString("verName"),
                        verCode = o.optString("verCode"),
                        remoteUrl = o.optString("remoteUrl")
                    )
                )
            }
            out[s.id] = byType
        }
        return out
    }

    private fun emulatorEntries(limit: Int): List<EmulatorEntry> {
        val text = repoFile("emulator-registry.json").readText().trimStart()
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val root = JSONObject(text)
            val key = root.keys().asSequence().first { root.get(it) is JSONArray }
            root.getJSONArray(key)
        }
        return (0 until minOf(limit, arr.length())).map { i ->
            val o = arr.getJSONObject(i)
            EmulatorEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                category = o.optString("category"),
                section = o.optString("section"),
                platforms = o.optJSONArray("platforms")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                description = o.optString("description"),
                summary = o.optString("summary"),
                owner = o.optString("owner"),
                repo = o.optString("repo"),
                homepage = o.optString("homepage"),
                repoUrl = o.optString("repo_url"),
                official = o.optBoolean("official"),
                channel = o.optString("channel", "stable"),
                latestTag = o.optString("latest_tag"),
                latestApkUrl = o.optString("latest_apk_url"),
                latestApkName = o.optString("latest_apk_name"),
                latestApkSize = o.optLong("latest_apk_size"),
                pushedAt = o.optString("pushed_at"),
                stars = o.optLong("stars"),
                forks = o.optLong("forks"),
                watchers = o.optLong("watchers"),
                downloads = o.optLong("downloads_total").takeIf { it > 0 }
                    ?: o.optLong("downloads_latest"),
                latestDate = o.optString("latest_date"),
                latestBody = o.optString("latest_body"),
                // 截图里要看到真实图标：用仓库内的 assets 文件（file:// 绝对路径），
                // 而不是等 jsDelivr 回源——测试机没网会全退化成字母牌，看不出真实观感
                logoUrl = localIconOr(o.optString("id"), o.optString("logo_url")),
                note = o.optString("note")
            )
        }
    }

    // ── 出图 ────────────────────────────────────────────────────

    /** 截图目录：默认 build/screenshots，可用 -Dscreenshot.dir=... 覆盖（不写死绝对路径） */
    private fun save(name: String) {
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots")
        dir.mkdirs()
        val out = File(dir, File(name).name)
        compose.onRoot().captureRoboImage(filePath = out.absolutePath)
        println("已保存 ${out.absolutePath}")
    }

    /** 让 Compose 的异步副作用有机会跑完（IO 线程 + 主线程帧都要推进） */
    private fun settle(rounds: Int = 44) {
        repeat(rounds) {
            Thread.sleep(500)
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun p1Device() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DeviceScreen(deviceInfo = onePlus13(), isLoading = false)
                    }
                }
            }
        }
        settle(30)
        save("p1-device.png")
    }

    @Test
    fun p2Drivers() {
        val sources = turnipSources().filter {
            it.supportedSeries.isEmpty() || "8xx" in it.supportedSeries
        }
        val picked = sources.firstOrNull { it.id == "stevenmxz" } ?: sources.first()
        val releases = releasesFor(picked, "8xx")
        println("驱动源 ${sources.size} 个 / ${picked.name} 在 8xx 上命中版本 ${releases.size} 个")
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DriverScreen(
                            deviceInfo = onePlus13(),
                            isLoading = false,
                            turnipSourceId = picked.id,
                            turnipSources = sources,
                            turnipReleases = releases,
                            panvkSourceId = "",
                            panvkSources = emptyList(),
                            panvkReleases = emptyList(),
                            qualcommSourceId = "",
                            qualcommSources = emptyList(),
                            qualcommReleases = emptyList(),
                            xclipseSourceId = "",
                            xclipseSources = emptyList(),
                            xclipseReleases = emptyList(),
                            wrapperSourceId = "",
                            wrapperSources = emptyList(),
                            wrapperReleases = emptyList(),
                            wrapperItems = emptyList(),
                            onTurnipSourceChange = {},
                            onPanvkSourceChange = {},
                            onQualcommSourceChange = {},
                            onXclipseSourceChange = {},
                            onWrapperSourceChange = {},
                            onDownloadAsset = { _, _ -> }
                        )
                    }
                }
            }
        }
        settle(6)
        // 驱动大区默认折叠（一屏看全大区）：点开 Turnip 区块，截图才等于用户点进去看到的样子
        runCatching {
            compose.onNodeWithText("Turnip 驱动").performClick()
            compose.waitForIdle()
        }
        settle(2)
        save("p2-drivers.png")
    }

    /** Mali 设备：按设备匹配时 PanVK 分区才会出现（骁龙设备默认隐藏 Mali 专用驱动） */
    private fun pixel7() = DeviceInfo(
        androidVersion = "15 (SDK 35)",
        ram = "12 GB",
        gpuRenderer = "Mali-G710",
        adrenoSeries = "",
        gpuModel = "710",
        gpuVendor = GpuVendor.MALI,
        deviceBrand = "Google",
        deviceModel = "Pixel 7",
        socName = "Google Tensor G2"
    )

    /**
     * PanVK 分区：panDXVK 只发 .wcp 与 zip，验证这两类资产能进版本卡。
     *
     * 改之前白名单只有 .zip/.adpkg，.wcp 全被挡在卡片外——Mali 用户点开这个源
     * 看到的是「没取到版本列表」，实际 release 里躺着 41 个 .wcp。
     */
    @Test
    fun p2bPanvkFiles() {
        val panvkSrc = TurnipSource(
            id = "reg-pandxvk",
            name = "panDXVK",
            apiUrl = "https://api.github.com/repos/isygold/panDXVK/releases",
            description = "panDXVK：把 BC 纹理转成 ASTC，让 Mali 跑 D3D11/D3D12 游戏；发 .wcp 与 zip 两种包（Beta）",
            experimental = true,
            supportedSeries = emptySet(),
            requiredVendor = "mali",
            filters = mapOf("default" to listOf("pandxvk")),
            targetEmulator = "winlator",
            stars = 13L,
            updatedAt = 1_792_540_800_000L
        )
        val panvkRel = listOf(
            GithubRelease(
                tagName = "v01",
                name = "panDXVK v01",
                assets = listOf(
                    GithubAsset(
                        name = "pandxvk-v01.wcp",
                        downloadUrl = "https://github.com/isygold/panDXVK/releases/download/v01/pandxvk-v01.wcp",
                        sizeBytes = 9_646_080L,
                        digest = ""
                    ),
                    GithubAsset(
                        name = "merged.zip",
                        downloadUrl = "https://github.com/isygold/panDXVK/releases/download/v01/merged.zip",
                        sizeBytes = 10_171_187L,
                        digest = ""
                    )
                ),
                publishedAt = "2026-10-23T09:12:00Z",
                body = "v01 是兼容性优先的构建，附带稳定性修复。",
                prerelease = false
            )
        )
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DriverScreen(
                            deviceInfo = pixel7(),
                            isLoading = false,
                            turnipSourceId = "",
                            turnipSources = emptyList(),
                            turnipReleases = emptyList(),
                            panvkSourceId = panvkSrc.id,
                            panvkSources = listOf(panvkSrc),
                            panvkReleases = panvkRel,
                            qualcommSourceId = "",
                            qualcommSources = emptyList(),
                            qualcommReleases = emptyList(),
                            xclipseSourceId = "",
                            xclipseSources = emptyList(),
                            xclipseReleases = emptyList(),
                            wrapperSourceId = "",
                            wrapperSources = emptyList(),
                            wrapperReleases = emptyList(),
                            wrapperItems = emptyList(),
                            onTurnipSourceChange = {},
                            onPanvkSourceChange = {},
                            onQualcommSourceChange = {},
                            onXclipseSourceChange = {},
                            onWrapperSourceChange = {},
                            onDownloadAsset = { _, _ -> }
                        )
                    }
                }
            }
        }
        settle(6)
        runCatching {
            compose.onNodeWithText("PanVK 驱动专区").performClick()
            compose.waitForIdle()
        }
        settle(2)
        save("p2b-panvk-wcp.png")
        compose.onNodeWithText("pandxvk-v01.wcp", substring = true).assertExists()
    }

    /**
     * 高通分区的折叠头：来源名 + 距上次发布多久。
     *
     * 改之前只有 Turnip 分区的折叠头带时间，高通分区只有一个来源名，
     * 用户要判断「这个源还活着吗」必须点开来源选择器。
     */
    @Test
    fun p2cQualcommCollapsedHint() {
        val qcSrc = QualcommSource(
            id = "k11mch1-qualcomm",
            name = "K11MCH1",
            apiUrl = "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases",
            description = "Skyline / Strato / Vita3K / Yuzu 通用的 Adreno 驱动集，A6xx–A7xx 型号覆盖最广",
            experimental = false,
            filters = listOf("Qualcomm"),
            stars = 5163L,
            updatedAt = 1_792_540_800_000L
        )
        val qcRel = listOf(
            GithubRelease(
                tagName = "v840",
                name = "Qualcomm drivers v840",
                assets = listOf(
                    GithubAsset(
                        name = "Qualcomm_v840.zip",
                        downloadUrl = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v840/Qualcomm_v840.zip",
                        sizeBytes = 8_388_608L,
                        digest = ""
                    )
                ),
                publishedAt = "2026-10-28T13:54:43Z",
                body = "Qualcomm drivers v840 extracted from Meta Ray-Ban Glasses.",
                prerelease = false
            )
        )
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DriverScreen(
                            deviceInfo = onePlus13(),
                            isLoading = false,
                            turnipSourceId = "",
                            turnipSources = emptyList(),
                            turnipReleases = emptyList(),
                            panvkSourceId = "",
                            panvkSources = emptyList(),
                            panvkReleases = emptyList(),
                            qualcommSourceId = qcSrc.id,
                            qualcommSources = listOf(qcSrc),
                            qualcommReleases = qcRel,
                            xclipseSourceId = "",
                            xclipseSources = emptyList(),
                            xclipseReleases = emptyList(),
                            wrapperSourceId = "",
                            wrapperSources = emptyList(),
                            wrapperReleases = emptyList(),
                            wrapperItems = emptyList(),
                            onTurnipSourceChange = {},
                            onPanvkSourceChange = {},
                            onQualcommSourceChange = {},
                            onXclipseSourceChange = {},
                            onWrapperSourceChange = {},
                            onDownloadAsset = { _, _ -> }
                        )
                    }
                }
            }
        }
        settle(6)
        save("p2c-qualcomm-collapsed.png")
        // 折叠态就能看到「· N 天前」，不用展开
        compose.onNodeWithText("天前", substring = true).assertExists()
    }

    @Test
    fun p3Components() {
        val sources = componentSources()
        val catalogs = componentCatalogs()
        println("组件源 ${sources.size} 个，有清单的 ${catalogs.size} 个")
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        ComponentScreen(
                            componentSources = sources,
                            componentCatalogs = catalogs,
                            onDownloadComponent = {}
                        )
                    }
                }
            }
        }
        settle(4)
        save("p3-components.png")
    }

    @Test
    fun p4Emulators() {
        val entries = emulatorEntries(24)
        println("模拟器条目 ${entries.size} 条")
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        EmulatorScreen(entries = entries, onDownload = {})
                    }
                }
            }
        }
        settle(4)
        save("p4-emulators.png")
    }

    @Test
    fun p5Runtime() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        RuntimeLibraryScreen(onDownload = {})
                    }
                }
            }
        }
        settle(30) // 等远程清单（超时会自动回退微软官方直链）
        save("p5-runtime.png")
    }

    @Test
    fun p6Downloads() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        DownloadsScreen(onBack = {}, showBack = true)
                    }
                }
            }
        }
        settle(10)
        save("p6-downloads.png")
    }

    @Test
    fun p7Settings() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        SettingsScreen(
                            onBack = {},
                            themeMode = ThemeMode.SYSTEM,
                            colorTheme = ColorTheme.EMUHUB,
                            onThemeModeChange = {},
                            onColorThemeChange = {},
                            appLanguage = AppLanguage.CHINESE,
                            onAppLanguageChange = {},
                            uiScale = UiScale.STANDARD,
                            onUiScaleChange = {},
                            onSourceCatalogChanged = {}
                        )
                    }
                }
            }
        }
        settle(2)
        save("p7-settings.png")
    }

    /** 设置页整页：首屏之下还有配色主题 / 存储 / 缓存 / 关于，需要长视口才看得全 */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1900dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun p7bSettingsTall() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        SettingsScreen(
                            onBack = {},
                            themeMode = ThemeMode.SYSTEM,
                            colorTheme = ColorTheme.EMUHUB,
                            onThemeModeChange = {},
                            onColorThemeChange = {},
                            appLanguage = AppLanguage.CHINESE,
                            onAppLanguageChange = {},
                            uiScale = UiScale.STANDARD,
                            onUiScaleChange = {},
                            onSourceCatalogChanged = {}
                        )
                    }
                }
            }
        }
        settle(2)
        save("p7b-settings-tall.png")
    }

    /** 组件页整页：首屏被「来源级捆绑包」占满，用长图看真正的版本列表长什么样 */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1900dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun p3bComponentsTall() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        ComponentScreen(
                            componentSources = componentSources(),
                            componentCatalogs = componentCatalogs(),
                            onDownloadComponent = {}
                        )
                    }
                }
            }
        }
        settle(4)
        save("p3b-components-tall.png")
    }

    /** 指南页：验证顶部不再有大图标卡，首个主题卡能否落在首屏 */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun p8Guide() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        ComponentGuideScreen(
                            onBack = {},
                            adrenoSeries = "740",
                            deviceInfo = onePlus13(),
                        )
                    }
                }
            }
        }
        settle(6)
        save("p8-guide.png")
    }

    /** 指南页展开态：确认展开后 chip 行与四个说明块完整显示，不被裁切 */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1900dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun p8bGuideExpanded() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        ComponentGuideScreen(
                            onBack = {},
                            adrenoSeries = "740",
                            initialTopicId = "wine",
                            deviceInfo = onePlus13(),
                        )
                    }
                }
            }
        }
        settle(6)
        save("p8b-guide-expanded.png")
    }

    /** 驱动指南：同一函数走 driverMode 分支，确认主题过滤与顺序都没错 */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-560dpi", shadows = [ShadowNativeVulkanBridge::class])
    fun p8cDriverGuide() {
        compose.setContent {
            ProvideAppLanguage(AppLanguage.CHINESE) {
                EmuBoxTheme(themeMode = ThemeMode.LIGHT, colorTheme = ColorTheme.EMUHUB) {
                    Surface(Modifier.fillMaxSize()) {
                        ComponentGuideScreen(
                            onBack = {},
                            adrenoSeries = "830",
                            driverMode = true,
                            deviceInfo = onePlus13(),
                        )
                    }
                }
            }
        }
        settle(6)
        save("p8c-driver-guide.png")
    }
}
