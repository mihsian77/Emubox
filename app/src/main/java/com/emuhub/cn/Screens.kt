package com.emuhub.cn

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, showBack: Boolean = true) {
    if (showBack) {
        BackHandler { onBack() }
    }

    val active = DownloadsManager.activeDownloads
    val completed = DownloadsManager.completedDownloads
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val completedBytes = completed.sumOf { it.sizeBytes }
    val openWithLabel = appString(R.string.open_with)
    val shareChooserLabel = appString(R.string.share_chooser)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(appString(R.string.downloads))
                        Text(
                            appString(R.string.your_download_library),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = appString(R.string.back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "download_summary") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Icon(
                                    Icons.Default.DownloadDone,
                                    contentDescription = null,
                                    modifier = Modifier.padding(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(appString(R.string.download_library), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    appString(R.string.download_library_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DownloadStatCard(
                                icon = Icons.Default.Downloading,
                                value = active.size.toString(),
                                label = appString(R.string.active),
                                modifier = Modifier.weight(1f)
                            )
                            DownloadStatCard(
                                icon = Icons.Default.Inventory2,
                                value = completed.size.toString(),
                                label = appString(R.string.saved),
                                modifier = Modifier.weight(1f)
                            )
                            DownloadStatCard(
                                icon = Icons.Default.Storage,
                                value = formatBytes(completedBytes),
                                label = appString(R.string.stored),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (active.isNotEmpty()) {
                item(key = "active_header") {
                    DownloadListHeader(
                        title = appString(R.string.downloading_now),
                        subtitle = appString(R.string.active_downloads_count, active.size),
                        icon = Icons.Default.Downloading
                    )
                }

                active.forEach { (name, download) ->
                    item(key = "active_$name") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.secondary
                                    ) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.padding(10.dp),
                                            tint = MaterialTheme.colorScheme.onSecondary
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2
                                        )
                                        Text(
                                            when (download.status) {
                                                DownloadStatus.PAUSED -> appString(R.string.paused_progress, download.progress)
                                                DownloadStatus.CANCELLING -> appString(R.string.cancelling)
                                                DownloadStatus.CONNECTING -> appString(R.string.connecting)
                                                DownloadStatus.DOWNLOADING -> when {
                                                    download.totalBytes > 0L -> appString(R.string.progress_complete, download.progress)
                                                    download.downloadedBytes > 0L -> appString(R.string.downloading)
                                                    else -> appString(R.string.starting)
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    AssistChip(
                                        onClick = {},
                                        enabled = false,
                                        label = {
                                            Text(
                                                when (download.status) {
                                                    DownloadStatus.PAUSED -> appString(R.string.paused)
                                                    DownloadStatus.CANCELLING -> appString(R.string.stopping)
                                                    DownloadStatus.CONNECTING -> appString(R.string.connecting_short)
                                                    DownloadStatus.DOWNLOADING -> appString(R.string.active)
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                when (download.status) {
                                                    DownloadStatus.PAUSED -> Icons.Default.Pause
                                                    DownloadStatus.CANCELLING -> Icons.Default.Close
                                                    DownloadStatus.CONNECTING -> Icons.Default.Sync
                                                    DownloadStatus.DOWNLOADING -> Icons.Default.Downloading
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }

                                if (download.totalBytes > 0L) {
                                    LinearProgressIndicator(
                                        progress = download.progress / 100f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(7.dp)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(7.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            formatBytes(download.downloadedBytes),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        if (download.status == DownloadStatus.DOWNLOADING && download.speedBytesPerSec > 0) {
                                            Text(
                                                Accelerator.formatSpeed(download.speedBytesPerSec),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            if (download.totalBytes > 0L) formatBytes(download.totalBytes) else appString(R.string.size_pending),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (download.status == DownloadStatus.DOWNLOADING && download.speedBytesPerSec > 0 && download.totalBytes > 0) {
                                            val remaining = download.totalBytes - download.downloadedBytes
                                            Text(
                                                appString(R.string.remaining_eta, Accelerator.formatEta(context, remaining, download.speedBytesPerSec)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            if (download.status == DownloadStatus.PAUSED) {
                                                resumeActiveDownload(context, name)
                                            } else {
                                                pauseActiveDownload(name)
                                            }
                                        },
                                        enabled = download.status != DownloadStatus.CANCELLING,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            if (download.status == DownloadStatus.PAUSED) {
                                                Icons.Default.PlayArrow
                                            } else {
                                                Icons.Default.Pause
                                            },
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(if (download.status == DownloadStatus.PAUSED) appString(R.string.resume) else appString(R.string.pause))
                                    }

                                    OutlinedButton(
                                        onClick = { cancelActiveDownload(context, name) },
                                        enabled = download.status != DownloadStatus.CANCELLING,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null)
                                        Spacer(Modifier.width(7.dp))
                                        Text(appString(R.string.cancel))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "completed_header") {
                    DownloadListHeader(
                        title = appString(R.string.downloaded_files),
                        subtitle = appString(R.string.ready_open_share_manage),
                        icon = Icons.Default.Folder
                    )
                }

                completed.forEach { file ->
                    item(key = "completed_${file.id}") {
                        var showDeleteDialog by remember { mutableStateOf(false) }
                        var showMetaDialog by remember { mutableStateOf(false) }
                        var metaInfo by remember { mutableStateOf<DriverMetaParser.DriverMeta?>(null) }
                        var metaLoading by remember { mutableStateOf(false) }
                        val isDriverPkg = remember(file.fileName) { DriverMetaParser.isDriverPackage(file.fileName) }
                        val displayPath = remember(file.filePath) { getFullPath(file.filePath, context) }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Icon(
                                            Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            modifier = Modifier.padding(11.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.fileName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2
                                        )
                                        Text(
                                            "${formatBytes(file.sizeBytes)} • ${formatDate(file.timestamp)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = appString(R.string.downloaded),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            displayPath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            val uri = if (file.filePath.startsWith("content://")) {
                                                Uri.parse(file.filePath)
                                            } else {
                                                Uri.fromFile(File(file.filePath))
                                            }
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "application/octet-stream")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try {
                                                context.startActivity(Intent.createChooser(intent, openWithLabel))
                                            } catch (e: Exception) {
                                                // 无可用打开程序（zip/so 等常见于文件管理器），提示而不是崩溃
                                                Toast.makeText(
                                                    context,
                                                    appStringFor(context, SettingsManager.getAppLanguage(), R.string.no_app_to_open),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                                        Spacer(Modifier.width(7.dp))
                                        Text(appString(R.string.open))
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val uri = if (file.filePath.startsWith("content://")) {
                                                Uri.parse(file.filePath)
                                            } else {
                                                Uri.fromFile(File(file.filePath))
                                            }
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, shareChooserLabel))
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                        Spacer(Modifier.width(7.dp))
                                        Text(appString(R.string.share))
                                    }

                                    FilledTonalIconButton(
                                        onClick = { showDeleteDialog = true },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = appString(R.string.delete))
                                    }
                                }

                                // 所有文件类型都提供"查看信息"：驱动包解析 meta.json，其他文件展示基础信息
                                OutlinedButton(
                                    onClick = {
                                        metaLoading = true
                                        scope.launch {
                                            // 防御性包裹：个别异常文件（坏 zip / 特殊路径）解析失败时
                                            // Toast 提示而不是让 App 闪退
                                            runCatching {
                                                metaInfo = if (isDriverPkg) {
                                                    DriverMetaParser.parse(context, file.filePath)
                                                } else {
                                                    null
                                                }
                                            }.onFailure { e ->
                                                android.util.Log.w("EmuHub", "parse file info failed", e)
                                                Toast.makeText(
                                                    context,
                                                    appStringFor(context, SettingsManager.getAppLanguage(), R.string.file_info_error),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            metaLoading = false
                                            showMetaDialog = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = !metaLoading
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        if (metaLoading) appString(R.string.meta_loading)
                                        else if (isDriverPkg) appString(R.string.view_package_info)
                                        else appString(R.string.view_file_info)
                                    )
                                }
                            }
                        }

                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                title = { Text(appString(R.string.delete_file)) },
                                text = { Text(appString(R.string.delete_confirm, file.fileName)) },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val deleted = deleteFile(context, file)
                                                if (deleted) DownloadsManager.removeCompleted(file.id)
                                                showDeleteDialog = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Text(appString(R.string.delete))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text(appString(R.string.cancel))
                                    }
                                }
                            )
                        }

                        if (showMetaDialog) {
                            AlertDialog(
                                onDismissRequest = { showMetaDialog = false },
                                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                                title = {
                                    Text(
                                        if (isDriverPkg) appString(R.string.driver_package_info)
                                        else appString(R.string.file_info_title)
                                    )
                                },
                                text = {
                                    if (metaInfo == null || metaInfo!!.isEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (metaInfo == null) {
                                                // 非驱动文件：基础信息 + 用途说明（按文件名/扩展名推断）
                                                FileInfoRow(appString(R.string.file_name), file.fileName)
                                                FileInfoRow(
                                                    appString(R.string.file_type),
                                                    file.fileName.substringAfterLast('.', "").uppercase().ifEmpty { "—" }
                                                )
                                                FileInfoRow(appString(R.string.file_purpose), filePurposeHint(file.fileName))
                                                FileInfoRow(appString(R.string.file_size), formatBytes(file.sizeBytes))
                                                FileInfoRow(appString(R.string.file_path), displayPath)
                                                FileInfoRow(appString(R.string.file_time), formatDate(file.timestamp))
                                            } else {
                                                Text(appString(R.string.meta_not_found))
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            MetaRow(appString(R.string.meta_name), metaInfo!!.name)
                                            MetaRow(appString(R.string.meta_driver_version), metaInfo!!.driverVersion)
                                            MetaRow(appString(R.string.meta_vendor), metaInfo!!.vendor)
                                            MetaRow(appString(R.string.meta_author), metaInfo!!.author)
                                            MetaRow(appString(R.string.meta_package_version), metaInfo!!.packageVersion)
                                            MetaRow(appString(R.string.meta_min_api), metaInfo!!.minApi)
                                            MetaRow(appString(R.string.meta_library), metaInfo!!.libraryName)
                                            if (metaInfo!!.description.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    metaInfo!!.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showMetaDialog = false }) { Text(appString(R.string.close)) }
                                }
                            )
                        }
                    }
                }
            }

            if (active.isEmpty() && completed.isEmpty()) {
                item(key = "empty_downloads") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(
                                    Icons.Default.DownloadForOffline,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Text(appString(R.string.no_downloads_yet), style = MaterialTheme.typography.titleMedium)
                            Text(
                                appString(R.string.downloads_started_here),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
/** 文件信息行（下载库弹窗用，允许空值显示占位） */
private fun FileInfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 非驱动文件的用途推断：按文件名/扩展名匹配常见组件类型，未命中给通用说明 */
private fun filePurposeHint(fileName: String): String {
    val n = fileName.lowercase()
    val base = fileName.substringBeforeLast('.').trim()
    // 先按运行库中文映射匹配（vc2022 / dxvk / lavfilters 等）
    RuntimeChineseHints.lookup(base)?.let { return it }
    return when {
        n.endsWith(".wcp") -> "Winlator 组件包（DXVK/FEXCore/VKD3D 等，供模拟器加载）"
        n.contains("dxvk") || n.contains("d7vk") -> "DXVK 组件：DirectX 9/10/11 转 Vulkan"
        n.contains("vkd3d") -> "VKD3D 组件：DirectX 12 转 Vulkan"
        n.contains("fex") -> "FEXCore 组件：x86 指令模拟核心"
        n.contains("proton") -> "Proton 组件：Windows 兼容层（含 CachyOS 等发行版构建）"
        n.contains("wine") -> "Wine 组件：Windows API 兼容层"
        n.contains("box64") -> "Box64 组件：x86_64 指令转译"
        n.contains("turnip") || n.contains("freedreno") || (n.contains("mesa") && n.endsWith(".zip")) ->
            "Turnip 驱动（Mesa 开源 Adreno 驱动，模拟器内加载）"
        n.contains("adreno") || n.contains("qualcomm") -> "高通 Adreno 驱动包（系统提取闭源驱动）"
        n.contains("panvk") || n.contains("panv") -> "PanVK 驱动（Mesa 开源 Mali 驱动）"
        n.endsWith(".dll") -> "DLL 动态链接库（Windows 运行库组件，放入模拟器系统目录）"
        n.endsWith(".exe") || n.endsWith(".msi") -> "Windows 安装程序（在模拟器内运行安装）"
        n.endsWith(".ttf") || n.endsWith(".otf") || n.contains("font") || n.contains("cjk") ->
            "字体文件（CJK 中文字体，解决模拟器内乱码）"
        n.endsWith(".zip") || n.endsWith(".adpkg") -> "驱动/组件压缩包（含驱动本体与 meta.json）"
        else -> "已下载文件（${fileName.substringAfterLast('.', "").uppercase().ifEmpty { "未知" }} 格式）"
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DownloadStatCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadListHeader(
    title: String,
    subtitle: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getFullPath(filePath: String, context: android.content.Context): String {
    if (!filePath.startsWith("content://")) {
        return filePath
    }

    val uri = Uri.parse(filePath)

    // MediaStore URI (default download path): query RELATIVE_PATH + DISPLAY_NAME
    if (uri.authority?.startsWith("media") == true) {
        runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val rel = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1).orEmpty()
                    if (name.isNotEmpty()) return "$rel$name"
                }
            }
        }
    }

    // Try to get folder using DocumentFile
    val docFile = DocumentFile.fromSingleUri(context, uri)
    if (docFile != null) {
        val parent = docFile.parentFile
        val folderName = parent?.name
        val fileName = docFile.name ?: "unknown"
        if (folderName != null) {
            return "$folderName/$fileName"
        }
    }

    // Manual parse of SAF URI path
    val path = uri.path ?: ""
    val treeIndex = path.indexOf("/tree/")
    val documentIndex = path.indexOf("/document/")

    if (treeIndex != -1 && documentIndex != -1 && documentIndex > treeIndex) {
        val folderEncoded = path.substring(treeIndex + "/tree/".length, documentIndex)
        val folderDecoded = Uri.decode(folderEncoded)
        val folderPath = folderDecoded.replace("primary:", "")

        val afterDocument = path.substring(documentIndex + "/document/".length)
        val fileNameEncoded = afterDocument.substringAfterLast('/')
        val fileName = Uri.decode(fileNameEncoded)

        return "$folderPath/$fileName"
    }

    // Ultimate fallback
    val fileName = uri.lastPathSegment?.let { Uri.decode(it) } ?: "file"
    return "Unknown/$fileName"
}

private suspend fun deleteFile(context: android.content.Context, file: DownloadsManager.CompletedDownload): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            if (file.filePath.startsWith("content://")) {
                val uri = Uri.parse(file.filePath)
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                    true
                } else if (DocumentsContract.isTreeUri(uri)) {
                    val docFile = DocumentFile.fromTreeUri(context, uri)
                    docFile?.delete() ?: false
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } else {
                val f = File(file.filePath)
                f.exists() && f.delete()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, appStringFor(context, SettingsManager.getAppLanguage(), R.string.error_deleting, e.message ?: "Unknown"), Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/** GitHub ISO 时间（如 2026-10-05T12:34:56Z）转相对时间：x 天前 / x 小时前 / 刚刚 */
private fun formatRelativeTime(iso: String): String {
    return try {
        val parsed = java.time.Instant.parse(iso)
        val now = java.time.Instant.now()
        val diffMs = java.time.Duration.between(parsed, now).toMillis()
        when {
            diffMs < 0 -> "刚刚"
            diffMs < 60_000 -> "刚刚"
            diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
            diffMs < 86_400_000 -> "${diffMs / 3_600_000} 小时前"
            diffMs < 30L * 86_400_000 -> "${diffMs / 86_400_000} 天前"
            else -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.format(Date(parsed.toEpochMilli()))
            }
        }
    } catch (_: Exception) {
        iso
    }
}

/** 基于 epoch 毫秒的相对时间（组件源仓库更新时间用） */
private fun formatRelativeTimeMs(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    return when {
        diffMs < 0 -> "刚刚"
        diffMs < 60_000 -> "刚刚"
        diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
        diffMs < 86_400_000 -> "${diffMs / 3_600_000} 小时前"
        diffMs < 30L * 86_400_000 -> "${diffMs / 86_400_000} 天前"
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(Date(epochMs))
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    colorTheme: ColorTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onColorThemeChange: (ColorTheme) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onSourceCatalogChanged: () -> Unit,
    showBack: Boolean = true
) {
    if (showBack) {
        BackHandler { onBack() }
    }

    val context = LocalContext.current
    val downloadsDefaultLabel = appString(R.string.downloads_default)
    val sourceCatalogSavedMessage = appString(R.string.source_catalog_saved)
    val invalidCatalogUrlMessage = appString(R.string.invalid_catalog_url)
    var currentFolderUri by remember { mutableStateOf(SettingsManager.getDownloadFolderUri()) }
    var displayPath by remember { mutableStateOf<String?>(null) }
    var sourceCatalogUrl by remember { mutableStateOf(SettingsManager.getSourceCatalogUrl()) }
    var matchDriversByDevice by remember { mutableStateOf(SettingsManager.getMatchDriversByDevice()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                SettingsManager.setDownloadFolderUri(uri.toString())
                currentFolderUri = uri.toString()
                val docFile = DocumentFile.fromTreeUri(context, uri)
                displayPath = docFile?.name ?: uri.path
            }
        }
    )

    LaunchedEffect(currentFolderUri, appLanguage) {
        displayPath = if (currentFolderUri != null) {
            val uri = Uri.parse(currentFolderUri)
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.path
        } else {
            downloadsDefaultLabel
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appString(R.string.settings)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = appString(R.string.back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSectionHeader(
                    icon = Icons.Default.Palette,
                    title = appString(R.string.appearance),
                    subtitle = appString(R.string.appearance_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.app_language)) {
                    Text(
                        text = appString(R.string.language_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    LanguageSelector(
                        selected = appLanguage,
                        onSelected = onAppLanguageChange
                    )
                }
            }

            item {
                SettingsCard(title = appString(R.string.theme_mode)) {
                    Text(
                        text = appString(R.string.theme_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemeModeSelector(
                        selected = themeMode,
                        onSelected = onThemeModeChange
                    )
                }
            }

            item {
                SettingsCard(title = appString(R.string.color_theme)) {
                    Text(
                        text = appString(R.string.color_theme_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    ColorThemeSelector(
                        selected = colorTheme,
                        onSelected = onColorThemeChange
                    )
                }
            }

            item {
                SettingsSectionHeader(
                    icon = Icons.Default.Dns,
                    title = appString(R.string.sources),
                    subtitle = appString(R.string.sources_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.source_catalog)) {
                    Text(
                        text = appString(R.string.source_catalog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sourceCatalogUrl,
                        onValueChange = { sourceCatalogUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appString(R.string.catalog_url)) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val normalized = sourceCatalogUrl.trim()
                                if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
                                    SettingsManager.setSourceCatalogUrl(normalized)
                                    sourceCatalogUrl = normalized
                                    onSourceCatalogChanged()
                                    Toast.makeText(context, sourceCatalogSavedMessage, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, invalidCatalogUrlMessage, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(appString(R.string.save))
                        }
                        OutlinedButton(
                            onClick = {
                                SettingsManager.resetSourceCatalogUrl()
                                sourceCatalogUrl = DEFAULT_SOURCE_CATALOG_URL
                                onSourceCatalogChanged()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(appString(R.string.default_label))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (sourceCatalogUrl == DEFAULT_SOURCE_CATALOG_URL) {
                            appString(R.string.using_managed_catalog)
                        } else {
                            appString(R.string.using_custom_catalog)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            item {
                SettingsCard(title = appString(R.string.match_drivers_by_device)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = appString(R.string.match_drivers_by_device_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = matchDriversByDevice,
                            onCheckedChange = { enabled ->
                                matchDriversByDevice = enabled
                                SettingsManager.setMatchDriversByDevice(enabled)
                                onSourceCatalogChanged()
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    icon = Icons.Default.Download,
                    title = appString(R.string.downloads),
                    subtitle = appString(R.string.downloads_settings_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.download_folder)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayPath ?: appString(R.string.downloads_default),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (currentFolderUri == null) appString(R.string.system_downloads_folder) else appString(R.string.custom_folder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = appString(R.string.download_folder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(appString(R.string.choose_folder))
                    }

                    if (currentFolderUri != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                SettingsManager.clearDownloadFolder()
                                currentFolderUri = null
                                displayPath = downloadsDefaultLabel
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(appString(R.string.reset_to_default))
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    icon = Icons.Default.Bolt,
                    title = appString(R.string.accelerator_title),
                    subtitle = appString(R.string.accelerator_desc)
                )
            }

            item {
                AcceleratorSettingsCard()
            }

            item {
                SettingsSectionHeader(
                    icon = Icons.Default.Info,
                    title = appString(R.string.about_section),
                    subtitle = appString(R.string.about_upstream)
                )
            }

            item {
                AboutCard()
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ── 国内下载加速设置 ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceleratorSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Accelerator.getMode()) }
    var manualNodeId by remember { mutableStateOf(Accelerator.getManualNode().id) }
    var latencies by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var speeds by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }
    var autoResult by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refreshMode() { mode = Accelerator.getMode() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(appString(R.string.accelerator_mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == Accelerator.Mode.AUTO,
                    onClick = { Accelerator.setMode(Accelerator.Mode.AUTO); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_auto)) }
                )
                FilterChip(
                    selected = mode == Accelerator.Mode.MANUAL,
                    onClick = { Accelerator.setMode(Accelerator.Mode.MANUAL); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_manual)) }
                )
                FilterChip(
                    selected = mode == Accelerator.Mode.OFF,
                    onClick = { Accelerator.setMode(Accelerator.Mode.OFF); refreshMode() },
                    label = { Text(appString(R.string.accelerator_mode_off)) }
                )
            }

            if (mode == Accelerator.Mode.MANUAL) {
                Spacer(Modifier.height(16.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = Accelerator.ALL_NODES.firstOrNull { it.id == manualNodeId }?.displayName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text(appString(R.string.accelerator_node)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        shape = RoundedCornerShape(18.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Accelerator.ALL_NODES.forEach { node ->
                            DropdownMenuItem(
                                text = { Text(node.displayName) },
                                onClick = {
                                    manualNodeId = node.id
                                    Accelerator.setManualNode(node)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (mode == Accelerator.Mode.AUTO && autoResult != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = appString(R.string.accelerator_auto_result, autoResult!!.first, autoResult!!.second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    testing = true
                    autoResult = null
                    scope.launch(Dispatchers.IO) {
                        val (latMap, speedMap) = Accelerator.testAllLatenciesAndSpeeds()
                        latencies = latMap
                        speeds = speedMap
                        if (mode == Accelerator.Mode.AUTO) {
                            // 综合评分（延迟 40% + 速度 60%）选最优，避免"直连延迟低但网速慢"被误选
                            val best = Accelerator.selectBestNode(latMap, speedMap)
                            Accelerator.cacheBestNode(best)
                            val lat = latMap[best.id]
                            autoResult = best.displayName to Accelerator.formatLatency(context, lat)
                        }
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.accelerator_testing))
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.accelerator_test_latency))
                }
            }

            if (latencies.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Accelerator.ALL_NODES.forEach { node ->
                    val ms = latencies[node.id]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (ms != null) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (ms != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = node.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (node.trafficKinds.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = appString(R.string.raw_only),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = Accelerator.formatLatency(context, ms),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    ms == null -> MaterialTheme.colorScheme.error
                                    ms < 300 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            val speed = speeds[node.id]
                            if (speed != null) {
                                Text(
                                    text = Accelerator.formatSpeed(speed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        speed >= 5 * 1024 * 1024 -> MaterialTheme.colorScheme.primary
                                        speed >= 1024 * 1024 -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            } else if (ms != null) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // 节点来源声明（MirrorHub，MIT 协议；不提供跳转入口，避免节点仓库被滥用）
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = appString(R.string.mirrorhub_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 关于 / 汉化署名 ────────────────────────────────────────────

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) { "1.0.0-cn" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = appString(R.string.about_translation),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = appString(R.string.about_translation_by),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = appString(R.string.about_version, versionName ?: "1.0.0-cn"),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = appString(R.string.about_upstream),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = appString(R.string.about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = AppLanguage.values().toList()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = languageLabel(selected),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(appString(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(18.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { language ->
                DropdownMenuItem(
                    text = { Text(languageLabel(language)) },
                    leadingIcon = if (language == selected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelected(language)
                    }
                )
            }
        }
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> appString(R.string.language_system)
    AppLanguage.ENGLISH -> appString(R.string.language_english)
    AppLanguage.CHINESE -> appString(R.string.language_chinese)
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit
) {
    val choices = listOf(
        ThemeMode.SYSTEM to Pair(appString(R.string.theme_system), Icons.Default.SettingsBrightness),
        ThemeMode.LIGHT to Pair(appString(R.string.theme_light), Icons.Default.LightMode),
        ThemeMode.DARK to Pair(appString(R.string.theme_dark), Icons.Default.DarkMode),
        ThemeMode.AMOLED to Pair("AMOLED", Icons.Default.Contrast)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (mode, data) ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        label = { Text(data.first) },
                        leadingIcon = {
                            Icon(
                                imageVector = data.second,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorThemeSelector(
    selected: ColorTheme,
    onSelected: (ColorTheme) -> Unit
) {
    val choices = listOf(
        ColorTheme.DYNAMIC to appString(R.string.theme_dynamic),
        ColorTheme.EMUHUB to "EmuHub",
        ColorTheme.BLUE to appString(R.string.theme_blue),
        ColorTheme.PURPLE to appString(R.string.theme_purple),
        ColorTheme.ORANGE to appString(R.string.theme_orange),
        ColorTheme.CN to appString(R.string.theme_cn)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { (theme, title) ->
            FilterChip(
                selected = selected == theme,
                onClick = { onSelected(theme) },
                label = { Text(title) },
                leadingIcon = if (selected == theme) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else null
            )
        }
    }
}

// ---------- Component guide ----------
private data class GuideTopic(
    val id: String,
    @StringRes val categoryRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
    @StringRes val badgeRes: Int,
    @StringRes val whatItIsRes: Int,
    @StringRes val tryFirstRes: Int,
    @StringRes val bestForRes: Int,
    @StringRes val switchWhenRes: Int,
    val tipRes: List<Int>
)

private val componentGuideTopics = listOf(
    GuideTopic(
        id = "turnip",
        categoryRes = R.string.category_gpu_driver,
        titleRes = R.string.guide_turnip_title,
        subtitleRes = R.string.guide_turnip_subtitle,
        icon = Icons.Default.Eco,
        badgeRes = R.string.badge_recommended,
        whatItIsRes = R.string.guide_turnip_whatitis,
        tryFirstRes = R.string.guide_turnip_tryfirst,
        bestForRes = R.string.guide_turnip_bestfor,
        switchWhenRes = R.string.guide_turnip_switchwhen,
        tipRes = listOf(
            R.string.guide_turnip_tip1,
            R.string.guide_turnip_tip2,
            R.string.guide_turnip_tip3
        )
    ),
    GuideTopic(
        id = "qualcomm",
        categoryRes = R.string.category_gpu_driver,
        titleRes = R.string.guide_qualcomm_title,
        subtitleRes = R.string.guide_qualcomm_subtitle,
        icon = Icons.Default.Memory,
        badgeRes = R.string.badge_alternative,
        whatItIsRes = R.string.guide_qualcomm_whatitis,
        tryFirstRes = R.string.guide_qualcomm_tryfirst,
        bestForRes = R.string.guide_qualcomm_bestfor,
        switchWhenRes = R.string.guide_qualcomm_switchwhen,
        tipRes = listOf(
            R.string.guide_qualcomm_tip1,
            R.string.guide_qualcomm_tip2,
            R.string.guide_qualcomm_tip3
        )
    ),
    GuideTopic(
        id = "wine",
        categoryRes = R.string.category_windows,
        titleRes = R.string.guide_wine_title,
        subtitleRes = R.string.guide_wine_subtitle,
        icon = Icons.Default.WineBar,
        badgeRes = R.string.badge_base_runtime,
        whatItIsRes = R.string.guide_wine_whatitis,
        tryFirstRes = R.string.guide_wine_tryfirst,
        bestForRes = R.string.guide_wine_bestfor,
        switchWhenRes = R.string.guide_wine_switchwhen,
        tipRes = listOf(
            R.string.guide_wine_tip1,
            R.string.guide_wine_tip2,
            R.string.guide_wine_tip3
        )
    ),
    GuideTopic(
        id = "proton",
        categoryRes = R.string.category_windows,
        titleRes = R.string.guide_proton_title,
        subtitleRes = R.string.guide_proton_subtitle,
        icon = Icons.Default.Bolt,
        badgeRes = R.string.badge_games,
        whatItIsRes = R.string.guide_proton_whatitis,
        tryFirstRes = R.string.guide_proton_tryfirst,
        bestForRes = R.string.guide_proton_bestfor,
        switchWhenRes = R.string.guide_proton_switchwhen,
        tipRes = listOf(
            R.string.guide_proton_tip1,
            R.string.guide_proton_tip2,
            R.string.guide_proton_tip3
        )
    ),
    GuideTopic(
        id = "box64",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_box64_title,
        subtitleRes = R.string.guide_box64_subtitle,
        icon = Icons.Default.Inventory2,
        badgeRes = R.string.badge_common_default,
        whatItIsRes = R.string.guide_box64_whatitis,
        tryFirstRes = R.string.guide_box64_tryfirst,
        bestForRes = R.string.guide_box64_bestfor,
        switchWhenRes = R.string.guide_box64_switchwhen,
        tipRes = listOf(
            R.string.guide_box64_tip1,
            R.string.guide_box64_tip2,
            R.string.guide_box64_tip3
        )
    ),
    GuideTopic(
        id = "wowbox64",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_wowbox64_title,
        subtitleRes = R.string.guide_wowbox64_subtitle,
        icon = Icons.Default.AutoAwesome,
        badgeRes = R.string.badge_advanced,
        whatItIsRes = R.string.guide_wowbox64_whatitis,
        tryFirstRes = R.string.guide_wowbox64_tryfirst,
        bestForRes = R.string.guide_wowbox64_bestfor,
        switchWhenRes = R.string.guide_wowbox64_switchwhen,
        tipRes = listOf(
            R.string.guide_wowbox64_tip1,
            R.string.guide_wowbox64_tip2,
            R.string.guide_wowbox64_tip3
        )
    ),
    GuideTopic(
        id = "fexcore",
        categoryRes = R.string.category_cpu,
        titleRes = R.string.guide_fexcore_title,
        subtitleRes = R.string.guide_fexcore_subtitle,
        icon = Icons.Default.DeveloperBoard,
        badgeRes = R.string.badge_alternative,
        whatItIsRes = R.string.guide_fexcore_whatitis,
        tryFirstRes = R.string.guide_fexcore_tryfirst,
        bestForRes = R.string.guide_fexcore_bestfor,
        switchWhenRes = R.string.guide_fexcore_switchwhen,
        tipRes = listOf(
            R.string.guide_fexcore_tip1,
            R.string.guide_fexcore_tip2,
            R.string.guide_fexcore_tip3
        )
    ),
    GuideTopic(
        id = "dxvk",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_dxvk_title,
        subtitleRes = R.string.guide_dxvk_subtitle,
        icon = Icons.Default.SportsEsports,
        badgeRes = R.string.badge_dx8_11,
        whatItIsRes = R.string.guide_dxvk_whatitis,
        tryFirstRes = R.string.guide_dxvk_tryfirst,
        bestForRes = R.string.guide_dxvk_bestfor,
        switchWhenRes = R.string.guide_dxvk_switchwhen,
        tipRes = listOf(
            R.string.guide_dxvk_tip1,
            R.string.guide_dxvk_tip2,
            R.string.guide_dxvk_tip3
        )
    ),
    GuideTopic(
        id = "vkd3d",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_vkd3d_title,
        subtitleRes = R.string.guide_vkd3d_subtitle,
        icon = Icons.Default.ViewInAr,
        badgeRes = R.string.badge_dx12,
        whatItIsRes = R.string.guide_vkd3d_whatitis,
        tryFirstRes = R.string.guide_vkd3d_tryfirst,
        bestForRes = R.string.guide_vkd3d_bestfor,
        switchWhenRes = R.string.guide_vkd3d_switchwhen,
        tipRes = listOf(
            R.string.guide_vkd3d_tip1,
            R.string.guide_vkd3d_tip2,
            R.string.guide_vkd3d_tip3
        )
    ),
    GuideTopic(
        id = "d7vk",
        categoryRes = R.string.category_graphics,
        titleRes = R.string.guide_d7vk_title,
        subtitleRes = R.string.guide_d7vk_subtitle,
        icon = Icons.Default.Gamepad,
        badgeRes = R.string.badge_legacy_games,
        whatItIsRes = R.string.guide_d7vk_whatitis,
        tryFirstRes = R.string.guide_d7vk_tryfirst,
        bestForRes = R.string.guide_d7vk_bestfor,
        switchWhenRes = R.string.guide_d7vk_switchwhen,
        tipRes = listOf(
            R.string.guide_d7vk_tip1,
            R.string.guide_d7vk_tip2,
            R.string.guide_d7vk_tip3
        )
    )
)

private fun guideTopicIdForSection(sectionId: String): String = when {
    sectionId == "turnip" -> "turnip"
    sectionId == "qualcomm" -> "qualcomm"
    sectionId.startsWith("component:") -> sectionId.substringAfter("component:").lowercase(Locale.ROOT)
    else -> sectionId.lowercase(Locale.ROOT)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentGuideScreen(
    onBack: () -> Unit,
    adrenoSeries: String?,
    initialTopicId: String? = null
) {
    BackHandler { onBack() }

    val listState = rememberLazyListState()
    var expandedId by rememberSaveable { mutableStateOf(initialTopicId) }

    LaunchedEffect(initialTopicId) {
        if (initialTopicId != null) {
            val index = componentGuideTopics.indexOfFirst { it.id == initialTopicId }
            if (index >= 0) {
                expandedId = initialTopicId
                listState.animateScrollToItem(index + 3)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(appString(R.string.component_guide))
                        Text(
                            appString(R.string.what_each_download_does),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = appString(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "guide_intro") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appString(R.string.choose_components_confidence), style = MaterialTheme.typography.titleLarge)
                            Text(
                                appString(R.string.guide_intro_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            item(key = "guide_quick_pick") {
                QuickPickGuideCard(adrenoSeries)
            }

            item(key = "guide_notice") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            appString(R.string.guide_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            componentGuideTopics.forEach { topic ->
                item(key = "guide_topic_${topic.id}") {
                    GuideTopicCard(
                        topic = topic,
                        expanded = expandedId == topic.id,
                        onClick = {
                            expandedId = if (expandedId == topic.id) null else topic.id
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPickGuideCard(adrenoSeries: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(appString(R.string.quick_pick), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (adrenoSeries != null) appString(R.string.starting_points_adreno, adrenoSeries) else appString(R.string.good_starting_points),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
            QuickPickRow("DirectX 8–11", appString(R.string.quick_dx8_11))
            QuickPickRow("DirectX 12", appString(R.string.quick_dx12))
            QuickPickRow("Direct3D 3–7", appString(R.string.quick_d3d3_7))
            QuickPickRow(appString(R.string.windows_runtime), appString(R.string.quick_windows))
            QuickPickRow(appString(R.string.x86_64_arm64), appString(R.string.quick_cpu))
        }
    }
}

@Composable
private fun QuickPickRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun GuideTopicCard(
    topic: GuideTopic,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Icon(
                        topic.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appString(topic.titleRes), style = MaterialTheme.typography.titleMedium)
                    Text(
                        appString(topic.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) appString(R.string.collapse) else appString(R.string.expand)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(onClick = {}, label = { Text(appString(topic.categoryRes)) })
                AssistChip(onClick = {}, label = { Text(appString(topic.badgeRes)) })
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider()
                    GuideDetailBlock(appString(R.string.what_it_is), appString(topic.whatItIsRes), Icons.Default.Info)
                    GuideDetailBlock(appString(R.string.try_first), appString(topic.tryFirstRes), Icons.Default.PlayArrow)
                    GuideDetailBlock(appString(R.string.best_for), appString(topic.bestForRes), Icons.Default.CheckCircle)
                    GuideDetailBlock(appString(R.string.when_to_switch), appString(topic.switchWhenRes), Icons.Default.SwapHoriz)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            appString(R.string.useful_notes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        topic.tipRes.forEach { tipRes ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    appString(tipRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDetailBlock(label: String, text: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Driver hub / index ----------
private data class HubSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val latest: String,
    val source: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverHubScreen(
    modifier: Modifier = Modifier,
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    turnipSourceId: String,
    turnipSources: List<TurnipSource>,
    turnipReleases: List<GithubRelease>,
    qualcommSourceId: String,
    qualcommSources: List<QualcommSource>,
    qualcommReleases: List<GithubRelease>,
    componentSources: List<ComponentSource>,
    componentCatalogs: Map<String, Map<String, List<Component>>>,
    sourceCatalogRemote: Boolean,
    onTurnipSourceChange: (String) -> Unit,
    onQualcommSourceChange: (String) -> Unit,
    onOpenGuide: (String?) -> Unit,
    onDownloadAsset: (GithubRelease, GithubAsset) -> Unit,
    onDownloadComponent: (Component) -> Unit
) {
    val showQualcomm = qualcommReleases.isNotEmpty() &&
        (deviceInfo?.adrenoSeries == "6xx" || deviceInfo?.adrenoSeries == "7xx")

    val preferredComponentOrder = listOf("Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D", "D7VK")
    val discoveredComponentTypes = componentCatalogs.values
        .flatMap { it.keys }
        .distinct()
        .filterNot { it in preferredComponentOrder }
        .sorted()
    val componentOrder = preferredComponentOrder + discoveredComponentTypes
    val componentSourceSelections = remember { mutableStateMapOf<String, String>() }

    // 来源"综合最新最全"评分：类型覆盖数主导（×1000），版本总数次之。
    // 默认首推覆盖类型最全、版本最多的源；用户手动选择仍优先尊重。
    val sourceRichness = remember(componentSources, componentCatalogs) {
        componentSources.associate { s ->
            val catalog = componentCatalogs[s.id]
            val typeCount = catalog?.keys?.size ?: 0
            val versionCount = catalog?.values?.sumOf { it.size } ?: 0
            s.id to (typeCount * 1000 + versionCount)
        }
    }
    val sourcesByRichness = remember(componentSources, sourceRichness) {
        componentSources.sortedByDescending { sourceRichness[it.id] ?: 0 }
    }

    LaunchedEffect(componentSources, componentCatalogs) {
        componentOrder.forEach { type ->
            val saved = SettingsManager.getComponentSource(type)
            val candidates = sourcesByRichness.filter { source ->
                componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
            }
            val resolved = candidates.firstOrNull { it.id == saved } ?: candidates.firstOrNull()

            if (resolved != null) {
                componentSourceSelections[type] = resolved.id
                if (saved != resolved.id) SettingsManager.setComponentSource(type, resolved.id)
            }
        }
    }

    fun currentComponentSource(type: String): ComponentSource? {
        val selectedId = componentSourceSelections[type] ?: SettingsManager.getComponentSource(type)
        val candidates = sourcesByRichness.filter { source ->
            componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
        }
        return candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    }

    val currentTurnipSource = turnipSources.firstOrNull { it.id == turnipSourceId }
        ?: turnipSources.firstOrNull()
    val currentQualcommSource = qualcommSources.firstOrNull { it.id == qualcommSourceId }
        ?: qualcommSources.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "device_summary") {
            DeviceSummaryCard(deviceInfo = deviceInfo, isLoading = isLoading)
        }

        // ── 驱动专区：驱动区块直接平铺，不再通过索引卡片切换 ──
        item(key = "driver_region_header") {
            RegionHeader(
                title = appString(R.string.driver_region),
                icon = Icons.Default.Memory,
                remote = sourceCatalogRemote
            )
        }

        if (deviceInfo != null) {
            item(key = "driver_device_summary") {
                DriverDeviceSummaryCard(deviceInfo = deviceInfo)
            }
        }

        if (isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) {
            item(key = "driver_loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(appString(R.string.fetching_latest))
                    }
                }
            }
        }

        // 源列表始终展示（releases 失败时 DriverReleasePicker 显示空态提示），
        // 避免整个驱动区块因 releases 拉取失败而消失（修复"驱动专区空白"）。
        if (turnipSources.isNotEmpty()) {
            item(key = "turnip_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = turnipSources,
                    currentSourceId = turnipSourceId,
                    onSourceChange = onTurnipSourceChange,
                    releases = turnipReleases,
                    selectionKey = "turnip:$turnipSourceId",
                    onDownload = onDownloadAsset
                )
            }
        }

        if (showQualcomm && currentQualcommSource != null) {
            item(key = "qualcomm_section") {
                DriverCardDynamic(
                    title = appString(R.string.qualcomm_driver),
                    description = appString(R.string.qualcomm_driver_desc),
                    icon = Icons.Default.Memory,
                    sources = qualcommSources,
                    currentSourceId = qualcommSourceId,
                    onSourceChange = onQualcommSourceChange,
                    releases = qualcommReleases,
                    selectionKey = "qualcomm:$qualcommSourceId",
                    onDownload = onDownloadAsset
                )
            }
        }

        // ── 组件专区：每个组件类型一个独立区块，直接展示来源+版本+下载 ──
        item(key = "component_region_header") {
            RegionHeader(
                title = appString(R.string.component_region),
                icon = Icons.Default.Extension,
                remote = null
            )
        }

        componentOrder.forEach { type ->
            val source = currentComponentSource(type)
            val list = source?.let { componentCatalogs[it.id]?.get(type).orEmpty() }.orEmpty()
            if (list.isNotEmpty() && source != null) {
                item(key = "component:$type") {
                    ComponentSection(
                        type = type,
                        sources = sourcesByRichness.filter { s ->
                            componentCatalogs[s.id]?.get(type).orEmpty().isNotEmpty()
                        },
                        currentSource = source,
                        components = list,
                        selectionKey = "component:$type:${source.id}",
                        onSourceChange = { sourceId ->
                            componentSourceSelections[type] = sourceId
                            SettingsManager.setComponentSource(type, sourceId)
                        },
                        onDownload = onDownloadComponent
                    )
                }
            }
        }

        item(key = "footer") {
            Text(
                text = appString(R.string.versions_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 专区大标题（驱动专区 / 组件专区） */
@Composable
private fun RegionHeader(title: String, icon: ImageVector, remote: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (remote != null) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(if (remote) appString(R.string.live_sources) else appString(R.string.fallback_sources))
                },
                icon = {
                    Icon(
                        if (remote) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun DeviceSummaryCard(deviceInfo: DeviceInfo?, isLoading: Boolean) {
    val context = LocalContext.current
    val socName = remember { SocNameMapper.getCurrentSocName(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appString(R.string.your_device), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (isLoading) appString(R.string.detecting_hardware) else deviceInfo?.gpuRenderer ?: appString(R.string.hardware_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2
                    )
                    if (!isLoading) {
                        Text(
                            "SOC: $socName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (deviceInfo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DeviceStat(
                        icon = Icons.Default.Android,
                        label = appString(R.string.android_label),
                        value = deviceInfo.androidVersion,
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStat(
                        icon = Icons.Default.Memory,
                        label = appString(R.string.gpu_label),
                        value = "Adreno ${deviceInfo.adrenoSeries}",
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStat(
                        icon = Icons.Default.Storage,
                        label = appString(R.string.ram_label),
                        value = deviceInfo.ram,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceStat(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/**
 * 根据 GPU 信息给出 Winlator 驱动路线推荐。
 * 参考 Winlator 官方文档：Turnip 仅 Adreno 6xx+，不支持型号用 VirGL，Mali 用 Zink/Gladio。
 */
private data class DriverRouteRecommendation(
    val primaryRoute: String,        // 主推驱动路线
    val secondaryRoute: String,       // 备选
    val turnipSupport: TurnipSupport, // Turnip 支持状态
    val note: String                  // 简短说明
)

private enum class TurnipSupport { FULL, PARTIAL, NONE }

private fun recommendDriverRoute(deviceInfo: DeviceInfo): DriverRouteRecommendation {
    val model = deviceInfo.gpuModel
    val vendor = deviceInfo.gpuVendor
    val series = deviceInfo.adrenoSeries

    return when (vendor) {
        GpuVendor.ADRENO -> {
            // Turnip 官方弱支持的 Adreno 型号（735/732/720/710/613 等）
            val weakModels = setOf("735", "732", "720", "710", "613", "612", "610")
            when {
                weakModels.contains(model) -> DriverRouteRecommendation(
                    primaryRoute = "VirGL",
                    secondaryRoute = "Turnip（可能不稳定）",
                    turnipSupport = TurnipSupport.PARTIAL,
                    note = "该 Adreno 型号 Turnip 支持有限，DX9 游戏用 VirGL 更稳，3D 游戏可尝试 Turnip"
                )
                series == "8xx" -> DriverRouteRecommendation(
                    primaryRoute = "Turnip + Zink",
                    secondaryRoute = "Eden 专用 Turnip（部分仓库）",
                    turnipSupport = TurnipSupport.FULL,
                    note = "Adreno 8xx 为最新架构，推荐最新 Turnip 构建，部分驱动针对 Eden 模拟器优化"
                )
                else -> DriverRouteRecommendation(
                    primaryRoute = "Turnip + Zink",
                    secondaryRoute = "VirGL（DX9 轻量游戏）",
                    turnipSupport = TurnipSupport.FULL,
                    note = "Adreno 6xx/7xx 主流型号，Turnip + Zink 是 Winlator 最佳性能组合，支持 DXVK/VKD3D"
                )
            }
        }
        GpuVendor.MALI -> DriverRouteRecommendation(
            primaryRoute = "Zink / Gladio",
            secondaryRoute = "PanVK（部分型号实验性）",
            turnipSupport = TurnipSupport.NONE,
            note = "Mali GPU 不支持 Turnip，Winlator 用 Zink 或 Gladio 翻译层，兼容性和性能弱于 Adreno+Turnip"
        )
        GpuVendor.XCLIPSE -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "无成熟方案",
            turnipSupport = TurnipSupport.NONE,
            note = "Exynos Xclipse（AMD RDNA）在 Android 上无成熟 Vulkan 驱动，Winlator 兼容性差"
        )
        GpuVendor.POWERVR -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "Zink（实验性）",
            turnipSupport = TurnipSupport.NONE,
            note = "PowerVR GPU 无 Turnip 支持，VirGL 是唯一较稳定的选择"
        )
        GpuVendor.UNKNOWN -> DriverRouteRecommendation(
            primaryRoute = "VirGL",
            secondaryRoute = "尝试 Zink",
            turnipSupport = TurnipSupport.NONE,
            note = "无法识别 GPU 厂商，建议先用 VirGL 兜底，再根据实际情况尝试其他驱动"
        )
    }
}

/**
 * 驱动适配推荐卡片：基于注册表匹配结果给出本机可用驱动源，
 * 直接告诉用户"该下哪个"，点击一键跳转驱动专区。
 * 取代旧版纯科普文本的"路线推荐"（内容人人都知道，无实操价值）。
 */
@Composable
private fun DriverRecommendationCard(
    deviceInfo: DeviceInfo,
    onNavigateToDrivers: (() -> Unit)?
) {
    var recommendations by remember(deviceInfo) { mutableStateOf<List<DriverMatchResult>?>(null) }
    var loadFailed by remember(deviceInfo) { mutableStateOf(false) }

    LaunchedEffect(deviceInfo) {
        loadFailed = false
        try {
            val result = withContext(Dispatchers.IO) {
                val entries = DriverRegistryRepository.load()
                DriverRegistryRepository.match(deviceInfo, entries)
                    .filter { it.recommended || it.score >= 80 }
                    .take(3)
            }
            if (result.isEmpty()) loadFailed = true else recommendations = result
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val matches = recommendations
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.driver_recommend_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.weight(1f))
            }

            // 技术路线一行（保留专业信息，但压缩为说明而非主体）
            val route = recommendDriverRoute(deviceInfo)
            val routeColor = when (route.turnipSupport) {
                TurnipSupport.FULL -> Color(0xFF2E7D32)
                TurnipSupport.PARTIAL -> Color(0xFFE65100)
                TurnipSupport.NONE -> Color(0xFFC62828)
            }
            Text(
                "主推 ${route.primaryRoute} · 备选 ${route.secondaryRoute}",
                style = MaterialTheme.typography.bodySmall,
                color = routeColor
            )
            // 专业说明：解释翻译层组合，用户能看懂为什么这么推荐
            Text(
                route.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )

            when {
                loadFailed -> {
                    Text(
                        appString(R.string.driver_recommend_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                matches == null -> {
                    // 匹配计算中
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    matches.forEach { match ->
                        val entry = match.entry
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    buildString {
                                        append(match.matchReason)
                                        entry.targetEmulator.takeIf { it.isNotBlank() && it != "generic" }?.let {
                                            append(" · 适配 $it")
                                        }
                                        entry.maturity.takeIf { it.isNotBlank() }?.let {
                                            append(" · ${it.uppercase()}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                )
                                // 来源简介（注册表中文描述）：说明维护者/构建类型，体现推荐理由
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (match.recommended) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        appString(R.string.category_recommended),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (onNavigateToDrivers != null) {
                Button(
                    onClick = onNavigateToDrivers,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.goto_driver_region))
                }
            }
        }
    }
}

@Composable
private fun DownloadIndexCard(
    section: HubSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 142.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = appString(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                appString(R.string.source_format, section.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                appString(R.string.latest_format, section.latest),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

private fun componentIcon(type: String): ImageVector = when (type) {
    "Wine" -> Icons.Default.WineBar
    "Proton" -> Icons.Default.Bolt
    "Box64" -> Icons.Default.Inventory2
    "WOWBox64" -> Icons.Default.AutoAwesome
    "DXVK" -> Icons.Default.SportsEsports
    "FEXCore" -> Icons.Default.DeveloperBoard
    "VKD3D" -> Icons.Default.ViewInAr
    "D7VK" -> Icons.Default.Gamepad
    else -> Icons.Default.Extension
}

@Composable
private fun componentSubtitle(type: String): String = when (type) {
    "Wine" -> appString(R.string.component_wine_subtitle)
    "Proton" -> appString(R.string.component_proton_subtitle)
    "Box64" -> appString(R.string.component_box64_subtitle)
    "WOWBox64" -> appString(R.string.component_wowbox64_subtitle)
    "DXVK" -> appString(R.string.component_dxvk_subtitle)
    "FEXCore" -> appString(R.string.component_fex_subtitle)
    "VKD3D" -> appString(R.string.component_vkd3d_subtitle)
    "D7VK" -> appString(R.string.component_d7vk_subtitle)
    else -> appString(R.string.runtime_component)
}

/** 根据设备 Adreno 系列计算 Turnip 源优先级：匹配型号/系列 → 推荐，有白名单无匹配 → 专属，无白名单 → 通用 */
private fun turnipCategoryOf(source: TurnipSource, adrenoSeries: String?): String {
    val seriesHit = source.supportedSeries.contains(adrenoSeries)
    // 通用多系列 turnip 构建（覆盖 ≥2 个系列且不限定具体型号）→ 适合绝大多数设备
    val isUniversal = source.supportedModels.isEmpty() && source.supportedSeries.size >= 2
    return when {
        adrenoSeries == null ->
            if (source.supportedModels.isEmpty() && source.supportedSeries.size >= 2) "general"
            else "exclusive"
        // 通用构建且适配当前系列 → 推荐
        isUniversal && seriesHit -> "recommended"
        // 系列匹配（含具体型号专用源，如 s1mptom 830/840）→ 专属
        seriesHit -> "exclusive"
        // 当前系列不适配（如 8xx 设备看 7xx 专用源）→ 专属，不隐藏但也不推荐
        else -> "exclusive"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnipDriverSection(
    adrenoSeries: String?,
    sources: List<TurnipSource>,
    currentSourceId: String,
    onSourceChange: (String) -> Unit,
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DownloadPanelHeader(
                icon = Icons.Default.Eco,
                title = appString(R.string.turnip_driver),
                description = when (adrenoSeries) {
                    "8xx" -> appString(R.string.turnip_desc_8xx)
                    "6xx", "7xx" -> appString(R.string.turnip_desc_6_7)
                    else -> appString(R.string.turnip_desc_generic)
                }
            )

            if (currentSource != null) {
                val currentCategory = turnipCategoryOf(currentSource, adrenoSeries)
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentCategory = currentCategory,
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            category = turnipCategoryOf(source, adrenoSeries),
                            preview = buildTurnipPreview(source)
                        )
                    },
                    onSelected = { sourceId ->
                        if (sourceId != currentSourceId) onSourceChange(sourceId)
                    }
                )
            }

            DriverReleasePicker(
                releases = releases,
                selectionKey = selectionKey,
                onDownload = onDownload,
                buttonLabel = appString(R.string.download_turnip)
            )
        }
    }
}

/** 来源预览窗格条目：图标 + 文本（支持型号 / 目标模拟器 / 活跃度等） */
private data class PreviewItem(val icon: ImageVector, val text: String)

private data class SourcePickerOption(
    val id: String,
    val name: String,
    val description: String,
    val experimental: Boolean,
    /** 英文 key：recommended / general / exclusive，UI 层映射为本地化文案 */
    val category: String = "general",
    /** 已归档/停更源，UI 显示"已停更"标签 */
    val archived: Boolean = false,
    /** 源仓库更新时间提示（组件侧传入，如"更新于 3 天前"） */
    val updatedHint: String? = null,
    /** 预览窗格：一行一条（图标 + 文本），如支持型号 / 目标模拟器 / stars */
    val preview: List<PreviewItem> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePickerCard(
    title: String,
    currentName: String,
    currentDescription: String,
    currentExperimental: Boolean,
    options: List<SourcePickerOption>,
    onSelected: (String) -> Unit,
    currentArchived: Boolean = false,
    /** 当前源的优先级分类（英文 key），用于顶部标签显示 */
    currentCategory: String = "general",
    /** 源仓库更新时间提示（如"更新于 3 天前"），组件侧传入 */
    currentUpdatedHint: String? = null
) {
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(currentName, style = MaterialTheme.typography.titleMedium)
                        // 优先级标签（推荐 / 专属 / 通用），停更优先显示停更
                        if (!currentArchived && currentCategory == "recommended") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_recommended)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        if (!currentArchived && currentCategory == "exclusive") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_exclusive)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                        if (!currentArchived && currentCategory == "general") {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.category_general)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        if (currentExperimental) {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.experimental)) }
                            )
                        }
                        if (currentArchived) {
                            Spacer(Modifier.width(6.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(appString(R.string.source_archived)) }
                            )
                        }
                    }
                    if (currentDescription.isNotBlank()) {
                        Text(
                            currentDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                            maxLines = 2
                        )
                    }
                    // 源仓库更新时间（组件清单无时间字段，用仓库活跃度参考）
                    if (currentUpdatedHint != null) {
                        Text(
                            currentUpdatedHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = { showSheet = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = options.size > 1
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (options.size > 1) appString(R.string.change_source) else appString(R.string.only_source_available))
            }
        }
    }

    if (showSheet) {
        // 近全屏 Dialog：替代 ModalBottomSheet，避免固定高度导致的底部大空白
        Dialog(onDismissRequest = { showSheet = false }) {
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
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 顶部：标题 + 说明 + 关闭
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appString(R.string.choose_source),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                        }
                    }
                    Text(
                        appString(R.string.emuhub_downloads_upstream),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 当前选中提示
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                currentName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    HorizontalDivider()

                    // 分组：推荐 / 通用 / 专属 / 已停更
                    val categoryLabel = mapOf(
                        "recommended" to appString(R.string.category_recommended),
                        "general" to appString(R.string.category_general),
                        "exclusive" to appString(R.string.category_exclusive),
                        "archived" to appString(R.string.source_archived)
                    )
                    val grouped = options.groupBy { opt ->
                        if (opt.archived) "archived" else opt.category
                    }
                    val categoryOrder = listOf("recommended", "general", "exclusive", "archived")
                        .filter { grouped.containsKey(it) }
                    val flatItems = buildList {
                        categoryOrder.forEach { cat ->
                            val group = grouped[cat].orEmpty()
                            if (group.isNotEmpty()) {
                                add("__header__$cat")
                                group.forEach { add(it) }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // 分组头用 GridItemSpan 跨满两列：此前 header 只占一格，
                        // 导致后续卡片左右列错位（用户反馈的"排列问题"根因）。
                        flatItems.forEach { item ->
                            if (item is String) {
                                val cat = item.removePrefix("__header__")
                                item(
                                    key = "__header_$cat",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${categoryLabel[cat].orEmpty()}（${grouped[cat].orEmpty().size}）",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when (cat) {
                                                "recommended" -> MaterialTheme.colorScheme.primary
                                                "exclusive" -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Divider(modifier = Modifier.weight(1f))
                                    }
                                }
                            } else {
                                val option = item as SourcePickerOption
                                item(key = option.id) {
                                    SourcePickerGridCard(
                                        option = option,
                                        selected = option.name == currentName,
                                        onSelected = {
                                            onSelected(option.id)
                                            showSheet = false
                                        }
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

/** Turnip 来源预览：支持型号 / 目标模拟器 / 活跃度，全部来自注册表静态元数据 */
@Composable
private fun buildTurnipPreview(source: TurnipSource): List<PreviewItem> {
    val lines = mutableListOf<PreviewItem>()
    val models = source.supportedModels.sorted()
    val series = source.supportedSeries.sorted()
    if (models.isNotEmpty()) {
        lines += PreviewItem(Icons.Default.PhoneAndroid, appString(R.string.preview_supported, models.joinToString("/")))
    } else if (series.isNotEmpty()) {
        lines += PreviewItem(Icons.Default.PhoneAndroid, appString(R.string.preview_series, series.joinToString("/")))
    }
    when (source.targetEmulator) {
        "eden" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_eden))
        "winlator" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_winlator))
        "termux" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_termux))
        "gamehub" -> lines += PreviewItem(Icons.Default.PlayArrow, appString(R.string.preview_gamehub))
    }
    if (source.stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(source.stars)))
    return lines
}

/** Qualcomm 来源预览：系统驱动说明 + 活跃度 + 停更状态 */
@Composable
private fun buildQualcommPreview(source: QualcommSource): List<PreviewItem> {
    val lines = mutableListOf<PreviewItem>()
    lines += PreviewItem(Icons.Default.Memory, appString(R.string.preview_qualcomm_system))
    if (source.archived) lines += PreviewItem(Icons.Default.Warning, appString(R.string.preview_archived))
    if (source.stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(source.stars)))
    return lines
}

private fun formatStars(stars: Long): String =
    if (stars >= 1000) String.format("%.1fk", stars / 1000.0)
    else stars.toString()

/** 来源选择弹窗里的单个卡片：名称 + 成熟度 + 简介 + 预览窗格（双列网格单元） */
@Composable
private fun SourcePickerGridCard(
    option: SourcePickerOption,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Card(
        onClick = onSelected,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：图标 + 名称（名称独占剩余空间，标签不挤占，避免被压成单字）
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ) {
                    Icon(
                        if (selected) Icons.Default.Check else Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    option.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            // 第二行：状态标签（实验版/已归档/推荐/专属），独立成行
            val hasTag = option.experimental || option.archived ||
                (!option.archived && (option.category == "recommended" || option.category == "exclusive"))
            if (hasTag) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (option.experimental) {
                        TagChip(appString(R.string.experimental), MaterialTheme.colorScheme.tertiary)
                    }
                    if (option.archived) {
                        TagChip(
                            appString(R.string.source_archived),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    if (!option.archived && option.category == "recommended") {
                        TagChip(appString(R.string.category_recommended), MaterialTheme.colorScheme.primary)
                    }
                    if (!option.archived && option.category == "exclusive") {
                        TagChip(appString(R.string.category_exclusive), MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            if (option.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (option.updatedHint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    option.updatedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
            if (option.preview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        option.preview.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 来源卡片/列表用的小标签 chip */
@Composable
private fun TagChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DownloadPanelHeader(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverCardDynamic(
    title: String,
    description: String,
    icon: ImageVector,
    sources: List<QualcommSource>,
    currentSourceId: String,
    onSourceChange: (String) -> Unit,
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DownloadPanelHeader(icon = icon, title = title, description = description)
            if (currentSource != null) {
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentArchived = currentSource.archived,
                    currentCategory = "general",
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            archived = source.archived,
                            category = "general",
                            preview = buildQualcommPreview(source)
                        )
                    },
                    onSelected = { sourceId ->
                        if (sourceId != currentSourceId) onSourceChange(sourceId)
                    }
                )
            }
            DriverReleasePicker(
                releases = releases,
                selectionKey = selectionKey,
                onDownload = onDownload,
                buttonLabel = appString(R.string.download_type, title)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReleaseChangelog(release: GithubRelease?) {
    val notes = remember(release?.tagName, release?.body) {
        release?.body?.let { cleanReleaseNotes(it) }.orEmpty()
    }
    if (notes.isBlank()) return
    var expanded by remember(release?.tagName) { mutableStateOf(false) }
    var showTranslated by remember(release?.tagName) { mutableStateOf(false) }
    var translated by remember(release?.tagName) { mutableStateOf<String?>(null) }
    var translating by remember(release?.tagName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    appString(R.string.changelog),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (expanded) {
                    // 中/英切换按钮（在线翻译，失败回退原文）
                    TextButton(
                        onClick = {
                            if (showTranslated && translated != null) {
                                showTranslated = false
                            } else if (translated != null) {
                                showTranslated = true
                            } else if (!translating) {
                                translating = true
                                scope.launch {
                                    val result = translateReleaseNotes(notes)
                                    translated = result
                                    showTranslated = result != null
                                    translating = false
                                }
                            }
                        },
                        enabled = !translating
                    ) {
                        if (translating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (showTranslated) appString(R.string.show_original)
                                else appString(R.string.translate_changelog),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                val displayText = if (showTranslated) translated ?: notes else notes
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * GitHub release 正文是 Markdown，这里不引入完整 Markdown 渲染库，
 * 只剥离常见标记（标题/粗体/代码/链接/图片/引用/列表前缀），保留纯文本与换行。
 */
private fun cleanReleaseNotes(raw: String): String = raw
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("!\\[([^]]*)]\\(\\s*[^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\(\\s*[^)]*\\)"), "$1")
    .replace(Regex("(?s)(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
    .replace(Regex("(?m)^\\s*>\\s?"), "")
    .replace(Regex("[ \\t]+\n"), "\n")
    .trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverReleasePicker(
    releases: List<GithubRelease>,
    selectionKey: String,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    buttonLabel: String
) {
    val context = LocalContext.current
    val selectVersionFileMessage = appString(R.string.select_version_file)
    var expandedAsset by remember { mutableStateOf(false) }
    var selectedTag by rememberSaveable(selectionKey) {
        mutableStateOf(SettingsManager.getSelectedReleaseTag(selectionKey))
    }

    val latestRelease = releases.firstOrNull()
    val selectedRelease = remember(releases, selectedTag) {
        releases.firstOrNull { it.tagName == selectedTag } ?: releases.firstOrNull()
    }

    // If a previously saved release disappeared, gracefully fall back to the
    // newest available one. User choices are never reset just by navigating.
    LaunchedEffect(selectionKey, releases) {
        val savedTag = SettingsManager.getSelectedReleaseTag(selectionKey)
        val resolved = releases.firstOrNull { it.tagName == savedTag } ?: releases.firstOrNull()
        if (resolved != null && selectedTag != resolved.tagName) {
            selectedTag = resolved.tagName
            SettingsManager.setSelectedReleaseTag(selectionKey, resolved.tagName)
        }
    }

    val currentReleaseTag = selectedRelease?.tagName.orEmpty()
    var selectedAssetName by rememberSaveable(selectionKey, currentReleaseTag) {
        mutableStateOf(
            if (currentReleaseTag.isNotEmpty()) {
                SettingsManager.getSelectedAssetName(selectionKey, currentReleaseTag)
            } else null
        )
    }
    val selectedAsset = remember(selectedRelease, selectedAssetName) {
        selectedRelease?.assets?.firstOrNull { it.name == selectedAssetName }
            ?: selectedRelease?.assets?.firstOrNull()
    }

    // 已下载态：文件存在则按钮置灰显示"已下载"（下载路径 Driver/<subPath>）
    val downloaded = remember(selectedRelease, selectedAsset) {
        val release = selectedRelease
        val asset = selectedAsset
        if (release != null && asset != null) {
            val desired = sanitizeFileName("${release.tagName}_${asset.name}")
            fileExistsOnDisk(context, desired, "Drivers")
        } else false
    }

    if (releases.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                appString(R.string.no_compatible_releases),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = appString(R.string.version),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${releases.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Divider(modifier = Modifier.weight(1f))
    }

    // 版本双列网格：每个版本一张卡片，点击选中，选中高亮
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            releases.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { release ->
                DriverVersionCard(
                    release = release,
                    isSelected = release.tagName == selectedRelease?.tagName,
                    isLatest = release.tagName == latestRelease?.tagName,
                    onClick = {
                        selectedTag = release.tagName
                        SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                    }
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            releases.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { release ->
                DriverVersionCard(
                    release = release,
                    isSelected = release.tagName == selectedRelease?.tagName,
                    isLatest = release.tagName == latestRelease?.tagName,
                    onClick = {
                        selectedTag = release.tagName
                        SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                    }
                )
            }
        }
    }

    ReleaseChangelog(selectedRelease)

    if ((selectedRelease?.assets?.size ?: 0) > 1) {
        ExposedDropdownMenuBox(
            expanded = expandedAsset,
            onExpandedChange = { expandedAsset = !expandedAsset }
        ) {
            OutlinedTextField(
                value = selectedAsset?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAsset) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(appString(R.string.file)) },
                shape = RoundedCornerShape(18.dp),
                maxLines = 1
            )
            ExposedDropdownMenu(
                expanded = expandedAsset,
                onDismissRequest = { expandedAsset = false }
            ) {
                selectedRelease?.assets?.forEach { asset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(asset.name)
                                Text(
                                    formatBytes(asset.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            selectedAssetName = asset.name
                            selectedRelease.tagName.let { tag ->
                                SettingsManager.setSelectedAssetName(selectionKey, tag, asset.name)
                            }
                            expandedAsset = false
                        }
                    )
                }
            }
        }
    }

    Button(
        onClick = {
            val release = selectedRelease
            val asset = selectedAsset
            if (release != null && asset != null) {
                // Also persist the automatically selected first asset. This makes
                // the exact choice stable even when a release has multiple files.
                SettingsManager.setSelectedReleaseTag(selectionKey, release.tagName)
                SettingsManager.setSelectedAssetName(selectionKey, release.tagName, asset.name)
                onDownload(release, asset)
            } else {
                Toast.makeText(context, selectVersionFileMessage, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
        enabled = !downloaded
    ) {
        if (downloaded) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appString(R.string.already_downloaded))
        } else {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(buttonLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 组件版本文件大小：清单不含 size，展开版本列表时 HEAD 懒加载（结果全局缓存） */
@Composable
private fun ComponentSizeText(component: Component) {
    var sizeBytes by remember(component.remoteUrl) { mutableStateOf(component.sizeBytes) }
    LaunchedEffect(component.remoteUrl) {
        if (sizeBytes == null) {
            sizeBytes = fetchRemoteSizeBytes(component.remoteUrl)
        }
    }
    val bytes = sizeBytes
    if (bytes != null && bytes > 0) {
        val text = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else "%.0f KB".format(bytes / 1024.0)
        Text(
            " · $text",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 驱动版本双列网格卡片：版本号 + 日期 + 文件数 + 大小 + latest 标签 */
@Composable
private fun DriverVersionCard(
    release: GithubRelease,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit
) {
    val totalSize = release.assets.sumOf { it.sizeBytes }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = release.tagName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLatest) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${release.assets.size} ${appString(R.string.files)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (totalSize > 0) {
                    Text(
                        text = formatBytes(totalSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 发布时间：GitHub release 的 published_at 转为相对时间（x 天前）
            if (release.publishedAt.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatRelativeTime(release.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 组件版本双列网格卡片：版本号 + 来源 + 大小 + latest 标签 */
@Composable
private fun ComponentVersionCard(
    component: Component,
    sourceName: String,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = component.verName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLatest) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ComponentSizeText(component)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** 组件分支识别：从 verName 解析主线/Sarek/GPLAsync/ARM64EC 等变体 */
private fun componentBranch(verName: String): String {
    val n = verName.lowercase()
    return when {
        n.contains("sarek") -> "Sarek"
        n.contains("gplasync") || n.contains("async") -> "GPLAsync"
        n.contains("arm64ec") -> "ARM64EC"
        else -> componentBranchMainlineLabel
    }
}

/** 组件"主线"分支的显示名 */
private const val componentBranchMainlineLabel = "主线"

@Composable
fun ComponentSection(
    type: String,
    sources: List<ComponentSource>,
    currentSource: ComponentSource,
    components: List<Component>,
    selectionKey: String,
    updatedAtMap: Map<String, Long> = emptyMap(),
    onSourceChange: (String) -> Unit,
    onDownload: (Component) -> Unit
) {
    var selectedVersion by rememberSaveable(selectionKey) {
        mutableStateOf(SettingsManager.getSelectedComponentVersion(selectionKey))
    }
    val context = LocalContext.current
    val selectVersionMessage = appString(R.string.select_version)
    val latestComponent = components.firstOrNull()
    val selected = remember(components, selectedVersion) {
        components.firstOrNull { it.verName == selectedVersion } ?: components.firstOrNull()
    }

    // 已下载态：与 downloadComponent 相同命名/路径规则（Components/<type>/）
    val downloaded = remember(selected) {
        if (selected != null) {
            val fileName = sanitizeFileName(Uri.decode(selected.remoteUrl.substringAfterLast("/")))
            fileExistsOnDisk(context, fileName, "Components/$type")
        } else false
    }

    LaunchedEffect(selectionKey, components) {
        val saved = SettingsManager.getSelectedComponentVersion(selectionKey)
        val resolved = components.firstOrNull { it.verName == saved } ?: components.firstOrNull()
        if (resolved != null && selectedVersion != resolved.verName) {
            selectedVersion = resolved.verName
            SettingsManager.setSelectedComponentVersion(selectionKey, resolved.verName)
        }
    }

    // 折叠状态：默认收起，一屏可浏览全部类型
    var expanded by rememberSaveable(type) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // 折叠头：图标 + 类型名 + 版本数 + 当前版本 + 来源 + 展开箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    componentIcon(type),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(type, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${components.size} ${appString(R.string.version)} · ${currentSource.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded) {
                    Text(
                        text = selected?.verName ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(0.55f)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DownloadPanelHeader(
                        icon = componentIcon(type),
                        title = type,
                        description = componentSubtitle(type)
                    )

                    SourcePickerCard(
                        title = appString(R.string.source_type, type),
                        currentName = currentSource.name,
                        currentDescription = currentSource.description,
                        currentExperimental = currentSource.experimental,
                        currentUpdatedHint = updatedAtMap[currentSource.id]?.let { formatRelativeTimeMs(it) },
                        options = sources.map { source ->
                            SourcePickerOption(
                                id = source.id,
                                name = source.name,
                                description = source.description,
                                experimental = source.experimental,
                                updatedHint = updatedAtMap[source.id]?.let { formatRelativeTimeMs(it) }
                            )
                        },
                        onSelected = onSourceChange
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = appString(R.string.version),
                            style = MaterialTheme.typography.titleSmall,
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

                    // 组件版本双列网格：按分支分组（主线 / Sarek / GPLAsync / ARM64EC）
                    // 修改原因：原版把 sarek/gplasync 等分支混在 DXVK 大类里，用户无法区分构建来源。
                    // 影响范围：组件区块展开后的版本区，不影响下载逻辑。
                    // 回滚方法：删除 componentBranch 与分组循环，恢复原双列逻辑。
                    val branchGroups = components.groupBy { componentBranch(it.verName) }
                    val branchOrder = listOf(
                        componentBranchMainlineLabel,
                        "Sarek", "GPLAsync", "ARM64EC"
                    )
                    val orderedBranches = branchOrder.filter { branchGroups.containsKey(it) } +
                        (branchGroups.keys - branchOrder.toSet()).toList()
                    orderedBranches.forEach { branch ->
                        val branchItems = branchGroups[branch].orEmpty()
                        // 分支标题（单个分支时无需标注）
                        if (orderedBranches.size > 1) {
                            Text(
                                branch,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                branchItems.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { component ->
                                    ComponentVersionCard(
                                        component = component,
                                        sourceName = currentSource.name,
                                        isSelected = component.verName == selected?.verName,
                                        isLatest = component.verName == latestComponent?.verName,
                                        onClick = {
                                            selectedVersion = component.verName
                                            SettingsManager.setSelectedComponentVersion(selectionKey, component.verName)
                                        }
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                branchItems.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { component ->
                                    ComponentVersionCard(
                                        component = component,
                                        sourceName = currentSource.name,
                                        isSelected = component.verName == selected?.verName,
                                        isLatest = component.verName == latestComponent?.verName,
                                        onClick = {
                                            selectedVersion = component.verName
                                            SettingsManager.setSelectedComponentVersion(selectionKey, component.verName)
                                        }
                                    )
                                }
                            }
                        }
                        if (branchItems.isNotEmpty() && branch != orderedBranches.last()) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // 组件官方更新日志：从选中版本的 release 地址反查正文，支持在线翻译
                    var compLog by remember(selectionKey, selected?.verName) { mutableStateOf<String?>(null) }
                    var compLogLoading by remember(selectionKey, selected?.verName) { mutableStateOf(false) }
                    LaunchedEffect(selectionKey, selected?.verName) {
                        compLog = null
                        if (selected != null) {
                            compLogLoading = true
                            compLog = fetchComponentReleaseNotes(selected.remoteUrl)
                            compLogLoading = false
                        }
                    }
                    if (compLogLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appString(R.string.component_log_loading),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (compLog != null) {
                        ReleaseChangelog(
                            GithubRelease(
                                tagName = selected?.verName.orEmpty(),
                                name = selected?.verName.orEmpty(),
                                assets = emptyList(),
                                body = compLog.orEmpty()
                            )
                        )
                    }

                    Button(
                        onClick = {
                            selected?.let {
                                SettingsManager.setSelectedComponentVersion(selectionKey, it.verName)
                                onDownload(it)
                            } ?: Toast.makeText(context, selectVersionMessage, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                        enabled = !downloaded
                    ) {
                        if (downloaded) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(appString(R.string.already_downloaded))
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(appString(R.string.download_type, type))
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 5 Tab 导航：设备 / 驱动 / 组件 / 下载 / 设置
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 设备 Tab：展示设备摘要 + Vulkan 采集信息。
 * Vulkan 信息通过 native 引擎（dlopen 系统 libvulkan）采集，
 * 替换原先仅靠 GL_RENDERER 字符串解析的方案。
 */
@Composable
fun DeviceScreen(
    modifier: Modifier = Modifier,
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    onNavigateToDrivers: (() -> Unit)? = null
) {
    var vulkanPayload by remember { mutableStateOf<VulkanInfoPayload?>(null) }
    var vulkanLoading by remember { mutableStateOf(true) }
    var vulkanError by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    var benchmarkLoading by remember { mutableStateOf(false) }
    var benchmarkError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vulkanLoading = true
        vulkanError = null
        try {
            val payload = withContext(Dispatchers.IO) {
                NativeVulkanBridge.collectSystemVulkanInfo()
            }
            vulkanPayload = payload
            if (!payload.success) {
                vulkanError = payload.errorMessage ?: payload.errorCode
            }
        } catch (e: Exception) {
            vulkanError = e.message ?: "unknown error"
        } finally {
            vulkanLoading = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "device_summary") {
            DeviceSummaryCard(deviceInfo = deviceInfo, isLoading = isLoading)
        }

        if (!isLoading && deviceInfo != null) {
            item(key = "driver_route") {
                DriverRecommendationCard(
                    deviceInfo = deviceInfo,
                    onNavigateToDrivers = onNavigateToDrivers
                )
            }
        }

        item(key = "vulkan_header") {
            RegionHeader(
                title = appString(R.string.vulkan_title),
                icon = Icons.Default.Memory,
                remote = null
            )
        }

        if (vulkanLoading) {
            item(key = "vulkan_loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(appString(R.string.vulkan_collecting))
                    }
                }
            }
        } else if (vulkanError != null) {
            item(key = "vulkan_error") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.vulkan_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vulkanError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else if (vulkanPayload != null && vulkanPayload!!.devices.isNotEmpty()) {
            val device = vulkanPayload!!.devices[0]

            item(key = "vulkan_overview") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            device.deviceName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        InfoRow("Vulkan API", device.apiVersion)
                        InfoRow(appString(R.string.meta_driver_version), device.driverVersion)
                        if (device.driverName.isNotEmpty()) {
                            InfoRow(appString(R.string.vulkan_driver_name), device.driverName)
                        }
                    }
                }
            }

            item(key = "vulkan_toggle") {
                OutlinedButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showDetails) appString(R.string.vulkan_hide_details) else appString(R.string.vulkan_view_details))
                }
            }

            if (showDetails) {
                item(key = "vulkan_basic") {
                    VulkanDetailCard(
                        title = appString(R.string.vulkan_basic_info),
                        content = {
                            Text("类型: ${device.deviceType}  ·  Vendor: 0x${"%04X".format(device.vendorId)}  ·  Device: 0x${"%04X".format(device.deviceId)}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            if (device.driverInfo.isNotEmpty()) {
                                InfoRow(appString(R.string.vulkan_driver_info), device.driverInfo)
                            }
                            InfoRow(appString(R.string.vulkan_extensions), device.extensions.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_types), device.memoryTypes.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_heaps), device.memoryHeaps.size.toString())
                            InfoRow(appString(R.string.vulkan_queue_families), device.queueFamilies.size.toString())
                        }
                    )
                }

                item(key = "vulkan_features") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_features)}（${device.features.filter { it.value }.size}/${device.features.size}）",
                        content = {
                            device.features.filter { it.value }.keys.sorted().forEach { feature ->
                                Text("• $feature", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_extensions") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_extension_list)}（${device.extensions.size}）",
                        content = {
                            device.extensions.sortedBy { it.name }.forEach { ext ->
                                Text("• ${ext.name} (v${ext.specVersion})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_memory") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_memory_heaps)}（${device.memoryHeaps.size}）",
                        content = {
                            device.memoryHeaps.forEachIndexed { index, heap ->
                                val sizeMB = heap.size / (1024 * 1024)
                                Text("• ${appString(R.string.vulkan_heap, index)}: ${sizeMB}MB  [${heap.flags}]", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("${appString(R.string.vulkan_memory_types)}（${device.memoryTypes.size}）：", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            device.memoryTypes.forEachIndexed { index, mt ->
                                Text("• ${appString(R.string.vulkan_memory_type, index)}: ${appString(R.string.vulkan_heap, mt.heapIndex)}  [${mt.propertyFlags}]", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_queues") {
                    VulkanDetailCard(
                        title = "${appString(R.string.vulkan_queue_families)}（${device.queueFamilies.size}）",
                        content = {
                            device.queueFamilies.forEachIndexed { index, qf ->
                                Text("• ${appString(R.string.vulkan_queue_family, index)}: ${appString(R.string.vulkan_queues_count, qf.queueCount)}  [${qf.queueFlags}]", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    )
                }

                item(key = "vulkan_limits") {
                    VulkanDetailCard(
                        title = appString(R.string.vulkan_key_limits),
                        content = {
                            val keyLimits = listOf(
                                "maxImageDimension2D", "maxImageDimension3D", "maxImageDimensionCube",
                                "maxImageArrayLayers", "maxUniformBufferRange", "maxStorageBufferRange",
                                "maxMemoryAllocationCount", "maxBoundDescriptorSets",
                                "maxVertexInputAttributes", "maxFragmentOutputAttachments",
                                "maxComputeSharedMemorySize", "maxComputeWorkGroupInvocations",
                                "maxViewports", "maxColorAttachments", "maxSamplerAnisotropy",
                                "timestampPeriod", "maxClipDistances", "maxCullDistances"
                            )
                            keyLimits.forEach { key ->
                                device.limits[key]?.let { value ->
                                    InfoRow(key, value.toString())
                                }
                            }
                        }
                    )
                }
            }
        }

        // ── GPU 性能跑分：native Vulkan 带宽基准，对比不同驱动性能 ──
        item(key = "benchmark_header") {
            RegionHeader(
                title = appString(R.string.benchmark_title),
                icon = Icons.Default.Memory,
                remote = null
            )
        }

        item(key = "benchmark_card") {
            GpuBenchmarkCard(
                result = benchmarkResult,
                isLoading = benchmarkLoading,
                error = benchmarkError,
                onRun = {
                    benchmarkLoading = true
                    benchmarkError = null
                    benchmarkResult = null
                    // native 跑分在 IO 线程执行，避免阻塞 UI
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val result = NativeVulkanBridge.benchmarkSystemVulkan()
                            withContext(Dispatchers.Main) {
                                benchmarkResult = result
                                if (!result.success) {
                                    benchmarkError = result.errorMessage ?: result.errorCode
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                benchmarkError = e.message ?: "unknown error"
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                benchmarkLoading = false
                            }
                        }
                    }
                }
            )
        }

        // ── 已下载驱动检测：解压 .zip → dlopen .so → 采集 Vulkan 能力 → 与系统驱动对比 ──
        item(key = "driver_detect_header") {
            RegionHeader(
                title = appString(R.string.driver_detection_title),
                icon = Icons.Default.Memory,
                remote = null
            )
        }

        item(key = "driver_detect_list") {
            DriverDetectionList(
                systemDevice = vulkanPayload?.devices?.firstOrNull()
            )
        }
    }
}

/**
 * GPU 性能跑分卡片：触发 native Vulkan 带宽基准，展示总分 + fill/copy 带宽。
 *
 * 修改原因：用户要求实测跑分，对比不同驱动在当前设备上的性能差异。
 * 影响范围：设备 Tab 新增卡片，不影响其他页面。
 * 回滚方法：删除本函数及 DeviceScreen 中 benchmark 相关 item/状态即可。
 */
@Composable
private fun GpuBenchmarkCard(
    result: BenchmarkResult?,
    isLoading: Boolean,
    error: String?,
    onRun: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            // 说明文字
            Text(
                appString(R.string.benchmark_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            when {
                isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(appString(R.string.benchmark_running))
                    }
                }

                error != null -> {
                    Text(
                        appString(R.string.benchmark_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRun) {
                        Text(appString(R.string.benchmark_start))
                    }
                }

                result != null && result.success -> {
                    // 总分 + 设备名
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                appString(R.string.benchmark_total_score),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                result.totalScore.toString(),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                appString(R.string.benchmark_device),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                result.deviceName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // fill 带宽条
                    BandwidthRow(
                        label = appString(R.string.benchmark_fill_bandwidth),
                        valueGBs = result.fillBandwidthGBs
                    )
                    Spacer(Modifier.height(10.dp))

                    // copy 带宽条
                    BandwidthRow(
                        label = appString(R.string.benchmark_copy_bandwidth),
                        valueGBs = result.copyBandwidthGBs
                    )
                    Spacer(Modifier.height(10.dp))

                    // compute 吞吐条（计算着色器实测 GFLOPS）
                    ComputeRow(label = appString(R.string.benchmark_compute_throughput), gflops = result.computeGFLOPS)
                    if (result.computeError.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "计算基准受限：${result.computeError}（带宽结果仍有效）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Button(onClick = onRun) {
                        Text(appString(R.string.benchmark_start))
                    }
                }

                else -> {
                    // 初始状态：只有按钮
                    Button(onClick = onRun) {
                        Text(appString(R.string.benchmark_start))
                    }
                }
            }
        }
    }
}

/** 计算吞吐数值 + 进度条（满格 1500 GFLOPS，对应 Adreno 8 系 compute 上限）。 */
@Composable
private fun ComputeRow(label: String, gflops: Double) {
    val fraction = (gflops / 1500.0).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (gflops > 0) String.format("%.1f GFLOPS", gflops) else "不可用",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 带宽数值 + 进度条（满格 20 GB/s）。 */
@Composable
private fun BandwidthRow(label: String, valueGBs: Double) {
    val fraction = (valueGBs / 20.0).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format("%.2f GB/s", valueGBs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun DriverDetectionList(
    systemDevice: VulkanDeviceInfo?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extractFailedMsg = appString(R.string.extract_failed)
    var detectionResults by remember { mutableStateOf<Map<String, VulkanInfoPayload>>(emptyMap()) }
    var detectingId by remember { mutableStateOf<String?>(null)
    }
    // 驱动包 meta.json 解析结果（进页面自动解析，无需用户点击）
    var driverMetas by remember { mutableStateOf<Map<String, DriverMetaParser.DriverMeta?>>(emptyMap()) }
    // .so 是否导出 vkGetInstanceProcAddr（静态 ELF 检查，决定能否实测跑分）
    var exportFlags by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    // 实测跑分结果（fork 子进程加载驱动，崩溃隔离）
    var benchmarkResults by remember { mutableStateOf<Map<String, BenchmarkResult>>(emptyMap()) }
    var benchmarkingId by remember { mutableStateOf<String?>(null) }

    val downloadedDrivers = DownloadsManager.completedDownloads.filter { download ->
        val isDriverPackage = download.fileName.endsWith(".zip", ignoreCase = true) ||
                download.fileName.endsWith(".adpkg", ignoreCase = true)
        if (!isDriverPackage) return@filter false
        // 优先按下载目录分类（Drivers/），旧记录无 subPath 时回退关键词匹配
        if (download.subPath.isNotBlank()) {
            download.subPath.startsWith("Drivers", ignoreCase = true)
        } else {
            listOf(
                "turnip", "qualcomm", "adreno", "mali", "panvk", "driver", "vulkan", "mesa"
            ).any { download.fileName.contains(it, ignoreCase = true) }
        }
    }

    if (downloadedDrivers.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    appString(R.string.no_downloaded_drivers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    appString(R.string.no_downloaded_drivers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // 自动解析所有已下载驱动包的 meta.json（轻量，只读 zip 内小文件，不解压 .so）
    LaunchedEffect(downloadedDrivers.map { it.id }.toSet()) {
        val pending = downloadedDrivers.filter { !driverMetas.containsKey(it.id) }
        if (pending.isEmpty()) return@LaunchedEffect
        val parsed = withContext(Dispatchers.IO) {
            pending.associate { dl ->
                dl.id to runCatching { DriverMetaParser.parse(context, dl.filePath) }.getOrNull()
            }
        }
        driverMetas = driverMetas + parsed
    }

    // 解压驱动包拿 .so（本地路径或 content:// 均支持；已提取则直接复用）
    val extractSoFor: (String, String) -> File? = { id, filePath ->
        runCatching {
            val existing = DriverExtractor.getSoFile(context, id)
            if (existing != null && existing.exists()) existing
            else {
                val uri = if (filePath.startsWith("content://")) Uri.parse(filePath)
                          else Uri.fromFile(File(filePath))
                DriverExtractor.extract(context, uri, id)
            }
        }.getOrNull()
    }

    // 静态检查 .so 是否导出 vkGetInstanceProcAddr（懒触发，IO 线程，缓存结果）
    val checkExportable: (String, String) -> Unit = { id, filePath ->
        scope.launch(Dispatchers.IO) {
            val flag = DriverMetaParser.soExportsVkGetInstanceProcAddr(extractSoFor(id, filePath))
            exportFlags = exportFlags + (id to flag)
        }
    }

    // 实测跑分：确保 .so 已解压 → fork 子进程加载驱动跑分（崩溃只死子进程）
    val benchmarkDriver: (String, String) -> Unit = { id, filePath ->
        benchmarkingId = id
        scope.launch(Dispatchers.IO) {
            try {
                val soFile = extractSoFor(id, filePath)
                val flag = DriverMetaParser.soExportsVkGetInstanceProcAddr(soFile)
                exportFlags = exportFlags + (id to flag)
                if (flag != true || soFile == null) {
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                        success = false, errorCode = "NOT_EXPORTING",
                        errorMessage = "驱动 .so 未导出 ICD 入口（vk_icdGetInstanceProcAddr/vkGetInstanceProcAddr），无法加载",
                        deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                        computeGFLOPS = 0.0, computeError = "", totalScore = 0,
                        bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                    ))
                } else {
                    val raw = NativeVulkanBridge.benchmarkVulkan(soFile.absolutePath)
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult.fromJson(raw))
                }
            } catch (e: Exception) {
                benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                    success = false, errorCode = "BENCHMARK_EXCEPTION",
                    errorMessage = e.message ?: "unknown",
                    deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                    computeGFLOPS = 0.0, computeError = "", totalScore = 0,
                    bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                ))
            } finally {
                benchmarkingId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        downloadedDrivers.forEach { download ->
            val result = detectionResults[download.id]
            val isDetecting = detectingId == download.id
            val meta = driverMetas[download.id]

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                download.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                "${"%.1f".format(download.sizeBytes / 1024.0 / 1024.0)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDetecting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = {
                                    detectingId = download.id
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            // 只读 meta.json 判定打包格式，不再解压 .so / dlopen：
                                            // zip 分发的 Turnip/PanVK 均为 adrenotools 模拟器专用包，
                                            // App 进程加载必然失败，无意义的检测只会误导用户。
                                            val parsed = meta
                                                ?: runCatching { DriverMetaParser.parse(context, download.filePath) }.getOrNull()
                                            driverMetas = driverMetas + (download.id to parsed)
                                            val type = parsed?.driverType()
                                                ?: DriverMetaParser.DriverType.UNKNOWN
                                            val payload = when (type) {
                                                DriverMetaParser.DriverType.VENDOR_SYSTEM -> VulkanInfoPayload(
                                                    success = false,
                                                    errorCode = "VENDOR_SYSTEM_DRIVER",
                                                    errorMessage = "厂商系统提取驱动（需 root/系统级安装），无法在 App 内检测，请在模拟器内加载验证",
                                                    deviceCount = 0,
                                                    devices = emptyList()
                                                )
                                                DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> VulkanInfoPayload(
                                                    success = false,
                                                    errorCode = "ADRENOTOOLS_PACKAGE",
                                                    errorMessage = "adrenotools",
                                                    deviceCount = 0,
                                                    devices = emptyList()
                                                )
                                                else -> VulkanInfoPayload(
                                                    success = false,
                                                    errorCode = "UNKNOWN_PACKAGE",
                                                    errorMessage = "无法识别的驱动包格式，未发现有效的 meta.json",
                                                    deviceCount = 0,
                                                    devices = emptyList()
                                                )
                                            }
                                            detectionResults = detectionResults + (download.id to payload)
                                        } catch (e: Exception) {
                                            val errPayload = VulkanInfoPayload(
                                                success = false,
                                                errorCode = "DETECT_EXCEPTION",
                                                errorMessage = e.message ?: "unknown",
                                                deviceCount = 0,
                                                devices = emptyList()
                                            )
                                            detectionResults = detectionResults + (download.id to errPayload)
                                        } finally {
                                            detectingId = null
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (result != null) appString(R.string.redetect) else appString(R.string.view_driver_info),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // meta.json 驱动信息（自动解析后直接展示，这是该驱动包的真实身份信息）
                    if (meta != null && !meta.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Divider()
                        Spacer(Modifier.height(8.dp))
                        if (meta.name.isNotBlank())
                            InfoRow(appString(R.string.meta_name), meta.name)
                        if (meta.packageVersion.isNotBlank())
                            InfoRow(appString(R.string.meta_package_version), meta.packageVersion)
                        if (meta.driverVersion.isNotBlank())
                            InfoRow(appString(R.string.meta_driver_version), meta.driverVersion)
                        if (meta.vendor.isNotBlank())
                            InfoRow(appString(R.string.meta_vendor), meta.vendor)
                        if (meta.author.isNotBlank())
                            InfoRow(appString(R.string.meta_author), meta.author)
                        if (meta.minApi.isNotBlank())
                            InfoRow(appString(R.string.meta_min_api), meta.minApi)
                    }

                    // ── 实测：App 内加载驱动（fork 子进程隔离，崩溃只死子进程）──
                    // 驱动需导出 vk_icdGetInstanceProcAddr / vkGetInstanceProcAddr（ICD 入口）；
                    // 能否真正初始化 GPU 取决于设备 SELinux 是否允许 App 访问 GPU 节点。
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(6.dp))
                    when {
                        benchmarkingId == download.id -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    appString(R.string.benchmark_loading_driver),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        benchmarkResults[download.id] != null -> {
                            val br = benchmarkResults[download.id]!!
                            if (br.success) {
                                Text(
                                    "实测跑分（${br.deviceName}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "综合 ${br.totalScore}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(
                                        "带宽 ${"%.1f".format(br.fillBandwidthGBs)} GB/s",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    "实测失败：${br.errorCode ?: "?"} ${br.errorMessage ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { benchmarkDriver(download.id, download.filePath) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(appString(R.string.benchmark_rerun), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        exportFlags[download.id] == true -> {
                            Button(
                                onClick = { benchmarkDriver(download.id, download.filePath) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appString(R.string.benchmark_run_driver), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                appString(R.string.benchmark_isolated_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        exportFlags[download.id] == false -> {
                            Text(
                                appString(R.string.benchmark_not_exporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        else -> {
                            // 懒检查：进页面不解压大 .so，点按钮才检查 ICD 入口并实测
                            TextButton(
                                onClick = { checkExportable(download.id, download.filePath) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(appString(R.string.benchmark_check_export), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (result != null) {
                        Spacer(Modifier.height(10.dp))
                        if (result.errorCode == "ADRENOTOOLS_PACKAGE") {
                            // 模拟器专用格式：这是正常的格式说明，不是错误，用中性信息色
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        appString(R.string.adrenotools_format_title),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        appString(R.string.adrenotools_format_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        } else if (!result.success) {
                            val errMsg = when (result.errorCode) {
                                "EXTRACT_FAILED" -> appString(R.string.detection_error_extract)
                                "VENDOR_SYSTEM_DRIVER" -> (result.errorMessage
                                    ?: "厂商系统提取驱动，无法在 App 内检测，请在模拟器内加载验证")
                                "UNKNOWN_PACKAGE" -> (result.errorMessage
                                    ?: appString(R.string.unknown_package_hint))
                                "DL_OPEN_FAILED", "NO_VK_ENTRY", "CREATE_INSTANCE_FAILED",
                                "ENUM_DEVICES_MISSING", "DRIVER_CRASHED" -> appString(R.string.detection_error_adrenotools)
                                "DETECT_EXCEPTION" -> appString(R.string.detection_error_exception, (result.errorMessage ?: "unknown"))
                                else -> {
                                    val raw = result.errorMessage ?: ""
                                    if (raw.contains("not resolved", ignoreCase = true) || raw.contains("dlopen", ignoreCase = true))
                                        appString(R.string.detection_error_adrenotools)
                                    else
                                        appString(R.string.detection_failed, raw.ifEmpty { result.errorCode ?: "" })
                                }
                            }
                            Text(
                                errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (result.devices.isNotEmpty()) {
                            val d = result.devices[0]
                            Text(
                                d.deviceName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            InfoRow("Vulkan API", d.apiVersion)
                            InfoRow(appString(R.string.meta_driver_version), d.driverVersion)
                            if (d.driverName.isNotEmpty()) InfoRow(appString(R.string.vulkan_driver_name), d.driverName)
                            InfoRow(appString(R.string.vulkan_extensions), "${d.extensions.size}")
                            InfoRow(appString(R.string.vulkan_memory_types), "${d.memoryTypes.size}")
                            InfoRow(appString(R.string.vulkan_queue_families), "${d.queueFamilies.size}")

                            if (systemDevice != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    appString(R.string.compare_system),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                val extDiff = d.extensions.size - systemDevice.extensions.size
                                InfoRow(appString(R.string.api_version), "${d.apiVersion}（系统 ${systemDevice.apiVersion}）")
                                InfoRow(
                                    appString(R.string.vulkan_extensions),
                                    "${d.extensions.size}（系统 ${systemDevice.extensions.size}，${if (extDiff > 0) "+$extDiff" else if (extDiff < 0) extDiff.toString() else appString(R.string.same)}）"
                                )
                                val compatible = d.deviceName == systemDevice.deviceName
                                InfoRow(
                                    appString(R.string.gpu_compatibility),
                                    if (compatible) appString(R.string.gpu_compatible) else appString(R.string.gpu_not_compatible, d.deviceName)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

@Composable
private fun VulkanDetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content()
            }
        }
    }
}

/** 驱动专区顶部紧凑设备信息卡 */
@Composable
private fun DriverDeviceSummaryCard(deviceInfo: DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    deviceInfo.gpuRenderer.ifBlank { appString(R.string.unknown_gpu) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DriverInfoChip(label = appString(R.string.chip_arch), value = deviceInfo.adrenoSeries.ifBlank { "—" })
                    DriverInfoChip(label = appString(R.string.chip_ram), value = deviceInfo.ram)
                    DriverInfoChip(label = appString(R.string.chip_android), value = deviceInfo.androidVersion.takeWhile { it != ' ' })
                }
            }
        }
    }
}

@Composable
private fun DriverInfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}

/**
 * 驱动 Tab：仅展示驱动专区（Turnip + Qualcomm）。
 * 从 DriverHubScreen 拆分而来，设备摘要移至设备 Tab。
 */
@Composable
fun DriverScreen(
    modifier: Modifier = Modifier,
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    turnipSourceId: String,
    turnipSources: List<TurnipSource>,
    turnipReleases: List<GithubRelease>,
    qualcommSourceId: String,
    qualcommSources: List<QualcommSource>,
    qualcommReleases: List<GithubRelease>,
    sourceCatalogRemote: Boolean,
    onTurnipSourceChange: (String) -> Unit,
    onQualcommSourceChange: (String) -> Unit,
    onDownloadAsset: (GithubRelease, GithubAsset) -> Unit
) {
    val showQualcomm = qualcommReleases.isNotEmpty() &&
        (deviceInfo?.adrenoSeries == "6xx" || deviceInfo?.adrenoSeries == "7xx")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "driver_region_header") {
            RegionHeader(
                title = appString(R.string.driver_region),
                icon = Icons.Default.Memory,
                remote = sourceCatalogRemote
            )
        }

        if (deviceInfo != null) {
            item(key = "driver_device_summary") {
                DriverDeviceSummaryCard(deviceInfo = deviceInfo)
            }
        }

        if (isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) {
            item(key = "driver_loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(appString(R.string.fetching_latest))
                    }
                }
            }
        }

        // 源列表始终展示（releases 失败时 DriverReleasePicker 显示空态提示），
        // 避免整个驱动区块因 releases 拉取失败而消失（修复"驱动专区空白"）。
        if (turnipSources.isNotEmpty()) {
            item(key = "turnip_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = turnipSources,
                    currentSourceId = turnipSourceId,
                    onSourceChange = onTurnipSourceChange,
                    releases = turnipReleases,
                    selectionKey = "turnip:$turnipSourceId",
                    onDownload = onDownloadAsset
                )
            }
        }

        if (showQualcomm && qualcommSources.isNotEmpty()) {
            item(key = "qualcomm_section") {
                DriverCardDynamic(
                    title = appString(R.string.qualcomm_driver),
                    description = appString(R.string.qualcomm_driver_desc),
                    icon = Icons.Default.Memory,
                    sources = qualcommSources,
                    currentSourceId = qualcommSourceId,
                    onSourceChange = onQualcommSourceChange,
                    releases = qualcommReleases,
                    selectionKey = "qualcomm:$qualcommSourceId",
                    onDownload = onDownloadAsset
                )
            }
        }

        if (!isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) {
            item(key = "driver_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.no_drivers),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            appString(R.string.no_drivers_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 组件 Tab：仅展示组件专区（每个类型一个独立区块）。
 * 从 DriverHubScreen 拆分而来，内部管理组件源选择状态。
 */
@Composable
fun ComponentScreen(
    modifier: Modifier = Modifier,
    componentSources: List<ComponentSource>,
    componentCatalogs: Map<String, Map<String, List<Component>>>,
    componentSourceUpdatedAt: Map<String, Long> = emptyMap(),
    onDownloadComponent: (Component) -> Unit
) {
    val preferredComponentOrder = listOf("Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D", "D7VK")
    val discoveredComponentTypes = componentCatalogs.values
        .flatMap { it.keys }
        .distinct()
        .filterNot { it in preferredComponentOrder }
        .sorted()
    val componentOrder = preferredComponentOrder + discoveredComponentTypes
    val componentSourceSelections = remember { mutableStateMapOf<String, String>() }

    // 来源"综合最新最全"评分：类型覆盖数主导（×1000），版本总数次之。
    // 默认首推覆盖类型最全、版本最多的源；用户手动选择仍优先尊重。
    val sourceRichness = remember(componentSources, componentCatalogs) {
        componentSources.associate { s ->
            val catalog = componentCatalogs[s.id]
            val typeCount = catalog?.keys?.size ?: 0
            val versionCount = catalog?.values?.sumOf { it.size } ?: 0
            s.id to (typeCount * 1000 + versionCount)
        }
    }
    val sourcesByRichness = remember(componentSources, sourceRichness) {
        componentSources.sortedByDescending { sourceRichness[it.id] ?: 0 }
    }

    LaunchedEffect(componentSources, componentCatalogs) {
        componentOrder.forEach { type ->
            val saved = SettingsManager.getComponentSource(type)
            val candidates = sourcesByRichness.filter { source ->
                componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
            }
            val resolved = candidates.firstOrNull { it.id == saved } ?: candidates.firstOrNull()

            if (resolved != null) {
                componentSourceSelections[type] = resolved.id
                if (saved != resolved.id) SettingsManager.setComponentSource(type, resolved.id)
            }
        }
    }

    fun currentComponentSource(type: String): ComponentSource? {
        val selectedId = componentSourceSelections[type] ?: SettingsManager.getComponentSource(type)
        val candidates = sourcesByRichness.filter { source ->
            componentCatalogs[source.id]?.get(type).orEmpty().isNotEmpty()
        }
        return candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "component_region_header") {
            RegionHeader(
                title = appString(R.string.component_region),
                icon = Icons.Default.Extension,
                remote = null
            )
        }

        var hasAnyComponent = false
        componentOrder.forEach { type ->
            val source = currentComponentSource(type)
            val list = source?.let { componentCatalogs[it.id]?.get(type).orEmpty() }.orEmpty()
            if (list.isNotEmpty() && source != null) {
                hasAnyComponent = true
                item(key = "component:$type") {
                    ComponentSection(
                        type = type,
                        sources = sourcesByRichness.filter { s ->
                            componentCatalogs[s.id]?.get(type).orEmpty().isNotEmpty()
                        },
                        currentSource = source,
                        components = list,
                        selectionKey = "component:$type:${source.id}",
                        updatedAtMap = componentSourceUpdatedAt,
                        onSourceChange = { sourceId ->
                            componentSourceSelections[type] = sourceId
                            SettingsManager.setComponentSource(type, sourceId)
                        },
                        onDownload = onDownloadComponent
                    )
                }
            }
        }

        if (!hasAnyComponent) {
            item(key = "component_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            appString(R.string.no_components),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            appString(R.string.no_components_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "footer") {
            Text(
                text = appString(R.string.versions_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── 运行库 Tab：Windows 运行库在线下载（VC++/.NET/解码器/游戏依赖/字体）──

@Composable
fun RuntimeLibraryScreen(
    onDownload: (RuntimeComponent) -> Unit
) {
    var components by remember { mutableStateOf<List<RuntimeComponent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = appString(R.string.runtime_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = appString(R.string.runtime_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showGuide = true }) {
                Icon(Icons.Default.Info, contentDescription = appString(R.string.runtime_guide_title))
            }
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(appString(R.string.runtime_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (loadError) {
            RuntimeEmptyState(onRetry = {
                isLoading = true
                loadError = false
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (component.fileSize > 0) {
                    Text(
                        text = formatFileSize(component.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
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
                tint = MaterialTheme.colorScheme.outline
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

