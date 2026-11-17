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


@Composable
fun GlobalSearchSheet(
    emulators: List<EmulatorEntry>,
    drivers: List<DriverRegistryEntry>,
    componentNames: List<String>,
    downloads: List<DownloadsManager.CompletedDownload>,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var runtimes by remember { mutableStateOf<List<RuntimeComponent>>(emptyList()) }
    LaunchedEffect(Unit) {
        runtimes = RuntimeLibraryRepository.load()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(appString(R.string.global_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.clickable { query = "" }
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onDismiss) { Text(appString(R.string.cancel)) }
                }
                HorizontalDivider()
                if (query.isBlank()) {
                    Text(
                        appString(R.string.global_search_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 全局搜索与分区内搜索共用同一套匹配文本，避免「分区里搜得到、全局搜不到」
                    val emuIndex = emulators.associate { it.id to emulatorSearchText(it) }
                    val drvIndex = drivers.associate { it.id to driverSearchText(it) }
                    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                    fun hit(hay: String?) = hay != null && terms.all { t -> hay.contains(t) }
                    val emuHits = emulators.filter { hit(emuIndex[it.id]) }
                    val drvHits = drivers.filter { hit(drvIndex[it.id]) }
                    val compHits = componentNames.filter { n -> terms.all { n.lowercase().contains(it) } }
                    val rtHits = runtimes.filter { r ->
                        val hay = (r.name + " " + r.description + " " + r.category.name).lowercase()
                        terms.all { t -> hay.contains(t) }
                    }
                    val dlHits = downloads.filter { d ->
                        val hay = (d.fileName + " " + d.subPath).lowercase()
                        terms.all { t -> hay.contains(t) }
                    }
                    if (emuHits.isEmpty() && drvHits.isEmpty() && compHits.isEmpty() && rtHits.isEmpty() && dlHits.isEmpty()) {
                        Text(
                            appString(R.string.global_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        if (emuHits.isNotEmpty()) {
                            GlobalSearchGroup(appString(R.string.tab_emulator)) {
                                emuHits.take(8).forEach { e ->
                                    GlobalSearchRow(
                                        name = e.name,
                                        subtitle = emulatorCategoryLabel(e.category),
                                        onClick = { onNavigate("emulators"); onDismiss() }
                                    )
                                }
                            }
                        }
                        if (drvHits.isNotEmpty()) {
                            GlobalSearchGroup(appString(R.string.tab_driver)) {
                                drvHits.take(8).forEach { d ->
                                    GlobalSearchRow(
                                        name = d.name,
                                        subtitle = d.description,
                                        onClick = { onNavigate("drivers:${d.id}"); onDismiss() }
                                    )
                                }
                            }
                        }
                        if (compHits.isNotEmpty()) {
                            GlobalSearchGroup(appString(R.string.tab_component)) {
                                compHits.take(8).forEach { c ->
                                    GlobalSearchRow(
                                        name = c,
                                        subtitle = appString(R.string.tab_component),
                                        onClick = { onNavigate("components"); onDismiss() }
                                    )
                                }
                            }
                        }
                        if (rtHits.isNotEmpty()) {
                            GlobalSearchGroup(appString(R.string.tab_runtime)) {
                                rtHits.take(8).forEach { r ->
                                    GlobalSearchRow(
                                        name = r.name,
                                        subtitle = r.description,
                                        onClick = { onNavigate("runtime"); onDismiss() }
                                    )
                                }
                            }
                        }
                        if (dlHits.isNotEmpty()) {
                            GlobalSearchGroup(appString(R.string.tab_download)) {
                                dlHits.take(8).forEach { d ->
                                    GlobalSearchRow(
                                        name = d.fileName,
                                        subtitle = d.subPath.ifBlank { appString(R.string.tab_download) },
                                        onClick = { onNavigate("downloads"); onDismiss() }
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

@Composable
private fun GlobalSearchGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun GlobalSearchRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun mimeTypeForFileName(name: String): String {
    val n = name.lowercase()
    return when {
        n.endsWith(".zip") || n.endsWith(".apks") || n.endsWith(".xapk") -> "application/zip"
        n.endsWith(".apk") -> "application/vnd.android.package-archive"
        n.endsWith(".7z") -> "application/x-7z-compressed"
        n.endsWith(".tar") -> "application/x-tar"
        n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "application/x-compressed-tar"
        n.endsWith(".tar.xz") || n.endsWith(".txz") -> "application/x-xz"
        n.endsWith(".xz") -> "application/x-xz"
        n.endsWith(".gz") || n.endsWith(".gzip") -> "application/gzip"
        n.endsWith(".bz2") -> "application/x-bzip2"
        n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md") -> "text/plain"
        n.endsWith(".json") -> "application/json"
        n.endsWith(".xml") -> "application/xml"
        n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
        n.endsWith(".csv") -> "text/csv"
        n.endsWith(".png") -> "image/png"
        n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
        n.endsWith(".gif") -> "image/gif"
        n.endsWith(".webp") -> "image/webp"
        n.endsWith(".svg") -> "image/svg+xml"
        n.endsWith(".mp3") -> "audio/mpeg"
        n.endsWith(".wav") -> "audio/wav"
        n.endsWith(".ogg") -> "audio/ogg"
        n.endsWith(".m4a") -> "audio/mp4"
        n.endsWith(".mp4") -> "video/mp4"
        n.endsWith(".mkv") -> "video/x-matroska"
        n.endsWith(".webm") -> "video/webm"
        n.endsWith(".pdf") -> "application/pdf"
        n.endsWith(".so") -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}

/* ─────────────────────── 支撑类分区的互相跳转 ─────────────────────── */

/** 相关分区快捷入口：驱动 / 组件 / 运行库三者各自独立成区，但实际排查问题时
 *  人经常站在错的页面（「在运行库列表里翻 DXVK，其实它在组件区」）。
 *  底部导航能到，但需要先认出目标在哪一栏；这一行把另外两栏直接摆出来。 */
@Composable
internal fun RelatedPartitionRow(current: String, onNavigate: (String) -> Unit) {
    val others = listOf(
        "drivers" to appString(R.string.tab_driver),
        "components" to appString(R.string.tab_component),
        "runtime" to appString(R.string.tab_runtime),
    ).filterNot { it.first == current }
    if (others.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            appString(R.string.related_partitions),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        others.forEach { (key, label) ->
            FusionFilterChip(label = label, selected = false, onClick = { onNavigate(key) })
        }
    }
}

/* ─────────────────────────── 资讯总入口 ─────────────────────────── */

// data/news-data.json 实际只有这 5 类。历史上还有 eco / general 两个值，
// 现已被 device / tech 取代，不放进筛选行（放进去只会是 0 条的死 chip）。
private val NEWS_FEED_CATEGORIES = listOf("emulator", "driver", "component", "device", "tech")

@Composable
private fun newsCategoryText(category: String): String = appString(
    when (category) {
        "emulator" -> R.string.news_chip_emulator
        "driver" -> R.string.news_chip_driver
        "component" -> R.string.news_chip_component
        "device" -> R.string.news_chip_device
        "tech" -> R.string.news_chip_tech
        else -> R.string.news_chip_all
    }
)

/** 按自然日分组：同一批发布在一天内的条目并成一段，避免 255 条平铺看不出节奏。
 *  返回 (当天 0 点毫秒, 该天条目)，按日期倒序。 */
private fun groupNewsByDay(list: List<NewsItem>): List<Pair<Long, List<NewsItem>>> {
    val cal = Calendar.getInstance()
    fun dayStart(epochSec: Long): Long {
        cal.timeInMillis = epochSec * 1000L
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    return list.groupBy { dayStart(it.publishedAt) }
        .toSortedMap(compareByDescending { it })
        .map { (day, group) -> day to group.sortedByDescending { it.publishedAt } }
}

@Composable
private fun newsDayLabel(dayStartMs: Long): String {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val days = ((cal.timeInMillis - dayStartMs) / 86_400_000L).toInt()
    return when (days) {
        0 -> appString(R.string.news_day_today)
        1 -> appString(R.string.news_day_yesterday)
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(dayStartMs))
    }
}

/** 资讯总入口：原来设备 / 组件 / 运行库 / 模拟器四处各揣一份新闻，
 *  同一份 data/news-data.json 被加载四次、按 category 切成四段，谁也看不全。
 *  合并成一个一级分区：分类 chips（真实 category）+ 类型 chips（Turnip / Wine 等）+ 按天分组。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsFeedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var newsItems by remember { mutableStateOf<List<NewsItem>?>(null) }
    var query by remember { mutableStateOf("") }
    // 默认落在「模拟器」而不是「全部」：实测全部 267 条里 107 条是泛科技（相机、显示器、
    // 汽车、AI 论文），首屏全是与模拟器/驱动无关的内容，产品定位直接被冲散。
    var category by remember { mutableStateOf("emulator") }
    var typeFilter by remember { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        if (reloadToken > 0) {
            refreshing = true
            NewsRepository.clearCache()
        }
        newsItems = NewsRepository.loadNews(context)
        refreshing = false
    }

    val all = remember(newsItems) { newsItems.orEmpty() }
    val categoryCounts = remember(all) {
        NEWS_FEED_CATEGORIES.associateWith { c -> all.count { it.category == c } }
    }
    // 二级：类型（Turnip / Wine / DXVK…）。只在选中具体分类后出现——
    // 在「全部」下这一行会是全库 top 12 类型，既挤掉首屏又与上面那行分类重复。
    val typeOptions = remember(all, category) {
        all.asSequence()
            .filter { category != "all" && it.category == category }
            .map { it.type }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }
    val showTypes = category != "all" && typeOptions.size >= 3

    val filtered = remember(all, query, category, typeFilter) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        all.filter { n ->
            (category == "all" || n.category == category) &&
                (typeFilter.isBlank() || n.type == typeFilter) &&
                (words.isEmpty() || run {
                    val hay = buildString {
                        append(n.title).append(' ').append(n.summary).append(' ')
                        append(n.type).append(' ').append(n.tag).append(' ')
                        append(n.channel).append(' ').append(n.categoryLabel)
                    }.lowercase()
                    words.all { w -> hay.contains(w) }
                })
        }
    }
    val grouped = remember(filtered) { groupNewsByDay(filtered) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(appString(R.string.news_section_title))
                        Text(
                            appString(R.string.news_feed_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadToken++ }) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "news_search") {
                FusionSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = appString(R.string.news_feed_hint)
                )
            }
            item(key = "news_categories") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 分类 chip 带条数：没有条数的分类不出现，避免点了才知道是空的
                    FusionFilterChip(
                        label = "${appString(R.string.news_chip_all)} ${all.size}",
                        selected = category == "all",
                        onClick = { category = "all"; typeFilter = "" }
                    )
                    NEWS_FEED_CATEGORIES.forEach { c ->
                        val n = categoryCounts[c] ?: 0
                        if (n > 0) {
                            FusionFilterChip(
                                label = "${newsCategoryText(c)} $n",
                                selected = category == c,
                                onClick = { category = c; typeFilter = "" },
                                modifier = Modifier.testTag("news_category_$c")
                            )
                        }
                    }
                }
            }
            if (showTypes) {
                item(key = "news_types") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FusionFilterChip(
                            label = appString(R.string.news_type_all),
                            selected = typeFilter.isBlank(),
                            onClick = { typeFilter = "" }
                        )
                        typeOptions.forEach { t ->
                            FusionFilterChip(
                                label = t,
                                selected = typeFilter == t,
                                onClick = { typeFilter = t }
                            )
                        }
                    }
                }
            }
            // 有筛选条件时才报数：默认视图的条数已由分类 chip 与每天小计表达
            if (category != "all" || typeFilter.isNotBlank() || query.isNotBlank()) {
                item(key = "news_count") {
                    Text(
                        appString(R.string.news_feed_count, filtered.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            when {
                newsItems == null -> item(key = "news_loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                filtered.isEmpty() -> item(key = "news_empty") {
                    Text(
                        appString(R.string.news_feed_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                else -> {
                    grouped.forEach { (day, list) ->
                        item(key = "news_day_$day") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    newsDayLabel(day),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    appString(R.string.news_feed_count, list.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(list, key = { "news_${it.id}" }) { n ->
                            // 复用分区内的新闻行：自带分类徽章、缩略图、点击进详情弹窗
                            NewsRowItem(n)
                        }
                    }
                }
            }
        }
    }
}
