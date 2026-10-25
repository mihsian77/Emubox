package com.emubox.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.emubox.app.ui.theme.EmuBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen {
    DEVICE,
    DRIVERS,
    COMPONENTS,
    EMULATORS,
    RUNTIME,
    DOWNLOADS,
    SETTINGS,
    GUIDE
}

private val AppScreen.labelRes: Int
    get() = when (this) {
        AppScreen.DEVICE -> R.string.tab_device
        AppScreen.DRIVERS -> R.string.tab_driver
        AppScreen.COMPONENTS -> R.string.tab_component
        AppScreen.EMULATORS -> R.string.tab_emulator
        AppScreen.RUNTIME -> R.string.tab_runtime
        AppScreen.DOWNLOADS -> R.string.tab_download
        AppScreen.SETTINGS -> R.string.tab_settings
        AppScreen.GUIDE -> R.string.tab_driver
    }

class MainActivity : ComponentActivity() {

    /** 授权返回后已补扫标记：避免每次 onResume 重复扫描 */
    private var resumedScanDone = false

    /** 从系统授权页返回后补扫：启动时扫描早于用户完成授权（异步弹窗），
     *  授权落地前扫不到本地文件 → 返回时按新权限重扫一次，覆盖
     *  "必须打开下载区才能读取本地文件" 的体验问题。 */
    override fun onResume() {
        super.onResume()
        if (resumedScanDone) return
        val granted = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) ||
            SettingsManager.getDownloadFolderUri() != null ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (granted) {
            resumedScanDone = true
            lifecycleScope.launch(Dispatchers.IO) {
                DownloadsManager.scanExistingFiles(applicationContext)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DownloadsManager.init(applicationContext)
        SettingsManager.init(applicationContext)

        // 自动授权读取本地文件（用户要求"真正自动授权"，非进入下载区才弹）：
        // Android 11+ 每次启动检查——未授予文件管理权限且未用 SAF 指定目录时，
        // 自动跳系统授权页；授权一次后 App 永久直读 Download/EmuBox 目录，
        // 下载、扫描、删除全部走 File 直读，根治 MediaStore 索引扫不出/删除无效。
        // 已通过 SAF 指定目录（folderUri 非空）时跳过：SAF 授权已覆盖直读。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager() &&
            SettingsManager.getDownloadFolderUri() == null
        ) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            SettingsManager.getDownloadFolderUri() == null
        ) {
            // Android 10 以下：运行时存储权限（自动请求，覆盖"必须进下载区"）
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }

        // 扫描下载目录中磁盘已有、但记录缺失的文件（卸载重装 / 外部放入），
        // 并入下载库后旧文件可见可删，避免重复下载生成 (1)(2) 后缀
        lifecycleScope.launch(Dispatchers.IO) {
            DownloadsManager.scanExistingFiles(applicationContext)
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }

