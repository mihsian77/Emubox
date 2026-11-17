package com.emubox.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*


/** internal 仅为截图测试可见（私有函数无法在测试里单独渲染，只能靠点击列表绕一圈）。 */
@Composable
internal fun EmulatorDetailDialog(
    initialEntry: EmulatorEntry,
    onDismiss: () -> Unit,
    onDownloadAsset: (url: String, name: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 首屏索引不含 releases：进详情时补拉该条目所在分片，拉到后整页刷新。
    // 补拉期间先用索引里的卡片字段渲染，不出现空白弹窗。
    var entry by remember(initialEntry.id) { mutableStateOf(initialEntry) }
    LaunchedEffect(initialEntry.id) {
        entry = EmulatorRegistryRepository.loadDetail(context, initialEntry)
    }
    val isArchive = entry.source == "community-archive"
    // 卡片上的「下载」给的也是这个链接，两处口径必须一致
    val canDownload = entry.latestApkUrl.isNotBlank() && !(entry.dead && !isArchive)
    var expandedTag by remember { mutableStateOf(entry.releases.firstOrNull()?.tag) }
    var translatedMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var translatingTag by remember { mutableStateOf<String?>(null) }
    var showVersionDiff by remember { mutableStateOf(false) }
    // EmuReady 社区数据：该模拟器在兼容性数据库的报告数（未命中静默隐藏）
    var emuReadyInfo by remember { mutableStateOf<EmuReadyEmulator?>(null) }
    var emuReadyLoading by remember { mutableStateOf(true) }
    LaunchedEffect(entry.name) {
        emuReadyLoading = true
        emuReadyInfo = withContext(Dispatchers.IO) { fetchEmuReadyEmulator(entry.name) }
        emuReadyLoading = false
    }

    if (showVersionDiff) {
        VersionDiffDialog(
            entry = entry,
            onDismiss = { showVersionDiff = false }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 780.dp)
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (entry.dead && !isArchive) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            appString(
                                R.string.emulator_dead_banner,
                                entry.deadReason.ifBlank { appString(R.string.emulator_dead_unknown) }
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 详情页是这个项目名的最后一站：这里再截断，全 App 就没有任何地方能看到全名。
                    // 实测有项目名（含括号后缀）超过 22sp 单行宽度，给两行兜住。
                    Text(
                        softBreakLong(entry.name),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 详情页是全宽，名次和平台标签直接跟在标题后面，和卡片口径一致
                    EmulatorRankBadge(entry.rank, Modifier.padding(start = 6.dp))
                    EmulatorHostBadge(entry.hosts, Modifier.padding(start = 6.dp))
                    if (entry.official) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                appString(R.string.emulator_badge_official),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    if (entry.channel.isNotBlank() && entry.channel != "stable") {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(
                                if (entry.channel == "prerelease") appString(R.string.emulator_channel_prerelease) else entry.channel,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                // 中文名/中文作品名：详情页放在标题下面一行，不抢标题的位置
                if (entry.nameZh.isNotBlank()) {
                    Text(
                        entry.nameZh,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 中文简介优先，上游原文兜底（理由同列表卡片）
                val blurb = entry.description.ifBlank { entry.summary }
                if (blurb.isNotBlank()) {
                    Text(
                        blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 卡片上的红字说明只给两行，长内容在列表里必然被截断。
                // 详情页完整展示，用户点进来能看到「这条为什么没有下载」。
                if (entry.note.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            entry.note,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                // EmuReady 社区数据入口（按模拟器名匹配，命中才显示）
                // 快照：emuReadyInfo 是状态委托，两次读取间可能被后台协程置空
                val erInfo = emuReadyInfo
                if (!emuReadyLoading && erInfo != null && erInfo.listingsCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.emuready.com/emulators"))
                                        )
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("EmuReady 社区数据", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "该模拟器在 EmuReady 有 ${erInfo.listingsCount} 条兼容性报告 · 点击查看推荐设置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        emulatorCategoryLabel(entry.category),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (entry.stars > 0) {
                        Text(
                            "★${entry.stars}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.downloads > 0) {
                        Text(
                            "⬇ ${emulatorDownloadCount(entry.downloads)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                ArtifactSummaryBlock(entry = entry)
                Text(
                    appString(R.string.emulator_all_versions, entry.releases.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (entry.releases.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            // 上面刚说「共 48 个文件」，这里又说「暂无发布版本」会自相矛盾。
                            // 真实原因是版本明细在详情分片里、离线或分片没拉到，说清楚即可。
                            Text(
                                if (entry.artifacts.total > 0) "版本明细需联网加载，可先到仓库页查看具体文件"
                                else appString(R.string.emulator_no_releases),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (entry.repoLink.isNotBlank() && !(entry.dead && !isArchive)) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.repoLink)))
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(appString(R.string.emulator_repo), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (entry.releases.size >= 2) {
                    TextButton(
                        onClick = { showVersionDiff = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appString(R.string.version_diff_title), style = MaterialTheme.typography.labelMedium)
                    }
                }
                entry.releases.forEach { rel ->
                    val visibleAssets = rel.assets.filterNot { isNoiseAsset(it.name) }
                    val totalSize = visibleAssets.sumOf { it.size }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedTag = if (expandedTag == rel.tag) null else rel.tag
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    LongFieldText(
                                        rel.tag,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        buildString {
                                            append(shortDate(rel.date))
                                            if (visibleAssets.isNotEmpty()) {
                                                append(" · ${visibleAssets.size} 个文件")
                                                append(" · ${assetSizeText(totalSize)}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    if (expandedTag == rel.tag) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (expandedTag == rel.tag) {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(6.dp))
                                if (rel.body.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            appString(R.string.changelog),
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                val cur = translatedMap[rel.tag]
                                                if (cur != null) {
                                                    translatedMap = translatedMap + (rel.tag to null)
                                                } else if (translatingTag != rel.tag) {
                                                    translatingTag = rel.tag
                                                    scope.launch {
                                                        val result = translateReleaseNotes(rel.body)
                                                        translatedMap = translatedMap + (rel.tag to result)
                                                        translatingTag = null
                                                    }
                                                }
                                            },
                                            enabled = translatingTag != rel.tag
                                        ) {
                                            if (translatingTag == rel.tag) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text(
                                                    if (translatedMap[rel.tag] == null) appString(R.string.translate_changelog)
                                                    else appString(R.string.show_original),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                    // 日志原先固定 200dp 高、内部再套一层滚动：在弹窗整体滚动里
                                    // 这层嵌套滚动几乎拖不动，还把资产列表推到屏幕外。
                                    // 改成默认 4 行、点一下展开，进来的第一眼就能看到文件名。
                                    var logExpanded by remember(rel.tag) { mutableStateOf(false) }
                                    // 官方日志是 Markdown 原文：不清理会直接露出 "## 标题"、
                                    // "**粗体**" 星号和 [文字](链接) 裸链接（见 Termux 详情实测）
                                    val logText = cleanReleaseNotes(translatedMap[rel.tag] ?: rel.body)
                                    Text(
                                        logText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (logExpanded) Int.MAX_VALUE else 4,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { logExpanded = !logExpanded }
                                    )
                                    if (logText.count { it == '\n' } > 3) {
                                        Text(
                                            if (logExpanded) "收起日志" else "展开全部日志",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .clickable { logExpanded = !logExpanded }
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (visibleAssets.isEmpty()) {
                                    Text(
                                        appString(R.string.emulator_no_assets),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                visibleAssets.forEach { asset ->
                                    var showFullName by remember(asset.name) { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (asset.url.isNotBlank() && !(entry.dead && !isArchive)) {
                                                        onDownloadAsset(asset.url, asset.name)
                                                    }
                                                },
                                                onLongClick = { showFullName = true }
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            // 单行只剩前 30 来个字符，Termux 的 6 个包
                                            // 截断后完全一样，ABI 和 debug/release 看不到
                                            LongFieldText(
                                                asset.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3
                                            )
                                            Text(
                                                buildString {
                                                    append(assetSizeText(asset.size))
                                                    val arch = assetArchLabel(asset.name)
                                                    if (arch.isNotEmpty()) append(" · ").append(arch)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                assetTypeLabel(asset.name),
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { onDownloadAsset(asset.url, asset.name) },
                                            enabled = asset.url.isNotBlank() && !(entry.dead && !isArchive)
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (asset.url.isNotBlank())
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    if (showFullName) {
                                        FullNameDialog(asset.name) { showFullName = false }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 发布页没有附件、包走 nightly.link 或作者 CDN 的条目，弹窗里
                    // 一个可点的文件都没有，只能退出去再点卡片——这里补主按钮。
                    if (canDownload) {
                        val rawName = entry.latestApkName.ifBlank {
                            entry.latestApkUrl.substringAfterLast('/').substringBefore('?')
                        }.ifBlank { entry.id }
                        val apkName = if (rawName.contains('.')) rawName else "$rawName.apk"
                        Button(
                            onClick = { onDownloadAsset(entry.latestApkUrl, apkName) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(appString(R.string.emulator_download), maxLines = 1)
                        }
                    }
                    if (entry.repoLink.isNotBlank() && !(entry.dead && !isArchive)) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.repoLink)))
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(appString(R.string.emulator_repo), maxLines = 1)
                        }
                    }
                    if (entry.homepage.isNotBlank() && !(entry.dead && !isArchive)) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.homepage)))
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(appString(R.string.emulator_website), maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(appString(R.string.close))
                    }
                }
            }
        }
    }
}
