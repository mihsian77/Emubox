package com.emubox.app

import android.app.ActivityManager
import android.content.Context
import java.util.Locale
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 物理内存（MB）。拿到 0 表示读不到，调用方按「不设门槛」处理。 */
private fun totalRamMb(context: Context): Long = runCatching {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    info.totalMem / (1024L * 1024L)
}.getOrDefault(0L)

/**
 * 档位色。浅色下用深一档，深色下提亮，否则在深色主题里绿色会糊成一片灰。
 * 三个色只承担「一眼分辨」，具体结论由文字给出。
 */
@Composable
private fun gradeColor(grade: PlayGrade): Color {
    val dark = isSystemInDarkTheme()
    return when (grade) {
        PlayGrade.SMOOTH -> if (dark) Color(0xFF7BD88F) else Color(0xFF2E7D32)
        PlayGrade.PLAYABLE -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        PlayGrade.TIGHT -> if (dark) Color(0xFFBDBDBD) else Color(0xFF757575)
        PlayGrade.UNKNOWN -> if (dark) Color(0xFF9E9E9E) else Color(0xFF9E9E9E)
    }
}

@Composable
private fun gradeLabel(grade: PlayGrade): String = when (grade) {
    PlayGrade.SMOOTH -> appString(R.string.playable_grade_smooth)
    PlayGrade.PLAYABLE -> appString(R.string.playable_grade_playable)
    PlayGrade.TIGHT -> appString(R.string.playable_grade_tight)
    PlayGrade.UNKNOWN -> appString(R.string.playable_grade_unknown)
}

// 间距刻度：组与组 22、组标题到首行 8、行与行 8。三档足够拉开层级，
// 逐处手调会出现「这里 12 那里 14」的节奏断裂。
private val RowGap = 8.dp
private val GroupGap = 22.dp

/**
 * 设备页「可玩库」：按本机芯片 GPU 分与内存，把 15 个平台分成
 * 流畅 / 勉强 / 吃力 三组。
 *
 * 信息分两层：收起时只给「平台 + 比值 + 能力说明」，一眼扫完；
 * 点开才展开精确门槛、差距与同芯片社区实测。15 个平台全展开会有 60 行，
 * 默认全铺等于什么都没说。
 *
 * 为什么不做成「推荐 3 个模拟器」：用户问的是「我这台能不能跑 PS2」，
 * 平台是可验证的判断，具体项目会随仓库活跃度变，给结论比给清单稳。
 *
 * @param onOpenPlatform 点「在库里查看」跳模拟器页并按平台筛选
 * @param onOpenDrivers 点「下一步」跳驱动区
 * @param defaultExpandedKey 预览/测试用：指定默认展开的平台
 */
