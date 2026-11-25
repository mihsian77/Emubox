package com.emubox.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 发布文件的平台归类。
 *
 * 只服务于详情页顶部的「这版到底给了什么」这一个问题，所以分类粒度按
 * **用户能不能在手机上装** 来切，不追求和注册表里的 artifacts 计数一一对应
 * （那套计数由脚本按仓库 API 归类，详情页拿不到，两边口径不可能永远一致）。
 */
internal enum class ArtifactKind(private val text: String) {
    ANDROID("安卓"),
    APPLE("iOS"),
    DESKTOP("桌面端"),
    CONSOLE("主机"),
    SOURCE("源码"),
    OTHER("其他");

    fun text(): String = text
}

/**
 * 从文件名认平台。
 *
 * 判断顺序有讲究：先看 Android 专用后缀，再看包名里带 android 的 zip
 * （不少模拟器把 apk 打进 android.zip 发），最后才按桌面/主机后缀落。
 * 认不出来的一律归 OTHER，宁可让用户展开看一眼，也不猜错平台。
 */
internal fun artifactKindOf(name: String): ArtifactKind {
    val n = name.lowercase()
    val ext = n.substringAfterLast('.', "")
    return when {
        ext in ANDROID_EXT -> ArtifactKind.ANDROID
        ext == "ipa" -> ArtifactKind.APPLE
        ext in ARCHIVE_EXT && n.contains("android") -> ArtifactKind.ANDROID
        ext in CONSOLE_EXT -> ArtifactKind.CONSOLE
        ext in DESKTOP_EXT -> ArtifactKind.DESKTOP
        n.contains("source") || n.contains("-src") || n.contains("_src") -> ArtifactKind.SOURCE
        else -> ArtifactKind.OTHER
    }
}

private val ANDROID_EXT = setOf("apk", "xapk", "apks", "aab")
private val ARCHIVE_EXT = setOf("zip", "7z", "tar", "gz", "xz", "rar")
private val CONSOLE_EXT = setOf("nsp", "nro", "xci", "cia", "3dsx", "wad", "gcz", "wbfs", "rvz")
private val DESKTOP_EXT = setOf(
    "exe", "msi", "dmg", "pkg", "appimage", "deb", "rpm", "flatpak", "snap", "apk64"
)

/**
 * 发布文件清单：安卓包直接铺开，其他平台默认收起。
 *
 * 之前详情页顶部只有一句「共 N 个」+ 一串归类计数（安卓包 2 · 桌面端 3 · 源码 1），
 * 用户要判断「我能不能装」，得先把三个数字在心里加一遍，再翻到最底下的版本卡展开
 * 才能看到文件名。现在直接把能装的列出来，装不了的收进一行——总数、能装数、明细
 * 三个口径合并成一个列表，不再有「共 48 个」下面只列 40 个的自相矛盾。
 */
@Composable
internal fun ArtifactFileList(assets: List<EmulatorAsset>, modifier: Modifier = Modifier) {
    if (assets.isEmpty()) return
    val android = assets.filter { artifactKindOf(it.name) == ArtifactKind.ANDROID }
    val others = assets.filter { artifactKindOf(it.name) != ArtifactKind.ANDROID }
    // 默认收起：折叠态本身就是「手机装不了的部分」，不需要用户先看一遍再决定
    var showOthers by remember(assets) { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        android.forEach { asset -> AssetRow(asset, ArtifactKind.ANDROID) }
        if (others.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showOthers = !showOthers }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "其他平台 ${others.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (showOthers) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (showOthers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (showOthers) {
                // 展开后按平台聚在一起，同类相邻，扫读比按原始顺序铺更省力
                others.sortedBy { artifactKindOf(it.name).ordinal }
                    .forEach { asset -> AssetRow(asset, artifactKindOf(asset.name)) }
            }
        }
    }
}

/**
 * 单行文件。文件名默认一行，点一下展开全名——GitHub 的资产名常带版本号和后缀，
 * 411dp 下必被截断，而截掉的多半正是「arm64 还是 armeabi」这一段。
 */
@Composable
private fun AssetRow(asset: EmulatorAsset, kind: ArtifactKind) {
    var fullName by remember(asset.name) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { fullName = !fullName }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            kind.text(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp)
        )
        Text(
            softBreakLong(asset.name),
            style = MaterialTheme.typography.labelMedium,
            maxLines = if (fullName) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (asset.size > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                assetSizeText(asset.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
