package com.emubox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.template.SectionTabs
import com.emubox.app.ui.theme.Fusion
import kotlinx.coroutines.launch
import java.util.*


/** 模拟器收藏：本地持久化（id 集合） */
private fun loadEmulatorFavs(context: Context): Set<String> =
    context.getSharedPreferences("emubox_favs", Context.MODE_PRIVATE)
        .getStringSet("emulator_favs", emptySet()) ?: emptySet()

private fun saveEmulatorFavs(context: Context, favs: Set<String>) {
    context.getSharedPreferences("emubox_favs", Context.MODE_PRIVATE)
        .edit().putStringSet("emulator_favs", favs).apply()
}


/** 模拟器专区：双列网格，卡片含分类徽章/最新版本/下载/仓库入口 */

/** 原站分类 -> 中文分区名（t3st31 / the412banner 分类体系） */
/** GitHub topics 里的无信息量词：平台/来源/语言这些已经在别处体现，再挂一遍是噪音 */
internal val EMULATOR_TOPIC_NOISE = setOf(
    "android", "emulator", "emulators", "emulation", "windows", "linux", "macos",
    "ios", "java", "kotlin", "cpp", "c", "python", "rust", "open-source",
    "mit-license", "gpl-3.0", "game", "games", "gaming", "app", "tool", "tools"
)

/** 平台标签：数据里是 ps2 / switch 这种 key，界面要给中文 */
internal fun emulatorPlatformLabel(platform: String): String = when (platform) {
    "windows-pc" -> "Windows 游戏"
    "switch" -> "Switch"
    // Switch 2 单独一档：数据里 switch2 ⊂ switch，合并显示会让「Switch 2」
    // 筛选里出现一堆只跑 Switch 1 的项目，反过来也容易被误当成能跑 NS2。
    "switch2" -> "Switch 2"
    "ps5" -> "PS5"
    "ps4" -> "PS4"
    "ps3" -> "PS3"
    "ps2" -> "PS2"
    "ps1" -> "PS1"
    "psp" -> "PSP"
    "psvita" -> "PS Vita"
    "3ds" -> "3DS"
    "nds" -> "NDS"
    "gba" -> "GBA"
    "gbc" -> "GBC"
    "gb" -> "GB"
    "n64" -> "N64"
    "nes" -> "FC / NES"
    "snes" -> "SFC / SNES"
    "gamecube" -> "NGC"
    "wii" -> "Wii"
    "wii-u" -> "Wii U"
    "dreamcast" -> "Dreamcast"
    "saturn" -> "Saturn"
    "xbox" -> "Xbox"
    "xbox360" -> "Xbox 360"
    "arcade" -> "街机"
    "dos" -> "DOS"
    "terminal" -> "终端"
    "ios" -> "iOS"
    "android" -> "安卓"
    "visual-novel" -> "视觉小说"
    "multi" -> "多平台"
    // 以下为 2026-11-12 补齐：数据里在用、但此前落到 else 直接显示 key 的平台
    "nc2000" -> "文曲星 NC2000"
    "atari-st" -> "Atari ST"
    "atari-2600" -> "Atari 2600"
    "atari-jaguar" -> "Atari Jaguar"
    "atari-lynx" -> "Atari Lynx"
    "amiga" -> "Amiga"
    "amstrad-cpc" -> "Amstrad CPC"
    "apple2" -> "Apple II"
    "c64" -> "C64"
    "msx" -> "MSX"
    "pc-88" -> "PC-88"
    "pc-98" -> "PC-98"
    "pc-6001" -> "PC-6001"
    "pc-engine" -> "PC Engine"
    "mega-drive" -> "Mega Drive"
    "master-system" -> "Master System"
    "game-gear" -> "Game Gear"
    "sg-1000" -> "SG-1000"
    "colecovision" -> "ColecoVision"
    "neo-geo" -> "Neo Geo"
    "neo-geo-pocket" -> "Neo Geo Pocket"
    "wonderswan" -> "WonderSwan"
    "virtual-boy" -> "Virtual Boy"
    "pocketstation" -> "PocketStation"
    "philips-p2000" -> "Philips P2000"
    "zeebo" -> "Zeebo"
    "zx-spectrum" -> "ZX Spectrum"
    "model3" -> "Model 3"
    "java-me" -> "J2ME"
    "brew" -> "BREW"
    "symbian" -> "Symbian"
    "windows-mobile" -> "Windows Mobile"
    "pico-8" -> "PICO-8"
    "adventure" -> "文字冒险"
    "web" -> "网页"
    "wiiu" -> "Wii U"
    else -> platform
}

/** 用途分区顺序：与 registry 的 section 字段一致 */
private val EMULATOR_SECTION_ORDER = listOf(
    "console-emulation", "windows-compat", "native-port", "frontend-tool", "vn-engine"
)

