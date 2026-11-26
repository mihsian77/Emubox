package com.emubox.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var components by remember { mutableStateOf<List<RuntimeComponent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    // 重试令牌：LaunchedEffect 的 key。原来重试只改状态、不换 key，
    // 副作用不会重跑，点"重试"后永远停在转圈。
    var reloadToken by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(false) }

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
        isLoading = true
        loadError = false
        val result = withContext(Dispatchers.IO) { RuntimeLibraryRepository.load() }
        components = result
        isLoading = false
        loadError = result.isEmpty()
    }

    // 搜索过滤：匹配名称 / 中文用途 / 英文描述
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(components, searchQuery) {
        if (searchQuery.isBlank()) components
        else components.filter {
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
    val recommended = remember(components) {
        recommendedNames.mapNotNull { id ->
            components.firstOrNull {
                it.name.equals(id, ignoreCase = true) ||
                    it.name.lowercase().startsWith(id.lowercase())
            }
        }.distinctBy { it.name }
    }

    val grouped = remember(filtered) { RuntimeLibraryRepository.groupByCategory(filtered) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitleRow(
            title = appString(R.string.runtime_title),
            subtitle = appString(R.string.runtime_subtitle),
            trailing = {
                IconButton(onClick = { showGuide = true }) {
                    Icon(Icons.Default.Info, contentDescription = appString(R.string.runtime_guide_title))
                }
            },
        )

        RelatedPartitionRow(current = "runtime", onNavigate = onNavigate)

        // 搜索框
        FusionSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = appString(R.string.runtime_search_hint)
        )

        // 加载中不报数：0 会被读成「一个都没有」
        if (!isLoading && !loadError) {
            ResultCountRow(
                count = filtered.size,
                label = { appString(R.string.runtime_result_count, it) },
            )
        }

        if (isLoading) {
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
        } else if (loadError) {
            RuntimeEmptyState(onRetry = {
                // 换 key 触发上面那个 LaunchedEffect 真正重新拉一次
                reloadToken++
            })
        } else {
            // 推荐下载置顶区块（搜索时隐藏，聚焦搜索结果）
            if (searchQuery.isBlank() && recommended.isNotEmpty()) {
                Text(
                    text = appString(R.string.runtime_recommended),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                RuntimeRecommendedStrip(
                    components = recommended,
                    onDownload = onDownload
                )
            }
            grouped.forEach { (category, list) ->
                RuntimeCategorySection(
                    category = category,
                    components = list,
                    onDownload = onDownload
                )
            }
        }

        // 数据来源与版权免责声明（资讯已上移到一级「资讯」分区）
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
    onDownload: (RuntimeComponent) -> Unit
) {
    val context = LocalContext.current
    // 总包组件：递归下载其全部子组件；普通组件直接下载
    val handleDownload: (RuntimeComponent) -> Unit = { comp ->
        if (comp.isBundle) {
            val children = comp.dependencies.mapNotNull { dep ->
                components.firstOrNull { it.name.equals(dep, ignoreCase = true) }
            }.filter { it.primaryFile != null }
            children.forEach { onDownload(it) }
            Toast.makeText(
                context,
                "${comp.name}：正在下载 ${children.size} 个子组件",
                Toast.LENGTH_SHORT
            ).show()
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
                text = category.labelRes,
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
            Divider(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                components.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { comp ->
                    RuntimeComponentCard(component = comp, onDownload = handleDownload)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                components.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { comp ->
                    RuntimeComponentCard(component = comp, onDownload = handleDownload)
                }
            }
        }
    }
}

@Composable
private fun RuntimeComponentCard(
    component: RuntimeComponent,
    onDownload: (RuntimeComponent) -> Unit
) {
    val context = LocalContext.current
    // 已下载态：与 downloadRuntimeLibrary 相同命名/路径规则
    val downloaded = remember(component) {
        val f = component.primaryFile
        if (f != null) {
            val name = sanitizeFileName(f.rename.ifBlank { f.fileName })
            fileExistsOnDisk(context, name, "Runtime/${component.category.labelRes}")
        } else false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                if (!component.isReady) {
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

            // 安装方式子标签 + 来源 + 大小
            // 三段文字挤一行时，未加权的 Text 会互抢宽度，右列在窄屏被裁出卡片外。
            // 这里把它拆成两行：第一行只放「标签 + 提供方 + 大小」，来源单独一行。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (component.installTag.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = component.installTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (component.provider.isNotBlank()) {
                    Text(
                        text = component.provider,
                        style = MaterialTheme.typography.labelSmall,
                        // 原来是 outline（描边色 #373252），深色底上对比度只有 1.6:1，
                        // 整行等于隐形。文字用弱正文色，不给描边色打工。
                        color = Fusion.OnSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 大小先按自身宽度测量，剩余的才给提供方，窄屏不会互相挤掉
                        modifier = Modifier.weight(1f)
                    )
                }
                if (component.fileSize > 0) {
                    Text(
                        text = formatFileSize(component.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = Fusion.OnSurfaceFaint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (component.sourceInfo.url.isNotBlank()) {
                Text(
                    text = appString(R.string.runtime_source_from, component.sourceInfo.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(component.sourceInfo.url))
                                )
                            }
                        }
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
                Spacer(Modifier.height(2.dp))
            }

            if (component.downloadUrl.isNotBlank() || component.isBundle) {
                Spacer(Modifier.height(2.dp))
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
            } else {
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
