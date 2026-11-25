package com.emubox.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*


/** 频率格式化：4.35GHz → "4.35 GHz"（去多余小数） */
internal fun formatFreq(ghz: Double): String {
    val s = if (ghz % 1.0 == 0.0) ghz.toInt().toString() else String.format("%.2f", ghz)
        .trimEnd('0').trimEnd('.')
    return "$s GHz"
}

internal fun formatBytes(bytes: Long): String {    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/** 从 GitHub apiUrl/manifestUrl 提取 owner 并返回作者头像 URL（驱动/组件源卡用）。
 *  直接使用头像 CDN 直链：github.com/{owner}.png 会 302 跳转到
 *  avatars.githubusercontent.com，国内网络对跳转链加载不稳且易受限流。 */
internal fun githubOwnerAvatar(url: String): String? {
    // 按 URL 形态提取 owner：
    // 1) api.github.com/repos/owner/repo（注册表驱动/组件源）
    // 2) raw.githubusercontent.com/owner/repo/...（contents.json 清单源）
    // 3) github.com/owner/repo（普通仓库页）
    // 此前只匹配 github.com 会把 api 域的 owner 错取为 "repos" → 全部显示默认头像。
    val m = Regex("api\\.github\\.com/repos/([^/]+)").find(url)
        ?: Regex("raw\\.githubusercontent\\.com/([^/]+)").find(url)
        ?: Regex("github\\.com/([^/]+)").find(url)
        ?: return null
    val owner = m.groupValues[1].trim('/').ifBlank { return null }
    return "https://avatars.githubusercontent.com/$owner?size=64"
}

/** 仓库/作者头像：GitHub 头像加载失败（国内网络/头像缺失）时显示首字母圆形，
 *  本地绘制不依赖网络，避免出现 GitHub 官方默认图标。 */
@Composable
internal fun RepoAvatar(
    avatarUrl: String?,
    fallbackName: String,
    size: Dp = 26.dp,
    selected: Boolean = false,
    shape: Shape = CircleShape,
    seed: String = ""
) {
    val initial = fallbackName.trim().firstOrNull()?.uppercase() ?: "?"
    // 字母牌颜色按名称散列：同一屏里相邻卡片不会是同一种灰，扫读时更容易区分
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error
    )
    val tint = if (seed.isBlank()) MaterialTheme.colorScheme.primary
    else palette[(seed.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else tint.copy(alpha = 0.14f)
            ),
        contentAlignment = Alignment.Center
    ) {
        // 底层：首字母圆形（头像加载失败/加载中时可见）
        Text(
            initial,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else tint,
            textAlign = TextAlign.Center
        )
        if (avatarUrl != null) {
            // 顶层：GitHub 头像，加载成功即覆盖首字母
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(size).clip(shape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 项目图标统一链路：App 徽标（logoUrl，徽标站/仓库 social preview）→ 作者 GitHub 头像 → 首字母牌。
 *  模拟器/组件/驱动所有分区卡片共用，保证图标风格一致。 */
@Composable
internal fun ProjectAvatar(
    logoUrl: String,
    owner: String,
    fallbackName: String,
    size: Dp = 26.dp,
    shape: Shape = CircleShape
) {
    // 没有项目徽标就用作者头像：GitHub 仓库未设置 social preview 时，
    // logo_url 本来就等于作者头像，两者一致；作者也取不到才退到首字母牌。
    val avatar = logoUrl.ifBlank {
        owner.trim().takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { "https://avatars.githubusercontent.com/$it?size=96" }
            .orEmpty()
    }.ifBlank { null }
    RepoAvatar(
        avatarUrl = avatar,
        fallbackName = fallbackName,
        size = size,
        shape = shape,
        seed = fallbackName
    )
}

internal fun formatRelativeTime(iso: String): String {
    return try {
        val parsed = java.time.Instant.parse(iso)
        val now = java.time.Instant.now()
        val diffMs = java.time.Duration.between(parsed, now).toMillis()
        when {
            diffMs < 0 -> "刚刚"
            diffMs < 60_000 -> "刚刚"
            diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
            diffMs < 86_400_000 -> "${diffMs / 3_600_000} 小时前"
            // 一周内说「N 天前」，超过就写死日期：避免「28 天前」这种
            // 既算不清又跟发布日期混淆的口径。
            diffMs < 7L * 86_400_000 -> "${diffMs / 86_400_000} 天前"
            else -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.format(Date(parsed.toEpochMilli()))
            }
        }
    } catch (_: Exception) {
        iso
    }
}

/**
 * ISO 时间戳截到日期："2026-08-19T11:06:00Z" → "2026-08-19"。
 * 解析失败时取前 10 位兜底，不抛出。
 */
internal fun shortDate(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(instant.toEpochMilli()))
    }.getOrElse { iso.take(10) }
}

/** 基于 epoch 毫秒的相对时间（组件源仓库更新时间用） */
internal fun formatRelativeTimeMs(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    return when {
        diffMs < 0 -> "刚刚"
        diffMs < 60_000 -> "刚刚"
        diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
        // 48 小时内精确到小时：25h 显示"25 小时前"而非"1 天前"，驱动/组件源统一
        diffMs < 48L * 3_600_000 -> "${diffMs / 3_600_000} 小时前"
        // 与 formatRelativeTime 同口径：一周以上直接给日期
        diffMs < 7L * 86_400_000 -> "${diffMs / 86_400_000} 天前"
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(Date(epochMs))
        }
    }
}

internal fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/** 只到天：卡片底部行用。相对时间（「3 天前」）在列表里读不出到底是哪天。 */
internal fun formatDay(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

internal fun formatStars(stars: Long): String =
    if (stars >= 1000) String.format("%.1fk", stars / 1000.0)
    else stars.toString()
