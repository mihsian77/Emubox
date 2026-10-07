package com.emuhub.cn

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.emuhub.cn.ui.theme.EmuHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen {
    HOME,
    DOWNLOADS,
    GUIDE,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DownloadsManager.init(applicationContext)
        SettingsManager.init(applicationContext)

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
                EmuHubTheme(themeMode = themeMode, colorTheme = colorTheme) {
                val downloadScope = rememberCoroutineScope()
                val appContext = applicationContext

                var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
                var guideTopic by remember { mutableStateOf<String?>(null) }
                var refreshTrigger by remember { mutableIntStateOf(0) }
                var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
                var isLoading by remember { mutableStateOf(true) }

                var sourceCatalog by remember { mutableStateOf(SourceCatalogRepository.builtInCatalog()) }
                var turnipSourceId by remember { mutableStateOf(SettingsManager.getTurnipSource()) }
                var qualcommSourceId by remember { mutableStateOf(SettingsManager.getQualcommSource()) }

                var turnipReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var qualcommReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var componentCatalogs by remember {
                    mutableStateOf<Map<String, Map<String, List<Component>>>>(emptyMap())
                }

                val activeCount by remember { derivedStateOf { DownloadsManager.activeDownloads.size } }

                // 慢下载推荐弹窗
                var showSlowDownloadDialog by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    SlowDownloadDetector.suggestAccelerator.collect {
                        showSlowDownloadDialog = true
                    }
                }

                // The catalog itself is remote. Driver/component files stay on their upstream
                // repositories; EmuHub only reads their APIs/manifests and therefore sees new
                // releases without shipping a new APK.
                LaunchedEffect(refreshTrigger, turnipSourceId, qualcommSourceId) {
                    isLoading = true

                    val result = withContext(Dispatchers.IO) {
                        val info = DeviceInfo.collect(this@MainActivity)
                        val matchByDevice = SettingsManager.getMatchDriversByDevice()
                        val baseCatalog = SourceCatalogRepository.load()

                        // 驱动注册表：按设备 GPU 匹配（含 Mali PanVK 全系列），转换为
                        // TurnipSource 后与内置源合并；注册表推荐源排前，按 apiUrl 去重。
                        // 关闭"按设备匹配"时显示全部驱动源（不按 GPU 过滤）。
                        val registryEntries = DriverRegistryRepository.load()
                        val driverEntries = registryEntries.filter { it.driverType !in DriverRegistryRepository.COMPONENT_TYPES }
                        val matchedDriverSources = (if (matchByDevice) {
                            DriverRegistryRepository.match(info, registryEntries)
                        } else {
                            driverEntries.map { DriverMatchResult(it, 0, false, "") }
                        }).map { DriverRegistryRepository.toTurnipSource(it.entry) }
                        val existingDriverUrls = baseCatalog.turnipSources.map { it.apiUrl }.toSet()
                        val newDriverSources = matchedDriverSources.filter { it.apiUrl !in existingDriverUrls }

                        // 设备专属组件（如 Mali 的 panDXVK）：GitHub release 直接分发 .wcp，
                        // 不走 contents.json，单独拉取后并入对应组件分类。
                        // 关闭"按设备匹配"时显示全部注册表组件。
                        val deviceComponentEntries = if (matchByDevice) {
                            DriverRegistryRepository.matchComponents(info, registryEntries)
                        } else {
                            registryEntries.filter { it.componentType != null }
                        }
                        val registryComponentSources = deviceComponentEntries.map { entry ->
                            ComponentSource(
                                id = "registry-${entry.id}",
                                name = entry.name,
                                manifestUrl = entry.apiUrl,
                                description = entry.description,
                                experimental = entry.maturity in listOf("alpha", "experimental", "beta")
                            )
                        }

                        val catalog = baseCatalog.copy(
                            turnipSources = newDriverSources + baseCatalog.turnipSources,
                            componentSources = baseCatalog.componentSources + registryComponentSources
                        )

                        val compatibleSources = if (matchByDevice) {
                            catalog.compatibleTurnipSources(
                                info.adrenoSeries, info.gpuModel, info.gpuVendor.name.lowercase()
                            )
                        } else {
                            catalog.turnipSources
                        }

                        val selectedSource = compatibleSources.firstOrNull { source ->
                            source.id.equals(turnipSourceId, ignoreCase = true) ||
                                source.name.equals(turnipSourceId, ignoreCase = true)
                        } ?: compatibleSources.firstOrNull()
                            ?: catalog.turnipSources.first()

                        val releases = fetchTurnipReleases(selectedSource, info.adrenoSeries)

                        val selectedQualcommSource = catalog.qualcommSources.firstOrNull { source ->
                            source.id.equals(qualcommSourceId, ignoreCase = true) ||
                                source.name.equals(qualcommSourceId, ignoreCase = true)
                        } ?: catalog.qualcommSources.first()
                        val qualcomm = fetchQualcommReleases(selectedQualcommSource)

                        // 内置清单组件
                        val manifestComponentMaps = coroutineScope {
                            baseCatalog.componentSources.map { source ->
                                async {
                                    source.id to fetchComponentsFromUrl(source.manifestUrl)
                                }
                            }.awaitAll().toMap()
                        }
                        // 注册表设备专属组件
                        val registryComponentMaps = coroutineScope {
                            deviceComponentEntries.map { entry ->
                                async {
                                    val type = entry.componentType
                                    if (type.isNullOrBlank()) {
                                        "registry-${entry.id}" to emptyMap<String, List<Component>>()
                                    } else {
                                        "registry-${entry.id}" to mapOf(type to fetchGithubComponents(entry.apiUrl, type))
                                    }
                                }
                            }.awaitAll().toMap()
                        }

                        AppLoadResult(
                            deviceInfo = info,
                            catalog = catalog,
                            selectedTurnipSource = selectedSource,
                            selectedQualcommSource = selectedQualcommSource,
                            turnipReleases = releases,
                            qualcommReleases = qualcomm,
                            componentCatalogs = manifestComponentMaps + registryComponentMaps
                        )
                    }

                    deviceInfo = result.deviceInfo
                    sourceCatalog = result.catalog
                    turnipReleases = result.turnipReleases
                    qualcommReleases = result.qualcommReleases
                    componentCatalogs = result.componentCatalogs

                    if (turnipSourceId != result.selectedTurnipSource.id) {
                        turnipSourceId = result.selectedTurnipSource.id
                        SettingsManager.setTurnipSource(result.selectedTurnipSource.id)
                    }
                    if (qualcommSourceId != result.selectedQualcommSource.id) {
                        qualcommSourceId = result.selectedQualcommSource.id
                        SettingsManager.setQualcommSource(result.selectedQualcommSource.id)
                    }

                    isLoading = false
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        modifier = Modifier.fillMaxSize(),
                        label = "main-navigation",
                        transitionSpec = {
                            val enterSpec = tween<Float>(durationMillis = 240)
                            val exitSpec = tween<Float>(durationMillis = 170)
                            val slideSpec = tween<IntOffset>(durationMillis = 280)

                            if (targetState == AppScreen.HOME) {
                                (slideInHorizontally(slideSpec) { -it / 10 } + fadeIn(enterSpec)) togetherWith
                                    (slideOutHorizontally(slideSpec) { it / 14 } + fadeOut(exitSpec))
                            } else {
                                (slideInHorizontally(slideSpec) { it / 10 } + fadeIn(enterSpec)) togetherWith
                                    (slideOutHorizontally(slideSpec) { -it / 14 } + fadeOut(exitSpec))
                            }
                        }
                    ) { screen ->
                        when (screen) {
                            AppScreen.DOWNLOADS -> DownloadsScreen(
                                onBack = { currentScreen = AppScreen.HOME }
                            )

                            AppScreen.GUIDE -> ComponentGuideScreen(
                                onBack = { currentScreen = AppScreen.HOME },
                                adrenoSeries = deviceInfo?.adrenoSeries,
                                initialTopicId = guideTopic
                            )

                            AppScreen.SETTINGS -> SettingsScreen(
                                onBack = { currentScreen = AppScreen.HOME },
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

                            AppScreen.HOME -> {
                                Scaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    topBar = {
                                        TopAppBar(
                                            title = {
                                                Column {
                                                    Text(appString(R.string.app_name))
                                                    Text(
                                                        text = appString(R.string.alpha_version, appVersion),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
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
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(22.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                    } else {
                                                        Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse("https://notzeetaa.github.io/Donate-NotZeetaa/")
                                                            )
                                                        )
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Favorite, contentDescription = appString(R.string.donate))
                                                }

                                                IconButton(
                                                    onClick = {
                                                        guideTopic = null
                                                        currentScreen = AppScreen.GUIDE
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Info, contentDescription = appString(R.string.component_guide))
                                                }

                                                BadgedBox(
                                                    badge = {
                                                        if (activeCount > 0) Badge { Text(activeCount.toString()) }
                                                    }
                                                ) {
                                                    IconButton(onClick = { currentScreen = AppScreen.DOWNLOADS }) {
                                                        Icon(Icons.Default.Download, contentDescription = appString(R.string.downloads))
                                                    }
                                                }

                                                IconButton(onClick = { currentScreen = AppScreen.SETTINGS }) {
                                                    Icon(Icons.Default.Settings, contentDescription = appString(R.string.settings))
                                                }
                                            }
                                        )
                                    }
                                ) { innerPadding ->
                                    DriverHubScreen(
                                        modifier = Modifier.padding(innerPadding),
                                        deviceInfo = deviceInfo,
                                        isLoading = isLoading,
                                        turnipSourceId = turnipSourceId,
                                        turnipSources = if (SettingsManager.getMatchDriversByDevice()) {
                                            sourceCatalog.compatibleTurnipSources(deviceInfo?.adrenoSeries, deviceInfo?.gpuModel, deviceInfo?.gpuVendor?.name?.lowercase())
                                        } else {
                                            sourceCatalog.turnipSources
                                        },
                                        turnipReleases = turnipReleases,
                                        qualcommSourceId = qualcommSourceId,
                                        qualcommSources = sourceCatalog.qualcommSources,
                                        qualcommReleases = qualcommReleases,
                                        componentSources = sourceCatalog.componentSources,
                                        componentCatalogs = componentCatalogs,
                                        sourceCatalogRemote = sourceCatalog.isRemote,
                                        onTurnipSourceChange = { sourceId ->
                                            turnipSourceId = sourceId
                                            SettingsManager.setTurnipSource(sourceId)
                                        },
                                        onQualcommSourceChange = { sourceId ->
                                            qualcommSourceId = sourceId
                                            SettingsManager.setQualcommSource(sourceId)
                                        },
                                        onOpenGuide = { topicId ->
                                            guideTopic = topicId
                                            currentScreen = AppScreen.GUIDE
                                        },
                                        onDownloadAsset = { release, asset ->
                                            downloadScope.launch {
                                                downloadAsset(appContext, release, asset)
                                            }
                                        },
                                        onDownloadComponent = { component ->
                                            downloadScope.launch {
                                                downloadComponent(appContext, component)
                                            }
                                        }
                                    )
                                }
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
                            title = { Text("下载速度较慢") },
                            text = {
                                Text("检测到当前下载速度持续偏低，开启国内加速可显著提升下载速度。是否前往设置开启？")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSlowDownloadDialog = false
                                    currentScreen = AppScreen.SETTINGS
                                }) {
                                    Text("前往开启")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    SlowDownloadDetector.dismissForever()
                                    showSlowDownloadDialog = false
                                }) {
                                    Text("不再提示")
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

private data class AppLoadResult(
    val deviceInfo: DeviceInfo,
    val catalog: SourceCatalog,
    val selectedTurnipSource: TurnipSource,
    val selectedQualcommSource: QualcommSource,
    val turnipReleases: List<GithubRelease>,
    val qualcommReleases: List<GithubRelease>,
    val componentCatalogs: Map<String, Map<String, List<Component>>>
)
