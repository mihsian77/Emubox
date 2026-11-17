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


/** R5：来源级捆绑独立区块——列出全部组件源，一键下载各源最新一套文件。
 *  各源分发形态不同（release 仓库 / contents.json 清单），由
 *  DownloadUtils.downloadSourceBundle 按 manifestUrl 自动适配。 */
@Composable
private fun ComponentBundleSection(sources: List<ComponentSource>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busyIds = remember { mutableStateMapOf<String, Boolean>() }
    val results = remember { mutableStateMapOf<String, BundleResult>() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Fusion.GradientStart
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(appString(R.string.source_bundle_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        appString(R.string.source_bundle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            sources.forEach { source ->
                val busy = busyIds[source.id] == true
                val bundleDoneMsg = appString(R.string.source_bundle_done, source.name)
                val result = results[source.id]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = githubOwnerAvatar(source.manifestUrl),
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 最新整包信息：tag + 稳定/夜版徽标 + 资产数 + 发布时间
                        if (result != null && result.tag != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LongFieldText(
                                    result.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Fusion.GradientStart,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (result.prerelease) Fusion.SurfaceAlt.copy(alpha = 0.6f)
                                    else Fusion.GradientStart.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (result.prerelease) "夜版" else "稳定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (result.prerelease) Fusion.GradientStart else Fusion.GradientStart,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (result.assetCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${result.assetCount} 个文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // 下载结果汇总：成功/失败 + 失败项重试
                        if (result != null && (result.success.isNotEmpty() || result.failed.isNotEmpty())) {
                            if (result.failed.isEmpty()) {
                                Text(
                                    text = "已下载 ${result.success.size} 个文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Fusion.GradientStart
                                )
                            } else {
                                Text(
                                    text = "成功 ${result.success.size} / 失败 ${result.failed.size}，失败项：${result.failed.take(3).joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busyIds[source.id] = true
                            scope.launch {
                                try {
                                    results[source.id] = downloadSourceBundle(context, source.manifestUrl, source.name)
                                    val r = results[source.id]
                                    if (r != null && r.failed.isEmpty()) {
                                        Toast.makeText(context, bundleDoneMsg, Toast.LENGTH_LONG).show()
                                    } else if (r != null && r.failed.isNotEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "完成 ${r.success.size}，失败 ${r.failed.size} 个（可点击重试）",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "bundle failed", Toast.LENGTH_LONG).show()
                                } finally {
                                    busyIds[source.id] = false
                                }
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(appString(R.string.source_bundle_downloading), maxLines = 1)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (result != null && result.failed.isNotEmpty()) "重试" else appString(R.string.source_bundle_btn),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComponentSection(
    type: String,
    sources: List<ComponentSource>,
    currentSource: ComponentSource,
    components: List<Component>,
    selectionKey: String,
    updatedAtMap: Map<String, Long> = emptyMap(),
    onSourceChange: (String) -> Unit,
    onDownload: (Component) -> Unit
) {
    var selectedVersion by rememberSaveable(selectionKey) {
        mutableStateOf(SettingsManager.getSelectedComponentVersion(selectionKey))
    }
    val context = LocalContext.current
    val selectVersionMessage = appString(R.string.select_version)
    val latestComponent = components.firstOrNull()
    val selected = remember(components, selectedVersion) {
        components.firstOrNull { it.verName == selectedVersion } ?: components.firstOrNull()
    }

    // 已下载态：与 downloadComponent 相同命名/路径规则（Components/<type>/）
    val downloaded = remember(selected) {
        if (selected != null) {
            val fileName = sanitizeFileName(Uri.decode(selected.remoteUrl.substringAfterLast("/")))
            fileExistsOnDisk(context, fileName, "Components/$type")
        } else false
    }

    LaunchedEffect(selectionKey, components) {
        val saved = SettingsManager.getSelectedComponentVersion(selectionKey)
        val resolved = components.firstOrNull { it.verName == saved } ?: components.firstOrNull()
        if (resolved != null && selectedVersion != resolved.verName) {
            selectedVersion = resolved.verName
            SettingsManager.setSelectedComponentVersion(selectionKey, resolved.verName)
        }
    }

    // 折叠状态：默认收起，一屏可浏览全部类型
    var expanded by rememberSaveable(type) { mutableStateOf(false) }
    val profileCtx = LocalContext.current
    val typeProfile = remember(type) { SourceProfiles.ofComponentType(profileCtx, type) }
    val sourceProfile = remember(currentSource.id) {
        SourceProfiles.ofSource(profileCtx, currentSource.id)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // 折叠头：图标 + 类型名 + 版本数 + 当前版本 + 来源 + 展开箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    componentIcon(type),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(type, style = MaterialTheme.typography.titleMedium)
                    // 「给谁用」压到折叠态：一眼能判断这个组件要不要装
                    val fitLine = typeProfile?.fit?.takeIf { it.isNotBlank() }
                        ?: componentSubtitle(type)
                    Text(
                        fitLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Text(
                        buildString {
                            append("${components.size} ${appString(R.string.version)} · ${currentSource.name}")
                            val hint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) }
                            if (hint != null) append(" · ").append(hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 单行：副标题折成两行会把卡片撑高，且「25 天/前」这种断行很丑
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded) {
                    Text(
                        text = selected?.verName ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(0.55f)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DownloadPanelHeader(
                        icon = componentIcon(type),
                        title = type,
                        description = componentSubtitle(type)
                    )

                    // 组件类型通用说明 + 当前来源的补充说明（没有文案就不占位）
                    UsageBlock(typeProfile)
                    UsageBlock(sourceProfile)

                    SourcePickerCard(
                        title = appString(R.string.source_type, type),
                        currentName = currentSource.name,
                        currentDescription = currentSource.description,
                        currentExperimental = currentSource.experimental,
                        avatarUrl = githubOwnerAvatar(currentSource.manifestUrl),
                        currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                            .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                        options = sources.map { source ->
                            SourcePickerOption(
                                id = source.id,
                                name = source.name,
                                description = source.description,
                                experimental = source.experimental,
                                updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                    .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                                avatarUrl = githubOwnerAvatar(source.manifestUrl)
                            )
                        },
                        onSelected = onSourceChange
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = appString(R.string.version),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${components.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Divider(modifier = Modifier.weight(1f))
                    }

                    // 组件版本双列网格：按分支分组（主线 / Sarek / GPLAsync / ARM64EC）
                    // 修改原因：原版把 sarek/gplasync 等分支混在 DXVK 大类里，用户无法区分构建来源。
                    // 影响范围：组件区块展开后的版本区，不影响下载逻辑。
                    // 回滚方法：删除 componentBranch 与分组循环，恢复原双列逻辑。
                    val branchGroups = components.groupBy { componentBranch(it.verName) }
                    val branchOrder = listOf(
                        componentBranchMainlineLabel,
                        "Sarek", "GPLAsync", "ARM64EC"
                    )
                    val orderedBranches = branchOrder.filter { branchGroups.containsKey(it) } +
                        (branchGroups.keys - branchOrder.toSet()).toList()
                    orderedBranches.forEach { branch ->
                        val branchItems = branchGroups[branch].orEmpty()
                        // 分支标题（单个分支时无需标注）
                        if (orderedBranches.size > 1) {
                            Text(
                                branch,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                branchItems.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { component ->
                                    ComponentVersionCard(
                                        component = component,
                                        sourceName = currentSource.name,
                                        isSelected = component.verName == selected?.verName,
                                        isLatest = component.verName == latestComponent?.verName,
                                        onClick = {
                                            selectedVersion = component.verName
                                            SettingsManager.setSelectedComponentVersion(selectionKey, component.verName)
                                        }
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                branchItems.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { component ->
                                    ComponentVersionCard(
                                        component = component,
                                        sourceName = currentSource.name,
                                        isSelected = component.verName == selected?.verName,
                                        isLatest = component.verName == latestComponent?.verName,
                                        onClick = {
                                            selectedVersion = component.verName
                                            SettingsManager.setSelectedComponentVersion(selectionKey, component.verName)
                                        }
                                    )
                                }
                            }
                        }
                        if (branchItems.isNotEmpty() && branch != orderedBranches.last()) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // 组件官方更新日志：从选中版本的 release 地址反查正文，支持在线翻译
                    var compLog by remember(selectionKey, selected?.verName) { mutableStateOf<String?>(null) }
                    var compLogLoading by remember(selectionKey, selected?.verName) { mutableStateOf(false) }
                    LaunchedEffect(selectionKey, selected?.verName) {
                        compLog = null
                        if (selected != null) {
                            compLogLoading = true
                            compLog = fetchComponentReleaseNotes(selected.remoteUrl)
                            compLogLoading = false
                        }
                    }
                    if (compLogLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appString(R.string.component_log_loading),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (compLog != null) {
                        ReleaseChangelog(
                            GithubRelease(
                                tagName = selected?.verName.orEmpty(),
                                name = selected?.verName.orEmpty(),
                                assets = emptyList(),
                                body = compLog.orEmpty()
                            )
                        )
                    }

                    Button(
                        onClick = {
                            selected?.let {
                                SettingsManager.setSelectedComponentVersion(selectionKey, it.verName)
                                onDownload(it)
                            } ?: Toast.makeText(context, selectVersionMessage, Toast.LENGTH_SHORT).show()
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
                            Text(appString(R.string.download_type, type))
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 5 Tab 导航：设备 / 驱动 / 组件 / 下载 / 设置
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 组件 Tab：仅展示组件专区（每个类型一个独立区块）。
 * 从 DriverHubScreen 拆分而来，内部管理组件源选择状态。
 */
@Composable
fun ComponentScreen(
    modifier: Modifier = Modifier,
    componentSources: List<ComponentSource>,
    componentCatalogs: Map<String, Map<String, List<Component>>>,
    componentSourceUpdatedAt: Map<String, Long> = emptyMap(),
    onDownloadComponent: (Component) -> Unit,
    onOpenGuide: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val preferredComponentOrder = listOf("Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D", "D7VK")
    // Turnip 组件（Rutabaga 等 WCP 构建）已移至驱动专区 Turnip 分区展示，组件区不再显示该类型
    val discoveredComponentTypes = componentCatalogs.values
        .flatMap { it.keys }
        .distinct()
        .filterNot { it in preferredComponentOrder || it.equals("Turnip", ignoreCase = true) }
        .sorted()
    val componentOrder = preferredComponentOrder + discoveredComponentTypes
    val componentSourceSelections = remember { mutableStateMapOf<String, String>() }
    var newsExpanded by remember { mutableStateOf(false) }
    var bundleExpanded by remember { mutableStateOf(false) }

    // 来源"综合最新最全"评分：类型覆盖数主导（×1000），版本总数次之。
    // 默认首推覆盖类型最全、版本最多的源；用户手动选择仍优先尊重。
    val sourceRichness = remember(componentSources, componentCatalogs) {
        componentSources.associate { s ->
            val catalog = componentCatalogs[s.id]
            val typeCount = catalog?.keys?.size ?: 0
            val versionCount = catalog?.values?.sumOf { it.size } ?: 0
            s.id to (typeCount * 1000 + versionCount)
        }
    }
    val sourcesByRichness = remember(componentSources, sourceRichness) {
        componentSources.sortedByDescending { sourceRichness[it.id] ?: 0 }
    }

    LaunchedEffect(componentSources, componentCatalogs) {
        componentOrder.forEach { type ->
            val saved = SettingsManager.getComponentSource(type)
            val candidates = sourcesByRichness.filter { source ->
                componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
            }
            val resolved = candidates.firstOrNull { it.id == saved } ?: candidates.firstOrNull()

            if (resolved != null) {
                componentSourceSelections[type] = resolved.id
                if (saved != resolved.id) SettingsManager.setComponentSource(type, resolved.id)
            }
        }
    }

    fun currentComponentSource(type: String): ComponentSource? {
        val selectedId = componentSourceSelections[type] ?: SettingsManager.getComponentSource(type)
        val candidates = sourcesByRichness.filter { source ->
            componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
        }
        return candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    }

    // LazyListScope 不是 @Composable 上下文，文案必须先在函数体取好
    val guideLabel = appString(R.string.component_guide)
    val componentRegionTitle = appString(R.string.component_region)
    val bundleTitle = appString(R.string.source_bundle_title)

    Column(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        listPageHeader(
            title = componentRegionTitle,
            trailing = {
                IconButton(onClick = onOpenGuide) {
                    Icon(Icons.Default.Info, contentDescription = guideLabel)
                }
            },
        )

        item(key = "component_related") {
            RelatedPartitionRow(current = "components", onNavigate = onNavigate)
        }

        // R5：来源级捆绑——次要功能，默认收起，别把组件分类挤出首屏
        item(key = "component_bundle_collapse") {
            CollapseRow(
                title = bundleTitle,
                expanded = bundleExpanded,
                onToggle = { bundleExpanded = !bundleExpanded },
            )
        }
        if (bundleExpanded) {
            item(key = "component_bundle_region") {
                ComponentBundleSection(sources = componentSources)
            }
        }


        var hasAnyComponent = false
        componentOrder.forEach { type ->
            val source = currentComponentSource(type)
            val list = source?.let { componentCatalogs[it.id]?.get(type).orEmpty() }.orEmpty()
            if (list.isNotEmpty() && source != null) {
                hasAnyComponent = true
                item(key = "component:$type") {
                    ComponentSection(
                        type = type,
                        sources = sourcesByRichness.filter { s ->
                            componentCatalogs[s.id]?.get(type).orEmpty().isNotEmpty()
                        },
                        currentSource = source,
                        components = list,
                        selectionKey = "component:$type:${source.id}",
                        updatedAtMap = componentSourceUpdatedAt,
                        onSourceChange = { sourceId ->
                            componentSourceSelections[type] = sourceId
                            SettingsManager.setComponentSource(type, sourceId)
                        },
                        onDownload = onDownloadComponent
                    )
                }
            }
        }

        if (!hasAnyComponent) {
            item(key = "component_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.no_components),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            appString(R.string.no_components_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "footer") {
            Text(
                text = appString(R.string.versions_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    }
}

// ── 运行库 Tab：Windows 运行库在线下载（VC++/.NET/解码器/游戏依赖/字体）──