/** 分区标签：告诉用户「这一堆是什么」，比按主机名平铺更好找 */
private fun emulatorSectionLabel(section: String): String = when (section) {
    "console-emulation" -> "主机模拟"
    "windows-compat" -> "Windows 兼容"
    "native-port" -> "原生移植"
    "frontend-tool" -> "前端工具"
    "vn-engine" -> "视觉小说引擎"
    else -> section
}

/**
 * 平台家族顺序：按条目数从多到少排（任天堂 89 / 索尼 46 在前），
 * 让最常点的两组落在拇指够得着的左侧。
 */
private val EMULATOR_FAMILY_ORDER = listOf(
    "nintendo", "sony", "retro-computer", "sega",
    "arcade-handheld", "pc", "microsoft", "mobile-legacy"
)

/**
 * 二级轴按分区换。统一套平台家族会让两个分区失效：
 * windows-compat 49/50 全落 pc 一族（二级被「少于 3 族隐藏」吞掉），
 * frontend-tool 22 条无家族、剩下 10 条按「服务的主机」归类（PS5 工具落索尼系）。
 * 所以这两个分区改按容器方案 / 类型分，键值由 scripts/derive_subcategory.py 派生。
 */
private val SECTION_FAMILY_ORDER = mapOf(
    "windows-compat" to listOf(
        "winlator", "gamehub", "bannerhub", "other-container", "launcher-tool", "desktop"
    ),
    "frontend-tool" to listOf("frontend", "tool", "emulator"),
)

/** 第三级「具体机型」只在这两个分区有意义；容器方案/类型分组下面没有机型可言 */
private val SECTION_WITH_PLATFORM_ROW = setOf("all", "console-emulation", "native-port")

private fun emulatorFamilyKeys(e: EmulatorEntry, section: String): List<String> =
    when (section) {
        "windows-compat", "frontend-tool" ->
            listOfNotNull(e.subcategory.takeIf { it.isNotBlank() })
        else -> e.platformFamilies
    }

/** 二级 chip 文案：同一套 key 在不同分区含义不同（frontend 在前端区是「启动器」，不是平台） */
private fun emulatorFamilyLabel(section: String, family: String): String = when (section) {
    "windows-compat" -> when (family) {
        "winlator" -> "Winlator 系"
        "gamehub" -> "GameHub 系"
        "bannerhub" -> "BannerHub 系"
        "other-container" -> "其他容器"
        "launcher-tool" -> "启动器与工具"
        "desktop" -> "桌面 / 主机端"
        else -> family
    }
    "frontend-tool" -> when (family) {
        "frontend" -> "前端启动器"
        "tool" -> "工具"
        "emulator" -> "独立模拟器"
        else -> family
    }
    else -> emulatorFamilyLabel(family)
}

/** 家族标签：一级分区太粗（主机模拟 223 条），家族是「先认厂商，再认机型」的中间层 */
private fun emulatorFamilyLabel(family: String): String = when (family) {
    "sony" -> "索尼系"
    "nintendo" -> "任天堂系"
    "microsoft" -> "微软系"
    "sega" -> "世嘉系"
    "arcade-handheld" -> "街机与掌机"
    "retro-computer" -> "复古电脑"
    "mobile-legacy" -> "老移动系统"
    "pc" -> "PC 与移动端"
    else -> family
}

/**
 * 家族 → 机型。用于「进了家族只看自家机型」：
 * 一条多机型条目可能 platforms=[dreamcast, gb]，落 sega + nintendo 两个家族，
 * 不按这张表过滤的话，「世嘉系」下面会冒出 GB，「微软系」下面会冒出 PS3。
 * 与 scripts/derive_platform_families.py 的 FAMILIES 一一对应，改一边要改两边。
 */
private val EMULATOR_FAMILY_PLATFORMS: Map<String, Set<String>> = mapOf(
    "sony" to setOf("ps1", "ps2", "ps3", "ps4", "ps5", "psp", "psvita", "pocketstation"),
    "nintendo" to setOf(
        "nes", "snes", "n64", "gamecube", "wii", "wiiu", "switch", "switch2",
        "gb", "gbc", "gba", "nds", "3ds", "virtual-boy"
    ),
    "microsoft" to setOf("xbox", "xbox360"),
    "sega" to setOf(
        "dreamcast", "saturn", "mega-drive", "master-system", "game-gear",
        "sg-1000", "model3"
    ),
    "arcade-handheld" to setOf(
        "arcade", "neo-geo", "neo-geo-pocket", "wonderswan", "pc-engine",
        "atari-2600", "atari-jaguar", "atari-lynx", "colecovision"
    ),
    "retro-computer" to setOf(
        "dos", "amiga", "c64", "msx", "apple2", "pc-98", "zx-spectrum",
        "atari-st", "pc-6001", "amstrad-cpc", "philips-p2000", "nc2000", "pico-8"
    ),
    "mobile-legacy" to setOf("java-me", "symbian", "brew", "windows-mobile", "zeebo"),
    "pc" to setOf("windows-pc", "ios", "web"),
)