        setContent {
            var themeMode by remember { mutableStateOf(SettingsManager.getThemeMode()) }
            var colorTheme by remember { mutableStateOf(SettingsManager.getColorTheme()) }
            var appLanguage by remember { mutableStateOf(SettingsManager.getAppLanguage()) }
            var uiScale by remember { mutableStateOf(SettingsManager.getUiScale()) }

            ProvideAppLanguage(appLanguage) {
                EmuBoxTheme(themeMode = themeMode, colorTheme = colorTheme) {
                // 全局界面大小：density 缩放（字体/间距/图标一体），档位由设置页控制
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density * uiScale.scale,
                        fontScale = baseDensity.fontScale
                    )
                ) {
                val downloadScope = rememberCoroutineScope()
                val appContext = applicationContext

                var currentScreen by remember { mutableStateOf(AppScreen.DEVICE) }
                var globalSearchOpen by remember { mutableStateOf(false) }
                // 设备页推荐卡 → 驱动专区文件级跳转：待展开的驱动仓库 id（registry entry id）
                var pendingDriverRepo by remember { mutableStateOf<String?>(null) }
                var guideReturnTab by remember { mutableStateOf(AppScreen.DEVICE) }
                var guideTopic by remember { mutableStateOf<String?>(null) }
                var guideDriverMode by remember { mutableStateOf(false) }
                var refreshTrigger by remember { mutableIntStateOf(0) }
                var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
                var cachedDeviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
                var isLoading by remember { mutableStateOf(true) }
                var fullyLoaded by remember { mutableStateOf(false) }

                var sourceCatalog by remember { mutableStateOf(SourceCatalogRepository.builtInCatalog()) }
                var registryEntries by remember {
                    mutableStateOf<List<DriverRegistryEntry>>(emptyList())
                }
                var turnipSourceId by remember { mutableStateOf(SettingsManager.getTurnipSource()) }
                var panvkSourceId by remember { mutableStateOf(SettingsManager.getPanvkSource()) }
                var qualcommSourceId by remember { mutableStateOf(SettingsManager.getQualcommSource()) }
                var xclipseSourceId by remember { mutableStateOf(SettingsManager.getXclipseSource()) }
                var wrapperSourceId by remember { mutableStateOf(SettingsManager.getWrapperSource()) }

                var turnipReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var panvkReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var qualcommReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var xclipseReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var wrapperReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var wrapperItems by remember { mutableStateOf<List<WrapperItem>>(emptyList()) }
                var componentCatalogs by remember {
                    mutableStateOf<Map<String, Map<String, List<Component>>>>(emptyMap())
                }
                var componentSourceUpdatedAt by remember {
                    mutableStateOf<Map<String, Long>>(emptyMap())
                }
                var driverSourceUpdatedAt by remember {
                    mutableStateOf<Map<String, Long>>(emptyMap())
                }
                var driverSourceStars by remember {
                    mutableStateOf<Map<String, Long>>(emptyMap())
                }
                var emulatorEntries by remember {
                    mutableStateOf(EmulatorRegistryRepository.builtInRegistry())
                }
                var emulatorInsights by remember {
                    mutableStateOf(EmulatorInsights())
                }
                val emulatorAppContext = LocalContext.current.applicationContext

                val activeCount by remember { derivedStateOf { DownloadsManager.activeDownloads.size } }

                // 模拟器清单远程刷新：官方 registry（版本/APK）+ 社区排行（下载量/日志），
                // 磁盘缓存优先（秒开），后台刷新有更新才覆盖；失败保留缓存/内置
                LaunchedEffect(Unit) {
                    val registry = EmulatorRegistryRepository.fetchRemoteRegistry(emulatorAppContext)
                    val ranking = EmulatorRegistryRepository.fetchRemoteRanking(emulatorAppContext)
                    emulatorInsights = EmulatorInsightsRepository.fetchRemoteInsights()
                    // 合并：registry 数据优先（官方 APK 直链），ranking 补充下载量/日志
                    val merged = registry.map { base ->
                        val r = ranking.firstOrNull { it.id == base.id || it.repo == base.repo }
                        if (r != null) {
                            base.copy(
                                downloads = if (base.downloads == 0L) r.downloads else base.downloads,
                                latestDate = if (base.latestDate.isBlank()) r.latestDate else base.latestDate,
                                latestBody = if (base.latestBody.isBlank()) r.latestBody else base.latestBody,
                                stars = if (base.stars == 0L) r.stars else base.stars,
                                forks = if (base.forks == 0L) r.forks else base.forks,
                                watchers = if (base.watchers == 0L) r.watchers else base.watchers
                            )
                        } else base
                    } + ranking.filter { r ->
                        registry.none { it.id == r.id || it.repo == r.repo }
                    }
                    // 内置保底：远程 registry 覆盖面小于内置时，内置独有条目不被丢弃
                    // （此前远程 registry 仅 13 条，内置 Lemon/Eden 等独有条目会被覆盖丢失）
                    val mergedWithBuiltin = merged + emulatorEntries.filter { base ->
                        merged.none { it.id == base.id || it.repo == base.repo }
                    }
                    emulatorEntries = mergedWithBuiltin
                }

                // 慢下载推荐弹窗
                var showSlowDownloadDialog by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    SlowDownloadDetector.suggestAccelerator.collect {
                        showSlowDownloadDialog = true
                    }
                }

                // 完整加载：仅刷新/首次触发。源列表 + 注册表匹配 + 组件目录 + 初始 releases。
                // 切换驱动源只走下方快速路径（只重拉选中源 releases），避免重跑 EGL 采集和组件清单。
                LaunchedEffect(refreshTrigger) {
                    isLoading = true

                    val result = withContext(Dispatchers.IO) {
                        // EGL 采集较慢且结果不变，缓存；刷新时才重新采集
                        val info = cachedDeviceInfo ?: DeviceInfo.collect(this@MainActivity).also { cachedDeviceInfo = it }
                        val matchByDevice = SettingsManager.getMatchDriversByDevice()

                        // 源目录与驱动注册表并行加载（互不依赖，串行会多等一个超时周期）
                        val (baseCatalog, loadedRegistry) = coroutineScope {
                            val c = async { SourceCatalogRepository.load() }
                            val r = async { DriverRegistryRepository.load(context = this@MainActivity) }
                            c.await() to r.await()
                        }
                        registryEntries = loadedRegistry

                        // 驱动注册表：按设备 GPU 匹配（含 Mali PanVK 全系列），转换为
                        // TurnipSource 后与内置源合并；注册表推荐源排前，按 apiUrl 去重。
                        // 关闭"按设备匹配"时显示全部驱动源（不按 GPU 过滤）。
                        // wrapper 驱动包装器独立抽出：不参与 GPU 厂商硬过滤，始终可见。
                        val driverEntries = loadedRegistry.filter {
                            it.driverType !in DriverRegistryRepository.COMPONENT_TYPES &&
                                it.driverType != "wrapper"
                        }
                        val wrapperEntries = loadedRegistry.filter { it.driverType == "wrapper" }
                        // 全部注册表驱动源始终可见（本设备匹配的排前），不再按 GPU 厂商
                        // 硬排除——Adreno 设备也能看到 Mali PanVK 源，只是排在推荐之后。
                        // 修复：MineGraph PanVK（Mali）等新源此前被 match 厂商过滤掉。
                        val matchedDriverSources = (if (matchByDevice) {
                            val matched = DriverRegistryRepository.match(info, loadedRegistry)
                            val matchedIds = matched.map { it.entry.id }.toSet()
                            matched + driverEntries.filter { it.id !in matchedIds }
                                .map { DriverMatchResult(it, 0, false, "") }
                        } else {
                            driverEntries.map { DriverMatchResult(it, 0, false, "") }
                        }).map { DriverRegistryRepository.toTurnipSource(it.entry) }
                        val existingDriverUrls = baseCatalog.turnipSources.map { it.apiUrl }.toSet()
                        val newDriverSources = matchedDriverSources.filter { it.apiUrl !in existingDriverUrls }

                        // 驱动包装器源：始终全量可见；开启设备匹配时按 本设备厂商→通用→其他 排序
                        val wrapperVendorKey = info?.gpuVendor?.name?.lowercase()
                        val wrapperSources = wrapperEntries
                            .map { DriverRegistryRepository.toTurnipSource(it) }
                            .sortedBy { source ->
                                when {
                                    source.requiredVendor == "any" -> 1
                                    wrapperVendorKey != null && source.requiredVendor == wrapperVendorKey -> 0
                                    else -> 2
                                }
                            }

                        // 设备专属组件（如 Mali 的 panDXVK）：GitHub release 直接分发 .wcp，
                        // 不走 contents.json，单独拉取后并入对应组件分类。
                        // 修复：注册表组件对全部设备可见，本设备匹配的排前——之前按 GPU
                        // 厂商过滤导致 Adreno 用户看不到 Mali 纹理转码 DXVK（panDXVK）。
                        val deviceComponentEntries = if (matchByDevice) {
                            val matched = DriverRegistryRepository.matchComponents(info, loadedRegistry)
                            val matchedIds = matched.map { it.id }.toSet()
                            matched + loadedRegistry.filter { it.componentType != null && it.id !in matchedIds }
                        } else {
                            loadedRegistry.filter { it.componentType != null }
                        }
                        // 同一 componentType 的注册表仓库合并为一个源：pandxvk 与
                        // pandxvk-lloyd262 都是 DXVK 组件源，分开会导致组件 Tab 里
                        // 每个类型只显示当前选中源（用户只看到其中一个）。
                        val registryByType = deviceComponentEntries.groupBy { it.componentType ?: "registry-${it.id}" }
                        val registryComponentSources = registryByType.map { (type, entries) ->
                            ComponentSource(
                                id = "registry-${type}-${entries.first().id}",
                                name = entries.joinToString(" / ") { it.name },
                                manifestUrl = entries.first().apiUrl,
                                description = entries.joinToString("；") { it.description },
                                experimental = entries.any { it.maturity in listOf("alpha", "experimental", "beta") },
                                updatedAt = entries.maxOf { it.updatedAt }
                            )
                        }

                        val catalog = baseCatalog.copy(
                            turnipSources = newDriverSources + baseCatalog.turnipSources,
                            componentSources = baseCatalog.componentSources + registryComponentSources
                        )

                        // 直接使用合并后的完整列表：注册表源已按设备匹配排序在前，
                        // 内置源在后。不再二次过滤（避免已匹配源被 series 过滤排除）。
                        val compatibleSources = catalog.turnipSources
                        // PanVK（Mali）独立专区：按 requiredVendor 拆出，不再混在 Turnip 区
                        val panvkSources = compatibleSources.filter { it.requiredVendor == "mali" }
                        // 三星 Xclipse 独立专区：Exynos GPU（RDNA）与 Adreno/Mali 均不兼容
                        val xclipseSources = compatibleSources.filter { it.requiredVendor == "xclipse" }
                        val turnipOnlySources = compatibleSources.filterNot {
                            it.requiredVendor in listOf("mali", "xclipse")
                        }

                        val selectedSource = turnipOnlySources.firstOrNull { source ->
                            source.id.equals(turnipSourceId, ignoreCase = true) ||
                                source.name.equals(turnipSourceId, ignoreCase = true)
                        } ?: turnipOnlySources.firstOrNull()
                            ?: catalog.turnipSources.first()

                        val releases = fetchTurnipReleases(selectedSource, info.adrenoSeries)

                        val selectedPanvkSource = panvkSources.firstOrNull { source ->
                            source.id.equals(panvkSourceId, ignoreCase = true) ||
                                source.name.equals(panvkSourceId, ignoreCase = true)
                        } ?: panvkSources.firstOrNull()
                        val panvk = selectedPanvkSource?.let { fetchTurnipReleases(it, info.adrenoSeries) }
                            ?: emptyList()

                        val selectedXclipseSource = xclipseSources.firstOrNull { source ->
                            source.id.equals(xclipseSourceId, ignoreCase = true) ||
                                source.name.equals(xclipseSourceId, ignoreCase = true)
                        } ?: xclipseSources.firstOrNull()
                        val xclipse = selectedXclipseSource?.let { fetchTurnipReleases(it, info.adrenoSeries) }
                            ?: emptyList()

                        val selectedWrapperSource = wrapperSources.firstOrNull { source ->
                            source.id.equals(wrapperSourceId, ignoreCase = true) ||
                                source.name.equals(wrapperSourceId, ignoreCase = true)
                        } ?: wrapperSources.firstOrNull()
                        // banner 来源：wrapper 内容来自作者维护的 wrappers.json 清单
                        // （不依赖 release 标签顺序，Rutabaga/linuxfs/steamdeck 等混装
                        // release 不会误读）；其余 wrapper 源为 release 型（strict 过滤）。
                        val isBannerWrapper = selectedWrapperSource?.id == "reg-wrapper-banner"
                        val wrapperItems = if (isBannerWrapper && selectedWrapperSource != null) {
                            fetchWrappersCatalog(
                                "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/wrappers.json"
                            )
                        } else emptyList()
                        // 清单拉取失败（网络）时回退 strict release（wrappers-v1 标签），
                        // 保证 wrapper 区不空；Rutabaga/linuxfs 等混装 release 仍被过滤。
                        val wrapper = if (isBannerWrapper && wrapperItems.isNotEmpty()) emptyList()
                            else selectedWrapperSource?.let { fetchTurnipReleases(it, info.adrenoSeries) }
                            ?: emptyList()

                        val selectedQualcommSource = catalog.qualcommSources.firstOrNull { source ->
                            source.id.equals(qualcommSourceId, ignoreCase = true) ||
                                source.name.equals(qualcommSourceId, ignoreCase = true)
                        } ?: catalog.qualcommSources.first()
                        val qualcomm = fetchQualcommReleases(selectedQualcommSource)

                        // 内置清单组件（stevenmxz-wcp 拉取全量清单后合并 Contents-Cmod 历史稳定版）
                        val manifestComponentMaps = coroutineScope {
                            baseCatalog.componentSources.map { source ->
                                async {
                                    when (source.id) {
                                        "stevenmxz-wcp" -> {
                                            val base = fetchComponentsFromUrl(source.manifestUrl)
                                            source.id to mergeCmodHistorical(base)
                                        }
                                        "banner-components" -> {
                                            // banner 社区源：contents.json Turnip 分类 + 补 The412Banner
                                            // rutabaga-drivers-v1 release 全部 zip（App 只读 contents.json
                                            // 会漏掉该独立 release，用户实测 Rutabaga 卡缺失）
                                            val base = fetchComponentsFromUrl(source.manifestUrl)
                                            val rutabagaExtra = runCatching {
                                                fetchGithubReleasesFromUrl(
                                                    "https://api.github.com/repos/The412Banner/winlator-contents/releases"
                                                ).filter { it.tagName.contains("rutabaga", true) }
                                                    .flatMap { r ->
                                                        r.assets.map { a ->
                                                            Component(
                                                                type = "Turnip",
                                                                verName = r.tagName,
                                                                verCode = "",
                                                                remoteUrl = a.downloadUrl,
                                                                sizeBytes = a.sizeBytes.takeIf { it > 0 }
                                                            )
                                                        }
                                                    }
                                            }.getOrDefault(emptyList())
                                            if (rutabagaExtra.isEmpty()) {
                                                source.id to base
                                            } else {
                                                val merged = base.toMutableMap()
                                                merged["Turnip"] = merged["Turnip"].orEmpty() + rutabagaExtra
                                                source.id to merged
                                            }
                                        }
                                        else -> source.id to fetchComponentsFromUrl(source.manifestUrl)
                                    }
                                }
                            }.awaitAll().toMap()
                        }
                        // 注册表设备专属组件（同类型多仓库已合并为一个源，组件列表 flatMap 合并）
                        val registryComponentMaps = coroutineScope {
                            registryByType.map { (type, entries) ->
                                async {
                                    val srcId = "registry-${type}-${entries.first().id}"
                                    srcId to mapOf(
                                        type to entries.flatMap { e ->
                                            runCatching { fetchGithubComponents(e.apiUrl, type) }
                                                .getOrDefault(emptyList())
                                        }
                                    )
                                }
                            }.awaitAll().toMap()
                        }
                        // 驱动/组件源更新时间与星标：全部改用 catalog/registry 内置值
                        // （updatedAt/stars 由 CI 每日刷新，raw 拉取不消耗 GitHub API 配额）。
                        // 不再运行时直连 api.github.com 拉 repo 元数据——国内网络直连 API
                        // 易超时/限流，是来源弹窗"更新于 X 天前"不显示的根因。
                        val driverSourceUpdatedAt = emptyMap<String, Long>()
                        val driverSourceStars = emptyMap<String, Long>()

                        AppLoadResult(
                            deviceInfo = info,
                            catalog = catalog,
                            componentSourceUpdatedAt = emptyMap(),
                            driverSourceUpdatedAt = driverSourceUpdatedAt,
                            driverSourceStars = driverSourceStars,
                            selectedTurnipSource = selectedSource,
                            selectedPanvkSource = selectedPanvkSource,
                            selectedQualcommSource = selectedQualcommSource,
                            selectedXclipseSource = selectedXclipseSource,
                            selectedWrapperSource = selectedWrapperSource,
                            turnipReleases = releases,
                            panvkReleases = panvk,
                            qualcommReleases = qualcomm,
                            xclipseReleases = xclipse,
                            wrapperReleases = wrapper,
                            wrapperItems = wrapperItems,
                            componentCatalogs = manifestComponentMaps + registryComponentMaps
                        )
                    }

                    deviceInfo = result.deviceInfo
                    sourceCatalog = result.catalog
                    turnipReleases = result.turnipReleases
                    panvkReleases = result.panvkReleases
                    qualcommReleases = result.qualcommReleases
                    xclipseReleases = result.xclipseReleases
                    wrapperReleases = result.wrapperReleases
                    wrapperItems = result.wrapperItems
                    componentCatalogs = result.componentCatalogs
                    componentSourceUpdatedAt = result.componentSourceUpdatedAt
                    driverSourceUpdatedAt = result.driverSourceUpdatedAt
                    driverSourceStars = result.driverSourceStars

                    if (turnipSourceId != result.selectedTurnipSource.id) {
                        turnipSourceId = result.selectedTurnipSource.id
                        SettingsManager.setTurnipSource(result.selectedTurnipSource.id)
                    }
                    if (result.selectedPanvkSource != null && panvkSourceId != result.selectedPanvkSource.id) {
                        panvkSourceId = result.selectedPanvkSource.id
                        SettingsManager.setPanvkSource(result.selectedPanvkSource.id)
                    }
                    if (qualcommSourceId != result.selectedQualcommSource.id) {
                        qualcommSourceId = result.selectedQualcommSource.id
                        SettingsManager.setQualcommSource(result.selectedQualcommSource.id)
                    }
                    if (result.selectedXclipseSource != null && xclipseSourceId != result.selectedXclipseSource.id) {
                        xclipseSourceId = result.selectedXclipseSource.id
                        SettingsManager.setXclipseSource(result.selectedXclipseSource.id)
                    }
                    if (result.selectedWrapperSource != null && wrapperSourceId != result.selectedWrapperSource.id) {
                        wrapperSourceId = result.selectedWrapperSource.id
                        SettingsManager.setWrapperSource(result.selectedWrapperSource.id)
                    }

                    isLoading = false
                    fullyLoaded = true
                }

                // 快速路径：切换驱动源 → 只重拉该源的 releases（不重跑 EGL/目录）
                LaunchedEffect(turnipSourceId) {
                    if (!fullyLoaded || sourceCatalog.turnipSources.isEmpty()) return@LaunchedEffect
                    val selected = sourceCatalog.turnipSources.firstOrNull { source ->
                        source.id == turnipSourceId || source.name == turnipSourceId
                    } ?: sourceCatalog.turnipSources.first()
                    turnipReleases = withContext(Dispatchers.IO) {
                        fetchTurnipReleases(selected, deviceInfo?.adrenoSeries ?: "unknown")
                    }
                }

                // 快速路径：切换 PanVK 源 → 只重拉该源 releases
                LaunchedEffect(panvkSourceId) {
                    if (!fullyLoaded) return@LaunchedEffect
                    val panvkSources = sourceCatalog.turnipSources.filter { it.requiredVendor == "mali" }
                    if (panvkSources.isEmpty()) return@LaunchedEffect
                    val selected = panvkSources.firstOrNull { source ->
                        source.id == panvkSourceId || source.name == panvkSourceId
                    } ?: panvkSources.first()
                    panvkReleases = withContext(Dispatchers.IO) {
                        fetchTurnipReleases(selected, deviceInfo?.adrenoSeries ?: "unknown")
                    }
                }

                // 快速路径：切换 Qualcomm 源 → 只重拉该源 releases
                LaunchedEffect(qualcommSourceId) {
                    if (!fullyLoaded || sourceCatalog.qualcommSources.isEmpty()) return@LaunchedEffect
                    val selected = sourceCatalog.qualcommSources.firstOrNull { source ->
                        source.id == qualcommSourceId || source.name == qualcommSourceId
                    } ?: sourceCatalog.qualcommSources.first()
                    qualcommReleases = withContext(Dispatchers.IO) {
                        fetchQualcommReleases(selected)
                    }
                }

                // 快速路径：切换 Xclipse 源 → 只重拉该源 releases
                LaunchedEffect(xclipseSourceId) {
                    if (!fullyLoaded) return@LaunchedEffect
                    val xclipseSources = sourceCatalog.turnipSources.filter { it.requiredVendor == "xclipse" }
                    if (xclipseSources.isEmpty()) return@LaunchedEffect
                    val selected = xclipseSources.firstOrNull { source ->
                        source.id == xclipseSourceId || source.name == xclipseSourceId
                    } ?: xclipseSources.first()
                    xclipseReleases = withContext(Dispatchers.IO) {
                        fetchTurnipReleases(selected, deviceInfo?.adrenoSeries ?: "unknown")
                    }
                }

                // 快速路径：切换 Wrapper 源 → 只重拉该源 releases
                LaunchedEffect(wrapperSourceId) {
                    if (!fullyLoaded || registryEntries.isEmpty()) return@LaunchedEffect
                    val wrapperSources = registryEntries
                        .filter { it.driverType == "wrapper" }
                        .map { DriverRegistryRepository.toTurnipSource(it) }
                    if (wrapperSources.isEmpty()) return@LaunchedEffect
                    val selected = wrapperSources.firstOrNull { source ->
                        source.id == wrapperSourceId || source.name == wrapperSourceId
                    } ?: wrapperSources.first()
                    if (selected.id == "reg-wrapper-banner") {
                        wrapperItems = withContext(Dispatchers.IO) {
                            fetchWrappersCatalog(
                                "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/wrappers.json"
                            )
                        }
                        // 清单拉取失败时回退 strict release，避免 wrapper 区空白
                        wrapperReleases = if (wrapperItems.isEmpty()) {
                            withContext(Dispatchers.IO) {
                                fetchTurnipReleases(selected, deviceInfo?.adrenoSeries ?: "unknown")
                            }
                        } else emptyList()
                    } else {
                        wrapperItems = emptyList()
                        wrapperReleases = withContext(Dispatchers.IO) {
                            fetchTurnipReleases(selected, deviceInfo?.adrenoSeries ?: "unknown")
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 进入指南页：记录返回 Tab（驱动专区按钮进驱动指南，其余进组件指南）
                    fun openGuide(topic: String?, driverMode: Boolean = false) {
                        guideTopic = topic
                        guideDriverMode = driverMode
                        guideReturnTab = currentScreen
                        currentScreen = AppScreen.GUIDE
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            // 模板 A「璃光暗夜」：玻璃拟态导航 + 青紫渐变发光指示
                            Box {
                                NavigationBar(
                                    containerColor = Color(0xE6161423)
                                ) {
                                    val navItems = listOf(
                                        AppScreen.DEVICE to Icons.Default.PhoneAndroid,
                                        AppScreen.DRIVERS to Icons.Default.Memory,
                                        AppScreen.COMPONENTS to Icons.Default.Extension,
                                        AppScreen.EMULATORS to Icons.Default.Android,
                                        AppScreen.RUNTIME to Icons.Default.Apps,
                                        AppScreen.DOWNLOADS to Icons.Default.Download,
                                        AppScreen.SETTINGS to Icons.Default.Settings
                                    )
                                    navItems.forEach { (screen, icon) ->
                                        NavigationBarItem(
                                            selected = currentScreen == screen,
                                            onClick = { currentScreen = screen },
                                            icon = {
                                                if (screen == AppScreen.DOWNLOADS) {
                                                    BadgedBox(
                                                        badge = {
                                                            if (activeCount > 0) Badge { Text(activeCount.toString()) }
                                                        }
                                                    ) { Icon(icon, contentDescription = null) }
                                                } else {
                                                    Icon(icon, contentDescription = null)
                                                }
                                            },
                                            label = { Text(appString(screen.labelRes)) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF3FD9C4),
                                                selectedTextColor = Color(0xFF9B7CFF),
                                                unselectedIconColor = Color(0xFF8B86AC),
                                                unselectedTextColor = Color(0xFF756F95),
                                                indicatorColor = Color(0x333FD9C4),
                                                disabledIconColor = Color(0xFF756F95)
                                            )
                                        )
                                    }
                                }
                                // 顶部渐变发光分割线
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                listOf(
                                                    Color(0x003FD9C4),
                                                    Color(0x998B5CF6),
                                                    Color(0x003FD9C4)
                                                )
                                            )
                                        )
                                )
                            }
                        }
                    ) { innerPadding ->
                        AnimatedContent(
                            targetState = currentScreen,
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                            label = "main-navigation",
                            transitionSpec = {
                                val enterSpec = tween<Float>(durationMillis = 200)
                                val exitSpec = tween<Float>(durationMillis = 150)
                                fadeIn(enterSpec) togetherWith fadeOut(exitSpec)
                            }
                        ) { screen ->
                            when (screen) {
                                AppScreen.DEVICE -> {
                                    Scaffold(
                                        topBar = {
                                            TopAppBar(
                                                title = { Text(appString(R.string.device_info_title)) },
                                                actions = {
                                                    IconButton(onClick = { globalSearchOpen = true }) {
                                                        Icon(Icons.Default.Search, contentDescription = appString(R.string.global_search_hint))
                                                    }
                                                    var isRefreshingLocal by remember { mutableStateOf(false) }
                                                    val scope = rememberCoroutineScope()
                                                    IconButton(
                                                        onClick = {
                                                            if (!isRefreshingLocal) {
                                                                scope.launch {
                                                                    isRefreshingLocal = true
                                                                    refreshTrigger++
                                                                    delay(600)
                                                                    isRefreshingLocal = false
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        if (isRefreshingLocal) {
                                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    ) { padding ->
                                        DeviceScreen(
                                            modifier = Modifier.padding(padding),
                                            deviceInfo = deviceInfo,
                                            isLoading = isLoading,
                                            onNavigateToDrivers = { currentScreen = AppScreen.DRIVERS },
                                            onOpenDriverFiles = { repoId ->
                                                pendingDriverRepo = repoId
                                                currentScreen = AppScreen.DRIVERS
                                            }
                                        )
                                    }
                                    if (globalSearchOpen) {
                                        GlobalSearchSheet(
                                            emulators = emulatorEntries,
                                            drivers = DriverRegistryRepository.builtinEntries(),
                                            componentNames = buildList {
                                                sourceCatalog.turnipSources.forEach { add(it.name) }
                                                sourceCatalog.qualcommSources.forEach { add(it.name) }
                                                sourceCatalog.componentSources.forEach { add(it.name) }
                                            },
                                            downloads = DownloadsManager.completedDownloads,
                                            onNavigate = { target ->
                                                globalSearchOpen = false
                                                when (target) {
                                                    "emulators" -> currentScreen = AppScreen.EMULATORS
                                                    "drivers" -> currentScreen = AppScreen.DRIVERS
                                                    "components" -> currentScreen = AppScreen.COMPONENTS
                                                    "runtime" -> currentScreen = AppScreen.RUNTIME
                                                    "downloads" -> currentScreen = AppScreen.DOWNLOADS
                                                    else -> {
                                                        // 全局搜索驱动结果：携带注册表条目 id → 切换对应驱动源并加载其版本
                                                        if (target.startsWith("drivers:")) {
                                                            currentScreen = AppScreen.DRIVERS
                                                            val srcId = "reg-${target.removePrefix("drivers:")}"
                                                            turnipSourceId = srcId
                                                            SettingsManager.setTurnipSource(srcId)
                                                        }
                                                    }
                                                }
                                            },
                                            onDismiss = { globalSearchOpen = false }
                                        )
                                    }
                                }

                                AppScreen.DRIVERS -> {
                                    Scaffold(
                                        topBar = {
                                            TopAppBar(
                                                title = { Text(appString(R.string.driver_region)) },
                                                actions = {
                                                    var isRefreshingLocal by remember { mutableStateOf(false) }
                                                    val scope = rememberCoroutineScope()
                                                    IconButton(
                                                        onClick = {
                                                            if (!isRefreshingLocal) {
                                                                scope.launch {
                                                                    isRefreshingLocal = true
                                                                    refreshTrigger++
                                                                    delay(600)
                                                                    isRefreshingLocal = false
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        if (isRefreshingLocal) {
                                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                                                        }
                                                    }
                                                    IconButton(onClick = { openGuide(null, driverMode = true) }) {
                                                        Icon(Icons.Default.Info, contentDescription = appString(R.string.driver_guide))
                                                    }
                                                }
                                            )
                                        }
                                    ) { padding ->
                                        DriverScreen(
                                            modifier = Modifier.padding(padding),
                                            deviceInfo = deviceInfo,
                                            isLoading = isLoading,
                                            turnipSourceId = turnipSourceId,
                                            // 全量展示：注册表 39 源合并后不再二次过滤（厂商/系列过滤会
                                            // 把不匹配源隐藏，导致 8xx 设备 turnip 只剩 7 个、PanVK 被过滤空）。
                                            // 设备匹配只影响加载时的排序与推荐标签，不减少可见源数量。
                                            turnipSources = sourceCatalog.turnipSources.filterNot {
                                                it.requiredVendor in listOf("mali", "xclipse")
                                            },
                                            turnipReleases = turnipReleases,
                                            panvkSourceId = panvkSourceId,
                                            panvkSources = sourceCatalog.turnipSources.filter { it.requiredVendor == "mali" },
                                            panvkReleases = panvkReleases,
                                            qualcommSourceId = qualcommSourceId,
                                            qualcommSources = sourceCatalog.qualcommSources,
                                            qualcommReleases = qualcommReleases,
                                            xclipseSourceId = xclipseSourceId,
                                            xclipseSources = sourceCatalog.turnipSources.filter { it.requiredVendor == "xclipse" },
                                            xclipseReleases = xclipseReleases,
                                            wrapperSourceId = wrapperSourceId,
                                            wrapperSources = registryEntries
                                                .filter { it.driverType == "wrapper" }
                                                .map { DriverRegistryRepository.toTurnipSource(it) },
                                            wrapperReleases = wrapperReleases,
                                            wrapperItems = wrapperItems,
                                            sourceCatalogRemote = sourceCatalog.isRemote,
                                            onTurnipSourceChange = { sourceId ->
                                                turnipSourceId = sourceId
                                                SettingsManager.setTurnipSource(sourceId)
                                            },
                                            onPanvkSourceChange = { sourceId ->
                                                panvkSourceId = sourceId
                                                SettingsManager.setPanvkSource(sourceId)
                                            },
                                            onQualcommSourceChange = { sourceId ->
                                                qualcommSourceId = sourceId
                                                SettingsManager.setQualcommSource(sourceId)
                                            },
                                            onXclipseSourceChange = { sourceId ->
                                                xclipseSourceId = sourceId
                                                SettingsManager.setXclipseSource(sourceId)
                                            },
                                            onWrapperSourceChange = { sourceId ->
                                                wrapperSourceId = sourceId
                                                SettingsManager.setWrapperSource(sourceId)
                                            },
                                            onDownloadAsset = { release, asset ->
                                                downloadScope.launch {
                                                    downloadAsset(appContext, release, asset)
                                                }
                                            },
                                            driverSourceUpdatedAt = driverSourceUpdatedAt,
                                            driverSourceStars = driverSourceStars,
                                            pendingRepo = pendingDriverRepo,
                                            onPendingRepoConsumed = { pendingDriverRepo = null }
                                        )
                                    }
                                }

                                AppScreen.COMPONENTS -> {
                                    Scaffold(
                                        topBar = {
                                            TopAppBar(
                                                title = { Text(appString(R.string.component_region)) },
                                                actions = {
                                                    var isRefreshingLocal by remember { mutableStateOf(false) }
                                                    val scope = rememberCoroutineScope()
                                                    IconButton(
                                                        onClick = {
                                                            if (!isRefreshingLocal) {
                                                                scope.launch {
                                                                    isRefreshingLocal = true
                                                                    refreshTrigger++
                                                                    delay(600)
                                                                    isRefreshingLocal = false
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        if (isRefreshingLocal) {
                                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    ) { padding ->
                                        ComponentScreen(
                                            modifier = Modifier.padding(padding),
                                            componentSources = sourceCatalog.componentSources,
                                            componentCatalogs = componentCatalogs,
                                            componentSourceUpdatedAt = componentSourceUpdatedAt,
                                            onDownloadComponent = { component ->
                                                downloadScope.launch {
                                                    downloadComponent(appContext, component)
                                                }
                                            },
                                            onOpenGuide = { openGuide(null, driverMode = false) }
                                        )
                                    }
                                }

                                AppScreen.EMULATORS -> {
                                    Scaffold(
                                        topBar = {
                                            TopAppBar(
                                                title = { Text(appString(R.string.emulator_region)) },
                                                actions = {
                                                    var isRefreshingLocal by remember { mutableStateOf(false) }
                                                    val scope = rememberCoroutineScope()
                                                    IconButton(
                                                        onClick = {
                                                            if (!isRefreshingLocal) {
                                                                scope.launch {
                                                                    isRefreshingLocal = true
                                                                    val registry = EmulatorRegistryRepository.fetchRemoteRegistry(emulatorAppContext)
                                                                    val ranking = EmulatorRegistryRepository.fetchRemoteRanking(emulatorAppContext)
                                                                    emulatorInsights = EmulatorInsightsRepository.fetchRemoteInsights()
                                                                    val merged = registry.map { base ->
                                                                        val r = ranking.firstOrNull { it.id == base.id || it.repo == base.repo }
                                                                        if (r != null) {
                                                                            base.copy(
                                                                                downloads = if (base.downloads == 0L) r.downloads else base.downloads,
                                                                                latestDate = if (base.latestDate.isBlank()) r.latestDate else base.latestDate,
                                                                                latestBody = if (base.latestBody.isBlank()) r.latestBody else base.latestBody,
                                                                                stars = if (base.stars == 0L) r.stars else base.stars,
                                                                                forks = if (base.forks == 0L) r.forks else base.forks,
                                                                                watchers = if (base.watchers == 0L) r.watchers else base.watchers
                                                                            )
                                                                        } else base
                                                                    } + ranking.filter { r ->
                                                                        registry.none { it.id == r.id || it.repo == r.repo }
                                                                    }
                                                                    emulatorEntries = merged
                                                                    delay(600)
                                                                    isRefreshingLocal = false
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        if (isRefreshingLocal) {
                                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    ) { padding ->
                                        EmulatorScreen(
                                            modifier = Modifier.padding(padding),
                                            entries = emulatorEntries,
                                            insights = emulatorInsights,
                                            onDownload = { entry ->
                                                downloadScope.launch {
                                                    downloadEmulator(appContext, entry)
                                                }
                                            }
                                        )
                                    }
                                }

                                AppScreen.RUNTIME -> RuntimeLibraryScreen(
                                    onDownload = { component ->
                                        downloadScope.launch {
                                            downloadRuntimeLibrary(appContext, component)
                                        }
                                    }
                                )

                                AppScreen.DOWNLOADS -> DownloadsScreen(
                                    onBack = { },
                                    showBack = false
                                )

                                AppScreen.SETTINGS -> SettingsScreen(
                                    onBack = { },
                                    showBack = false,
                                    themeMode = themeMode,
                                    colorTheme = colorTheme,
                                    onThemeModeChange = { mode ->
                                        SettingsManager.setThemeMode(mode)
                                        themeMode = mode
                                    },
                                    onColorThemeChange = { theme ->
                                        SettingsManager.setColorTheme(theme)
                                        colorTheme = theme
                                    },
                                    appLanguage = appLanguage,
                                    onAppLanguageChange = { language ->
                                        SettingsManager.setAppLanguage(language)
                                        appLanguage = language
                                    },
                                    uiScale = uiScale,
                                    onUiScaleChange = { scale ->
                                        SettingsManager.setUiScale(scale)
                                        uiScale = scale
                                    },
                                    onSourceCatalogChanged = { refreshTrigger++ }
                                )

                                AppScreen.GUIDE -> ComponentGuideScreen(
                                    onBack = { currentScreen = guideReturnTab },
                                    adrenoSeries = deviceInfo?.adrenoSeries,
                                    initialTopicId = guideTopic,
                                    driverMode = guideDriverMode,
                                    deviceInfo = deviceInfo
                                )
                            }
                        }
                    }

                    // 慢下载推荐弹窗
                    if (showSlowDownloadDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                SlowDownloadDetector.snooze(30)
                                showSlowDownloadDialog = false
                            },
                            icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                            title = { Text(appString(R.string.slow_download_title)) },
                            text = {
                                Text(appString(R.string.slow_download_message))
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSlowDownloadDialog = false
                                    currentScreen = AppScreen.SETTINGS
                                }) {
                                    Text(appString(R.string.go_enable))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    SlowDownloadDetector.dismissForever()
                                    showSlowDownloadDialog = false
                                }) {
                                    Text(appString(R.string.dont_remind))
                                }
                            }
                        )
                    }
                }
                }
            }
        }
        }
    }
    }

/** 把 Contents-Cmod 独有 dxvk 组件（其他源缺失的历史稳定版）合并进 StevenMXZ/Winlator-Contents 清单，按版本名去重 */
private fun mergeCmodHistorical(base: Map<String, List<Component>>): Map<String, List<Component>> {
    val type = "DXVK"
    val baseUrl = "https://github.com/StevenMXZ/Contents-Cmod/releases/download/1.1.0/"
    val cmodVersions = listOf(
        "dxvk-2.7-8" to "dxvk_2.7-8.wcp",
        "dxvk-2.4.1-PreRegression" to "dxvk-2.4.1-PreRegression.wcp",
        "dxvk-2.4.1-fix" to "dxvk-2.4.1-fix.wcp",
        "dxvk-2.6-2-gplasync" to "dxvk-2.6-2-gplasync.wcp",
        "dxvk-2.1" to "dxvk-2.1.wcp",
        "dxvk-2.0" to "dxvk-2.0.wcp",
        "dxvk-gplasync-v2.3.1-1" to "dxvk-gplasync-v2.3.1-1.wcp",
        "dxvk-1.9.4" to "dxvk-1.9.4.wcp"
    )
    val existing = base[type].orEmpty()
    val existingNames = existing.mapTo(HashSet()) { it.verName.lowercase() }
    val extra = cmodVersions
        .filter { (ver, _) -> ver.lowercase() !in existingNames }
        .map { (ver, file) -> Component(type, ver, "", baseUrl + file) }
    return if (extra.isEmpty()) base
    else base + (type to (existing + extra))
}

private data class AppLoadResult(
    val deviceInfo: DeviceInfo,
    val catalog: SourceCatalog,
    val selectedTurnipSource: TurnipSource,
    val selectedPanvkSource: TurnipSource? = null,
    val selectedQualcommSource: QualcommSource,
    val selectedXclipseSource: TurnipSource? = null,
    val selectedWrapperSource: TurnipSource? = null,
    val turnipReleases: List<GithubRelease>,
    val panvkReleases: List<GithubRelease> = emptyList(),
    val qualcommReleases: List<GithubRelease>,
    val xclipseReleases: List<GithubRelease> = emptyList(),
    val wrapperReleases: List<GithubRelease> = emptyList(),
    /** wrapper 清单条目（winlator-contents wrappers.json，来源切换用） */
    val wrapperItems: List<WrapperItem> = emptyList(),
    val componentCatalogs: Map<String, Map<String, List<Component>>>,
    val componentSourceUpdatedAt: Map<String, Long> = emptyMap(),
    val driverSourceUpdatedAt: Map<String, Long> = emptyMap(),
    val driverSourceStars: Map<String, Long> = emptyMap()
)


