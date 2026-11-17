package com.emubox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.template.CollapseRow
import com.emubox.app.ui.template.PageTitleRow
import com.emubox.app.ui.template.ResultCountRow
import com.emubox.app.ui.template.ConclusionCard
import com.emubox.app.ui.template.ConclusionTone
import com.emubox.app.ui.template.DetailActionBar
import com.emubox.app.ui.template.FusionCard
import com.emubox.app.ui.template.SectionHeader
import com.emubox.app.ui.template.SectionTabs
import com.emubox.app.ui.template.SettingChoiceRow
import com.emubox.app.ui.template.SettingGroup
import com.emubox.app.ui.template.SettingRow
import com.emubox.app.ui.template.SpecItem
import com.emubox.app.ui.template.SpecStrip
import com.emubox.app.ui.template.listPageHeader
import com.emubox.app.ui.theme.Fusion
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


/**
 * 驱动适配推荐卡片：基于注册表匹配结果给出本机可用驱动源，
 * 直接告诉用户"该下哪个"，点击一键跳转驱动专区。
 * 取代旧版纯科普文本的"路线推荐"（内容人人都知道，无实操价值）。
 */
@Composable
internal fun DriverRecommendationCard(
    deviceInfo: DeviceInfo,
    onNavigateToDrivers: (() -> Unit)?,
    onOpenDriverFiles: ((String) -> Unit)? = null
) {
    var recommendations by remember(deviceInfo) { mutableStateOf<List<DriverMatchResult>?>(null) }
    var loadFailed by remember(deviceInfo) { mutableStateOf(false) }
    // 「没匹配到」与「没加载出来」是两件事：前者是数据结论，后者是故障。
    // 原来合并成一个 loadFailed，导致 Adreno 750 加载失败时显示「未匹配到适配驱动」。
    var matchedNothing by remember(deviceInfo) { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(deviceInfo) {
        loadFailed = false
        matchedNothing = false
        try {
            val result = withContext(Dispatchers.IO) {
                val entries = DriverRegistryRepository.load(context = context)
                val matched = DriverRegistryRepository.match(deviceInfo, entries)
                // 只留「完美适配」项：gpuModels 精确命中本机型号 + 架构兼容 + stable/beta/ci
                //（score>=100），按 star 取前 3，避免一堆「支持但非本机型号」的仓库堆在推荐卡
                val perfect = matched.filter { it.recommended }
                val picks = if (perfect.isNotEmpty()) perfect
                else matched.filter { it.score >= 100 }.take(1) // 实验性兜底：型号命中但非 stable，最多 1 条
                picks.sortedByDescending { it.entry.stars }.take(3)
            }
            if (result.isEmpty()) matchedNothing = true else recommendations = result
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val matches = recommendations
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.driver_recommend_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.weight(1f))
            }

            // 技术路线一行（保留专业信息，但压缩为说明而非主体）
            val route = recommendDriverRoute(deviceInfo)
            val routeColor = when (route.turnipSupport) {
                TurnipSupport.FULL -> Color(0xFF2E7D32)
                TurnipSupport.PARTIAL -> Color(0xFFE65100)
                // 不支持 Turnip 不等于「这条推荐是危险操作」：Mali 设备的
                // 「主推 PanVK」被染成红色后，用户会读成警告而不是路线。
                TurnipSupport.NONE -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            Text(
                "主推 ${route.primaryRoute} · 备选 ${route.secondaryRoute}",
                style = MaterialTheme.typography.bodySmall,
                color = routeColor
            )
            // 专业说明：解释翻译层组合，用户能看懂为什么这么推荐
            Text(
                route.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
            )

            when {
                loadFailed -> {
                    Text(
                        appString(R.string.driver_recommend_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                matchedNothing -> {
                    Text(
                        appString(R.string.driver_recommend_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                matches == null -> {
                    // 匹配计算中
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    matches.forEach { match ->
                        key(match.entry.id) {
                            DriverRecommendRow(
                                match = match,
                                onOpenDriverFiles = onOpenDriverFiles,
                                onDownload = { asset ->
                                    // 用全局作用域：卡片滚出屏幕被回收/切分区时不能把下载一起取消
                                    AppDownloadScope.launch {
                                        runCatching {
                                            downloadDriverAsset(
                                                context,
                                                match.entry.name,
                                                asset.downloadUrl,
                                                asset.name
                                            )
                                        }
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

/**
 * 单个推荐驱动：上面一行来源说明，下面直接摊开最近两个 release 的可下载文件。
 *
 * 原实现只给一个「查看驱动文件」按钮，点进去还要再翻一层才知道该下哪个。
 * 这里把文件列表摊开，最新一个非预发布版打上推荐标记，点文件即下载。
 */
@Composable
private fun DriverRecommendRow(
    match: DriverMatchResult,
    onOpenDriverFiles: ((String) -> Unit)?,
    onDownload: (GithubAsset) -> Unit
) {
    val entry = match.entry
    var releases by remember(entry.id) { mutableStateOf<List<GithubRelease>?>(null) }
    LaunchedEffect(entry.id) {
        releases = runCatching { fetchGithubReleasesFromUrl(entry.apiUrl) }
            .getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 作者头像：和驱动区分区头一致，用户能一眼认出这是哪个作者/仓库
            RepoAvatar(
                avatarUrl = githubOwnerAvatar(entry.apiUrl),
                fallbackName = entry.name,
                size = 26.dp
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(match.matchReason)
                        entry.targetEmulator.takeIf { it.isNotBlank() && it != "generic" }?.let {
                            append(" · 适配 $it")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 来源简介：两行封顶。原来一行截断，稍长的说明只剩省略号，用户判断不了该下哪个
                if (entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (match.recommended) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        appString(R.string.category_recommended),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        val list = releases
        when {
            list == null -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
            list.isEmpty() -> Text(
                when (lastApiError) {
                    "RATE_LIMITED" -> appString(R.string.source_empty_rate_limited)
                    "NOT_FOUND" -> appString(R.string.source_empty_no_release)
                    else -> "没取到版本列表，可进驱动专区查看"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
            )
            else -> {
                // 最新一个非预发布版算推荐版本
                val recommendedTag = list.firstOrNull { !it.prerelease }?.tagName
                // 三个推荐项各摊两版、每版三个文件，加起来近 900dp——首屏被灌满，
                // 搜索框和源列表全被推到屏幕外。默认只露最新版的第一个文件，
                // 点一下才展开旧版本与其余文件：「先给答案，细节按需」。
                var expanded by remember(entry.id) { mutableStateOf(false) }
                val newest = list.first()
                val newestFiles = newest.assets.filter { isDownloadableAsset(it.name) }
                val releasesToShow = if (expanded) list.take(2) else listOf(newest)

                releasesToShow.forEach { release ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            release.tagName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (release.tagName == recommendedTag) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    appString(R.string.category_recommended),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (release.publishedAt.length >= 10) {
                            Text(
                                release.publishedAt.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                    val files = release.assets.filter { isDownloadableAsset(it.name) }
                    val shown = if (expanded) files.take(3) else files.take(1)
                    shown.forEach { asset ->
                        // 长按看完整文件名：点击已经是下载，不能再抢；但只给 3 行，
                        // 万一还有更长的产物名，用户至少有个能看到全文的入口。
                        var showFullName by remember(asset.name) { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = { onDownload(asset) },
                                    onLongClick = { showFullName = true }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                // 原来 1 行：71 字符的 Termux 产物名只剩前 30 来个字符，
                                // 6 个包截断后长得一模一样，ABI 和 debug/release 全在尾部
                                LongFieldText(
                                    asset.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3
                                )
                                // 副行直接把「装得上吗」讲清楚：架构 + 类型 + 大小
                                Text(
                                    buildString {
                                        val arch = assetArchLabel(asset.name)
                                        if (arch.isNotBlank()) append(arch).append(" · ")
                                        append(assetTypeLabel(asset.name))
                                        if (asset.sizeBytes > 0) append(" · ").append(formatBytes(asset.sizeBytes))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                                )
                            }
                        }
                        if (showFullName) {
                            FullNameDialog(asset.name) { showFullName = false }
                        }
                    }
                }
                if (newestFiles.size > 1 || list.size > 1) {
                    // 不写「共 30 个版本」：展开只多给一个旧版本，写总数会让人以为点开能看到全部 30 版
                    Text(
                        if (expanded) "收起" else "共 ${newestFiles.size} 个文件 · 展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 10.dp, top = 2.dp)
                            .clickable { expanded = !expanded }
                    )
                }
                if (onOpenDriverFiles != null) {
                    TextButton(onClick = { onOpenDriverFiles(entry.id) }) {
                        Text("全部版本与说明", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 驱动 release 里值得列出的文件：安装包、压缩包、驱动 bin */
private fun isDownloadableAsset(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".7z") ||
        lower.endsWith(".tar.gz") || lower.endsWith(".so") || lower.endsWith(".adpkg") ||
        lower.endsWith(".wcp")
}

@Composable
private fun DownloadIndexCard(
    section: HubSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 142.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = appString(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                appString(R.string.source_format, section.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Text(
                appString(R.string.latest_format, section.latest),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

internal fun componentIcon(type: String): ImageVector = when (type) {
    "Wine" -> Icons.Default.WineBar
    "Proton" -> Icons.Default.Bolt
    "Box64" -> Icons.Default.Inventory2
    "WOWBox64" -> Icons.Default.AutoAwesome
    "DXVK" -> Icons.Default.SportsEsports
    "FEXCore" -> Icons.Default.DeveloperBoard
    "VKD3D" -> Icons.Default.ViewInAr
    "D7VK" -> Icons.Default.Gamepad
    else -> Icons.Default.Extension
}

@Composable
internal fun componentSubtitle(type: String): String = when (type) {
    "Wine" -> appString(R.string.component_wine_subtitle)
    "Proton" -> appString(R.string.component_proton_subtitle)
    "Box64" -> appString(R.string.component_box64_subtitle)
    "WOWBox64" -> appString(R.string.component_wowbox64_subtitle)
    "DXVK" -> appString(R.string.component_dxvk_subtitle)
    "FEXCore" -> appString(R.string.component_fex_subtitle)
    "VKD3D" -> appString(R.string.component_vkd3d_subtitle)
    "D7VK" -> appString(R.string.component_d7vk_subtitle)
    else -> appString(R.string.runtime_component)
}

/** 目录路径以 "/" 结尾（adrenotools 内部按 "dir + 文件名" 拼接，缺斜杠必加载失败） */
internal fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"

/** 根据设备 Adreno 系列计算 Turnip 源优先级：匹配型号/系列 → 推荐，有白名单无匹配 → 专属，无白名单 → 通用 */
private fun turnipCategoryOf(source: TurnipSource, adrenoSeries: String?): String {
    // PanVK（Mali）驱动独立分区：与 Turnip（Adreno）/ 高通提取驱动完全不同的
    // 技术路线（Mesa Panfrost 前端），混在推荐区会让用户误以为不兼容。
    if (source.requiredVendor == "mali") return "panvk"
    val seriesHit = source.supportedSeries.contains(adrenoSeries)
    // 通用多系列 turnip 构建（覆盖 ≥2 个系列且不限定具体型号）→ 适合绝大多数设备
    val isUniversal = source.supportedModels.isEmpty() && source.supportedSeries.size >= 2
    return when {
        adrenoSeries == null ->
            if (source.supportedModels.isEmpty() && source.supportedSeries.size >= 2) "general"
            else "exclusive"
        // 通用构建且适配当前系列 → 推荐
        isUniversal && seriesHit -> "recommended"
        // 系列匹配（含具体型号专用源，如 s1mptom 830/840）→ 专属
        seriesHit -> "exclusive"
        // 当前系列不适配（如 8xx 设备看 7xx 专用源）→ 专属，不隐藏但也不推荐
        else -> "exclusive"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnipDriverSection(
    adrenoSeries: String?,
    sources: List<TurnipSource>,
    currentSourceId: String,
    onSourceChange: (String) -> Unit,
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    updatedAtMap: Map<String, Long> = emptyMap(),
    remoteStars: Map<String, Long> = emptyMap(),
    title: String? = null,
    description: String? = null,
    expandTrigger: String? = null,
    catalogItems: List<WrapperItem> = emptyList(),
    onCatalogDownload: ((WrapperItem) -> Unit)? = null,
    /** 下载按钮文案：Turnip / PanVK / Xclipse / Wrapper 各用各的，不共用「下载 Turnip」 */
    downloadLabel: String? = null
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    val profileCtx = LocalContext.current
    val profile = remember(currentSource?.id) {
        SourceProfiles.ofSource(profileCtx, currentSource?.id)
    }
    // 区块默认折叠：与组件专区一致，一屏可浏览全部驱动大区
    var expanded by rememberSaveable(selectionKey) { mutableStateOf(false) }
    // I1：外部触发展开（切换来源后自动打开对应文件列表）
    val lastTrigger = remember { mutableStateOf("") }
    LaunchedEffect(selectionKey, expandTrigger) {
        if (expandTrigger != null && expandTrigger != lastTrigger.value) {
            expanded = true
            lastTrigger.value = expandTrigger
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 区块头像：与高通区一致，显示当前源作者头像；加载失败回退首字母
                val srcAvatar = currentSource?.apiUrl?.let { githubOwnerAvatar(it) }
                RepoAvatar(
                    avatarUrl = srcAvatar,
                    fallbackName = currentSource?.name ?: (title ?: "D"),
                    size = 24.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title ?: appString(R.string.turnip_driver), style = MaterialTheme.typography.titleSmall)
                    // 折叠时也要能一眼看出「这包给谁用」：取人工核定文案，没有就退回作者简介
                    if (!expanded) {
                        val fitLine = profile?.fit?.takeIf { it.isNotBlank() }
                            ?: currentSource?.description
                        if (!fitLine.isNullOrBlank()) {
                            Text(
                                fitLine,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (expanded) {
                        // I2：简介可点击展开/收起，解决长简介截断无法查看全文
                        var descExpanded by rememberSaveable(selectionKey + ":desc") { mutableStateOf(false) }
                        Text(
                            description ?: when (adrenoSeries) {
                                "8xx" -> appString(R.string.turnip_desc_8xx)
                                "6xx", "7xx" -> appString(R.string.turnip_desc_6_7)
                                else -> appString(R.string.turnip_desc_generic)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { descExpanded = !descExpanded }
                                .padding(top = 4.dp)
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                }
                if (!expanded && currentSource != null) {
                    CollapsedSourceHint(
                        name = currentSource.name,
                        latestPublishedAt = releases.firstOrNull()?.publishedAt
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

            UsageBlock(profile)

            if (currentSource != null) {
                val currentCategory = turnipCategoryOf(currentSource, adrenoSeries)
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentArchived = currentSource.archived,
                    currentCategory = currentCategory,
                    avatarUrl = currentSource?.apiUrl?.let { githubOwnerAvatar(it) },
                    currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                        .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            archived = source.archived,
                            category = turnipCategoryOf(source, adrenoSeries),
                            updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                            preview = buildTurnipPreview(source, remoteStars),
                            avatarUrl = source.apiUrl?.let { githubOwnerAvatar(it) }
                        )
                    },
                    onSelected = { sourceId ->
                        if (sourceId != currentSourceId) onSourceChange(sourceId)
                    }
                )
            }

            if (catalogItems.isNotEmpty()) {
                // wrapper 清单型源（banner wrappers.json）：展开区直接列清单卡，
                // 不依赖 release 标签；条目即版本卡。
                WrapperCatalogSection(
                    items = catalogItems,
                    onDownload = { item ->
                        onCatalogDownload?.invoke(item)
                    }
                )
            } else {
                DriverReleasePicker(
                    releases = releases,
                    selectionKey = selectionKey,
                    onDownload = onDownload,
                    buttonLabel = downloadLabel ?: appString(R.string.download_turnip)
                )
            }
            }
            }
        }
    }
}

/** 来源预览窗格条目：图标 + 文本（支持型号 / 目标模拟器 / 活跃度等） */
internal data class PreviewItem(val icon: ImageVector, val text: String)

internal data class SourcePickerOption(
    val id: String,
    val name: String,
    val description: String,
    val experimental: Boolean,
    /** 英文 key：recommended / general / exclusive，UI 层映射为本地化文案 */
    val category: String = "general",
    /** 已归档/停更源，UI 显示"已停更"标签 */
    val archived: Boolean = false,
    /** 源仓库更新时间提示（组件侧传入，如"更新于 3 天前"） */
    val updatedHint: String? = null,
    /** 预览窗格：一行一条（图标 + 文本），如支持型号 / 目标模拟器 / stars */
    val preview: List<PreviewItem> = emptyList(),
    /** 仓库作者头像 URL（GitHub 作者头像），弹窗条目区分仓库用；无则回退状态图标 */
    val avatarUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcePickerCard(
    title: String,
    currentName: String,
    currentDescription: String,
    currentExperimental: Boolean,
    options: List<SourcePickerOption>,
    onSelected: (String) -> Unit,
    currentArchived: Boolean = false,
    /** 当前源的优先级分类（英文 key），用于顶部标签显示 */
    currentCategory: String = "general",
    /** 源仓库更新时间提示（如"更新于 3 天前"），组件侧传入 */
    currentUpdatedHint: String? = null,
    /** 源作者头像 URL（GitHub 作者头像），无则显示默认源图标 */
    avatarUrl: String? = null
) {
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (avatarUrl != null) {
                    RepoAvatar(
                        avatarUrl = avatarUrl,
                        fallbackName = currentName
                    )
                } else {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            currentName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 优先级标签（推荐 / 专属 / 通用），停更优先显示停更
                        if (!currentArchived && currentCategory == "recommended") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_recommended)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        if (!currentArchived && currentCategory == "exclusive") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_exclusive)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                        if (!currentArchived && currentCategory == "general") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_general)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        if (currentExperimental) {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.experimental)) }
                            )
                        }
                        if (currentArchived) {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.source_archived)) }
                            )
                        }
                    }
                    if (currentDescription.isNotBlank()) {
                        // 来源简介可点击展开/收起，避免长简介截断无法查看全文
                        var descExpanded by rememberSaveable("${title}:src-desc") { mutableStateOf(false) }
                        Text(
                            currentDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                    // 源仓库更新时间（组件清单无时间字段，用仓库活跃度参考）
                    if (currentUpdatedHint != null) {
                        Text(
                            currentUpdatedHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // 只有一个源时不显示换源按钮：禁用态仍占一整行，看着像坏了
            if (options.size > 1) {
                FilledTonalButton(
                    onClick = { showSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.change_source))
                }
            }
        }
    }

    if (showSheet) {
        // 近全屏 Dialog：替代 ModalBottomSheet，避免固定高度导致的底部大空白
        Dialog(onDismissRequest = { showSheet = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 顶部：标题 + 说明 + 关闭
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appString(R.string.choose_source),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                        }
                    }
                    Text(
                        appString(R.string.downloads_upstream),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 当前选中提示
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                currentName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                    // 分组：推荐 / 通用 / 专属 / 已停更
                    val categoryLabel = mapOf(
                        "recommended" to appString(R.string.category_recommended),
                        "panvk" to "PanVK（Mali）",
                        "general" to appString(R.string.category_general),
                        "exclusive" to appString(R.string.category_exclusive),
                        "archived" to appString(R.string.source_archived)
                    )
                    val grouped = options.groupBy { opt ->
                        if (opt.archived) "archived" else opt.category.ifBlank { "general" }
                    }
                    val categoryOrder = listOf("recommended", "panvk", "general", "exclusive", "archived")
                        .filter { grouped.containsKey(it) }
                    val flatItems = buildList {
                        categoryOrder.forEach { cat ->
                            val group = grouped[cat].orEmpty()
                            if (group.isNotEmpty()) {
                                add("__header__$cat")
                                group.forEach { add(it) }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // 分组头用 GridItemSpan 跨满两列：此前 header 只占一格，
                        // 导致后续卡片左右列错位（用户反馈的"排列问题"根因）。
                        flatItems.forEach { item ->
                            if (item is String) {
                                val cat = item.removePrefix("__header__")
                                item(
                                    key = "__header_$cat",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${categoryLabel[cat].orEmpty()}（${grouped[cat].orEmpty().size}）",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when (cat) {
                                                "recommended" -> MaterialTheme.colorScheme.primary
                                                "exclusive" -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            } else {
                                val option = item as SourcePickerOption
                                item(key = option.id) {
                                    SourcePickerGridCard(
                                        option = option,
                                        selected = option.name == currentName,
                                        onSelected = {
                                            onSelected(option.id)
                                            showSheet = false
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
}

/** Turnip 来源预览：支持型号 / 目标模拟器 / 活跃度，star 优先用远程元数据覆盖 */
@Composable
private fun buildTurnipPreview(source: TurnipSource, remoteStars: Map<String, Long> = emptyMap()): List<PreviewItem> {
    val lines = mutableListOf<PreviewItem>()
    val models = source.supportedModels.sorted()
    val series = source.supportedSeries.sorted()
    if (models.isNotEmpty()) {
        lines += PreviewItem(Icons.Default.PhoneAndroid, appString(R.string.preview_supported, models.joinToString("/")))
    } else if (series.isNotEmpty()) {
        lines += PreviewItem(Icons.Default.PhoneAndroid, appString(R.string.preview_series, series.joinToString("/")))
    }
    when (source.targetEmulator) {
        "eden" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_eden))
        "winlator" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_winlator))
        "termux" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_termux))
        "gamehub" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_gamehub))
    }
    val stars = remoteStars[source.id] ?: source.stars
    if (stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(stars)))
    return lines
}

/** Rutabaga Turnip 驱动卡片（banner 社区源 WCP 构建）：并入驱动区 Turnip 分区。
 *  条目来自 winlator-contents manifest type=Turnip（Rutabaga-* 版本），
 *  下载走组件下载通道（onDownloadComponent）。 */
@Composable
private fun RutabagaDriverCard(
    components: List<Component>,
    onDownload: (Component) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Rutabaga（Turnip 驱动，banner 社区源）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Text(
                "Mesa Turnip 的 Rutabaga 实验构建，以 WCP 打包分发，Adreno 6xx/7xx 适用。版本随上游发布更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            components.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.verName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onDownload(c) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("下载", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Qualcomm 来源预览：系统驱动说明 + 活跃度 + 停更状态，star 优先用远程元数据覆盖 */
@Composable
private fun buildQualcommPreview(source: QualcommSource, remoteStars: Map<String, Long> = emptyMap()): List<PreviewItem> {
    val lines = mutableListOf<PreviewItem>()
    lines += PreviewItem(Icons.Default.Memory, appString(R.string.preview_qualcomm_system))
    if (source.archived) lines += PreviewItem(Icons.Default.Warning, appString(R.string.preview_archived))
    val stars = remoteStars[source.id] ?: source.stars
    if (stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(stars)))
    return lines
}

/** 来源选择弹窗里的单个卡片：名称 + 成熟度 + 简介 + 预览窗格（双列网格单元） */
@Composable
private fun SourcePickerGridCard(
    option: SourcePickerOption,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Card(
        onClick = onSelected,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：图标 + 名称（名称独占剩余空间，标签不挤占，避免被压成单字）
            Row(verticalAlignment = Alignment.Top) {
                if (option.avatarUrl != null) {
                    RepoAvatar(
                        avatarUrl = option.avatarUrl,
                        fallbackName = option.name,
                        selected = selected
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Icon(
                            if (selected) Icons.Default.Check else Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.padding(6.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    option.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            // 第二行：状态标签（实验版/已归档/推荐/专属），独立成行
            val hasTag = option.experimental || option.archived ||
                (!option.archived && (option.category == "recommended" || option.category == "exclusive"))
            if (hasTag) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (option.experimental) {
                        TagChip(appString(R.string.experimental), MaterialTheme.colorScheme.tertiary)
                    }
                    if (option.archived) {
                        TagChip(
                            appString(R.string.source_archived),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                        )
                    }
                    if (!option.archived && option.category == "recommended") {
                        TagChip(appString(R.string.category_recommended), MaterialTheme.colorScheme.primary)
                    }
                    if (!option.archived && option.category == "exclusive") {
                        TagChip(appString(R.string.category_exclusive), MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            if (option.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                // 简介可点击展开/收起：不截断信息，避免长简介（wrapper 发布说明等）被过滤
                var descExpanded by rememberSaveable(option.id) { mutableStateOf(false) }
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { descExpanded = !descExpanded }
                )
                if (!descExpanded && option.description.length > 50) {
                    Text(
                        appString(R.string.expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { descExpanded = true }
                            .padding(top = 2.dp)
                    )
                }
            }
            if (option.updatedHint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    option.updatedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
            if (option.preview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        option.preview.forEach { item ->
                            // 长行（如支持型号列表 610/612/...）默认 2 行截断，点击展开全文
                            var previewExpanded by rememberSaveable(option.id, item.text) {
                                mutableStateOf(false)
                            }
                            val longLine = item.text.length > 40
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (previewExpanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .then(
                                            if (longLine) Modifier.clickable {
                                                previewExpanded = !previewExpanded
                                            } else Modifier
                                        )
                                )
                                if (longLine && !previewExpanded) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        appString(R.string.expand),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { previewExpanded = true }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 来源卡片/列表用的小标签 chip */
@Composable
private fun TagChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun DownloadPanelHeader(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
// ── 游戏支持能力评估（驱动实测 → 转译游戏分级）────────────────────
data class GameSupportEval(val title: String, val level: String, val note: String)

/** 基于实测驱动类型 + 设备 GPU + 实测扩展列表，评估 Windows(D3D) 与 Switch 游戏转译支持等级。
 *  level: 基本能跑 / 可能能跑（缺条件）/ 跑不了
 *  依据：DXVK 按 D3D API 的成熟度（D3D8-11 成熟、D3D12 仍多问题）、
 *        PanVK/Turnip 驱动成熟度、Vulkan API 版本门槛，
 *        以及驱动实测上报的关键扩展覆盖（swapchain/maintenance/timeline/dynamic_rendering 等）。 */
internal val CRITICAL_DXVK_EXTS = setOf(
    "VK_KHR_swapchain", "VK_KHR_maintenance1", "VK_KHR_maintenance2",
    "VK_KHR_maintenance3", "VK_KHR_timeline_semaphore", "VK_KHR_dynamic_rendering",
    "VK_EXT_memory_budget", "VK_KHR_descriptor_indexing", "VK_KHR_16bit_storage"
)

/** 关键扩展覆盖度：驱动实测扩展列表中命中关键集的占比 */
internal fun criticalExtCoverage(extensions: List<String>): Float {
    if (extensions.isEmpty()) return 0f
    val set = extensions.toSet()
    return CRITICAL_DXVK_EXTS.count { it in set }.toFloat() / CRITICAL_DXVK_EXTS.size
}

fun assessGameSupport(
    driverType: String,          // turnip / panvk / qualcomm / system
    isAdrenoDevice: Boolean,
    isMaliDevice: Boolean,
    extensions: List<String>,
    apiVersion: String
): List<GameSupportEval> {
    val out = mutableListOf<GameSupportEval>()
    val vk13 = apiVersion.startsWith("1.3") || apiVersion.startsWith("1.4")
    val coverage = criticalExtCoverage(extensions)
    // 扩展充足：关键集覆盖 >= 70% 且必备 swapchain/maintenance1 齐全
    val rich = coverage >= 0.7f &&
        extensions.contains("VK_KHR_swapchain") && extensions.contains("VK_KHR_maintenance1")
    val coverageNote = "关键扩展覆盖 ${(coverage * 100).toInt()}%（DXVK 依赖集）"
    when (driverType) {
        "turnip" -> {
            out += GameSupportEval("Windows D3D8/9（DXVK）", "基本能跑", "DXVK 转译成熟，社区配置量大；具体版本以驱动源为准")
            out += if (rich) GameSupportEval("Windows D3D10/11（DXVK）", "基本能跑", "Turnip 扩展齐全，多数 3A 可玩（$coverageNote）")
                else GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "关键扩展覆盖不足（$coverageNote）")
            out += GameSupportEval(
                "Windows D3D12（VKD3D）", "可能能跑",
                if (vk13) "Vulkan 1.3 支持，转译问题仍多" else "仅 Vulkan 1.2，黑屏概率更高"
            )
            out += if (isAdrenoDevice) GameSupportEval("Switch 游戏（Eden/Citron）", if (vk13 && rich) "基本能跑" else "可能能跑", "Eden 对 Adreno 优化，需完整 Vulkan 特性")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "GPU 非 Adreno，兼容性以实测为准")
        }
        "panvk" -> {
            out += GameSupportEval("Windows D3D8/9（DXVK）", "可能能跑", "PanVK 在开发中，部分游戏异常")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "依赖 PanVK 成熟度，建议社区配置")
            out += GameSupportEval("Windows D3D12（VKD3D）", "跑不了", "PanVK 缺 D3D12 所需特性，暂不建议")
            out += if (isMaliDevice) GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "PanVK 对 Mali 逐步支持，黑屏/贴图错误可能")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "非 Mali 设备装 PanVK 意义有限")
        }
        "radv" -> { // Mesa RADV / 三星 Xclipse 实验性驱动
            out += GameSupportEval("Windows D3D8/9（DXVK）", "可能能跑", "Xclipse 驱动实验性，DXVK 需实测")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "RADV 移植起步阶段，社区配置稀缺")
            out += GameSupportEval("Windows D3D12（VKD3D）", "跑不了", "实验性驱动暂不建议 D3D12")
            out += GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "Xclipse 需专用 WinXclipse 分支，兼容性以实测为准")
        }
        else -> { // qualcomm 系统驱动
            out += GameSupportEval("Windows D3D8/9（DXVK）", "基本能跑", "系统驱动稳定，DXVK 成熟")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "基本能跑", "高通官方 Vulkan 完整")
            out += GameSupportEval("Windows D3D12（VKD3D）", "可能能跑", "Vulkan 1.3 支持则 D3D12 可尝试")
            out += if (isAdrenoDevice) GameSupportEval("Switch 游戏（Eden/Citron）", "基本能跑", "系统驱动 + Eden 优化组合")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "视设备 GPU 支持而定")
        }
    }
    return out
}

@Composable
fun DriverCardDynamic(
title: String,
    description: String,
    icon: ImageVector,
    sources: List<QualcommSource>,
    currentSourceId: String,
    onSourceChange: (String) -> Unit,
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    updatedAtMap: Map<String, Long> = emptyMap(),
    remoteStars: Map<String, Long> = emptyMap(),
    expandTrigger: String? = null
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    val profileCtx = LocalContext.current
    val profile = remember(currentSource?.id) {
        SourceProfiles.ofSource(profileCtx, currentSource?.id)
    }
    // 高通区块默认折叠：与 Turnip/PanVK 大区一致
    var expanded by rememberSaveable(selectionKey) { mutableStateOf(false) }
    // I1：外部触发展开（切换来源后自动打开对应文件列表）
    val lastTrigger = remember { mutableStateOf("") }
    LaunchedEffect(selectionKey, expandTrigger) {
        if (expandTrigger != null && expandTrigger != lastTrigger.value) {
            expanded = true
            lastTrigger.value = expandTrigger
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val srcAvatar = currentSource?.apiUrl?.let { githubOwnerAvatar(it) }
                RepoAvatar(
                    avatarUrl = srcAvatar,
                    fallbackName = currentSource?.name ?: title
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (!expanded) {
                        val fitLine = profile?.fit?.takeIf { it.isNotBlank() }
                            ?: currentSource?.description
                        if (!fitLine.isNullOrBlank()) {
                            Text(
                                fitLine,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (expanded) {
                        // I2：简介可点击展开/收起，解决长简介截断无法查看全文
                        var descExpanded by rememberSaveable(selectionKey + ":desc") { mutableStateOf(false) }
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { descExpanded = !descExpanded }
                                .padding(top = 4.dp)
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                }
                if (!expanded && currentSource != null) {
                    CollapsedSourceHint(
                        name = currentSource.name,
                        latestPublishedAt = releases.firstOrNull()?.publishedAt
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            UsageBlock(profile)

            if (currentSource != null) {
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentArchived = currentSource.archived,
                    currentCategory = "general",
                    avatarUrl = currentSource?.apiUrl?.let { githubOwnerAvatar(it) },
                    currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                        .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            archived = source.archived,
                            category = "general",
                            updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                            preview = buildQualcommPreview(source, remoteStars),
                            avatarUrl = source.apiUrl?.let { githubOwnerAvatar(it) }
                        )
                    },
                    onSelected = { sourceId ->
                        if (sourceId != currentSourceId) onSourceChange(sourceId)
                    }
                )
            }
            DriverReleasePicker(
                releases = releases,
                selectionKey = selectionKey,
                onDownload = onDownload,
                buttonLabel = appString(R.string.download_type, title)
            )
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReleaseChangelog(release: GithubRelease?) {
    val notes = remember(release?.tagName, release?.body) {
        release?.body?.let { cleanReleaseNotes(it) }.orEmpty()
    }
    if (notes.isBlank()) return
    // R4：官方日志按 Markdown 段落（## 标题）分类，用户自选感兴趣的段落查看
    val sections = remember(release?.tagName, notes) { splitChangelogSections(notes) }
    var selectedSection by remember(release?.tagName) { mutableStateOf(0) }
    var expanded by remember(release?.tagName) { mutableStateOf(false) }
    var showTranslated by remember(release?.tagName) { mutableStateOf(false) }
    var translated by remember(release?.tagName) { mutableStateOf<String?>(null) }
    var translating by remember(release?.tagName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.changelog),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (expanded) {
                    // 中/英切换按钮（在线翻译，失败回退原文）
                    TextButton(
                        onClick = {
                            if (showTranslated && translated != null) {
                                showTranslated = false
                            } else if (translated != null) {
                                showTranslated = true
                            } else if (!translating) {
                                translating = true
                                scope.launch {
                                    val result = translateReleaseNotes(
                                        if (sections.size > 1) sections.getOrNull(selectedSection)?.second ?: notes else notes
                                    )
                                    translated = result
                                    showTranslated = result != null
                                    translating = false
                                }
                            }
                        },
                        enabled = !translating
                    ) {
                        if (translating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (showTranslated) appString(R.string.show_original)
                                else appString(R.string.translate_changelog),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                // R4：段落分类自选——多段落日志按标题拆分，顶部 chips 选择
                if (sections.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sections.forEachIndexed { idx, sec ->
                            FilterChip(
                                selected = idx == selectedSection,
                                onClick = {
                                    selectedSection = idx
                                    translated = null
                                    showTranslated = false
                                },
                                label = {
                                    // 章节 chip 只能 1 行：chip 会跟着文字撑宽，
                                    // 多行会把整排筛选挤到换行。章节名是「新增/修复」这类短词
                                    Text(
                                        sec.first ?: appString(R.string.changelog_section_body),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val currentText = if (sections.size > 1) sections.getOrNull(selectedSection)?.second ?: notes else notes
                val displayText = if (showTranslated) translated ?: currentText else currentText
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * GitHub release 正文是 Markdown，这里不引入完整 Markdown 渲染库，
 * 只剥离常见标记（标题/粗体/代码/链接/图片/引用/列表前缀），保留纯文本与换行。
 */
internal fun cleanReleaseNotes(raw: String): String = decodeHtmlEntities(stripHtmlForLog(raw))
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("!\\[([^]]*)]\\(\\s*[^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\(\\s*[^)]*\\)"), "$1")
    .replace(Regex("(?s)(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
    .replace(Regex("(?m)^\\s*>\\s?"), "")
    .replace(Regex("[ \\t]+\n"), "\n")
    .trim()

/** GitHub 自动生成的章节名：剥掉 `##` 之后就是这些词，拿它当摘要等于什么都没说 */
private val RELEASE_BOILERPLATE = setOf(
    "what's changed", "whats changed", "new contributors", "full changelog",
    "changelog", "download", "installation", "install", "notes",
    "other changes", "changes", "new features", "bug fixes", "breaking changes",
    "deprecations", "documentation", "misc", "internal", "assets",
)

/**
 * 纯哈希串：GitHub 上传图片的 alt 常是 UUID 或附件 ID
 * （`![67180280-3c47-4c2e-a9a0-a086a57949dc](…)`），剥掉图片语法后只剩这串，
 * 拿它当摘要等于给用户看一串随机字符。判据是「字母数字多、但全是十六进制、没有空格」。
 */
private fun looksLikeHashLine(line: String): Boolean {
    val alnum = line.filter { it.isLetterOrDigit() }
    return alnum.length >= 20 && alnum.all { it in "0123456789abcdefABCDEF" }
}

/**
 * 动态列表的一句话摘要。
 *
 * 直接拿 release 正文第一行是错的：325 条有正文的项目里 137 条（42%）第一行是
 * `# 版本号`、`## New Contributors`、`<p align="center">`、`> [!NOTE]`、`![uuid](url)`。
 * 用户在动态列表看到的就是 `<h3>⚠️Important Release Candidate...` 这种原文，
 * 无法判断这一版到底改了什么。
 *
 * 做法：先按日志清洗规则剥标记，再取第一条「有实质内容」的行——
 * 字母数字够多（≥6，排除 `---`、`· ` 这种残留），且不是纯哈希串。
 */
internal fun releaseDigest(body: String, maxLen: Int = 300): String {
    val lines = cleanReleaseNotes(body).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    // 兜底故意不回到 lines.first()：有些项目的正文只有下载按钮和图片，
    // 过滤完一行不剩，回落到第一行又是一串 UUID 或残留标签，等于没修。
    // 这种情况返回空串，由调用方显示「这一版没写更新说明」。
    val pick = lines.firstOrNull { line ->
        line.count { it.isLetterOrDigit() } >= 6 &&
            !looksLikeHashLine(line) &&
            line.lowercase().trimEnd(':') !in RELEASE_BOILERPLATE
    }.orEmpty()
    // 摘要走的是 2 行定高布局，120 字已经超了；这里只防单行超长拖慢排版，
    // 真正的截断交给 Text 的 maxLines，避免在词中间硬切。
    return if (pick.length <= maxLen) pick else pick.take(maxLen).trimEnd() + "…"
}

/** R4：官方日志按 Markdown 标题段落拆分，返回 (段落标题, 段落正文) 列表 */
private fun splitChangelogSections(text: String): List<Pair<String?, String>> {
    val titleRe = Regex("^\\s*(#{1,6})\\s+(.*)$")
    val sections = mutableListOf<Pair<String?, String>>()
    var curTitle: String? = null
    val curBody = StringBuilder()
    for (line in text.lines()) {
        val m = titleRe.find(line)
        if (m != null) {
            if (curBody.isNotBlank() || sections.isNotEmpty()) {
                sections.add(curTitle to curBody.toString().trim())
            }
            curTitle = m.groupValues[2].trim()
            curBody.setLength(0)
        } else {
            if (line.isNotBlank()) {
                curBody.append(line).append("\n")
            }
        }
    }
    if (curBody.isNotBlank() || sections.isEmpty()) {
        sections.add(curTitle to curBody.toString().trim())
    }
    return sections.filter { it.second.isNotBlank() }.ifEmpty { listOf(null to text) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriverReleasePicker(
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    buttonLabel: String
) {
    val context = LocalContext.current
    val selectVersionFileMessage = appString(R.string.select_version_file)
    var expandedAsset by remember { mutableStateOf(false) }
    var selectedTag by rememberSaveable(selectionKey) {
        mutableStateOf(SettingsManager.getSelectedReleaseTag(selectionKey))
    }

    val latestRelease = releases.firstOrNull()
    val selectedRelease = remember(releases, selectedTag) {
        releases.firstOrNull { it.tagName == selectedTag } ?: releases.firstOrNull()
    }

    // If a previously saved release disappeared, gracefully fall back to the
    // newest available one. User choices are never reset just by navigating.
    LaunchedEffect(selectionKey, releases) {
        val savedTag = SettingsManager.getSelectedReleaseTag(selectionKey)
        val resolved = releases.firstOrNull { it.tagName == savedTag } ?: releases.firstOrNull()
        if (resolved != null && selectedTag != resolved.tagName) {
            selectedTag = resolved.tagName
            SettingsManager.setSelectedReleaseTag(selectionKey, resolved.tagName)
        }
    }

    val currentReleaseTag = selectedRelease?.tagName.orEmpty()
    var selectedAssetName by rememberSaveable(selectionKey, currentReleaseTag) {
        mutableStateOf(
            if (currentReleaseTag.isNotEmpty()) {
                SettingsManager.getSelectedAssetName(selectionKey, currentReleaseTag)
            } else null
        )
    }
    val selectedAsset = remember(selectedRelease, selectedAssetName) {
        selectedRelease?.assets?.firstOrNull { it.name == selectedAssetName }
            ?: selectedRelease?.assets?.firstOrNull()
    }

    // 已下载态：文件存在则按钮置灰显示"已下载"（下载路径 Driver/<subPath>）
    val downloaded = remember(selectedRelease, selectedAsset) {
        val release = selectedRelease
        val asset = selectedAsset
        if (release != null && asset != null) {
            val desired = sanitizeFileName("${release.tagName}_${asset.name}")
            fileExistsOnDisk(context, desired, "Drivers")
        } else false
    }

    if (releases.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                appString(R.string.no_compatible_releases),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = appString(R.string.version),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${releases.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Divider(modifier = Modifier.weight(1f))
    }

    // 版本双列网格：每个版本一张卡片，点击选中，选中高亮
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            releases.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { release ->
                DriverVersionCard(
                    release = release,
                    isSelected = release.tagName == selectedRelease?.tagName,
                    isLatest = release.tagName == latestRelease?.tagName,
                    onClick = {
                        selectedTag = release.tagName
                        SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                    }
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            releases.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { release ->
                DriverVersionCard(
                    release = release,
                    isSelected = release.tagName == selectedRelease?.tagName,
                    isLatest = release.tagName == latestRelease?.tagName,
                    onClick = {
                        selectedTag = release.tagName
                        SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                    }
                )
            }
        }
    }

    ReleaseChangelog(selectedRelease)

    if ((selectedRelease?.assets?.size ?: 0) >= 1) {
        ExposedDropdownMenuBox(
            expanded = expandedAsset,
            onExpandedChange = { expandedAsset = !expandedAsset }
        ) {
            OutlinedTextField(
                value = selectedAsset?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    if ((selectedRelease?.assets?.size ?: 0) > 1) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAsset)
                    }
                },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(appString(R.string.file)) },
                shape = RoundedCornerShape(18.dp),
                maxLines = 2
            )
            if ((selectedRelease?.assets?.size ?: 0) > 1) {
                ExposedDropdownMenu(
                    expanded = expandedAsset,
                    onDismissRequest = { expandedAsset = false }
                ) {
                    selectedRelease?.assets?.forEach { asset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    // 下拉里同样要能分辨 ABI：2 行只够看到前缀
                                    LongFieldText(
                                        asset.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 3
                                    )
                                    Text(
                                        formatBytes(asset.sizeBytes) +
                                            assetArchLabel(asset.name).let { if (it.isBlank()) "" else " · $it" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedAssetName = asset.name
                                selectedRelease.tagName.let { tag ->
                                    SettingsManager.setSelectedAssetName(selectionKey, tag, asset.name)
                                }
                                expandedAsset = false
                            }
                        )
                    }
                }
            }
        }
    }

    // 文件格式提示：按扩展名判断是否为模拟器标准驱动包（zip/adpkg）
    val assetName = selectedAsset?.name.orEmpty()
    val assetExt = assetName.substringAfterLast('.', "").lowercase()
    val standardPkg = assetExt == "zip" || assetExt == "adpkg"
    if (assetName.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (standardPkg) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    if (standardPkg) appString(R.string.file_format_standard, assetExt.uppercase())
                    else appString(R.string.file_format_special, assetExt.uppercase()),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (standardPkg) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = {
            val release = selectedRelease
            val asset = selectedAsset
            if (release != null && asset != null) {
                // Also persist the automatically selected first asset. This makes
                // the exact choice stable even when a release has multiple files.
                SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                SettingsManager.setSelectedAssetName(selectionKey, release.tagName, asset.name)
                onDownload(release, asset)
            } else {
                Toast.makeText(context, selectVersionFileMessage, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
        enabled = !downloaded
    ) {
        if (downloaded) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appString(R.string.already_downloaded))
        } else {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(buttonLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 组件版本文件大小：清单不含 size，展开版本列表时 HEAD 懒加载（结果全局缓存） */
@Composable
private fun ComponentSizeText(component: Component) {
    var sizeBytes by remember(component.remoteUrl) { mutableStateOf(component.sizeBytes) }
    LaunchedEffect(component.remoteUrl) {
        if (sizeBytes == null) {
            sizeBytes = fetchRemoteSizeBytes(component.remoteUrl)
        }
    }
    val bytes = sizeBytes
    if (bytes != null && bytes > 0) {
        val text = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else "%.0f KB".format(bytes / 1024.0)
        Text(
            " · $text",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 模拟器 APK 大小：HEAD 懒加载，格式化为可读文本后回调 */
@Composable
private fun EmulatorApkSize(apkUrl: String, content: @Composable (String) -> Unit) {
    var sizeText by remember(apkUrl) { mutableStateOf("") }
    LaunchedEffect(apkUrl) {
        if (sizeText.isBlank()) {
            val bytes = fetchRemoteSizeBytes(apkUrl)
            if (bytes != null && bytes > 0) {
                sizeText = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
                else "%.0f KB".format(bytes / 1024.0)
            }
        }
    }
    content(sizeText)
}

/** wrapper 清单区块（winlator-contents wrappers.json）：每个条目一张卡，
 *  简介为作者维护的发布说明（自动读取，不依赖 release 标签顺序），可折叠展开。 */
@Composable
private fun WrapperCatalogSection(
    items: List<WrapperItem>,
    onDownload: (WrapperItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            var descExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
            var useZh by rememberSaveable(item.id + ":zh") { mutableStateOf(true) }
            val displayName = if (useZh) wrapperNameZh(item.name) else item.name
            val displayDesc = if (useZh) wrapperDescZh(item.description) else item.description
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 与其他分区卡片一致的作者头像（author 为 GitHub 用户名时显示头像，否则首字母兜底）
                        ProjectAvatar(
                            logoUrl = "",
                            owner = item.author,
                            fallbackName = item.name,
                            size = 36.dp,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.version > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    "v${item.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // 译文/原文切换（记忆到条目级）
                        Text(
                            if (useZh) "译文" else "原文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { useZh = !useZh }
                                .padding(start = 6.dp)
                        )
                    }
                    if (!useZh && wrapperNameZh(item.name) != item.name) {
                        Text(
                            wrapperNameZh(item.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.author.isNotBlank() || item.license.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(
                                item.author.takeIf { it.isNotBlank() },
                                item.license.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            displayDesc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                        if (!descExpanded && displayDesc.length > 60) {
                            Text(
                                appString(R.string.expand),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { descExpanded = true }
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LongFieldText(
                            item.url.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.fileSizeBytes > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatBytes(item.fileSizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onDownload(item) },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(appString(R.string.download), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** 驱动版本双列网格卡片：版本号 + 日期 + 文件数 + 大小 + latest 标签 */
@Composable
private fun DriverVersionCard(
    release: GithubRelease,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit
) {
    val totalSize = release.assets.sumOf { it.sizeBytes }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LongFieldText(
                    release.tagName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                if (isLatest) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${release.assets.size} ${appString(R.string.files)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (totalSize > 0) {
                    Text(
                        text = formatBytes(totalSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 发布时间：GitHub release 的 published_at 转为相对时间（x 天前）
            if (release.publishedAt.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatRelativeTime(release.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
/** 组件版本双列网格卡片：版本号 + 来源 + 大小 + latest 标签 */
@Composable
internal fun ComponentVersionCard(
    component: Component,
    sourceName: String,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = component.verName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLatest) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ComponentSizeText(component)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 组件分支识别：从 verName 解析主线/Sarek/GPLAsync/ARM64EC 等变体 */
internal fun componentBranch(verName: String): String {
    val n = verName.lowercase()
    return when {
        n.contains("sarek") -> "Sarek"
        n.contains("gplasync") || n.contains("async") -> "GPLAsync"
        n.contains("arm64ec") -> "ARM64EC"
        else -> componentBranchMainlineLabel
    }
}

/** 组件"主线"分支的显示名 */
internal const val componentBranchMainlineLabel = "主线"