/**
 * 模拟器搜索文本：覆盖「用户实际会输入的东西」。
 * 原来只匹配 name/summary/description，搜「EX-Emulators.zip」「世嘉」「ps2」
 * 全部落空——用户以为库里没有，其实是没进匹配字段。
 * 平台、家族、分区、分类都同时写英文 key 和中文标签，中英文都能搜到。
 */
internal fun emulatorSearchText(e: EmulatorEntry): String = buildString {
    append(e.name).append(' ')
    append(e.nameZh).append(' ')
    append(e.summary).append(' ')
    append(e.description).append(' ')
    append(e.owner).append(' ')
    append(e.repo).append(' ')
    append(e.latestTag).append(' ')
    append(e.latestApkName).append(' ')
    e.platforms.forEach { append(it).append(' ').append(emulatorPlatformLabel(it)).append(' ') }
    e.platformFamilies.forEach { append(it).append(' ').append(emulatorFamilyLabel(it)).append(' ') }
    // Windows 兼容/前端工具的二级键（winlator / frontend…）也要能搜到中文标签
    if (e.subcategory.isNotBlank()) {
        append(e.subcategory).append(' ').append(emulatorFamilyLabel(e.section, e.subcategory)).append(' ')
    }
    append(e.section).append(' ').append(emulatorSectionLabel(e.section)).append(' ')
    append(e.category).append(' ').append(emulatorCategoryLabel(e.category))
}.lowercase()

/** 驱动搜索文本：驱动包文件名（wrapper zip）是用户最常搜的东西，必须进匹配 */
internal fun driverSearchText(d: DriverRegistryEntry): String = buildString {
    append(d.name).append(' ')
    append(d.description).append(' ')
    append(d.notes).append(' ')
    append(d.owner).append(' ')
    append(d.repo).append(' ')
    // gpu_vendor 存的是 adreno / mali / xclipse，用户会输入「骁龙」「高通」
    append(
        when (d.gpuVendor) {
            "adreno" -> "adreno 高通 骁龙 snapdragon"
            "mali" -> "mali arm 马里"
            "xclipse" -> "xclipse 三星 samsung rdna"
            else -> d.gpuVendor
        }
    ).append(' ')
    append(d.driverType).append(' ')
    append(d.packageFormat).append(' ')
    append(d.frontend.orEmpty()).append(' ')
    append(d.targetEmulator).append(' ')
    append(d.latestTag).append(' ')
    d.gpuModels.forEach { append(it).append(' ') }
    d.gpuArch.forEach { append(it).append(' ') }
}.lowercase()

internal fun emulatorCategoryLabel(category: String): String = when (category) {
    "Winlator" -> "Winlator 专区"
    "GameHub" -> "GameHub 专区"
    "PC Emulator" -> "PC 模拟器"
    "GameNative" -> "GameNative 专区"
    "All In One" -> "聚合前端"
    "Nintendo 3DS" -> "任天堂 3DS"
    "PSVITA" -> "PS Vita"
    "Emulator PS2" -> "PS2 模拟"
    "Emulator PS3" -> "PS3 模拟"
    "Emulator PS5" -> "PS5 模拟"
    "Wii U Emulator" -> "Wii U 模拟"
    "Sega Dreamcast" -> "Dreamcast 模拟"
    "Nintendo GameCube / Wii" -> "NGC / Wii 模拟"
    "Xbox" -> "Xbox 模拟"
    "Nintendo Switch Emulator" -> "Switch 模拟"
    "GAME" -> "游戏"
    "PlayStation 1 Emulator" -> "PS1 模拟"
    "Nintendo DS Emulator" -> "NDS 模拟"
    "Game Boy Advance Emulator" -> "GBA 模拟"
    "Nintendo 64 Emulator" -> "N64 模拟"
    "Arcade Emulator" -> "街机模拟"
    "Symbian Emulator" -> "塞班模拟"
    "Java Emulator" -> "Java 模拟"
    "iOS Emulator" -> "iOS 模拟"
    "Game Port" -> "游戏移植"
    "Super Nintendo Emulator" -> "SFC 模拟"
    "Sega Model 3 Emulator" -> "Model 3 模拟"
    "Atari Emulator" -> "雅达利模拟"
    "Brew Emulator" -> "Brew 模拟"
    "Pico-8 Emulator" -> "Pico-8 模拟"
    "terminal" -> "终端模拟"
    "console" -> "主机模拟"
    "multi" -> "多平台聚合"
    else -> category
}
/** 打开新闻原文（系统浏览器） */
internal fun openNewsLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 新闻分类色块 */
private fun newsCategoryColor(category: String): Color = when (category) {
    "emulator" -> Color(0xFF059669)
    "driver" -> Color(0xFFEA580C)
    "runtime" -> Color(0xFF7C3AED)
    "component" -> Color(0xFF0D9488)
    "device" -> Color(0xFF2563EB)
    "eco" -> Color(0xFF2563EB)
    else -> Color(0xFF64748B)   // tech 泛科技
}

