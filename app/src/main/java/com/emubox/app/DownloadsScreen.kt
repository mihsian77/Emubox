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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emubox.app.ui.template.SpecItem
import com.emubox.app.ui.template.SpecStrip
import com.emubox.app.ui.theme.Fusion
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*


/** SAF 初始定位 URI：优先返回系统 Download 目录，构建失败回退 null（launcher 默认行为）。 */
private fun defaultDownloadTreeUri(context: Context): android.net.Uri? {
    @Suppress("DEPRECATION")
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    return runCatching {
        if (dir.exists() || dir.mkdirs()) android.net.Uri.fromFile(dir) else null
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, showBack: Boolean = true) {
    if (showBack) {
        BackHandler { onBack() }
    }

    val active = DownloadsManager.activeDownloads
    // 渲染层去重兜底：MediaStore content URI 与磁盘绝对路径可能形成两条同文件
    // 记录（id 不同但 fileName+subPath 相同），按文件名+子路径只显示一条
    val completed = DownloadsManager.completedDownloads
        .distinctBy { it.fileName.lowercase() to it.subPath }
    // 按项目（GitHub 仓库）归组：同一项目的多个版本、多个文件合成一张卡。
    // 归属从下载 URL 解析，1.4.0 之前的旧记录解析不出，退回下载目录分类。
    val downloadGroups = remember(completed) { groupCompletedDownloads(completed) }
    // 组展开状态：单文件组直接展开（多一次点击没意义），多文件组默认收起
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val completedBytes = completed.sumOf { it.sizeBytes }
    val openWithLabel = appString(R.string.open_with)
    val shareChooserLabel = appString(R.string.share_chooser)

    // 授权扫描：默认（未设自定义目录）时下载走 MediaStore 写入，但扫描同样依赖
    // MediaStore 索引，部分 ROM/场景下查询不到磁盘文件（实测"扫不出"）。SAF 授权
    // Download 目录后，下载与扫描都走 DocumentFile 直读文件系统，可靠命中所有文件。
    var folderAuthorized by remember { mutableStateOf(SettingsManager.getDownloadFolderUri() != null) }
    val scanFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                SettingsManager.setDownloadFolderUri(uri.toString())
                folderAuthorized = true
                scope.launch(Dispatchers.IO) {
                    DownloadsManager.scanExistingFiles(context)
                }
            }
        }
    )

    // 进入下载 Tab 即触发一次扫描：覆盖安装新版后，之前下载未删除的本地文件
    // 默认仅 App 启动扫一次，MediaStore 索引延迟/权限会导致漏扫；此处打开即
    // 主动重扫，保证旧文件在下载库可见可删，避免重复下载生成 (1)(2) 后缀。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DownloadsManager.scanExistingFiles(context)
        }
        // 自动授权已由 MainActivity 启动时统一处理（Android 11+ 文件权限 / 低版本运行时权限），
        // 此处不再重复弹 SAF；未授权时保留手动按钮（授权后下载/扫描走 DocumentFile 直读）
    }

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
                },
                actions = {
                    // 手动重新扫描本地下载目录（默认仅 App 启动时扫一次，
                    // 下载完成/外部放入的新文件需手动触发才能在下载库显示）。
                    IconButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                DownloadsManager.scanExistingFiles(context)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = appString(R.string.rescan_local))
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
            // 统计收成一行三段：三个独立格子等于用 3 倍面积说 3 个数
            item(key = "download_summary") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecStrip(
                        listOf(
                            SpecItem(appString(R.string.active), active.size.toString()),
                            SpecItem(appString(R.string.saved), completed.size.toString()),
                            SpecItem(appString(R.string.stored), formatBytes(completedBytes)),
                        )
                    )
                    // 本地扫描诊断：显示最近一次扫描查到了什么，便于定位「扫不出」根因。
                    // 「扫描未完成/目录不可读」意味着文件列表可能缺块，之前用 11sp 浅灰脚注，
                    // 实测整行几乎看不见（截图里直接被当装饰忽略），降级状态改为可见告警行。
                    val scanNote = DownloadsManager.lastScanNote
                    if (scanNote.isNotBlank()) {
                        val degraded = scanNote.contains("未完成") || scanNote.contains("不可读")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (degraded) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                scanNote,
                                fontSize = if (degraded) 12.sp else 11.sp,
                                color = if (degraded) MaterialTheme.colorScheme.error
                                else Fusion.OnSurfaceFaint,
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

                active.forEach { (key, download) ->
                    item(key = "active_$key") {
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
                                            text = download.fileName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 3
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

                                if (!download.lastError.isNullOrBlank()) {
                                    Text(
                                        "失败原因: ${download.lastError}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
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
                                                resumeActiveDownload(context, key)
                                            } else {
                                                pauseActiveDownload(key)
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
                                        onClick = { cancelActiveDownload(context, key) },
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

                downloadGroups.forEach { group ->
                    val groupOpen = expandedGroups[group.key] ?: (group.files.size <= 1)
                    item(key = "download_group_${group.key}") {
                        DownloadProjectHeader(
                            group = group,
                            expanded = groupOpen,
                            onToggle = { expandedGroups[group.key] = !groupOpen }
                        )
                    }
                    if (groupOpen) {
                        group.files.forEach { file ->
                            item(key = "completed_${file.id}") {
                                var showDeleteDialog by remember { mutableStateOf(false) }
                                var showMetaDialog by remember { mutableStateOf(false) }
                                var metaInfo by remember { mutableStateOf<DriverMetaParser.DriverMeta?>(null) }
                                var metaLoading by remember { mutableStateOf(false) }
                                var archiveScan by remember { mutableStateOf<ArchiveScan?>(null) }
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
                                                LongFieldText(
                                                    file.fileName,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    maxLines = 3
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
                                                    val uri = exportFileUri(context, file.filePath)
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        // 按扩展名推断 MIME：像系统文件管理器一样，
                                                        // 让"打开方式"自动展开可打开该类型的所有应用
                                                        setDataAndType(uri, mimeTypeForFileName(file.fileName))
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    try {
                                                        context.startActivity(Intent.createChooser(intent, openWithLabel))
                                                    } catch (e: Exception) {
                                                        // 无可用打开程序（zip/so 等常见于文件管理器）：降级用
                                                        // octet-stream 再试一次（部分文件管理器按通用类型兜底展示）
                                                        try {
                                                            val fallback = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, "application/octet-stream")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            context.startActivity(Intent.createChooser(fallback, openWithLabel))
                                                        } catch (e2: Exception) {
                                                            Toast.makeText(
                                                                context,
                                                                appStringFor(context, SettingsManager.getAppLanguage(), R.string.no_app_to_open),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
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
                                                    val uri = exportFileUri(context, file.filePath)
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/octet-stream"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    // 包一层：没有能接收分享的 App 时给提示，不再直接崩
                                                    runCatching {
                                                        context.startActivity(Intent.createChooser(shareIntent, shareChooserLabel))
                                                    }.onFailure {
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
                                                        // 非驱动压缩包：轻量扫描内部文件，推断组件类型
                                                        archiveScan = if (!isDriverPkg && (file.fileName.endsWith(".zip", true) || file.fileName.endsWith(".adpkg", true))) {
                                                            scanArchiveEntries(file.filePath)
                                                        } else {
                                                            null
                                                        }
                                                    }.onFailure { e ->
                                                        android.util.Log.w("EmuBox", "parse file info failed", e)
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
                                                        // 无论删除成败都移除记录：避免 file:// 死文件记录
                                                        // 卡住下载库导致"无法重新下载"（文件在权限恢复后可再删）
                                                        DownloadsManager.removeCompleted(file.id)
                                                        if (!deleted) {
                                                            Toast.makeText(
                                                                context,
                                                                "文件已删除失败（权限不足），但已从列表移除，可重新下载",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
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
                                            val mi = metaInfo
                                            if (mi == null || mi.isEmpty()) {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (mi == null) {
                                                        // 非驱动文件：基础信息 + 用途说明（按文件名/扩展名推断）
                                                        FileInfoRow(appString(R.string.file_name), file.fileName)
                                                        FileInfoRow(
                                                            appString(R.string.file_type),
                                                            file.fileName.substringAfterLast('.', "").uppercase().ifEmpty { "—" }
                                                        )
                                                        FileInfoRow(appString(R.string.file_purpose), filePurposeHint(file.fileName))
                                                        val scan = archiveScan
                                                        if (scan != null && scan.keyFiles.isNotEmpty()) {
                                                            FileInfoRow(appString(R.string.archive_kind), scan.kind)
                                                            Text(
                                                                appString(R.string.archive_contains, scan.totalEntries),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Spacer(Modifier.height(4.dp))
                                                            scan.keyFiles.forEach { so ->
                                                                Text(
                                                                    "• $so",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                        FileInfoRow(appString(R.string.file_size), formatBytes(file.sizeBytes))
                                                        FileInfoRow(appString(R.string.file_path), displayPath)
                                                        FileInfoRow(appString(R.string.file_time), formatDate(file.timestamp))
                                                    } else {
                                                        Text(appString(R.string.meta_not_found))
                                                    }
                                                }
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    MetaRow(appString(R.string.meta_name), mi.name)
                                                    MetaRow(appString(R.string.meta_driver_version), mi.driverVersion)
                                                    MetaRow(appString(R.string.meta_vendor), mi.vendor)
                                                    MetaRow(appString(R.string.meta_author), mi.author)
                                                    MetaRow(appString(R.string.meta_package_version), mi.packageVersion)
                                                    MetaRow(appString(R.string.meta_min_api), mi.minApi)
                                                    MetaRow(appString(R.string.meta_library), mi.libraryName)
                                                    if (mi.description.isNotBlank()) {
                                                        Spacer(Modifier.height(4.dp))
                                                        Text(
                                                            mi.description,
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

/** 下载库的项目分组：同一 GitHub 仓库（或同一目录分类）下的文件归为一组 */
private data class DownloadGroup(
    val key: String,
    val label: String,
    val versions: List<String>,
    val files: List<DownloadsManager.CompletedDownload>
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
    val latestAt: Long get() = files.maxOfOrNull { it.timestamp } ?: 0L
}

/**
 * 按项目归组，组间按最近下载时间倒序、组内按时间倒序。
 *
 * 不按文件名猜项目：归属要么来自下载 URL（准确），要么明确退回
 * 「目录分类 / 扩展名」这种能一眼看懂的弱分组，不做模糊匹配。
 */
private fun groupCompletedDownloads(
    files: List<DownloadsManager.CompletedDownload>
): List<DownloadGroup> = files
    .groupBy { it.projectKey }
    .map { (key, list) ->
        val sorted = list.sortedByDescending { it.timestamp }
        DownloadGroup(
            key = key,
            label = sorted.first().projectLabel,
            versions = sorted.map { it.versionLabel }.filter { it.isNotBlank() }.distinct(),
            files = sorted
        )
    }
    .sortedByDescending { it.latestAt }

@Composable
private fun DownloadProjectHeader(
    group: DownloadGroup,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val meta = buildString {
        append(appString(R.string.download_group_files, group.files.size))
        append(" · ")
        append(formatBytes(group.sizeBytes))
        if (group.versions.size > 1) {
            append(" · ")
            append(appString(R.string.download_group_versions, group.versions.size))
        }
        append(" · ")
        // 相对时间而非完整日期：组头一行要同时放文件数/体积/版本，绝对日期会被截断
        append(formatRelativeTimeMs(group.latestAt))
    }
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 版本号放标题行右侧：分组后用户最想知道「这组是不是我要的版本」
                    group.versions.firstOrNull()?.let { v ->
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = v,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = Fusion.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
internal fun filePurposeHint(fileName: String): String {
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

/**
 * 把下载记录里的文件路径转成可以安全交给其他 App 的 Uri。
 *
 * - content:// ：原样返回（MediaStore / SAF 授权目录记录的就是这种）；
 * - 绝对路径  ：走 FileProvider 转成 content://。Android 7.0（API 24）起，
 *   把 file:// 的 Uri 交给其他 App 会抛 FileUriExposedException，必须先转；
 * - 只有路径不在 FileProvider 白名单内（极端情况）才退回 file://，
 *   调用方需自行 try/catch。
 */
private fun exportFileUri(context: android.content.Context, filePath: String): Uri {
    if (filePath.startsWith("content://")) return Uri.parse(filePath)
    val file = File(filePath)
    return runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }.getOrElse { Uri.fromFile(file) }
}

/** 压缩包扫描结果：条目总数 + 关键 .so / 可执行文件 + 组件类型推断 */
internal data class ArchiveScan(
    val totalEntries: Int,
    val keyFiles: List<String>,
    val kind: String
)

/** 轻量扫描 zip/adpkg：只读条目名（不解压 .so），推断组件/驱动类型 */
private fun scanArchiveEntries(path: String): ArchiveScan? {
    return runCatching {
        java.util.zip.ZipFile(path).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.isEmpty()) return@use null
            val libs = names.filter { it.contains(".so", ignoreCase = true) }
            val keyFiles = libs.filterNot {
                it.endsWith("/") || it.contains("meta.json")
            }.map { it.substringAfterLast('/') }
                .filter { it.startsWith("lib") || it.endsWith(".so") }
                .distinct()
            val kind = when {
                names.any { it.contains("meta.json", ignoreCase = true) } &&
                    keyFiles.any { it.contains("vulkan", ignoreCase = true) } -> "驱动包 (Vulkan ICD)"
                keyFiles.any { it.contains("turnip", ignoreCase = true) } -> "Turnip 驱动 (Mesa Adreno)"
                keyFiles.any { it.contains("panvk", ignoreCase = true) } -> "PanVK 驱动 (Mesa Mali)"
                keyFiles.any { it.contains("dxvk", ignoreCase = true) } -> "DXVK 组件 (DX9-11→Vulkan)"
                keyFiles.any { it.contains("vkd3d", ignoreCase = true) } -> "VKD3D 组件 (DX12→Vulkan)"
                keyFiles.any { it.contains("box64", ignoreCase = true) } -> "Box64 组件 (x86_64 转译)"
                keyFiles.any { it.contains("fex", ignoreCase = true) } -> "FEXCore 组件 (x86 模拟)"
                keyFiles.any { it.contains("glibc", ignoreCase = true) } -> "glibc 运行库组件"
                keyFiles.any { it.contains("wine", ignoreCase = true) } -> "Wine 兼容层组件"
                names.any { it.endsWith(".ttf") || it.endsWith(".otf") } -> "字体包 (CJK 等)"
                else -> "组件/资源压缩包"
            }
            ArchiveScan(totalEntries = names.size, keyFiles = keyFiles.take(10), kind = kind)
        }
    }.getOrNull()
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
                // file:// 前缀剥离：Uri.fromFile 记录存的带前缀，File() 需要裸路径
                val rawPath = file.filePath.removePrefix("file://")
                val f = File(rawPath)
                val direct = if (f.exists()) f.delete() else false
                if (direct) {
                    true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // File 直删失败（无文件权限/路径失效）→ fallback 按文件名+子目录
                    // 查 MediaStore 删除（Downloads 集合 Android 10+ 才有），保证删除真正落盘
                    runCatching {
                        val sub = file.subPath
                        val rel = if (sub.isBlank()) "%Download/EmuBox%" else "%Download/EmuBox/$sub%"
                        var uri: Uri? = null
                        context.contentResolver.query(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(android.provider.MediaStore.MediaColumns._ID),
                            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                            arrayOf(file.fileName, rel),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                uri = Uri.withAppendedPath(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    c.getString(0)
                                )
                            }
                        }
                        uri != null && context.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, appStringFor(context, SettingsManager.getAppLanguage(), R.string.error_deleting, e.message ?: "Unknown"), Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}
