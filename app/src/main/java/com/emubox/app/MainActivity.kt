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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DownloadsManager.init(applicationContext)
        SettingsManager.init(applicationContext)

        // 首次启动请求文件管理权限（Android 11+ 跳系统授权页）：授权后下载库
        // 可直读/直删 Download/EmuBox 目录，根治 MediaStore 索引扫不出与删除无效。
        // 仅首次引导，用户拒绝不影响下载（MediaStore 写入无需权限）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager() &&
            !SettingsManager.isStoragePermissionAsked()
        ) {
            SettingsManager.setStoragePermissionAsked()
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
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

            ProvideAppLanguage(appLanguage) {
                EmuBoxTheme(themeMode = themeMode, colorTheme = colorTheme) {
                val downloadScope = rememberCoroutineScope()
                val appContext = applicationContext

                var currentScreen by remember { mutableStateOf(AppScreen.DEVICE) }
                var globalSearchOpen by remember { mutableStateOf(false) }
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

                var turnipReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var panvkReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var qualcommReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
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

                val activeCount by remember { derivedStateOf { DownloadsManager.activeDownloads.size } }

                // 模拟器清单远程刷新：官方 registry（版本/APK）+ 社区排行（下载量/日志），失败保留内置
                LaunchedEffect(Unit) {
                    val registry = EmulatorRegistryRepository.fetchRemoteRegistry()
                    val ranking = EmulatorRegistryRepository.fetchRemoteRanking()
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
                    emulatorEntries = merged
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
                        val driverEntries = loadedRegistry.filter { it.driverType !in DriverRegistryRepository.COMPONENT_TYPES }
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
                        val turnipOnlySources = compatibleSources.filterNot { it.requiredVendor == "mali" }

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
                            turnipReleases = releases,
                            panvkReleases = panvk,
                            qualcommReleases = qualcomm,
                            componentCatalogs = manifestComponentMaps + registryComponentMaps
                        )
                    }

                    deviceInfo = result.deviceInfo
                    sourceCatalog = result.catalog
                    turnipReleases = result.turnipReleases
                    panvkReleases = result.panvkReleases
                    qualcommReleases = result.qualcommReleases
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
                            NavigationBar {
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.DEVICE,
                                    onClick = { currentScreen = AppScreen.DEVICE },
                                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_device)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.DRIVERS,
                                    onClick = { currentScreen = AppScreen.DRIVERS },
                                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_driver)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.COMPONENTS,
                                    onClick = { currentScreen = AppScreen.COMPONENTS },
                                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_component)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.EMULATORS,
                                    onClick = { currentScreen = AppScreen.EMULATORS },
                                    icon = { Icon(Icons.Default.Android, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_emulator)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.RUNTIME,
                                    onClick = { currentScreen = AppScreen.RUNTIME },
                                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_runtime)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.DOWNLOADS,
                                    onClick = { currentScreen = AppScreen.DOWNLOADS },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (activeCount > 0) Badge { Text(activeCount.toString()) }
                                            }
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null)
                                        }
                                    },
                                    label = { Text(appString(R.string.tab_download)) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.SETTINGS,
                                    onClick = { currentScreen = AppScreen.SETTINGS },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text(appString(R.string.tab_settings)) }
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
                                                    IconButton(onClick = { openGuide(null) }) {
                                                        Icon(Icons.Default.Info, contentDescription = appString(R.string.component_guide))
                                                    }
                                                }
                                            )
                                        }
                                    ) { padding ->
                                        DeviceScreen(
                                            modifier = Modifier.padding(padding),
                                            deviceInfo = deviceInfo,
                                            isLoading = isLoading,
                                            onNavigateToDrivers = { currentScreen = AppScreen.DRIVERS }
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
                                            turnipSources = sourceCatalog.turnipSources.filterNot { it.requiredVendor == "mali" },
                                            turnipReleases = turnipReleases,
                                            panvkSourceId = panvkSourceId,
                                            panvkSources = sourceCatalog.turnipSources.filter { it.requiredVendor == "mali" },
                                            panvkReleases = panvkReleases,
                                            qualcommSourceId = qualcommSourceId,
                                            qualcommSources = sourceCatalog.qualcommSources,
                                            qualcommReleases = qualcommReleases,
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
                                            onDownloadAsset = { release, asset ->
                                                downloadScope.launch {
                                                    downloadAsset(appContext, release, asset)
                                                }
                                            },
                                            driverSourceUpdatedAt = driverSourceUpdatedAt,
                                            driverSourceStars = driverSourceStars,
                                            registryStatus = buildRegistryStatus(
                                                registryEntries,
                                                sourceCatalog.turnipSources.size
                                            )
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
                                                    IconButton(onClick = { openGuide(null) }) {
                                                        Icon(Icons.Default.Info, contentDescription = appString(R.string.component_guide))
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
                                            }
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
                                                                    val registry = EmulatorRegistryRepository.fetchRemoteRegistry()
                                                                    val ranking = EmulatorRegistryRepository.fetchRemoteRanking()
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
                                    onSourceCatalogChanged = { refreshTrigger++ }
                                )

                                AppScreen.GUIDE -> ComponentGuideScreen(
                                    onBack = { currentScreen = guideReturnTab },
                                    adrenoSeries = deviceInfo?.adrenoSeries,
                                    initialTopicId = guideTopic,
                                    driverMode = guideDriverMode
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
    val turnipReleases: List<GithubRelease>,
    val panvkReleases: List<GithubRelease> = emptyList(),
    val qualcommReleases: List<GithubRelease>,
    val componentCatalogs: Map<String, Map<String, List<Component>>>,
    val componentSourceUpdatedAt: Map<String, Long> = emptyMap(),
    val driverSourceUpdatedAt: Map<String, Long> = emptyMap(),
    val driverSourceStars: Map<String, Long> = emptyMap()
)

/** 驱动源状态条文案：注册表加载来源 + 实际合并后 turnipSources 总数 */
private fun buildRegistryStatus(registryEntries: List<DriverRegistryEntry>, mergedTurnipCount: Int): String {
    val sourceLabel = when (DriverRegistryRepository.lastLoadSource) {
        "remote" -> "远程 GitHub"
        "jsdelivr" -> "远程 jsDelivr"
        "assets" -> "内置 APK"
        "fallback" -> "硬编码兜底"
        else -> "未知"
    }
    val driverCount = registryEntries.count { it.driverType !in DriverRegistryRepository.COMPONENT_TYPES }
    return "驱动源状态：注册表 ${driverCount} 个驱动源（来源：$sourceLabel）＋精选 ${mergedTurnipCount} 个已并入"
}