/** 新闻条目行（瘦身版）：分类标签 + 标题 + 一行摘要 + 时间。
 *  点击打开 App 内全文弹窗（不再跳外链，不暴露来源站名）。
 *  时间用相对时间，来源名一律不展示。 */
@Composable
internal fun NewsRowItem(item: NewsItem) {
    var showDetail by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { showDetail = true }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.image.isNotBlank()) {
            AsyncImage(
                model = item.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 88.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(newsCategoryColor(item.category).copy(alpha = 0.13f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                item.categoryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = newsCategoryColor(item.category)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    item.summary,
                    // 摘要是一句话，1 行只够看半句主谓宾；2 行能读完整句
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRelativeTimeMs(item.publishedAt * 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.commentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· ${item.commentCount} 条评论",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (showDetail) {
        NewsDetailDialog(item = item, onDismiss = { showDetail = false })
    }
}

/** 新闻全文弹窗（近全屏，仿指南窗口）：标题 + 分类 + 时间 + 正文。
 *  正文优先用 data.body（资讯正文 / release 完整日志），无全文时回退摘要。
 *  官方组件/运行库/驱动条目（type 非空）提供"翻译"与"查看原文"按钮；
 *  资讯类条目不暴露外链（来源站名不出现在任何位置）。 */
@Composable
private fun NewsDetailDialog(item: NewsItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translated by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    val isOfficial = item.type.isNotBlank()
    val baseText = item.body.takeIf { it.isNotBlank() } ?: item.summary
    val showText = translated ?: baseText
    Dialog(onDismissRequest = onDismiss) {
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
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // 顶栏：分类 + 标题 + 关闭
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(newsCategoryColor(item.category).copy(alpha = 0.13f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            item.categoryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = newsCategoryColor(item.category)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                    }
                }
                Text(
                    formatRelativeTimeMs(item.publishedAt * 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // 正文（可滚动）
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showText.isBlank()) {
                        Text(
                            appString(if (isOfficial) R.string.news_no_body_official else R.string.news_no_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // 封面图：设备/生态类报道的信息有一半在图里，只有文字等于缺内容
                            if (item.image.isNotBlank()) {
                                AsyncImage(
                                    model = item.image,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            // Release 正文是 Markdown 原文，直接 Text 渲染会把 ## / ** / [文字](网址)
                            // 原样显示出来；翻译结果已是纯文本，不再处理
                            val transSnapshot = translated
                            val shownBody = remember(item.id, transSnapshot, baseText) {
                                if (transSnapshot != null) transSnapshot.trim()
                                else if (isOfficial) cleanReleaseBody(baseText)
                                else baseText
                            }
                            Text(shownBody, style = MaterialTheme.typography.bodyMedium)
                            // 正文内嵌图（GitHub user-attachments / <img>）：示例图、对比图都在这里，
                            // 只渲染文字等于丢掉一半内容
                            val inlineImgs = remember(item.id) {
                                if (isOfficial) inlineImages(item.body) else emptyList()
                            }
                            inlineImgs.forEach { url ->
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            }
                        }
                    }
                }
                // 底部操作
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 设备/泛科技报道本来就是中文，翻译与「查看原文」在这里没有意义；
                    // 组件/驱动这类英文 release 才保留
                    val showActions = baseText.isNotBlank() &&
                        item.category !in setOf("device", "tech", "eco")
                    if (showActions) {
                        OutlinedButton(
                            onClick = {
                                if (translated == null) {
                                    translating = true
                                    scope.launch {
                                        translated = translateText(baseText)
                                        translating = false
                                    }
                                } else {
                                    translated = null
                                }
                            },
                            enabled = !translating
                        ) {
                            Text(
                                when {
                                    translating -> appString(R.string.translating)
                                    translated != null -> appString(R.string.show_original)
                                    else -> appString(R.string.translate)
                                }
                            )
                        }
                        OutlinedButton(onClick = { openNewsLink(context, item.url) }) {
                            Text(appString(R.string.view_original))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(appString(R.string.close)) }
                }
            }
        }
    }
}

/** Release 正文的 Markdown 轻量清理：留文字、去标记。
 *  这里不做完整 Markdown 渲染（引入 parser 会把 60MB 包再撑大，且 release 正文以段落为主），
 *  只处理正文里实际出现的几种标记：标题井号、粗体/行内代码星号、链接括号、引用符号。 */
private fun cleanReleaseBody(raw: String): String {
    if (raw.isBlank()) return raw
    var t = raw
    // 图片单独成区展示，正文里先摘掉，避免留下空括号
    t = t.replace(Regex("!\\[[^\\]]*\\]\\([^)\\s]+\\)"), "")
    t = t.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
    // [文字](链接) → 文字：正文里带一长串网址会让段落完全没法读
    t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    // 标题符号（行首 #）
    t = t.replace(Regex("(?m)^#{1,6}\\s*"), "")
    // 粗体 / 斜体 / 行内代码
    t = t.replace(Regex("\\*\\*([^*\\n]+)\\*\\*"), "$1")
    t = t.replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])"), "$1")
    t = t.replace(Regex("`([^`\\n]+)`"), "$1")
    // 引用与分割线
    t = t.replace(Regex("(?m)^&gt;\\s?"), "")
    t = t.replace(Regex("(?m)^>\\s?"), "")
    t = t.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
    // HTML 换行/段落标签
    t = t.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    t = t.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    t = t.replace(Regex("<[^>]+>"), "")
    // 连续空行压成一段间隔
    t = t.replace(Regex("\\n{3,}"), "\n\n")
    return t.trim()
}

/** 提取正文内嵌图片地址（Markdown 与 HTML 两种写法），去重并保持顺序，最多 6 张 */
private fun inlineImages(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val urls = buildList {
        Regex("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)").findAll(raw).forEach { add(it.groupValues[1]) }
        Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(raw)
            .forEach { add(it.groupValues[1]) }
    }
    return urls
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
        .take(6)
}

@Composable
internal fun NewsPager(
    total: Int,
    page: Int,
    pageSize: Int,
    onPage: (Int) -> Unit
) {
    if (total <= pageSize) return
    val maxPage = ((total + pageSize - 1) / pageSize).coerceAtMost(20)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { onPage(0) },
            enabled = page > 0,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("首页", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = { onPage((page - 1).coerceAtLeast(0)) },
            enabled = page > 0,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("上一页", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "${page + 1} / $maxPage",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { onPage((page + 1).coerceAtMost(maxPage - 1)) },
            enabled = page < maxPage - 1,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("下一页", style = MaterialTheme.typography.labelSmall)
        }
    }
}


/**
 * 分区 → 新闻条目的归属判定。
 *
 * 数据管道只产生 emulator / device / component / driver / tech 五类，
 * 但页面上有「运行库」分区。一直用 category == "runtime" 严格比对，
 * 数据里根本没有这个值 —— 运行库资讯因此恒为空（用户看到的就是「没有信息」）。
 *
 * 按既定口径拆 component：Wine / Box64 / Proton / FEX 属「组件」，
 * DXVK / VKD3D(D7VK) / Wine Mono / Wine Gecko / Mono 属「运行库」。
 */

@Composable
fun EmulatorScreen(
    modifier: Modifier = Modifier,
    entries: List<EmulatorEntry>,
    insights: EmulatorInsights = EmulatorInsights(),
    onDownload: (EmulatorEntry) -> Unit,
    /** 从设备页「可玩库」跳过来时带的平台值（ps2 / switch …）；n>0 才生效 */
    platformSeed: String = "",
    platformSeedNonce: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sortMode by remember { mutableStateOf("popular") }
    var detailEntry by remember { mutableStateOf<EmulatorEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    // 本地收藏（持久化）与视图模式
    var favorites by remember { mutableStateOf(loadEmulatorFavs(context)) }
    var viewMode by rememberSaveable { mutableStateOf("grid") }
    var onlyFav by remember { mutableStateOf(false) }
    // 对比槽（t3st31 compareSlots 移植）：最多 3 个项目
    var compareIds by remember { mutableStateOf(listOf<String>()) }
    var showCompare by remember { mutableStateOf(false) }
    // 两级筛选：先按用途分区（5 类，稳定），再按具体平台/家族细分。
    // 之前是一行 60+ 个分类 chips 横滑，找「PS2」要划半天。
    var filterSection by rememberSaveable { mutableStateOf("all") }
    val sectionOrder = remember(entries) {
        val seen = LinkedHashSet<String>()
        entries.forEach { if (it.section.isNotBlank()) seen.add(it.section) }
        EMULATOR_SECTION_ORDER.filter { it in seen } .ifEmpty { seen.toList() }
    }
    // 三级筛选：分区（5 类）→ 平台家族（8 个谱系）→ 具体机型。
    // 二级原来是具体平台平铺（前 18 个），PS5 / Switch / DOS 混在一行，
    // 且第 19 名之后的平台（atari-2600、colecovision、pico-8…）没有任何入口。
    // 中间插一层「家族」后，机型只在家族内出现，长尾也能全部列出。
    var filterPlatform by rememberSaveable { mutableStateOf("all") }
    var filterFamily by rememberSaveable { mutableStateOf("all") }

    // 可玩库跳转：平台值不在前 18 个机型里时，直接设 filterPlatform 会选中一个看不见的 chip，
    // 所以先用一条样本条目反查它属于哪个分区与家族，把三级筛选一起摆到位。
    LaunchedEffect(platformSeed, platformSeedNonce) {
        if (platformSeed.isNotBlank() && platformSeed != "all") {
            val sample = entries.firstOrNull { it.platforms.contains(platformSeed) }
            if (sample != null) {
                filterSection = sample.section
                filterFamily = sample.platformFamilies.firstOrNull() ?: "all"
            }
            filterPlatform = platformSeed
        }
    }
    val familyOrder = remember(entries, filterSection) {
        val count = HashMap<String, Int>()
        entries.filter { filterSection == "all" || it.section == filterSection }
            .forEach { e ->
                emulatorFamilyKeys(e, filterSection).forEach { count[it] = (count[it] ?: 0) + 1 }
            }
        val order = SECTION_FAMILY_ORDER[filterSection] ?: EMULATOR_FAMILY_ORDER
        val hit = order.filter { (count[it] ?: 0) > 0 }
        // 只有一两族时插这行没意义（视觉小说 3 条、老移动 13 条都不值得多一次点击）
        if (hit.size >= 3) hit else emptyList()
    }
    val platformOrder = remember(entries, filterSection, filterFamily) {
        if (filterSection !in SECTION_WITH_PLATFORM_ROW) emptyList() else {
            val count = HashMap<String, Int>()
            val own = EMULATOR_FAMILY_PLATFORMS[filterFamily]
            entries.filter { filterSection == "all" || it.section == filterSection }
                .filter { filterFamily == "all" || it.platformFamilies.contains(filterFamily) }
                .forEach { e ->
                    e.platforms.forEach { p ->
                        // 选了家族就只收自家机型，别把跨家族条目的其它机型也列出来
                        if (own == null || p in own) count[p] = (count[p] ?: 0) + 1
                    }
                }
            val sorted = count.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            // 进了具体家族就全列（家族内最多几十个机型），「全部」时才截断防爆行
            if (filterFamily == "all") sorted.take(18) else sorted
        }
    }

    // 搜索索引：一次算好 354 条的匹配文本，别在每次按键时重拼字符串。
    val searchIndex = remember(entries) {
        entries.associate { it.id to emulatorSearchText(it) }
    }

    val filtered = remember(entries, filterSection, filterFamily, filterPlatform, sortMode, searchQuery, onlyFav, favorites, searchIndex) {
        val base = entries
            .filter { filterSection == "all" || it.section == filterSection }
            .filter { filterFamily == "all" || emulatorFamilyKeys(it, filterSection).contains(filterFamily) }
            .filter { filterPlatform == "all" || it.platforms.contains(filterPlatform) }
        val favFiltered = if (onlyFav) base.filter { it.id in favorites } else base
        // 空格分词 = AND：搜「索尼 ps2」要求两个词都命中，而不是匹配整串
        val terms = searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val searched = if (terms.isEmpty()) favFiltered
        else favFiltered.filter { e ->
            val hay = searchIndex[e.id].orEmpty()
            terms.all { t -> hay.contains(t) }
        }
        when (sortMode) {
            "stars" -> searched.sortedByDescending { it.stars }
            "recent" -> searched.sortedByDescending { it.pushedAt }
            "forks" -> searched.sortedByDescending { it.forks }
            else -> searched.sortedByDescending { it.downloads + it.stars * 10L }
        }
    }

    // 更新动态：全部条目最新版本按时间倒序
    val newsList = remember(entries) {
        entries
            .filter { it.latestDate.isNotBlank() && it.latestTag.isNotBlank() }
            .sortedByDescending { it.latestDate }
            .take(10)
    }

    // 精选大卡：综合热度最高条目（方案 C hero 区）
    // 排序结果留着：英雄卡要说明「凭什么推它」，排名是最直接的一条依据。
    val heroRanking = remember(entries) { entries.sortedByDescending { it.downloads + it.stars * 10L } }
    val topEntry = heroRanking.firstOrNull()
    val heroReason = remember(topEntry, heroRanking.size) {
        topEntry?.let { heroReasonText(it, heroRanking.size) }.orEmpty()
    }

    // 分区/平台切换在顶部两行 chips，列表直接平铺，不再用折叠区块
    val flatItems = filtered

    // 模拟器页子分区：0 项目 / 1 推荐 / 2 动态
    // 原来「推荐+动态+榜单+热度+配方」全塞进列表末尾一个默认收起的折叠条，
    // 用户根本看不到，也不该滑到最底才有内容。
    var emuTab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
    SectionTabs(
        tabs = listOf(
            appString(R.string.emu_tab_projects),
            appString(R.string.emu_tab_picks),
            appString(R.string.emu_tab_dynamic),
        ),
        selected = emuTab,
        onSelect = { emuTab = it },
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (emuTab == 0) item(key = "emulator_region_header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // 融合风渐变标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                ) {
                    Text(
                        appString(R.string.emulator_region),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Fusion.OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${entries.size} 款",
                        fontSize = 12.sp,
                        color = Fusion.OnSurfaceFainter
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 渐变搜索条（方案 C）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Fusion.SurfaceAlt, Fusion.SurfaceAlt.copy(alpha = 0.9f))
                            )
                        )
                        .border(1.dp, Fusion.BorderAlt, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = Fusion.OnSurfaceDim
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    appString(R.string.emulator_search_hint),
                                    fontSize = 14.sp,
                                    color = Fusion.OnSurfaceFainter
                                )
                            }
                            inner()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            "×",
                            fontSize = 15.sp,
                            color = Fusion.OnSurfaceFainter,
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }
            }
        }
        if (emuTab == 0) item(key = "emulator_filter", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // 一级：用途分区
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("all") .plus(sectionOrder).forEach { sec ->
                        FusionFilterChip(
                            label = if (sec == "all") appString(R.string.emulator_cat_all)
                            else emulatorSectionLabel(sec),
                            selected = filterSection == sec,
                            onClick = {
                                filterSection = sec
                                // 切分区时下面两级一起重置，否则会停在上一个分区才有的家族/机型上
                                filterFamily = "all"
                                filterPlatform = "all"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 二级：主机模拟/原生移植按平台家族，Windows 兼容按容器方案，前端工具按类型。
                // 少于 3 组的分区不出这一行——插一层等于多一次点击。
                if (familyOrder.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("all").plus(familyOrder).forEach { f ->
                            FusionFilterChip(
                                label = if (f == "all") {
                                    if (filterSection == "console-emulation") "全部厂商" else "全部"
                                } else emulatorFamilyLabel(filterSection, f),
                                selected = filterFamily == f,
                                onClick = {
                                    filterFamily = f
                                    // 换家族时机型必须重置：PS2 不属于任天堂系
                                    filterPlatform = "all"
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 三级：具体机型。进了家族就全列（家族内不会有几十个），
                // 「全部」时截断 18 个，避免一行滑不到头。容器/类型分组下面没有机型，整行不出。
                if (platformOrder.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("all").plus(platformOrder).forEach { p ->
                            FusionFilterChip(
                                label = if (p == "all") appString(R.string.emulator_platform_all)
                                else emulatorPlatformLabel(p),
                                selected = filterPlatform == p,
                                onClick = { filterPlatform = p }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 排序原来是 4 个 emoji 按钮（🔥 ★ ⑂ 🕒），含义全靠猜；
                // 换成带文字的下拉，当前排序直接可读。
                var sortMenuOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        appString(R.string.emulator_sort_by),
                        style = MaterialTheme.typography.labelMedium,
                        color = Fusion.OnSurfaceDim
                    )
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Text(
                                when (sortMode) {
                                    "stars" -> appString(R.string.emulator_sort_stars)
                                    "recent" -> appString(R.string.emulator_sort_recent)
                                    "forks" -> appString(R.string.emulator_sort_forks)
                                    else -> appString(R.string.emulator_sort_popular)
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            listOf("popular", "stars", "recent", "forks").forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (s) {
                                                "stars" -> appString(R.string.emulator_sort_stars)
                                                "recent" -> appString(R.string.emulator_sort_recent)
                                                "forks" -> appString(R.string.emulator_sort_forks)
                                                else -> appString(R.string.emulator_sort_popular)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (s == sortMode) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        sortMode = s
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
if (emuTab == 0) item(key = "emulator_tools", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appString(R.string.emulator_result_count, flatItems.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = Fusion.OnSurfaceDim
                )
                Spacer(Modifier.weight(1f))
                IconToggleButton(
                    checked = onlyFav,
                    onCheckedChange = { onlyFav = it },
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        if (onlyFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (onlyFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewMode = if (viewMode == "grid") "list" else "grid" }) {
                    Icon(
                        if (viewMode == "grid") Icons.Default.List else Icons.Default.GridView,
                        contentDescription = null
                    )
                }
            }
        }
        if (compareIds.isNotEmpty()) {
            val compareEntries = compareIds.mapNotNull { id -> entries.firstOrNull { it.id == id } }
            if (compareEntries.isNotEmpty()) {
                if (emuTab == 0) item(key = "compare_bar", span = { GridItemSpan(maxLineSpan) }) {
                    CompareBar(
                        projects = compareEntries,
                        onOpen = { showCompare = true },
                        onClear = { compareIds = emptyList() }
                    )
                }
            }
        }
        // 空态：区分"清单还没拉回来"与"筛选没命中"，后者给出清除筛选入口。
        // 旧实现两种情况都显示"正在加载模拟器列表…"，搜索无结果时用户会一直等。
        if (flatItems.isEmpty()) {
            if (emuTab == 0) item(key = "emulator_empty_state", span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Fusion.Surface)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        if (entries.isEmpty()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            Text(appString(R.string.emulator_loading))
                        } else {
                            Text(
                                appString(R.string.emulator_filter_empty),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                appString(
                                    R.string.emulator_filter_detail,
                                    searchQuery.ifBlank { appString(R.string.emulator_cat_all) },
                                    if (onlyFav) appString(R.string.emulator_fav_only) else ""
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Fusion.OnSurfaceDim
                            )
                            Spacer(Modifier.height(10.dp))
                            FilledTonalButton(
                                onClick = {
                                    searchQuery = ""
                                    // 三级筛选任一层都可能把结果筛空，一键重置要全部回到「全部」
                                    filterSection = "all"
                                    filterFamily = "all"
                                    filterPlatform = "all"
                                    onlyFav = false
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(appString(R.string.clear_filters))
                            }
                        }
                    }
                }
            }
        }
        // 项目网格只属于「项目」tab。旧实现无条件铺满全部条目，
        // 推荐/动态的概览块被排到第 353 张卡之后，两个 tab 首屏逐像素相同。
        val gridEntries = if (emuTab == 0) flatItems else emptyList()
        gridEntries.forEach { entry ->
            item(key = entry.id, span = { GridItemSpan(if (viewMode == "list") maxLineSpan else 1) }) {
                if (viewMode == "list") {
                    EmulatorListRow(
                        entry = entry,
                        isFavorite = entry.id in favorites,
                        onToggleFavorite = {
                            favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
                            saveEmulatorFavs(context, favorites)
                        },
                        inCompare = entry.id in compareIds,
                        onToggleCompare = {
                            if (entry.id in compareIds) {
                                compareIds = compareIds - entry.id
                            } else if (compareIds.size < 3) {
                                compareIds = compareIds + entry.id
                            } else {
                                // 选满 3 个后再点原来毫无反应，用户以为按钮坏了
                                Toast.makeText(
                                    context,
                                    appStringFor(
                                    context,
                                    SettingsManager.getAppLanguage(),
                                    R.string.compare_limit_hint
                                ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onClickDetail = { detailEntry = entry },
                        onDownload = onDownload
                    )
                } else {
                    EmulatorCard(
                        entry = entry,
                        isFavorite = entry.id in favorites,
                        onToggleFavorite = {
                            favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
                            saveEmulatorFavs(context, favorites)
                        },
                        inCompare = entry.id in compareIds,
                        onToggleCompare = {
                            if (entry.id in compareIds) {
                                compareIds = compareIds - entry.id
                            } else if (compareIds.size < 3) {
                                compareIds = compareIds + entry.id
                            } else {
                                // 选满 3 个后再点原来毫无反应，用户以为按钮坏了
                                Toast.makeText(
                                    context,
                                    appStringFor(
                                    context,
                                    SettingsManager.getAppLanguage(),
                                    R.string.compare_limit_hint
                                ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onClickDetail = { detailEntry = entry },
                        onDownload = onDownload
                    )
                }
            }
        }
        // 概览区（推荐 / 动态 / 榜单）整体移到列表之后。
        // 列表型页面的第一任务是「找到并下载」：首屏必须见到结果，
        // 而不是先滚过英雄卡 + 简介 + 统计 + 排序说明才看到第一条。
        // 概览区（英雄卡 / 简介 / 统计 / 更新动态）与工具栏在列表非空时同样显示。
        // 旧实现把整块包在 flatItems.isEmpty() 里，真实数据一到位就全部消失。
        if (emuTab != 0) item(key = "emulator_dynamic", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (emuTab == 1) {
                        topEntry?.let { hero ->
                            EmulatorHeroCard(
                                entry = hero,
                                categoryLabel = emulatorCategoryLabel(hero.category),
                                reason = heroReason,
                                onClickDetail = { detailEntry = hero }
                            )
                        }
                        Text(
                            appString(R.string.emulator_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        val officialCount = entries.count { it.official }
                        val direct = entries.count { it.latestApkUrl.isNotBlank() }
                        Text(
                            appString(R.string.emulator_summary, entries.size, officialCount, entries.size - officialCount, direct),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        }
                        if (emuTab == 2 && insights.totalProjects > 0) {
                            EmulatorInsightsCard(insights = insights)
                        }
                        val news = newsList
                        if (emuTab == 2 && news.isNotEmpty()) {
                            EmulatorNewsCard(news = news, onOpen = { detailEntry = it })
                        }
                        if (emuTab == 2 && insights.trending14.isNotEmpty()) {
                            EmulatorTrendingCard(
                                trending = insights.trending14,
                                baseDate = insights.trendBaseDate,
                                latestDate = insights.trendLatestDate,
                                entries = entries,
                                onOpen = { detailEntry = it }
                            )
                        }
                        if (emuTab == 2 && insights.categoryDownloads.size > 1) {
                            EmulatorCategoryShareCard(categories = insights.categoryDownloads)
                        }
                        if (emuTab == 2 && insights.recipes.isNotEmpty()) {
                            EmulatorRecipesCard(recipes = insights.recipes)
                        }
                    }
            }
        }
    }
    }

    detailEntry?.let { entry ->
        EmulatorDetailDialog(
            initialEntry = entry,
            onDismiss = { detailEntry = null },
            onDownloadAsset = { url, name ->
                AppDownloadScope.launch { downloadEmulatorAsset(context, entry.name, url, name) }
            }
        )
    }

    if (showCompare) {
        val compareEntries = compareIds.mapNotNull { id -> entries.firstOrNull { it.id == id } }
        if (compareEntries.size >= 2) {
            EmulatorCompareDialog(
                projects = compareEntries,
                onRemove = { id -> compareIds = compareIds - id },
                onDismiss = { showCompare = false }
            )
        } else {
            showCompare = false
        }
    }
}
