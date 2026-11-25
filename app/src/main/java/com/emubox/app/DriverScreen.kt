package com.emubox.app

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.emubox.app.ui.template.SectionTabs
import com.emubox.app.ui.template.listPageHeader
import kotlinx.coroutines.launch
import java.util.*


/**
 * 驱动 Tab：仅展示驱动专区（Turnip + Qualcomm）。
 * 从 DriverHubScreen 拆分而来，设备摘要移至设备 Tab。
 */
/** 驱动子分区索引：原来全是裸数字，加一个「动态」就得整体错位，用常量钉住 */
private const val DRIVER_TAB_ALL = 0
private const val DRIVER_TAB_ACTIVITY = 1
private const val DRIVER_TAB_TURNIP = 2
private const val DRIVER_TAB_QUALCOMM = 3
private const val DRIVER_TAB_PANVK = 4
private const val DRIVER_TAB_XCLIPSE = 5
private const val DRIVER_TAB_WRAPPER = 6

/** 驱动页顶部子分区行：前 7 项是分区筛选（索引对应 DRIVER_TAB_* 常量），
 *  最后一项「组件」是跳转入口，不参与 driverTab 状态。 */
@Composable
private fun driverTabs(): List<String> = listOf(
    appString(R.string.driver_tab_all), "动态", "Turnip",
    appString(R.string.qualcomm_driver), "PanVK", "Xclipse", "Wrapper",
    appString(R.string.tab_component),
)

