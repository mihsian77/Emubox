package com.emubox.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emubox.app.ui.template.PageTitleRow
import com.emubox.app.ui.template.ResultCountRow
import com.emubox.app.ui.theme.Fusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*


@Composable
fun RuntimeLibraryScreen(
    onDownload: (RuntimeComponent) -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    // null = 正在读清单；Failed = 拉不到；Ok = 拿到列表。
    // 以前失败返回空列表，界面把它画成「一个组件都没有」，重试按钮无处可放。
    var result by remember { mutableStateOf<RuntimeLoadResult?>(null) }
    // 重试令牌：LaunchedEffect 的 key。原来重试只改状态、不换 key，
    // 副作用不会重跑，点"重试"后永远停在转圈。
    var reloadToken by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAutoApplied by remember { mutableStateOf(false) }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(appString(R.string.runtime_guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appString(R.string.runtime_guide_intro), style = MaterialTheme.typography.bodySmall)
                    Text(appString(R.string.runtime_guide_exe), style = MaterialTheme.typography.bodySmall)
                    Text(appString(R.string.runtime_guide_dll), style = MaterialTheme.typography.bodySmall)
                    Text(appString(R.string.runtime_guide_font), style = MaterialTheme.typography.bodySmall)
                    Text(appString(R.string.runtime_guide_recommend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) { Text(appString(R.string.ok)) }
            }
        )
    }

    LaunchedEffect(reloadToken) {
        result = null
        result = withContext(Dispatchers.IO) { RuntimeLibraryRepository.load() }
    }

    val all = (result as? RuntimeLoadResult.Ok)?.components.orEmpty()

    // 搜索过滤：匹配名称 / 中文用途 / 英文描述
    val filtered = remember(all, searchQuery) {
        if (searchQuery.isBlank()) all
        else all.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.chineseHint.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    // 推荐置顶：Winlator 高频必备组件（按名称前缀匹配，不存在则跳过）
    val recommendedNames = listOf(
        "vcredist2022", "vc2022", "vcredist2019", "dotnet48", "dotnet46",
        "dxvk", "vkd3d", "lavfilters", "cjkfonts", "sourcehan",
        "directx9", "d3dx943", "physx", "xinput", "vc2019"
    )
    val recommended = remember(all) {
        recommendedNames.mapNotNull { id ->
            all.firstOrNull {
                it.name.equals(id, ignoreCase = true) ||
                    it.name.lowercase().startsWith(id.lowercase())
            }
        }.distinctBy { it.name }
    }
    val recommendedSet = remember(recommended) { recommended.mapTo(HashSet()) { it.name } }

    // 「自动生效」的条目没有文件可下，混在两列里只会稀释可操作项，收到页尾折叠组
    val autoApplied = remember(filtered) { filtered.filter { it.isAutoApplied } }
    val actionable = remember(filtered) { filtered.filterNot { it.isAutoApplied } }
    val grouped = remember(actionable) { RuntimeLibraryRepository.groupByCategory(actionable) }

    // 下载活动表是 SnapshotStateMap：读一次 keys，当某个组件下完（key 被移除）时，
    // 卡片上的「已下载」才会跟着翻；不读它就只能靠重进页面。
    val activeKeys by remember { derivedStateOf { DownloadsManager.activeDownloads.keys.toSet() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "title") {
            PageTitleRow(
                title = appString(R.string.runtime_title),
                subtitle = appString(R.string.runtime_subtitle),
                trailing = {
                    IconButton(onClick = { showGuide = true }) {
                        Icon(Icons.Default.Info, contentDescription = appString(R.string.runtime_guide_title))
                    }
                },
            )
        }

        item(key = "search") {
            FusionSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = appString(R.string.runtime_search_hint)
            )
        }

        // 加载中不报数：0 会被读成「一个都没有」
        if (result is RuntimeLoadResult.Ok) {
            item(key = "count") {
                ResultCountRow(
                    count = filtered.size,
                    label = { appString(R.string.runtime_result_count, it) },
                )
            }
        }

        when (val r = result) {
            null -> item(key = "loading") {
                // 只转圈不给字，整屏看起来像白屏/数据缺失；补一句在做什么
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        appString(R.string.runtime_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is RuntimeLoadResult.Failed -> item(key = "error") {
                RuntimeEmptyState(onRetry = { reloadToken++ })
            }

            is RuntimeLoadResult.Ok -> {
                // 推荐条不再单独起标题：它本来就在首屏第一位，标题只是多占一层
                if (searchQuery.isBlank() && recommended.isNotEmpty()) {
                    item(key = "recommended") {
                        RuntimeRecommendedStrip(components = recommended, onDownload = onDownload)
                    }
                }

                grouped.forEach { (category, list) ->
                    item(key = "cat-${category.name}") {
                        RuntimeCategorySection(
                            category = category,
                            components = list,
                            allComponents = r.components,
                            recommendedNames = recommendedSet,
                            activeKeys = activeKeys,
                            onDownload = onDownload
                        )
                    }
                }

                if (autoApplied.isNotEmpty()) {
                    item(key = "auto-applied") {
                        AutoAppliedSection(
                            components = autoApplied,
                            expanded = showAutoApplied,
                            activeKeys = activeKeys,
                            onToggle = { showAutoApplied = !showAutoApplied }
                        )
                    }
                }
            }
        }

        // 数据来源与版权免责声明（资讯已上移到一级「资讯」分区）
        item(key = "disclaimer") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = appString(R.string.runtime_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 「继续看」挪到页尾：原来压在标题下面，首屏第一眼看到的是别的分区入口
        item(key = "related") {
            RelatedPartitionRow(current = "runtime", onNavigate = onNavigate)
        }
    }
}

/** 运行库推荐条：横向滑动小卡，高频必备组件一键下载 */
@Composable
private fun RuntimeRecommendedStrip(
    components: List<RuntimeComponent>,
    onDownload: (RuntimeComponent) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(components, key = { it.name }) { comp ->
            Card(
                modifier = Modifier
                    .width(150.dp)
                    .clickable {
                        if (comp.isReady && comp.downloadUrl.isNotBlank()) onDownload(comp)
                    },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        comp.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        comp.chineseHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (comp.isReady && comp.downloadUrl.isNotBlank())
                            "${formatFileSize(comp.fileSize)} · ${comp.installTag}"
                        else comp.noDownloadReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeCategorySection(
    category: RuntimeCategory,
    components: List<RuntimeComponent>,
    allComponents: List<RuntimeComponent>,
    recommendedNames: Set<String>,
    activeKeys: Set<String>,
    onDownload: (RuntimeComponent) -> Unit
) {
    val context = LocalContext.current
    // 总包组件：递归下载其全部子组件；普通组件直接下载。
    // 子组件必须从全量清单里找——原来只在当前分类里翻，跨分类的依赖永远缺席，
    // 点「下载 N 个子组件」结果一个都没下。
    val byName = remember(allComponents) {
        allComponents.associateBy { it.name.lowercase() }
    }
    val handleDownload: (RuntimeComponent) -> Unit = { comp ->
        if (comp.isBundle) {
            val wanted = comp.dependencies.map { it.lowercase() }
            val children = wanted.mapNotNull { byName[it] }.filter { it.primaryFile != null }
            children.forEach { onDownload(it) }
            val missing = wanted.size - children.size
            val msg = if (missing > 0) {
                "${comp.name}：下载 ${children.size} 个子组件，另有 $missing 个不在当前清单里"
            } else {
                "${comp.name}：正在下载 ${children.size} 个子组件"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else {
            onDownload(comp)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appString(category.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${components.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        // 逐行铺、行内等高：原来两列各自堆叠，左 3 右 3 时右列会被拉到半屏空白，
        // 卡片高度也互相不齐。chunked(2) 后每行是一个 Row，卡片 fillMaxHeight 拉平。
        components.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { comp ->
                    RuntimeComponentCard(
                        component = comp,
                        isRecommended = comp.name in recommendedNames,
                        isActive = isComponentDownloading(comp, activeKeys),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onDownload = handleDownload
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 「自动生效」组：只有 set_windows / override_dll 这类动作，没有文件可下 */
@Composable
private fun AutoAppliedSection(
    components: List<RuntimeComponent>,
    expanded: Boolean,
    activeKeys: Set<String>,
    onToggle: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${appString(R.string.runtime_auto_applied)} · ${components.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            components.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { comp ->
                        RuntimeComponentCard(
                            component = comp,
                            isRecommended = false,
                            isActive = isComponentDownloading(comp, activeKeys),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onDownload = {}
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 这个组件此刻是否在下载队列里（只读 key 集合，进度变化不会拖着重绘） */
private fun isComponentDownloading(component: RuntimeComponent, activeKeys: Set<String>): Boolean {
    val f = component.primaryFile ?: return false
    val name = sanitizeFileName(f.rename.ifBlank { f.fileName })
    return activeDownloadKey(name, "Runtime/${component.category.dirName}") in activeKeys
}

@Composable
private fun RuntimeComponentCard(
    component: RuntimeComponent,
    isRecommended: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onDownload: (RuntimeComponent) -> Unit
) {
    val context = LocalContext.current
    val downloaded = rememberDownloaded(component, isActive)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = component.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!component.isReady && !component.isAutoApplied) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (component.status) {
                            "needs-upstream" -> appString(R.string.runtime_status_upstream)
                            "pending-manual" -> appString(R.string.runtime_status_pending)
                            else -> component.status
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                // 上游页入口：只跳转，不写任何仓库/搬运者名字。
                // 「来源·The412Banner 镜像托管」那种行既占一整行，又把搬运者误当成作者。
                if (component.sourceInfo.url.isNotBlank()) {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = appString(R.string.runtime_open_upstream),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(component.sourceInfo.url))
                                    )
                                }
                            },
                        tint = Fusion.OnSurfaceFaint
                    )
                }
            }

            if (component.chineseHint.isNotBlank()) {
                Text(
                    text = component.chineseHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 元信息压成一行：安装方式 · 提供方 · 大小。
            // 三段各占一个 Text 时，窄卡片里必然互相挤，最后一段被裁到卡片外。
            val meta = listOfNotNull(
                component.installTag.takeIf { it.isNotBlank() },
                component.provider.takeIf { it.isNotBlank() },
                component.fileSize.takeIf { it > 0L }?.let { formatFileSize(it) },
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Fusion.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isRecommended) {
                Text(
                    text = appString(R.string.runtime_in_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 总包徽章（仅总包组件）
            if (component.isBundle) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "${component.dependencies.size} 个子组件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f, fill = false))

            when {
                // 自动生效：配置项，没有文件要下。以前这里写「无需下载」，
                // 卡片上又标着「必需」，两句话读起来是矛盾的
                component.isAutoApplied -> {
                    FilledTonalButton(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        enabled = false
                    ) {
                        Text(
                            appString(R.string.runtime_no_download_needed),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                component.downloadUrl.isNotBlank() || component.isBundle -> {
                    FilledTonalButton(
                        onClick = { onDownload(component) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        enabled = !downloaded
                    ) {
                        if (downloaded) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                appString(R.string.already_downloaded),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (component.isBundle) {
                                    "${appString(R.string.download)} ${component.dependencies.size} ${appString(R.string.runtime_bundle_children)}"
                                } else {
                                    appString(R.string.download)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 社区清单基本不带摘要：明确标注「没校验」而不是让用户误以为已校验
                    if (!component.isBundle && component.downloadUrl.isNotBlank() &&
                        component.checksum.isBlank()
                    ) {
                        Text(
                            appString(R.string.runtime_checksum_missing),
                            style = MaterialTheme.typography.labelSmall,
                            color = Fusion.OnSurfaceFaint
                        )
                    }
                }

                else -> {
                    Text(
                        text = component.noDownloadReason.ifBlank { appString(R.string.runtime_no_download) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 已下载态。
 *
 * 两个改动：
 *  1. 查盘走 IO —— 原来在组合期直接 fileExistsOnDisk，131 张卡就是一屏 131 次主线程磁盘访问；
 *  2. 状态跟着下载队列变 —— 原来 remember(component) 只算一次，下载完按钮还是灰的，
 *     得退出页面再进来才会变。
 */
@Composable
private fun rememberDownloaded(component: RuntimeComponent, isActive: Boolean): Boolean {
    val context = LocalContext.current
    val prepared = remember(component) {
        val f = component.primaryFile ?: return@remember null
        val name = sanitizeFileName(f.rename.ifBlank { f.fileName })
        name to "Runtime/${component.category.dirName}"
    }
    var downloaded by remember(component) { mutableStateOf(false) }
    LaunchedEffect(prepared, isActive) {
        if (prepared == null || isActive) {
            downloaded = false
            return@LaunchedEffect
        }
        val (name, dir) = prepared
        downloaded = withContext(Dispatchers.IO) {
            DownloadsManager.existsCompleted(name, dir) || fileExistsOnDisk(context, name, dir)
        }
    }
    return downloaded
}

@Composable
private fun RuntimeEmptyState(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Fusion.OnSurfaceFaint
            )
            Text(
                text = appString(R.string.runtime_load_failed),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = appString(R.string.runtime_load_failed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onRetry) {
                Text(appString(R.string.retry))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
