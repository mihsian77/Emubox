package com.emubox.app

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private fun gradeColor(grade: PlayGrade): Color = when (grade) {
    PlayGrade.SMOOTH -> Color(0xFF2E7D32)
    PlayGrade.PLAYABLE -> Color(0xFFE65100)
    PlayGrade.TIGHT -> Color(0xFF9E9E9E)
    PlayGrade.UNKNOWN -> Color(0xFF9E9E9E)
}

@Composable
private fun gradeLabel(grade: PlayGrade): String = when (grade) {
    PlayGrade.SMOOTH -> appString(R.string.playable_grade_smooth)
    PlayGrade.PLAYABLE -> appString(R.string.playable_grade_playable)
    PlayGrade.TIGHT -> appString(R.string.playable_grade_tight)
    PlayGrade.UNKNOWN -> appString(R.string.playable_grade_unknown)
}

/**
 * 设备页「可玩库」：按本机芯片 GPU 分与内存，把 15 个平台分成
 * 流畅 / 勉强 / 吃力 三组，点击任一平台跳到模拟器页并带上该平台筛选。
 *
 * 为什么不做成「推荐 3 个模拟器」：用户问的是「我这台能不能跑 PS2」，
 * 平台是可验证的判断，具体项目会随仓库活跃度变，给结论比给清单稳。
 */
@Composable
fun PlayableLibrarySection(
    entries: List<EmulatorEntry>,
    onOpenPlatform: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 预览/测试注入用：Robolectric 下 Build.SOC_MODEL 是空的，解析不出芯片 */
    socOverride: ResolvedSoc? = null
) {
    val context = LocalContext.current
    val soc = remember(socOverride) {
        socOverride ?: runCatching { SocResolver.resolveCurrent(context) }.getOrNull()
    }
    val ramMb = remember { totalRamMb(context) }
    val report = remember(soc, ramMb, entries) {
        runCatching { PlayableCatalog.evaluate(context, soc, ramMb, entries) }.getOrNull()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (report == null) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Text(
                    appString(R.string.playable_load_failed),
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        val grouped = report.platforms.groupBy { it.grade }
        PlayableHeaderCard(
            report = report,
            ramMb = ramMb,
            smoothN = grouped[PlayGrade.SMOOTH].orEmpty().size,
            playN = grouped[PlayGrade.PLAYABLE].orEmpty().size,
            tightN = grouped[PlayGrade.TIGHT].orEmpty().size,
            unknownN = grouped[PlayGrade.UNKNOWN].orEmpty().size
        )
        listOf(
            PlayGrade.SMOOTH to R.string.playable_grade_smooth,
            PlayGrade.PLAYABLE to R.string.playable_grade_playable,
            PlayGrade.TIGHT to R.string.playable_grade_tight,
            PlayGrade.UNKNOWN to R.string.playable_grade_unknown,
        ).forEach { (grade, labelRes) ->
            val items = grouped[grade].orEmpty()
            if (items.isEmpty()) return@forEach
            Text(
                appString(labelRes) + " · " + items.size,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            items.forEach { p ->
                PlayablePlatformRow(platform = p, onOpen = onOpenPlatform)
            }
        }

        if (report.experimental.isNotEmpty()) {
            Text(
                appString(R.string.playable_experimental_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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

        Text(
            appString(R.string.playable_disclaimer, report.benchmarkBase),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayableHeaderCard(
    report: PlayableReport,
    ramMb: Long,
    smoothN: Int,
    playN: Int,
    tightN: Int,
    unknownN: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsEsports, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.playable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                if (report.socName.isNotBlank()) report.socName else appString(R.string.playable_soc_unknown),
                style = MaterialTheme.typography.bodyMedium
            )
            val score = report.score
            if (score == null) {
                Text(
                    appString(R.string.playable_no_score),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                val prefix = if (report.scoreSrc == "socpk") "" else "≈"
                Text(
                    appString(
                        R.string.playable_score,
                        prefix + score.toInt().toString(),
                        when (report.scoreSrc) {
                            "socpk" -> appString(R.string.playable_src_socpk)
                            "family" -> appString(R.string.playable_src_family)
                            else -> appString(R.string.playable_src_antutu)
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (ramMb > 0) {
                Text(
                    appString(R.string.playable_ram, (ramMb / 1024).toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            // 旗舰芯片会出现「十几个平台全绿」，一片同色等于没有信息；
            // 在卡片内给一句结论，用户不用数卡片就知道这台机器的位置。
            if (unknownN == 0) {
                Text(
                    when {
                        playN == 0 && tightN == 0 -> appString(R.string.playable_all_smooth, smoothN.toString())
                        tightN == 0 -> appString(R.string.playable_summary_two, smoothN.toString(), playN.toString())
                        else -> appString(
                            R.string.playable_summary,
                            smoothN.toString(), playN.toString(), tightN.toString()
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun PlayablePlatformRow(platform: PlayablePlatform, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                platform.match.firstOrNull()?.let(onOpen)
            },
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    platform.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = buildString {
                    append(appString(R.string.playable_app_count, platform.appCount))
                    if (platform.ramLimited) {
                        append(" · ")
                        append(appString(R.string.playable_ram_limited, (platform.minRamMb / 1024).toString()))
                    }
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = gradeColor(platform.grade).copy(alpha = 0.14f)
            ) {
                Text(
                    gradeLabel(platform.grade),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = gradeColor(platform.grade)
                )
            }
            if (platform.appCount > 0) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
