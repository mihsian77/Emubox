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


/** 版本更新对比（t3st31 renderReleaseComparison 移植）：选两个版本，diff 更新日志 added/removed */
@Composable
internal fun VersionDiffDialog(
    entry: EmulatorEntry,
    onDismiss: () -> Unit
) {
    val rels = remember(entry) { entry.releases }
    var fromIdx by remember { mutableStateOf(if (rels.size > 1) 1 else 0) }
    var toIdx by remember { mutableStateOf(0) }
    val from = rels.getOrNull(fromIdx)
    val to = rels.getOrNull(toIdx)

    // GitHub compare API 提交对比（标签可能不存在 → fallback 全文）
    var commits by remember(from?.tag, to?.tag) { mutableStateOf<List<CompareCommit>?>(null) }
    var loading by remember(from?.tag, to?.tag) { mutableStateOf(false) }
    var apiFailed by remember(from?.tag, to?.tag) { mutableStateOf(false) }
    var translated by remember(from?.tag, to?.tag) { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }

    LaunchedEffect(from?.tag, to?.tag) {
        if (from == null || to == null || fromIdx == toIdx) {
            commits = null; loading = false; apiFailed = false; translated = null
            return@LaunchedEffect
        }
        loading = true; apiFailed = false; translated = null
        commits = fetchCompareCommits(entry.owner, entry.repo, from.tag, to.tag)
        loading = false
        if (commits == null) apiFailed = true
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appString(R.string.version_diff_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null) }
                }
                // 版本选择：from -> to
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.version_diff_from), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                    VersionPicker(
                        tag = from?.tag ?: "—",
                        options = rels.map { it.tag },
                        onSelect = { fromIdx = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.version_diff_to), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                    VersionPicker(
                        tag = to?.tag ?: "—",
                        options = rels.map { it.tag },
                        onSelect = { toIdx = it }
                    )
                }
                HorizontalDivider()
                // 快照：commits 是状态委托，when 各分支只读快照，避免后台协程置空后 !! 崩
                val cmts = commits
                when {
                    fromIdx == toIdx || from == null || to == null -> {
                        Text(
                            appString(R.string.version_diff_pick),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(appString(R.string.version_diff_loading), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    cmts != null && cmts.isNotEmpty() -> {
                        // 摘要行
                        Text(
                            appString(R.string.version_diff_commits, cmts.size, from.tag, to.tag),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // 翻译按钮
                        val scope = rememberCoroutineScope()
                        if (translated == null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        translating = true
                                        val joined = cmts.joinToString("\n") { it.message }
                                        translated = translateReleaseNotes(joined) ?: joined
                                        translating = false
                                    }
                                },
                                enabled = !translating,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (translating) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(appString(R.string.version_diff_translate), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        // 提交列表（译文优先）
                        val shown = translated ?: cmts.joinToString("\n") { it.message }
                        if (translated != null) {
                            Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            cmts.forEach { c ->
                                Column {
                                    Text(c.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append(c.author.ifBlank { "—" })
                                            if (c.date.isNotBlank()) append(" · ${c.date}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    apiFailed -> {
                        Text(
                            appString(R.string.version_diff_api_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // fallback：两版本日志各自全文（不做文本 diff，各自独立）
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                from to appString(R.string.version_diff_from),
                                to to appString(R.string.version_diff_to)
                            ).forEach { (rel, label) ->
                                Text(
                                    "$label ${rel!!.tag}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    rel.body.ifBlank { appString(R.string.version_diff_empty) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            appString(R.string.version_diff_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionPicker(
    tag: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.width(140.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(tag, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, t ->
                DropdownMenuItem(
                    text = { Text(t, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 更新日志按行拆分，过滤标题/链接/分隔线（同 t3st31 changelogLines） */
/** 更新日志里的 HTML：很多项目直接发 HTML，不剥就是一堆 <p align="center"><img src=… */
internal fun stripHtmlForLog(raw: String): String = raw
    .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</(p|div|li|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
    // 标签内允许任意长度：`<source media="…" srcset="https://…">` 带上长 URL 轻松过 300 字符，
    // 原来卡在 300 就整段漏剥，正文里直接露出属性原文。
    // `<` 后面必须是字母 / `/` / `!`，这样 `a < b > c` 这种比较式不会被当成标签吃掉。
    .replace(Regex("<[a-zA-Z/!][^>]*>"), "")
    // 孤立标签残行：入库时正文按字符数截断，正好切在标签中间（`<source … srcset="https://github`），
    // 没有闭合 `>` 任何标签正则都剥不掉。整行以 `<` 开头的丢掉，正文里的 `a < b` 不受影响。
    .replace(Regex("(?m)^[ \\t]*<[^\\n]*$"), "")

private fun changelogLines(body: String): List<String> =
    decodeHtmlEntities(stripHtmlForLog(body))
        .split('\n')
        .map { it.trim() }
        .filter {
            it.isNotBlank() &&
                !it.startsWith("#") &&
                !it.startsWith("**") &&
                !it.startsWith("![") &&
                !it.startsWith("---") &&
                !it.contains("Full Changelog") &&
                !it.startsWith(">")
        }
        .map { it.removePrefix("- ").removePrefix("* ").removePrefix("+ ") }

/** diff：新版新增行 added、旧版移除行 removed，各最多 14 条 */
private fun diffChangelog(fromBody: String, toBody: String): Pair<List<String>, List<String>> {
    val fromLines = changelogLines(fromBody)
    val toLines = changelogLines(toBody)
    val fromSet = fromLines.map { it.lowercase() }.toSet()
    val toSet = toLines.map { it.lowercase() }.toSet()
    val added = toLines.filter { it.lowercase() !in fromSet }.take(14)
    val removed = fromLines.filter { it.lowercase() !in toSet }.take(14)
    return added to removed
}

@Composable
private fun EmulatorSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label（$count）",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Fusion.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** 模拟器双列紧凑卡：名称/官方徽章/最新版本/APK 文件名/统计 + 下载按钮，点击进详情 */
@Composable
internal fun EmulatorInsightsCard(insights: EmulatorInsights) {
    val fmt = { v: Long ->
        when {
            v >= 1_000_000_000 -> String.format("%.1f亿", v / 1_000_000_000.0)
            v >= 10_000_000 -> String.format("%.0f万", v / 1_000_000.0)
            v >= 1_000_000 -> String.format("%.1f万", v / 1_000_000.0)
            v >= 1000 -> String.format("%.1fk", v / 1000.0)
            else -> "$v"
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = Fusion.GradientStart)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.emulator_insights_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(appString(R.string.emulator_insights_projects), fmt(insights.totalProjects.toLong()), null),
                    Triple(appString(R.string.emulator_insights_downloads), fmt(insights.totalDownloads), null),
                    Triple(appString(R.string.emulator_insights_stars), fmt(insights.totalStars), null)
                ).forEach { (label, value, _) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Fusion.SurfaceAlt
                        )
                    ) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = MaterialTheme.typography.titleMedium,
                                color = Fusion.GradientStart)
                            Spacer(Modifier.height(2.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = Fusion.OnSurfaceDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val delta = insights.trending14.sumOf { it.delta }
            Spacer(Modifier.height(8.dp))
            Text(
                appString(R.string.emulator_insights_trend, fmt(delta), insights.trendBaseDate, insights.trendLatestDate),
                style = MaterialTheme.typography.labelSmall,
                color = Fusion.OnSurfaceDim
            )
            if (insights.weeklyReleases > 0 || insights.totalVisits > 0 || insights.onlineNow > 0) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (insights.weeklyReleases > 0) {
                        Text(
                            appString(R.string.emulator_insights_weekly, insights.weeklyReleases),
                            style = MaterialTheme.typography.labelSmall,
                            color = Fusion.GradientStart
                        )
                    }
                    if (insights.totalVisits > 0) {
                        Text(
                            appString(R.string.emulator_insights_visits, fmt(insights.totalVisits)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Fusion.GradientStart
                        )
                    }
                    if (insights.onlineNow > 0) {
                        Text(
                            appString(R.string.emulator_insights_online, insights.onlineNow),
                            style = MaterialTheme.typography.labelSmall,
                            color = Fusion.GradientStart
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmulatorNewsCard(
    news: List<EmulatorEntry>,
    onOpen: (EmulatorEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = Fusion.Amber)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.emulator_news_title), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = Fusion.OnSurface)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = Fusion.OnSurfaceDim
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                // 分页：每页 8 条，最多 20 页（与主页/生态动态 NewsZoneCard 一致）
                val pageSize = 8
                var page by remember { mutableStateOf(0) }
                val maxPage = ((news.size + pageSize - 1) / pageSize).coerceAtMost(20)
                val pageNews = news.drop(page * pageSize).take(pageSize)
                pageNews.forEachIndexed { idx, e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 项目图标统一链路：App 徽标（logoUrl）→ 作者头像 → 首字母兜底
                        ProjectAvatar(
                            logoUrl = e.logoUrl,
                            owner = e.owner,
                            fallbackName = e.name,
                            size = 32.dp,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            LongFieldText("${e.name} · ${e.latestTag}", style = MaterialTheme.typography.labelMedium,
                                color = Fusion.OnSurface,
                                maxLines = 2)
                            Spacer(Modifier.height(2.dp))
                            // 原来直接取 release 正文第一行：42% 的项目第一行是 `##`、`>`、
                            // `<p>` 这类标记，列表里显示的就是标记原文。改成剥标记后取正文首句。
                            Text(
                                releaseDigest(e.latestBody).ifBlank { appString(R.string.emulator_news_no_body) },
                                style = MaterialTheme.typography.bodySmall,
                                color = Fusion.OnSurfaceDim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatRelativeTime(e.latestDate),
                            style = MaterialTheme.typography.labelSmall,
                            color = Fusion.Primary
                        )
                    }
                    if (idx < pageNews.lastIndex) HorizontalDivider(color = Fusion.BorderAlt.copy(alpha = 0.6f))
                }
                // 分页控制条：首页 / 上一页 / 页码 / 下一页
                NewsPager(total = news.size, page = page, pageSize = pageSize, onPage = { page = it })
            }
        }
    }
}

@Composable
internal fun EmulatorTrendingCard(
    trending: List<TrendingRepo>,
    baseDate: String,
    latestDate: String,
    entries: List<EmulatorEntry>,
    onOpen: (EmulatorEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = Fusion.GradientStart)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.emulator_trending_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = Fusion.GradientStart
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.emulator_trending_period, baseDate, latestDate),
                style = MaterialTheme.typography.labelSmall,
                color = Fusion.OnSurfaceDim
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                trending.take(8).forEachIndexed { idx, t ->
                    val entry = entries.firstOrNull { it.repo.equals(t.repo, ignoreCase = true) || it.repoLink.contains(t.repo, ignoreCase = true) }
                    val name = entry?.name ?: t.repo.substringAfter('/')
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = entry != null) { entry?.let(onOpen) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Fusion.GradientStart,
                            modifier = Modifier.width(22.dp)
                        )
                        // 项目图标统一链路：App 徽标（logoUrl）→ 作者头像 → 首字母兜底
                        ProjectAvatar(
                            logoUrl = entry?.logoUrl ?: "",
                            owner = entry?.owner ?: "",
                            fallbackName = name,
                            size = 24.dp,
                            shape = RoundedCornerShape(6.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(
                            "+${formatStars(t.delta)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmulatorCategoryShareCard(categories: List<CategoryDownloads>) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }) {
                Icon(Icons.Default.PieChart, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = Fusion.GradientStart)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.emulator_cat_share_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = Fusion.GradientStart
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                val total = categories.maxOf { it.downloads }.toDouble()
                categories.take(10).forEach { c ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text(formatStars(c.downloads), style = MaterialTheme.typography.labelSmall,
                                color = Fusion.OnSurfaceDim)
                        }
                        Spacer(Modifier.height(3.dp))
                        val frac = if (total > 0) (c.downloads / total).coerceIn(0.02, 1.0) else 0.02
                        Box(
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Fusion.SurfaceAlt)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(frac.toFloat()).fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Fusion.GradientStart)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmulatorRecipesCard(recipes: List<EmulatorRecipe>) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }) {
                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.emulator_recipes_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                recipes.forEach { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.title, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            if (r.summary.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(r.summary, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (r.settings.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                r.settings.forEach { (k, v) ->
                                    Row(Modifier.padding(vertical = 2.dp)) {
                                        Text("· $k", style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.width(110.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(v, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            if (r.checks.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                r.checks.forEach { c ->
                                    Text("✓ $c", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 英雄卡「为什么推荐它」。
 *
 * 只用数据里能核对的量：热度排名、最近提交、是否有安卓直装包、是否官方。
 * 不写「口碑最好」「强烈推荐」这类无法验证的话——上一版的卡片就是因为只放
 * 名称和星标，用户看不出这张大卡想表达什么。
 */
internal fun heroReasonText(entry: EmulatorEntry, total: Int): String {
    val parts = ArrayList<String>(4)
    if (total > 1) parts += "${total} 个项目里热度第 1"
    val days = daysSinceIso(entry.pushedAt)
    when {
        days == 0L -> parts += "今天有提交"
        days in 1L..45L -> parts += "${days} 天前更新"
    }
    if (entry.latestApkUrl.isNotBlank()) parts += "有 Android 直装包"
    if (entry.official) parts += "官方项目"
    if (parts.size <= 1 && entry.stars > 0) parts += "★${entry.stars}"
    return parts.take(3).joinToString(" · ")
}

@Composable
internal fun EmulatorHeroCard(
    entry: EmulatorEntry,
    categoryLabel: String,
    reason: String,
    onClickDetail: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickDetail),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Fusion.BorderAlt)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors = listOf(Fusion.HeroStart, Fusion.HeroEnd)))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 项目图标统一链路：App 徽标（logoUrl）→ 作者头像 → 首字母兜底
            ProjectAvatar(
                logoUrl = entry.logoUrl,
                owner = entry.owner,
                fallbackName = entry.name,
                size = 48.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Fusion.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.nameZh.isNotBlank()) {
                    Text(
                        entry.nameZh,
                        fontSize = 11.sp,
                        color = Fusion.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    softBreakLong("$categoryLabel · ${entry.latestTag.ifBlank { "latest" }}"),
                    fontSize = 12.sp,
                    color = Fusion.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "★ ${entry.stars}",
                        fontSize = 10.sp,
                        color = Fusion.Amber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (entry.latestApkUrl.isNotBlank()) {
                        Text(
                            "Android",
                            fontSize = 10.sp,
                            color = Fusion.OnSurfaceDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                // 「为什么是它」：排名、更新、直装能力都来自数据本身，
                // 不放主观评语，用户能自己对上号。
                if (reason.isNotBlank()) {
                    Text(
                        reason,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = Fusion.OnSurfaceFaint,
                        modifier = Modifier.padding(top = 6.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // 浅色主题下 HeroStart 是 #DDE6FF，白底白字等于看不见箭头。
                    // 改用 primary/onPrimary，深浅两套主题都保证对比度。
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "›",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

/** 桌面宿主集合：win/mac/linux 归一类，卡片上只显示「桌面」，不铺开三个词 */
private val DESKTOP_HOSTS = setOf("windows", "macos", "linux", "win", "mac")

/**
 * 支持平台简标：安卓 / 桌面 / 全平台。
 *
 * 卡片和列表都挂在项目名后面，回答用户第一眼最关心的问题「这东西我手机上能不能装」。
 * 不用 A/PC 这类字母简写：两个汉字在 labelSmall 下只有 20dp 出头，不比字母宽多少，
 * 但不需要额外图例，第一次看也懂。
 */
private fun emulatorHostLabel(hosts: List<String>): String {
    val h = hosts.map { it.lowercase() }.toSet()
    val android = "android" in h
    val desktop = h.any { it in DESKTOP_HOSTS }
    return when {
        android && desktop -> "全平台"
        android -> "安卓"
        desktop -> "桌面"
        else -> ""   // 主机自制程序（wii/ps4）或未知：不臆造标签
    }
}

/** 排行榜名次徽章：前三金银铜，其余中性色。rank=0 表示不在社区榜内，不显示 */
@Composable
internal fun EmulatorRankBadge(rank: Int, modifier: Modifier = Modifier) {
    if (rank !in 1..99) return
    val rankColor = when (rank) {
        1 -> Color(0xFFE0A82E)
        2 -> Color(0xFFA8B4C4)
        3 -> Color(0xFFC0764A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = rankColor.copy(alpha = 0.16f)
    ) {
        Text(
            "#$rank",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = rankColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 平台标签：安卓可用用主色（能装），仅桌面用第三色（要提醒） */
@Composable
internal fun EmulatorHostBadge(hosts: List<String>, modifier: Modifier = Modifier) {
    val label = emulatorHostLabel(hosts)
    if (label.isBlank()) return
    val androidReady = hosts.any { it.equals("android", ignoreCase = true) }
    val container = if (androidReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    val fg = if (androidReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(modifier = modifier, shape = RoundedCornerShape(5.dp), color = container) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun EmulatorCard(
    entry: EmulatorEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    inCompare: Boolean,
    onToggleCompare: () -> Unit,
    onClickDetail: () -> Unit,
    onDownload: (EmulatorEntry) -> Unit
) {
    val context = LocalContext.current
    // 来源分级徽标（github/gitea/gitlab/官网/社区归档），与官方徽标并列
    val sourceLabel = when (entry.source) {
        "github" -> "GitHub"
        "gitea" -> "Gitea"
        "gitlab" -> "GitLab"
        "notabug" -> "NotABug"
        "official-web" -> "官网"
        "official-play" -> "Play 商店"
        "community-archive" -> "社区归档"
        else -> ""
    }
    // DMCA/停更仓库：社区归档（123盘历史包）保留下载，其余不跳仓库、隐藏下载
    val isArchive = entry.source == "community-archive"
    val canDownload = entry.latestApkUrl.isNotBlank() && !(entry.dead && !isArchive)
    val websiteUrl = if (entry.dead && !isArchive) "" else
        entry.homepage.ifBlank { entry.repoUrl.ifBlank { entry.repoLink.ifBlank { entry.downloadUrl } } }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickDetail),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 项目图标统一链路：App 徽标（logoUrl）→ 作者头像 → 首字母兜底
                ProjectAvatar(
                    logoUrl = entry.logoUrl,
                    owner = entry.owner,
                    fallbackName = entry.name,
                    size = 36.dp,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.width(6.dp))
                // 名称独占主行宽度（maxLines=2），避免被徽章+按钮挤压成逐字截断
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            // 名次 / 平台 / 官方：单独一行。
            // 塞进标题行会重演「ShadPS4 被压成 ShadPS / 4」的逐字断行——
            // 半屏卡片只有 ~190dp，头像 + 名称 + 三个徽章放不下。
            val hostLabel = emulatorHostLabel(entry.hosts)
            if (entry.rank in 1..99 || hostLabel.isNotBlank() || entry.official) {
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EmulatorRankBadge(entry.rank)
                    EmulatorHostBadge(entry.hosts)
                    if (entry.official) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                appString(R.string.emulator_badge_official),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
            // 中文名只在这条目有译名时出现（原生移植项目居多），
            // 原名本身可读的项目不重复占一行。
            if (entry.nameZh.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.nameZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.channel.isNotBlank() && entry.channel != "stable") {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (entry.channel == "prerelease") appString(R.string.emulator_channel_prerelease) else entry.channel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (entry.latestTag.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    // 只显示日期：原始值为 "2026-08-19T11:06:00Z"，在卡片里被截成
                    // "…2026-08-19T11:06…"，末尾一截毫秒毫无信息量。
                    // tag 仍按软断点折行：shadPS4 的 tag 有 71 字符，单行会把日期挤掉
                    softBreakLong("${entry.latestTag} · ${shortDate(entry.latestDate)}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 桌面模拟器在安卓端装不了，必须标出来；多生态保留，但不假装能装
            if (entry.hosts.isNotEmpty() && !entry.hosts.contains("android")) {
                Spacer(Modifier.height(2.dp))
                Text(
                    // 平台徽章已经说了「桌面」，这行只补「具体哪几个桌面系统」。
                    // 全称 Windows/macOS/Linux 在半屏卡片里必被截成 "…Windows/macOS/Li"，
                    // 去掉 a 字尾改成 Win/Mac/Linux，一行放得下。
                    appString(R.string.emulator_hosts_desktop_only) + " · " + entry.hosts.joinToString("/") {
                        when (it) {
                            "windows" -> "Win"
                            "macos" -> "Mac"
                            "linux" -> "Linux"
                            else -> it
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.pushedAt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    appString(R.string.emulator_updated_prefix) + formatRelativeTime(entry.pushedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1
                )
            }
            // 卡片上原来放的是 APK 文件名（"Winlator_11.2.apk"），既占一行又被截断，
            // 用户看不出什么。换成一句话简介，一眼知道这东西是干什么的。
            // 顺序是「中文简介优先、上游原文兜底」：description 是我们按统一标准写的
            // 中文一句话，summary 是上游 README 首段（多为英文）。之前反着来，
            // 结果七成卡片在给中文用户看英文原句。
            val cardBlurb = entry.description.ifBlank { entry.summary }
            if (cardBlurb.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    cardBlurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 两行装不下 40 字的中文简介，几乎每条都在句子中间断掉。
                    // 卡片是双列，多一行只多 16dp，换整句可读性值。
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.stars > 0 || entry.downloads > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (entry.stars > 0) append("★${entry.stars}")
                        if (entry.stars > 0 && entry.downloads > 0) append(" · ")
                        if (entry.downloads > 0) append(emulatorDownloadCount(entry.downloads))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (canDownload) onDownload(entry)
                        else if (websiteUrl.isNotBlank()) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
                            }
                        }
                    },
                    enabled = canDownload || websiteUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (canDownload) appString(R.string.emulator_download)
                        else appString(R.string.emulator_website),
                        style = MaterialTheme.typography.labelSmall,
                        softWrap = false,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggleCompare, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.CompareArrows,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (inCompare) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 对比底部栏（t3st31 compareSlots 移植）：已选 N/3，可打开对比矩阵或清空 */
@Composable
internal fun CompareBar(
    projects: List<EmulatorEntry>,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appString(R.string.compare_selected, projects.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpen) {
                Text(appString(R.string.compare_open), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 对比矩阵弹窗：每维度一行（各项目并排 + 归一化打分条），胜者列高亮 */
@Composable
internal fun EmulatorCompareDialog(
    projects: List<EmulatorEntry>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val stats = remember(projects) { projects.map { emuCompareStats(it) } }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appString(R.string.compare_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null) }
                }
                // 项目头行（可单独移除）
                Row(verticalAlignment = Alignment.Top) {
                    Spacer(Modifier.width(100.dp))
                    projects.forEach { p ->
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (p.logoUrl.isNotBlank()) {
                                AsyncImage(
                                    model = p.logoUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(p.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                IconButton(
                                    onClick = { onRemove(p.id) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(11.dp))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                // 数值维度
                CompareDimensionRow(
                    label = appString(R.string.compare_dim_downloads),
                    values = stats.map { it.downloads.toString() },
                    scores = stats.map { it.downloads.toFloat() }
                )
                CompareDimensionRow(
                    label = appString(R.string.compare_dim_avg),
                    values = stats.map { it.avgDownloads.toString() },
                    scores = stats.map { it.avgDownloads.toFloat() }
                )
                CompareDimensionRow(
                    label = appString(R.string.compare_dim_releases),
                    values = stats.map { it.releaseCount.toString() },
                    scores = stats.map { it.releaseCount.toFloat() }
                )
                CompareDimensionRow(
                    label = appString(R.string.compare_dim_stars),
                    values = stats.map { it.stars.toString() },
                    scores = stats.map { it.stars.toFloat() }
                )
                CompareDimensionRow(
                    label = appString(R.string.compare_dim_update),
                    values = stats.map { s -> if (s.daysSinceUpdate >= 0) "${s.daysSinceUpdate} 天" else "—" },
                    scores = stats.map { s -> if (s.daysSinceUpdate >= 0) (365 - s.daysSinceUpdate).toFloat() else 0f }
                )
                // 版本维度（文本行）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appString(R.string.compare_dim_latest),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(100.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    stats.forEachIndexed { i, s ->
                        Text(
                            // 对比表每列只有约 85dp，71 字符的 tag 必须按 _ - 断行
                            softBreakLong(s.latestTag.ifBlank { "—" }),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (stats.maxByOrNull { it.daysSinceUpdate }?.daysSinceUpdate == s.daysSinceUpdate && s.daysSinceUpdate >= 0)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    appString(R.string.compare_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单维度对比行：标签 + 各项目值 + 归一化打分条，胜者列高亮 */
@Composable
private fun CompareDimensionRow(
    label: String,
    values: List<String>,
    scores: List<Float>
) {
    val maxScore = scores.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val maxVal = scores.maxOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        values.forEachIndexed { i, v ->
            val isWinner = maxVal != null && maxVal > 0f && scores[i] == maxVal
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    v,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                    color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = (scores[i] / maxScore).coerceIn(0f, 1f),
                    color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(0.82f).height(4.dp)
                )
            }
        }
    }
}

private data class EmuCompareStats(
    val downloads: Long,
    val releaseCount: Int,
    val stars: Long,
    val latestTag: String,
    val daysSinceUpdate: Long,
    val avgDownloads: Long
)

/** t3st31 getCompareStats 移植：对比维度计算 */
private fun emuCompareStats(e: EmulatorEntry): EmuCompareStats {
    val rc = e.releases.size
    return EmuCompareStats(
        downloads = e.downloads,
        releaseCount = rc,
        stars = e.stars,
        latestTag = e.latestTag,
        daysSinceUpdate = daysSinceIso(e.pushedAt),
        avgDownloads = if (rc > 0) e.downloads / rc else e.downloads
    )
}

private fun daysSinceIso(iso: String): Long {
    if (iso.isBlank()) return -1L
    return runCatching {
        val parsed = java.time.Instant.parse(iso)
        java.time.Duration.between(parsed, java.time.Instant.now()).toDays().coerceAtLeast(0L)
    }.getOrDefault(-1L)
}

/** 列表视图紧凑行：图标+名称+版本摘要+操作按钮横排，一行一条 */
@Composable
internal fun EmulatorListRow(
    entry: EmulatorEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    inCompare: Boolean,
    onToggleCompare: () -> Unit,
    onClickDetail: () -> Unit,
    onDownload: (EmulatorEntry) -> Unit
) {
    val context = LocalContext.current
    val sourceLabel = when (entry.source) {
        "github" -> "GitHub"
        "gitea" -> "Gitea"
        "gitlab" -> "GitLab"
        "notabug" -> "NotABug"
        "official-web" -> "官网"
        "official-play" -> "Play 商店"
        "community-archive" -> "社区归档"
        else -> ""
    }
    val canDownload = entry.latestApkUrl.isNotBlank()
    // 官网优先 homepage，无则回退仓库（模拟器专区为社区排行榜，保留仓库跳转）
    val websiteUrl = entry.homepage.ifBlank { entry.repoUrl.ifBlank { entry.repoLink.ifBlank { entry.downloadUrl } } }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClickDetail),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 项目图标统一链路：App 徽标（logoUrl）→ 作者头像 → 首字母兜底
            ProjectAvatar(
                logoUrl = entry.logoUrl,
                owner = entry.owner,
                fallbackName = entry.name,
                size = 36.dp,
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 原生移植项目的原名是英文（Ship of Harkinian / Zelda64 Recompiled），
                    // 中文用户看不出是哪个游戏：有译名时主标题用译名，原名缩成下一行小字
                    Text(
                        entry.nameZh.ifBlank { entry.name },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 社区下载量名次：只给有统计的项目挂，前三是金银铜
                    EmulatorRankBadge(entry.rank, Modifier.padding(start = 5.dp))
                    if (entry.official) {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                appString(R.string.emulator_badge_official),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    if (entry.dead) {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                appString(R.string.emulator_badge_dead),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (sourceLabel.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                sourceLabel,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (entry.channel.isNotBlank() && entry.channel != "stable") {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(
                                if (entry.channel == "prerelease") appString(R.string.emulator_channel_prerelease) else entry.channel,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                if (entry.nameZh.isNotBlank()) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 能跑什么：平台标签 + 家族（分支/移植来源）。
                // 之前只有「桌面 / 预发布 / 官方」这类状态标签，用户看不出项目是干嘛的。
                val goalTags = buildList {
                    entry.platforms.take(3).forEach { p ->
                        emulatorPlatformLabel(p).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    // 宿主系统不再在这里拼：平台简标（安卓/桌面）已经用带色徽章
                    // 挂在同一行最左边，再拼一遍 Windows/macOS/Linux 只是重复。
                    entry.topics
                        .filter { t -> t.length in 2..18 && t.lowercase() !in EMULATOR_TOPIC_NOISE }
                        .take(2)
                        .forEach { add("#$it") }
                }
                val hostLabel = emulatorHostLabel(entry.hosts)
                if (goalTags.isNotEmpty() || hostLabel.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmulatorHostBadge(entry.hosts)
                        goalTags.take(3).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    tag,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    softBreakLong(buildString {
                        append(entry.latestTag.ifBlank { "暂无版本" })
                        if (entry.latestDate.isNotBlank()) append(" · ${entry.latestDate.take(10)}")
                        append(" · 下载 ${emulatorDownloadCount(entry.downloads)}")
                    }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleCompare, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.CompareArrows,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (inCompare) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = {
                    if (canDownload) onDownload(entry)
                    else if (websiteUrl.isNotBlank()) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))) }
                    }
                },
                enabled = canDownload || (websiteUrl.isNotBlank() && !entry.dead),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text(
                    if (canDownload) appString(R.string.emulator_download) else appString(R.string.emulator_website),
                    style = MaterialTheme.typography.labelSmall,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
    }
}

internal fun emulatorDownloadCount(d: Long): String = when {
    d >= 1_000_000 -> "%.1fM".format(d / 1_000_000.0)
    d >= 1_000 -> "%.1fK".format(d / 1_000.0)
    else -> "$d"
}

internal fun assetSizeText(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
    size >= 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "$size B"
}

/**
 * 长字段软断行：在 `_` `-` `+` `/` 之后插入零宽空格（U+200B），扩展名整体保留。
 *
 * 为什么非做不可：Termux 一次发 6 个产物，
 * `termux-app_v0.119.0-beta.3.1+apt-android-7-github-debug_arm64-v8a.apk`
 * 前 33 个字符一模一样，真正决定装得上装不上的是尾部的 apt 版本、
 * debug/release 和 ABI。单行截断后六行看起来完全一致，用户按名字选必然下错包
 * （arm64 包装到 armeabi 设备上直接 INSTALL_FAILED_NO_MATCHING_ABIS）。
 *
 * 默认断行规则只认空格和连字符，遇到 `_` 连成一串的标识符会整段溢出或被硬切，
 * 断在哪里完全不可控。插入 ZWSP 等于把这些位置标成「可以断」，渲染器
 * 就会优先在语义边界折行。
 */
internal fun softBreakLong(text: String): String {
    if (text.isBlank()) return text
    val dot = text.lastIndexOf('.')
    // 扩展名最长 `.tar.gz`；超过 8 个字符说明这个点属于版本号或域名，不能当扩展名切
    val suffix = if (dot > 0 && text.length - dot <= 8 && text.indexOf('/', dot) < 0) {
        text.substring(dot)
    } else ""
    var head = if (suffix.isEmpty()) text else text.substring(0, dot)

    // ABI 串整体保护：`arm64-v8a` 自带连字符，不保护会被拆成
    // 「…debug_arm64-」+「v8a.apk」，架构名读起来是断的。
    // 用私用区字符占位，绕开后面按 `-` 插断点这一步。
    val held = LinkedHashMap<String, String>()
    for (abi in ABI_TOKENS) {
        val re = Regex(Regex.escape(abi), RegexOption.IGNORE_CASE)
        head = re.replace(head) { m ->
            val key = "\uE000${held.size}\uE001"
            held[key] = m.value
            key
        }
    }
    head = head.replace(Regex("([_\\-+/])"), "$1\u200B")
    for ((k, v) in held) head = head.replace(k, v)
    return head + suffix
}

/** 断行时整体保留的标识符：这些串内部的 `-` 不属于可读断点 */
private val ABI_TOKENS = listOf(
    "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "aarch64", "universal"
)

/**
 * 长字段文本：文件名、tag、URL 这类不能按普通句子折行的内容。
 *
 * 和直接用 Text 的区别只有两点：走 [softBreakLong] 得到可控断点，
 * 行数上限默认给 3 行（71 字符的最长产物名在半屏宽度下正好放得下）。
 */
@Composable
internal fun LongFieldText(
    text: String,
    style: TextStyle,
    maxLines: Int = 3,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        softBreakLong(text),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 完整文件名弹窗：3 行仍然放不下的超长名（或用户想核对 ABI）走这里。
 *
 * 复制出去的是原始字符串，零宽空格全部剥掉——不剥的话粘到终端或文件管理器里
 * 文件名会带隐藏字符，改名、校验哈希都会对不上。
 */
@Composable
internal fun FullNameDialog(name: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(name) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.asset_full_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    appString(R.string.file_purpose) + "：" + filePurposeHint(name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(name))
                copied = true
            }) {
                Text(appString(if (copied) R.string.copied else R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appString(R.string.close)) }
        }
    )
}

internal fun assetTypeLabel(name: String): String = when {
    name.endsWith(".apk", true) -> "APK"
    name.endsWith(".zip", true) || name.endsWith(".7z", true) || name.endsWith(".tar.gz", true) ||
        name.endsWith(".tgz", true) || name.endsWith(".tar", true) -> "压缩包"
    name.endsWith(".patch", true) || name.endsWith(".diff", true) -> "补丁"
    name.endsWith(".so", true) -> "库文件"
    name.endsWith(".exe", true) -> "安装程序"
    else -> "文件"
}

/** 模拟器详情：全部 releases 全部文件，每个文件可下载，日志可翻译 */
/**
 * 发布页里的校验和/签名文件。列出来只会让用户误下一堆 .sha256，
 * 也不能装、也不能看。数量统计和列表都要用过滤后的口径，否则「12 个文件」
 * 下面只列 6 行，用户会以为加载不全。
 */
internal fun isNoiseAsset(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".sha256") || n.endsWith(".sha512") || n.endsWith(".sha1") ||
        n.endsWith(".md5") || n.endsWith(".sig") || n.endsWith(".asc") ||
        n.endsWith(".zsync") || n.endsWith(".blockmap") ||
        // 有的项目不加扩展名，直接叫 xxx_sha256sums（Termux 就是这样）
        n.contains("sha256sum") || n.contains("sha512sum") || n.contains("md5sum")
}

/**
 * 从文件名认架构。选包时这是第二个要判断的东西（第一是「是不是安卓包」），
 * 但文件名长到必被截断，截掉的多半正是 arm64/armeabi 这一段。
 */
internal fun assetArchLabel(name: String): String {
    val n = name.lowercase()
    return when {
        "arm64-v8a" in n || "arm64" in n || "aarch64" in n -> "arm64"
        "armeabi-v7a" in n || "armv7" in n || "armeabi" in n -> "armv7"
        "x86_64" in n || "x64" in n || "amd64" in n -> "x64"
        "x86" in n || "i686" in n || "i386" in n -> "x86"
        "universal" in n || "all" in n.split('.', '-', '_') -> "通用"
        else -> ""
    }
}

/** 产物明细：只列非零项，手机能用的排前面（用户先关心「能不能装」） */
private fun artifactBreakdown(a: EmulatorArtifacts): String = buildList {
    if (a.apk > 0) add("安卓包 ${a.apk}")
    if (a.androidZip > 0) add("安卓 zip ${a.androidZip}")
    if (a.ipa > 0) add("iOS 包 ${a.ipa}")
    if (a.libretroCore > 0) add("RetroArch 核心 ${a.libretroCore}")
    if (a.desktop > 0) add("桌面端 ${a.desktop}")
    if (a.console > 0) add("主机端 ${a.console}")
    if (a.source > 0) add("源码 ${a.source}")
}.joinToString(" · ")

/**
 * 发布文件清单：在详情页顶部就回答「这版到底给了什么」。
 * 以前要翻到最底下的版本卡、再展开，才能看见文件名；而「给了桌面包没有安卓包」
 * 正是用户最容易被误导的地方（点下载，装不上）。
 *
 * 无产物时不再单给一个「去下载页」按钮——底栏已经有「官网获取」，同一个动作
 * 同屏出现两次只会让用户以为要走两套流程。
 */
@Composable
internal fun ArtifactSummaryBlock(entry: EmulatorEntry) {
    val a = entry.artifacts
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // 口径与下方版本卡一致：数最新 release 的全部资产（版本卡标题写的是
            // "${release.assets.size} 个文件"）。之前用注册表的归类数
            // （openMSX 只认出 1 个源码包），同一张弹窗出现「共 1 个」与「7 个文件」。
            val newestCount = entry.releases.firstOrNull()?.assets?.size ?: 0
            val totalCount = when {
                newestCount > 0 -> newestCount
                a.displayTotal > 0 -> a.displayTotal
                else -> 0
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发布文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    if (totalCount == 0) "无" else "共 $totalCount 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 卡片上的「下载」给的是 latest_apk_url，它不一定是最新版的包：
            // 最新版只发源码/桌面包时，脚本会回退到更早那个带 APK 的版本。
            // 所以这里的口径要跟按钮对齐——只说「最新版有什么」会出现
            // 「手机装不了」旁边挂着下载按钮的矛盾说法。
            val hasDirect = entry.latestApkUrl.isNotBlank() &&
                !(entry.dead && entry.source != "community-archive")
            val apkTag = entry.latestApkTag
            val olderApk = hasDirect && apkTag.isNotBlank() && apkTag != entry.latestTag
            when {
                totalCount == 0 -> {
                    Text(
                        if (hasDirect) "发布页没有附件，安装包由项目自己的构建产物直链提供，点下方「下载」即可。"
                        else "作者没把安装包传到发布页，改在官网、网盘或 Discord 分发。用下方「官网获取」过去找。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val phone = a.installableOnPhone
                    Text(
                        when {
                            phone > 0 -> "手机可直接安装 · $phone 个"
                            // 按钮能给包时，先说清它是哪一版，别让用户以为装的是最新版
                            olderApk -> softBreakLong("最新版没有安卓包 · 下载按钮给的是 $apkTag")
                            hasDirect -> "最新版没有安卓包 · 下载按钮给的是单独提供的安装包"
                            a.recognized > 0 -> "手机装不了 · 只有桌面端或源码"
                            else -> "发布页 ${a.total} 个文件都不是手机安装包"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            phone > 0 -> MaterialTheme.colorScheme.primary
                            olderApk || hasDirect -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    val detail = artifactBreakdown(a)
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