@Composable
fun DriverScreen(
    modifier: Modifier = Modifier,
    onOpenComponents: () -> Unit = {},
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    turnipSourceId: String,
    turnipSources: List<TurnipSource>,
    turnipReleases: List<GithubRelease>,
    panvkSourceId: String,
    panvkSources: List<TurnipSource>,
    panvkReleases: List<GithubRelease>,
    qualcommSourceId: String,
    qualcommSources: List<QualcommSource>,
    qualcommReleases: List<GithubRelease>,
    xclipseSourceId: String,
    xclipseSources: List<TurnipSource>,
    xclipseReleases: List<GithubRelease>,
    wrapperSourceId: String,
    wrapperSources: List<TurnipSource>,
    wrapperReleases: List<GithubRelease>,
    wrapperItems: List<WrapperItem> = emptyList(),
    /** 驱动注册表全量条目：用于「动态」子分区汇总各源最新发布 */
    driverEntries: List<DriverRegistryEntry> = emptyList(),
    onTurnipSourceChange: (String) -> Unit,
    onPanvkSourceChange: (String) -> Unit,
    onQualcommSourceChange: (String) -> Unit,
    onXclipseSourceChange: (String) -> Unit,
    onWrapperSourceChange: (String) -> Unit,
    onDownloadAsset: (GithubRelease, GithubAsset) -> Unit,
    driverSourceUpdatedAt: Map<String, Long> = emptyMap(),
    driverSourceStars: Map<String, Long> = emptyMap(),
    pendingRepo: String? = null,
    onPendingRepoConsumed: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    // 高通提取驱动分区：有数据即展示（不因 8xx 等系列隐藏——高通官方提取
    // 驱动同样适用于最新 Adreno，用户应能看到全量可选项）
    val showQualcomm = qualcommReleases.isNotEmpty()

    // 按设备匹配驱动：开启时只显示与当前 GPU 厂商兼容的分区
    // （骁龙用户默认不显示 PanVK Mali 分区；Mali 用户默认不显示 Turnip/高通分区），
    // 关闭该功能后展示全部分区。防止无关分区干扰推荐，符合"按设备检测推荐"。
    val matchByDevice = remember { SettingsManager.getMatchDriversByDevice() }
    val gpuVendor = deviceInfo?.gpuVendor
    val isAdreno = gpuVendor == GpuVendor.ADRENO
    val isMali = gpuVendor == GpuVendor.MALI
    val showTurnipSection = !matchByDevice || !isMali
    val showPanvkSection = !matchByDevice || isMali
    val showQualcommSection = !matchByDevice || !isMali
    // Xclipse 只对三星 Exynos(RDNA) 设备有意义：按设备匹配时仅 Xclipse 设备显示
    val isXclipse = gpuVendor == GpuVendor.XCLIPSE
    val showXclipseSection = !matchByDevice || isXclipse
    // Wrapper 通用性强（wmali→Mali、compat/gamenative→通用），始终显示
    val showWrapperSection = true

    // 驱动局部搜索：按源名称/简介过滤三个区块
    var driverQuery by remember { mutableStateOf("") }
    val q = driverQuery.trim()
    val filteredTurnip = remember(turnipSources, q) {
        if (q.isBlank()) turnipSources
        else turnipSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredPanvk = remember(panvkSources, q) {
        if (q.isBlank()) panvkSources
        else panvkSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredQualcomm = remember(qualcommSources, q) {
        if (q.isBlank()) qualcommSources
        else qualcommSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredXclipse = remember(xclipseSources, q) {
        if (q.isBlank()) xclipseSources
        else xclipseSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredWrapper = remember(wrapperSources, q) {
        if (q.isBlank()) wrapperSources
        else wrapperSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }

    // I1：切换来源后滚动定位到对应分区并自动展开其文件列表
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expandTrigger by remember { mutableStateOf<String?>(null) }
    fun sectionIndex(target: Int): Int { // 0=turnip 1=panvk 2=qualcomm 3=xclipse 4=wrapper
        var idx = 3
        if (deviceInfo != null) idx++
        if (isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) idx++
        var off = 0
        if (target > 0 && showTurnipSection && filteredTurnip.isNotEmpty()) off++
        if (target > 1 && showPanvkSection && filteredPanvk.isNotEmpty()) off++
        if (target > 2 && showQualcommSection && filteredQualcomm.isNotEmpty()) off++
        if (target > 3 && showXclipseSection && filteredXclipse.isNotEmpty()) off++
        if (target > 4 && showWrapperSection && filteredWrapper.isNotEmpty()) off++
        return idx + off
    }

    // 设备页推荐卡「查看驱动文件」→ 文件级跳转：切来源 + 展开 + 滚动到对应分区
    val pendingContext = LocalContext.current
    LaunchedEffect(pendingRepo) {
        val target = pendingRepo ?: return@LaunchedEffect
        val entry = DriverRegistryRepository.loadFromAssets(pendingContext)
            ?.firstOrNull { it.id == target }
        if (entry != null) {
            val prefix = when {
                entry.driverType == "qualcomm" -> "qualcomm"
                entry.gpuVendor == "mali" -> "panvk"
                entry.gpuVendor == "xclipse" -> "xclipse"
                entry.driverType == "wrapper" -> "wrapper"
                else -> "turnip"
            }
            val sectionIdx = when (prefix) {
                "qualcomm" -> 2
                "xclipse" -> 3
                "wrapper" -> 4
                "panvk" -> 1
                else -> 0
            }
            val hasTarget = when (prefix) {
                "turnip" -> filteredTurnip.any { it.id == target }
                "panvk" -> filteredPanvk.any { it.id == target }
                "qualcomm" -> filteredQualcomm.any { it.id == target }
                "xclipse" -> filteredXclipse.any { it.id == target }
                else -> filteredWrapper.any { it.id == target }
            }
            if (hasTarget) {
                val current = when (prefix) {
                    "turnip" -> turnipSourceId
                    "panvk" -> panvkSourceId
                    "qualcomm" -> qualcommSourceId
                    "xclipse" -> xclipseSourceId
                    else -> wrapperSourceId
                }
                if (current != target) {
                    when (prefix) {
                        "turnip" -> onTurnipSourceChange(target)
                        "panvk" -> onPanvkSourceChange(target)
                        "qualcomm" -> onQualcommSourceChange(target)
                        "xclipse" -> onXclipseSourceChange(target)
                        else -> onWrapperSourceChange(target)
                    }
                }
            }
            expandTrigger = "$prefix:$target"
            scope.launch { listState.animateScrollToItem(sectionIndex(sectionIdx)) }
        }
        onPendingRepoConsumed()
    }

    // 头部文案在 Composable 上下文取好（LazyColumn content 内不能调 appString）
    val headerTitle = appString(R.string.driver_region)
    val headerHint = appString(R.string.driver_search_hint)

    // 五类驱动分开展示：原来 5 个区块全竖排，一屏只够看一个源，找另一个系列要滑很久。
    // 「动态」排第二位：驱动区用户最常问的是「我该换驱动了吗」，这是唯一能回答的入口；
    // 放末尾会被挤到屏幕外（7 个标签在 411dp 上放不下，必须横滑才看得到）。
    var driverTab by remember { mutableStateOf(DRIVER_TAB_ALL) }

    // 「动态」子分区数据：各驱动源最新一条 release，按时间倒序。
    // 驱动没有媒体新闻源，更新日志就是它唯一的动态；这里把 tag / 日期 / 日志 / 文件名
    // 汇总成时间流，并按发布说明判出变更类型，替代原来那张没人展开的折叠卡。
    val driverNews = remember(driverEntries) {
        driverEntries
            .filter { it.latestTag.isNotBlank() }
            .sortedByDescending { it.latestDate }
            .map { d ->
                DriverNewsItem(
                    sourceName = d.name,
                    family = when (d.driverType) {
                        "panvk" -> "panvk"
                        "qualcomm" -> "qualcomm"
                        "wrapper" -> "wrapper"
                        "component" -> "wrapper"
                        else -> if (d.gpuVendor == "xclipse") "xclipse" else "turnip"
                    },
                    tag = d.latestTag,
                    // registry 存的是 epoch ms，UI 侧统一用 ISO 字符串（formatRelativeTime 认这个）
                    date = if (d.latestDate > 0)
                        java.time.Instant.ofEpochMilli(d.latestDate).toString() else "",
                    body = d.latestBody,
                    zipName = "",
                    apiUrl = d.apiUrl,
                    kinds = driverChangeKinds(d.latestBody)
                )
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
    // 底部的“驱动/组件”切换条已撤，“组件”并进顶部子分区行最后一项
    val tabs = driverTabs()
    SectionTabs(
        tabs = tabs,
        selected = driverTab,
        onSelect = { idx -> if (idx == tabs.lastIndex) onOpenComponents() else driverTab = idx },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // 模板化头部（排版规格 v1）：标题 → 搜索 → 结果计数，控件不超过三层。
        // 「N 个驱动源」是搜索/过滤是否生效的唯一反馈，缺了它用户只能靠数卡片。
        // 文案在页面级取好：LazyColumn 的 content 不是 Composable 上下文，里面不能调 appString。
        listPageHeader(
            title = headerTitle,
            icon = Icons.Default.Memory,
            query = driverQuery,
            onQueryChange = { v ->
                driverQuery = v
                // 搜索词精确匹配源名 → 自动切换该源并加载其版本列表
                // （修复"搜索后仅该来源但可下载文件不刷新"：仅过滤源列表不切换源的问题）
                val key = v.trim()
                if (key.isNotBlank()) {
                    turnipSources.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let {
                        onTurnipSourceChange(it.id)
                    }
                }
            },
            // 本机推荐前置：用户打开驱动区的第一句话是「我这台该装哪个」，
            // 不是「一共有哪些仓库」。设备摘要 + 推荐卡放进头部（搜索框之上），
            // 下面仍是可继续下滑的完整源列表——两条路径都保留，只是顺序换了。
            // 只在「全部」子分区显示：切到 Turnip / 动态时用户已经在找具体东西，
            // 再压一张大卡会挡住列表。
            headerContext = if (deviceInfo != null) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DriverDeviceSummaryCard(deviceInfo = deviceInfo)
                        if (driverTab == DRIVER_TAB_ALL) {
                            DriverRecommendationCard(
                                deviceInfo = deviceInfo,
                                onNavigateToDrivers = null
                            )
                        }
                    }
                }
            } else null,
            searchPlaceholder = if (driverTab == DRIVER_TAB_ACTIVITY) {
                "搜索更新（驱动名 / 版本 / 日志）"
            } else headerHint,
            // 一条源都没有时不显示「0 个驱动源」——下面已有专门的空态卡，重复说两次没有意义
            resultCount = (filteredTurnip.size + filteredPanvk.size + filteredQualcomm.size +
                filteredXclipse.size + filteredWrapper.size).takeIf { it > 0 },
            resultLabel = { appString(R.string.driver_result_count, it) },
        )

        if (driverTab == DRIVER_TAB_ALL) item(key = "driver_related") {
            RelatedPartitionRow(current = "drivers", onNavigate = onNavigate)
        }

        // 「动态」子分区：驱动更新流独立成页，不与源选择/版本列表挤在一起。
        // 顶部搜索框在动态页也可用——按驱动名 / 版本号 / 日志正文过滤，
        // 用户常搜「v849」「a8xx」这类版本机型代号。
        if (driverTab == DRIVER_TAB_ACTIVITY) item(key = "driver_activity") {
            val q = driverQuery.trim()
            val news = if (q.isBlank()) driverNews
            else driverNews.filter {
                it.sourceName.contains(q, true) || it.tag.contains(q, true) ||
                    it.body.contains(q, true) || it.zipName.contains(q, true)
            }
            DriverNewsCard(items = news, loading = isLoading)
        }

        if (isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) {
            item(key = "driver_loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(appString(R.string.fetching_latest))
                    }
                }
            }
        }

        // 源列表始终展示（releases 失败时 DriverReleasePicker 显示空态提示），
        // 避免整个驱动区块因 releases 拉取失败而消失（修复"驱动专区空白"）。
        if (filteredTurnip.isNotEmpty() && showTurnipSection && (driverTab == DRIVER_TAB_ALL || driverTab == DRIVER_TAB_TURNIP)) {
            item(key = "turnip_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredTurnip,
                    currentSourceId = turnipSourceId,
                    onSourceChange = { id ->
                        onTurnipSourceChange(id)
                        expandTrigger = "turnip:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = turnipReleases,
                    selectionKey = "turnip:$turnipSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("turnip:") }
                )
            }
        }

        // ── PanVK 驱动专区：Mesa Panfrost 开源 Mali 驱动独立区块 ──
        if (filteredPanvk.isNotEmpty() && showPanvkSection && (driverTab == DRIVER_TAB_ALL || driverTab == DRIVER_TAB_PANVK)) {
            item(key = "panvk_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredPanvk,
                    currentSourceId = panvkSourceId,
                    onSourceChange = { id ->
                        onPanvkSourceChange(id)
                        expandTrigger = "panvk:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = panvkReleases,
                    selectionKey = "panvk:$panvkSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("panvk:") },
                    title = "PanVK 驱动专区",
                    description = "Mesa Panfrost 开源 Mali 驱动（G57–G720 全系），在 Winlator/Eden 等模拟器中为 Mali 设备提供 Vulkan 支持",
                    downloadLabel = appString(R.string.download_panvk)
                )
            }
        }

        if (showQualcomm && showQualcommSection && filteredQualcomm.isNotEmpty() && (driverTab == DRIVER_TAB_ALL || driverTab == DRIVER_TAB_QUALCOMM)) {
            item(key = "qualcomm_section") {
                DriverCardDynamic(
                    title = appString(R.string.qualcomm_driver),
                    description = appString(R.string.qualcomm_driver_desc),
                    icon = Icons.Default.Memory,
                    sources = filteredQualcomm,
                    currentSourceId = qualcommSourceId,
                    onSourceChange = { id ->
                        onQualcommSourceChange(id)
                        expandTrigger = "qualcomm:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = qualcommReleases,
                    selectionKey = "qualcomm:$qualcommSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("qualcomm:") }
                )
            }
        }

        // ── 三星 Xclipse 驱动专区：Exynos RDNA GPU（Exynos 2200/2400）独立区块 ──
        if (filteredXclipse.isNotEmpty() && showXclipseSection && (driverTab == DRIVER_TAB_ALL || driverTab == DRIVER_TAB_XCLIPSE)) {
            item(key = "xclipse_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredXclipse,
                    currentSourceId = xclipseSourceId,
                    onSourceChange = { id ->
                        onXclipseSourceChange(id)
                        expandTrigger = "xclipse:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = xclipseReleases,
                    selectionKey = "xclipse:$xclipseSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("xclipse:") },
                    title = "三星 Xclipse 驱动专区",
                    description = "Mesa RADV / WinXclipse 驱动，为三星 Exynos（RDNA Xclipse GPU）在 Winlator 等模拟器提供 Vulkan 支持",
                    downloadLabel = appString(R.string.download_xclipse)
                )
            }
        }

        // ── 驱动包装器专区：Vulkan/GL 包装层（wmali、compat-bcn、gamenative 等）──
        // Wrapper 专区：三源（banner/fristoner/winx）单区块并列，源切换弹窗可选。
        // banner 源为 wrappers.json 清单型：展开区显示清单卡（集成在 TurnipDriverSection
        // 的 catalogItems 分支），不占满页面；fristoner/winx 为 release 型展开显示版本卡。
        if (filteredWrapper.isNotEmpty() && showWrapperSection && (driverTab == DRIVER_TAB_ALL || driverTab == DRIVER_TAB_WRAPPER)) {
            item(key = "wrapper_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredWrapper,
                    currentSourceId = wrapperSourceId,
                    onSourceChange = { id ->
                        onWrapperSourceChange(id)
                        expandTrigger = "wrapper:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = wrapperReleases,
                    selectionKey = "wrapper:$wrapperSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("wrapper:") },
                    title = "Wrapper",
                    description = "Vulkan/GL 包装层与兼容层（wmali-wrapper、compat-bcn、gamenative、FristOneRR、WinXclipse 等），配合对应驱动使用提升兼容性",
                    downloadLabel = appString(R.string.download_wrapper),
                    catalogItems = wrapperItems,
                    onCatalogDownload = { item ->
                        val fileName = item.url.substringAfterLast('/')
                        onDownloadAsset(
                            GithubRelease(
                                tagName = item.id,
                                name = item.name,
                                assets = listOf(
                                    GithubAsset(
                                        name = fileName,
                                        downloadUrl = item.url,
                                        sizeBytes = item.fileSizeBytes
                                    )
                                ),
                                publishedAt = "",
                                body = item.description
                            ),
                            GithubAsset(
                                name = fileName,
                                downloadUrl = item.url,
                                sizeBytes = item.fileSizeBytes
                            )
                        )
                    }
                )
            }
        }

        if (q.isNotBlank() && filteredTurnip.isEmpty() && filteredPanvk.isEmpty() && filteredQualcomm.isEmpty()) {
            item(key = "driver_search_empty") {
                Text(
                    appString(R.string.search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        if (!isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) {
            item(key = "driver_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.no_drivers),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            appString(R.string.no_drivers_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    }
}