@Composable
fun PlayableLibrarySection(
    entries: List<EmulatorEntry>,
    onOpenPlatform: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 预览/测试注入用：Robolectric 下 Build.SOC_MODEL 是空的，解析不出芯片 */
    socOverride: ResolvedSoc? = null,
    onOpenDrivers: (() -> Unit)? = null,
    defaultExpandedKey: String? = null
) {
    val context = LocalContext.current
    val soc = remember(socOverride) {
        socOverride ?: runCatching { SocResolver.resolveCurrent(context) }.getOrNull()
    }
    val ramMb = remember { totalRamMb(context) }
    val report = remember(soc, ramMb, entries) {
        runCatching { PlayableCatalog.evaluate(context, soc, ramMb, entries) }.getOrNull()
    }
    var expandedKey by remember(defaultExpandedKey) { mutableStateOf(defaultExpandedKey) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (report == null) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Text(
                    appString(R.string.playable_load_failed),
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        val grouped = report.platforms.groupBy { it.grade }
        PlayableSummaryCard(report = report, ramMb = ramMb, grouped = grouped)

        listOf(
            PlayGrade.SMOOTH to R.string.playable_grade_smooth,
            PlayGrade.PLAYABLE to R.string.playable_grade_playable,
            PlayGrade.TIGHT to R.string.playable_grade_tight,
            PlayGrade.UNKNOWN to R.string.playable_grade_unknown,
        ).forEach { (grade, labelRes) ->
            val items = grouped[grade].orEmpty()
            if (items.isEmpty()) return@forEach
            Spacer(Modifier.height(GroupGap))
            PlayableGroupHeader(grade = grade, labelRes = labelRes)
            Spacer(Modifier.height(RowGap))
            items.forEachIndexed { index, p ->
                if (index > 0) Spacer(Modifier.height(RowGap))
                PlayablePlatformRow(
                    platform = p,
                    score = report.score,
                    ramMb = ramMb,
                    expanded = expandedKey == p.key,
                    onToggle = { expandedKey = if (expandedKey == p.key) null else p.key },
                    onOpen = { p.match.firstOrNull()?.let(onOpenPlatform) }
                )
            }
        }

        if (report.experimental.isNotEmpty()) {
            Spacer(Modifier.height(GroupGap))
            Text(
                appString(R.string.playable_experimental_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(RowGap))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    report.experimental.forEachIndexed { index, e ->
                        // 原因一行放不下（「shadPS4 系，仅少数独立游戏可进」），
                        // 挤在同行会把平台名压成省略号，所以改成上名下因
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                e.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                e.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 可玩库的落点是驱动：档位告诉你差多少，下一步告诉你差的那部分怎么补。
        val hint = report.driverHint
        val miss = report.nearBelowSmooth
        if (hint != null && miss != null && onOpenDrivers != null) {
            Spacer(Modifier.height(GroupGap))
            NextStepCard(
                driver = hint,
                platform = miss.label,
                gap = miss.pointsToSmooth(report.score!!),
                onClick = onOpenDrivers
            )
        }

        Spacer(Modifier.height(GroupGap))
        Text(
            appString(R.string.playable_disclaimer, report.benchmarkBase),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (report.evidenceSoc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.playable_evidence_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 顶部：芯片 + 参考分 + 分布条 + 一句结论。数字只在这里出现一次。 */
@Composable
private fun PlayableSummaryCard(
    report: PlayableReport,
    ramMb: Long,
    grouped: Map<PlayGrade, List<PlayablePlatform>>
) {
    val smoothN = grouped[PlayGrade.SMOOTH].orEmpty().size
    val playN = grouped[PlayGrade.PLAYABLE].orEmpty().size
    val tightN = grouped[PlayGrade.TIGHT].orEmpty().size
    val unknownN = grouped[PlayGrade.UNKNOWN].orEmpty().size
    val total = report.platforms.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.playable_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append(
                        if (report.socName.isNotBlank()) report.socName
                        else appString(R.string.playable_soc_unknown)
                    )
                    if (ramMb > 0) {
                        append(" · ")
                        append(appString(R.string.playable_ram, trimGb(ramMb)))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            val score = report.score
            if (score == null) {
                Text(
                    appString(R.string.playable_no_score),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        (if (report.scoreSrc == "socpk") "" else "≈") + score.toInt().toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appString(
                            R.string.playable_score_inline,
                            when (report.scoreSrc) {
                                "socpk" -> appString(R.string.playable_src_socpk)
                                "family" -> appString(R.string.playable_src_family)
                                else -> appString(R.string.playable_src_antutu)
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }

            if (unknownN == 0 && total > 0) {
                Spacer(Modifier.height(14.dp))
                SpectrumBar(smoothN = smoothN, playN = playN, tightN = tightN)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(PlayGrade.SMOOTH, smoothN)
                    LegendDot(PlayGrade.PLAYABLE, playN)
                    if (tightN > 0) LegendDot(PlayGrade.TIGHT, tightN)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    verdictText(report, smoothN, playN, tightN),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 一句结论：落到「最接近达标的那一个平台」上，比「13 流畅 2 勉强」可执行。 */
@Composable
private fun verdictText(report: PlayableReport, smoothN: Int, playN: Int, tightN: Int): String {
    val runnable = smoothN + playN
    val tight = report.nearTight
    val tightGap = tight?.gapToNextTier(report.score)
    val below = report.nearBelowSmooth
    return when {
        // 有跑不动的平台：先说最接近达标的那一个，用户知道差多少
        tightN > 0 && tight != null && tightGap != null ->
            appString(R.string.playable_verdict_gap, runnable, tight.label, tightGap)
        // 全部跑得动但没全满帧：指出离满帧最近的那个
        playN > 0 && below != null ->
            appString(R.string.playable_verdict_playable, runnable, below.label, below.pointsToSmooth(report.score!!))
        else ->
            appString(R.string.playable_verdict_all, runnable)
    }
}

@Composable
private fun LegendDot(grade: PlayGrade, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(3.5.dp))
                .background(gradeColor(grade))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            appString(R.string.playable_legend, gradeLabel(grade), count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * 分布条：三段按平台数分宽。它替代了原来「流畅 13 · 勉强 2」那行纯文字，
 * 多给出比例感，数字仍只出现一次（在图例里）。
 */
@Composable
private fun SpectrumBar(smoothN: Int, playN: Int, tightN: Int) {
    val total = (smoothN + playN + tightN).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(9.dp)
            .clip(RoundedCornerShape(4.5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        listOf(
            PlayGrade.SMOOTH to smoothN,
            PlayGrade.PLAYABLE to playN,
            PlayGrade.TIGHT to tightN
        ).forEach { (grade, n) ->
            if (n <= 0) return@forEach
            Box(
                Modifier
                    .weight(n.toFloat() / total)
                    .fillMaxHeight()
                    .background(gradeColor(grade))
            )
        }
    }
}

/**
 * 组标题只承担「这是哪一档」，不重复条数（图例已有）也不写门槛——
 * 同组内门槛从 4 到 300 不等，写一个「门槛 4 分」会被理解成整组的门槛。
 * 精确门槛在每行展开后给出。
 */
@Composable
private fun PlayableGroupHeader(grade: PlayGrade, @StringRes labelRes: Int) {
    Text(
        appString(labelRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = gradeColor(grade)
    )
}

/**
 * 平台行。收起态三行：名称 + 比值 / 微条 / 能力说明。
 * 展开后补精确门槛、差距、内存条件与同芯片实测。
 */
@Composable
private fun PlayablePlatformRow(
    platform: PlayablePlatform,
    score: Double?,
    ramMb: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val color = gradeColor(platform.grade)
    val ratio = platform.ratioToSmooth(score)
    val gap = platform.gapToNextTier(score)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (expanded) color.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                platform.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    score == null -> gradeLabel(platform.grade)
                    gap != null && gap > 0 -> appString(R.string.playable_gap, gap)
                    else -> appString(
                        R.string.playable_score_pair_short,
                        score.toInt(),
                        platform.smooth
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(16.dp)
                    .padding(start = 2.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        MicroBar(ratio = ratio, color = color)
        Spacer(Modifier.height(7.dp))
        Text(
            platform.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                appString(R.string.playable_thresholds, platform.smooth, platform.ok),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (platform.ramLimited && ramMb > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    appString(
                        R.string.playable_ram_requirement,
                        trimGb(platform.minRamMb.toLong()),
                        trimGb(ramMb)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            platform.evidence?.let { ev ->
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ev.bestEmulator.isBlank()) {
                        appString(R.string.playable_evidence_plain, ev.listings, ev.score)
                    } else {
                        appString(
                            R.string.playable_evidence,
                            ev.listings,
                            ev.score,
                            ev.bestEmulator
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (platform.appCount > 0) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpen),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        appString(R.string.playable_open_apps, platform.appCount),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/**
 * 微条：满格 = 1.5 倍门槛，刻度线落在 2/3（正好达标）。
 * 用条长而不是圆环，是因为圆环在「远超门槛」时一律满环，看不出 4 倍和 12 倍的区别。
 */
@Composable
private fun MicroBar(ratio: Double?, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(3.dp))
                .background(trackColor)
        )
        if (ratio != null) {
            val frac = (ratio / 1.5).coerceIn(0.03, 1.0).toFloat()
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        // 达标线：白色描边在浅色轨道上也看得见
        Box(Modifier.fillMaxWidth(2f / 3f).height(10.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
            )
        }
    }
}

/** 下一步：把「差多少」变成「怎么补」。只有存在差一口气的平台时才出现。 */
@Composable
private fun NextStepCard(driver: String, platform: String, gap: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    appString(R.string.playable_next_driver, driver),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    appString(R.string.playable_next_driver_body, platform, gap),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/** 12.0 → "12"，5.6 → "5.6"：整数字节省掉无意义的小数位。 */
private fun trimGb(mb: Long): String {
    if (mb <= 0) return "0"
    val gb = mb / 1024.0
    return if (gb == gb.toLong().toDouble()) gb.toLong().toString()
    else String.format(Locale.US, "%.1f", gb)
}
