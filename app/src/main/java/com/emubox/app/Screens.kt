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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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
                    // 本地扫描诊断：显示最近一次扫描查到了什么，便于定位「扫不出」根因
                    val scanNote = DownloadsManager.lastScanNote
                    if (scanNote.isNotBlank()) {
                        Text(
                            scanNote,
                            fontSize = 11.sp,
                            color = Fusion.OnSurfaceFaint,
                        )
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
private data class ArchiveScan(
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
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** 频率格式化：4.35GHz → "4.35 GHz"（去多余小数） */
private fun formatFreq(ghz: Double): String {
    val s = if (ghz % 1.0 == 0.0) ghz.toInt().toString() else String.format("%.2f", ghz)
        .trimEnd('0').trimEnd('.')
    return "$s GHz"
}

private fun formatBytes(bytes: Long): String {    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/** 从 GitHub apiUrl/manifestUrl 提取 owner 并返回作者头像 URL（驱动/组件源卡用）。
 *  直接使用头像 CDN 直链：github.com/{owner}.png 会 302 跳转到
 *  avatars.githubusercontent.com，国内网络对跳转链加载不稳且易受限流。 */
private fun githubOwnerAvatar(url: String): String? {
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
private fun RepoAvatar(
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
private fun ProjectAvatar(
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

/**
 * ISO 时间戳截到日期："2026-08-19T11:06:00Z" → "2026-08-19"。
 * 解析失败时取前 10 位兜底，不抛出。
 */
private fun shortDate(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(instant.toEpochMilli()))
    }.getOrElse { iso.take(10) }
}

/** 基于 epoch 毫秒的相对时间（组件源仓库更新时间用） */
private fun formatRelativeTimeMs(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    return when {
        diffMs < 0 -> "刚刚"
        diffMs < 60_000 -> "刚刚"
        diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
        // 48 小时内精确到小时：25h 显示"25 小时前"而非"1 天前"，驱动/组件源统一
        diffMs < 48L * 3_600_000 -> "${diffMs / 3_600_000} 小时前"
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
    uiScale: UiScale,
    onUiScaleChange: (UiScale) -> Unit,
    onSourceCatalogChanged: () -> Unit,
    onOpenDownloads: (() -> Unit)? = null,
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
    var translateEngine by remember { mutableStateOf(SettingsManager.getTranslateEngine()) }
    var translateEmail by remember { mutableStateOf(SettingsManager.getTranslateEmail()) }
    var deepSeekKey by remember { mutableStateOf(SettingsManager.getDeepSeekKey()) }

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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 「我的」入口：下载页不再占一级导航，改从这里进入
            if (onOpenDownloads != null) {
                item {
                    SettingGroup {
                        SettingRow(
                            title = appString(R.string.tab_download),
                            subtitle = appString(R.string.downloads_entry_desc),
                            showDivider = false,
                            onClick = onOpenDownloads,
                            trailing = {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Fusion.OnSurfaceFaint
                                )
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.appearance),
                    subtitle = appString(R.string.appearance_desc)
                )
            }
            item {
                // 外观四项合为一组：一组一张卡，项与项之间只用分隔线分层
                SettingGroup {
                    SettingChoiceRow(
                        title = appString(R.string.app_language),
                        subtitle = appString(R.string.language_desc),
                    ) {
                        LanguageSelector(selected = appLanguage, onSelected = onAppLanguageChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.theme_mode),
                        subtitle = appString(R.string.theme_mode_desc),
                    ) {
                        ThemeModeSelector(selected = themeMode, onSelected = onThemeModeChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.ui_scale_title),
                        subtitle = appString(R.string.ui_scale_desc),
                    ) {
                        UiScaleSelector(selected = uiScale, onSelected = onUiScaleChange)
                    }
                    SettingChoiceRow(
                        title = appString(R.string.color_theme),
                        subtitle = appString(R.string.color_theme_desc),
                        showDivider = false,
                    ) {
                        ColorThemeSelector(selected = colorTheme, onSelected = onColorThemeChange)
                    }
                }
            }

            item {
                SettingsSectionHeader(
                    title = appString(R.string.translate_section),
                    subtitle = appString(R.string.translate_section_desc)
                )
            }

            item {
                SettingsCard(title = appString(R.string.translate_engine)) {
                    val isMyMemory = translateEngine != "deepseek"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isMyMemory,
                            onClick = {
                                translateEngine = "mymemory"
                                SettingsManager.setTranslateEngine("mymemory")
                            },
                            label = { Text(appString(R.string.translate_engine_mymemory)) }
                        )
                        FilterChip(
                            selected = !isMyMemory,
                            onClick = {
                                translateEngine = "deepseek"
                                SettingsManager.setTranslateEngine("deepseek")
                            },
                            label = { Text(appString(R.string.translate_engine_deepseek)) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = appString(
                            if (isMyMemory) R.string.translate_engine_mymemory_note
                            else R.string.translate_engine_deepseek_note
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (isMyMemory) {
                        OutlinedTextField(
                            value = translateEmail,
                            onValueChange = {
                                translateEmail = it
                                SettingsManager.setTranslateEmail(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(appString(R.string.translate_email)) },
                            placeholder = { Text(appString(R.string.translate_email_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = appString(R.string.translate_email_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedTextField(
                            value = deepSeekKey,
                            onValueChange = {
                                deepSeekKey = it
                                SettingsManager.setDeepSeekKey(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(appString(R.string.translate_deepseek_key)) },
                            placeholder = { Text(appString(R.string.translate_deepseek_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = appString(R.string.translate_deepseek_key_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(
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
                SettingGroup {
                    SettingRow(
                        title = appString(R.string.match_drivers_by_device),
                        subtitle = appString(R.string.match_drivers_by_device_desc),
                        showDivider = false,
                        trailing = {
                            Switch(
                                checked = matchDriversByDevice,
                                onCheckedChange = { enabled ->
                                    matchDriversByDevice = enabled
                                    SettingsManager.setMatchDriversByDevice(enabled)
                                    onSourceCatalogChanged()
                                }
                            )
                        }
                    )
                }
            }

            item {
                SettingsSectionHeader(
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
                    title = appString(R.string.accelerator_title),
                    subtitle = appString(R.string.accelerator_desc)
                )
            }

            item {
                AcceleratorSettingsCard()
            }

            item {
                GithubTokenCard()
            }

            item {
                SettingsSectionHeader(
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

/**
 * 设置页分组标题。
 *
 * 旧实现给每组配一个 60dp 高的图标盒子，等于用整行高度换零信息量；
 * 现在按排版规格 C 收敛成「小字 + 弱色」的一行标题。
 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String
) {
    SectionHeader(title = title, subtitle = subtitle)
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingGroup(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                color = Fusion.OnSurface,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ── GitHub API Token（可选）──────────────────────────────────────

/**
 * 未认证的 GitHub API 限额 60 次/小时/IP。冷启动会并发拉 5 个驱动源，
 * 几次强刷就会 403，表现为「驱动列表突然空了」。填 Token 后 5000 次/小时。
 * Token 用 Keystore 加密保存，且只发给 api.github.com（经代理时自动剥离）。
 */
@Composable
private fun GithubTokenCard() {
    var input by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(SettingsManager.hasGithubToken()) }
    var justSaved by remember { mutableStateOf(false) }

    SettingsCard(title = appString(R.string.settings_github_token_title)) {
        Text(
            appString(R.string.settings_github_token_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; justSaved = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(appString(R.string.settings_github_token_hint)) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    SettingsManager.setGithubToken(input)
                    configured = SettingsManager.hasGithubToken()
                    justSaved = configured
                    input = ""
                },
                enabled = input.isNotBlank()
            ) { Text(appString(R.string.save)) }

            if (configured) {
                TextButton(
                    onClick = {
                        SettingsManager.setGithubToken("")
                        configured = false
                        justSaved = false
                    }
                ) { Text(appString(R.string.delete)) }
            }

            if (justSaved) {
                Spacer(Modifier.height(0.dp))
                Text(
                    appString(R.string.settings_github_token_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
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
            Spacer(Modifier.height(6.dp))
            Column {
                listOf(
                    appString(R.string.about_project) to "https://github.com/mihsian77/Emubox",
                    appString(R.string.about_feedback) to "https://github.com/mihsian77/Emubox/issues",
                    appString(R.string.about_support) to "https://afdian.com/a/moon279"
                ).forEach { (label, url) ->
                    TextButton(
                        onClick = { openNewsLink(context, url) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = appString(R.string.app_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
private fun UiScaleSelector(
    selected: UiScale,
    onSelected: (UiScale) -> Unit
) {
    val choices = UiScale.entries.map { it to appString(it.labelRes) }
    // 四个选项标签都很短，一行放完；分两行等于白占半行高度
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { (scale, label) ->
            FilterChip(
                selected = selected == scale,
                onClick = { onSelected(scale) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }
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
        ColorTheme.EMUHUB to "EmuBox",
        ColorTheme.BLUE to appString(R.string.theme_blue),
        ColorTheme.PURPLE to appString(R.string.theme_purple),
        ColorTheme.ORANGE to appString(R.string.theme_orange),
        ColorTheme.CN to appString(R.string.theme_cn)
    )

    // 六个主题横排一屏放不下，横滑等于把后两个藏起来（用户不知道能滑）。
    // 改成 3×2 换行，一眼看全，不用猜。
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (theme, title) ->
                    FilterChip(
                        selected = selected == theme,
                        onClick = { onSelected(theme) },
                        label = { Text(title, maxLines = 1) },
                        leadingIcon = if (selected == theme) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
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
    val tipRes: List<Int>,
    val stepsRes: List<Int> = emptyList()
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
        ),
        stepsRes = listOf(
            R.string.guide_step_turnip_1,
            R.string.guide_step_turnip_2,
            R.string.guide_step_turnip_3,
            R.string.guide_step_turnip_4
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
        ),
        stepsRes = listOf(
            R.string.guide_step_qualcomm_1,
            R.string.guide_step_qualcomm_2,
            R.string.guide_step_qualcomm_3,
            R.string.guide_step_qualcomm_4
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
        ),
        stepsRes = listOf(
            R.string.guide_step_wine_1,
            R.string.guide_step_wine_2,
            R.string.guide_step_wine_3
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
        ),
        stepsRes = listOf(
            R.string.guide_step_proton_1,
            R.string.guide_step_proton_2,
            R.string.guide_step_proton_3
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
    initialTopicId: String? = null,
    driverMode: Boolean = false,
    deviceInfo: DeviceInfo? = null
) {
    BackHandler { onBack() }

    val listState = rememberLazyListState()
    var expandedId by rememberSaveable { mutableStateOf(initialTopicId) }

    // 驱动指南只保留驱动相关主题（Turnip/AdrenoTools/PanVK/厂商驱动），
    // 组件指南只保留 Wine/Proton/DXVK 等运行环境与图形组件主题。
    val driverTopicIds = setOf("turnip", "qualcomm", "adrenotools", "panvk")
    val visibleTopics = componentGuideTopics.filter {
        if (driverMode) it.id in driverTopicIds else it.id !in driverTopicIds
    }
    // 按当前设备 GPU 重排主题：Adreno 用户 Turnip/高通驱动优先，Mali 用户 PanVK 优先
    val isAdrenoGuide = deviceInfo?.gpuVendor == GpuVendor.ADRENO
    val isMaliGuide = deviceInfo?.gpuVendor == GpuVendor.MALI
    val sortedTopics = remember(visibleTopics, isAdrenoGuide, isMaliGuide) {
        if (driverMode) {
            val order = when {
                isAdrenoGuide -> listOf("turnip", "qualcomm", "adrenotools", "panvk")
                isMaliGuide -> listOf("panvk", "adrenotools", "turnip", "qualcomm")
                else -> listOf("turnip", "qualcomm", "adrenotools", "panvk")
            }
            visibleTopics.sortedBy { t ->
                val i = order.indexOf(t.id)
                if (i < 0) Int.MAX_VALUE else i
            }
        } else visibleTopics
    }

    LaunchedEffect(initialTopicId) {
        if (initialTopicId != null) {
            val index = visibleTopics.indexOfFirst { it.id == initialTopicId }
            if (index >= 0) {
                expandedId = initialTopicId
                // 主题卡前面只剩「说明行」一个 item，偏移量跟着信息架构走，别再写死 +3
                listState.animateScrollToItem(index + 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (driverMode) appString(R.string.driver_guide) else appString(R.string.component_guide))
                        Text(
                            if (driverMode) appString(R.string.driver_guide_subtitle) else appString(R.string.what_each_download_does),
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
            // 顶部只留一句话说明：标题和副标题已在 TopAppBar，再来一张大卡是重复
            item(key = "guide_intro") {
                Text(
                    appString(
                        if (driverMode) R.string.guide_intro_desc_driver
                        else R.string.guide_intro_desc
                    ),
                    fontSize = 12.sp,
                    color = Fusion.OnSurfaceDim,
                )
            }

            sortedTopics.forEach { topic ->
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

            item(key = "guide_quick_pick") {
                QuickPickGuideCard(adrenoSeries, driverMode, deviceInfo)
            }

            // 配置推荐：整合两个 Winlator 社区配置仓库（bannerlator-game-configs +
            // Winlator-Mali-Community-Configs），按 GPU 型号的热度排行给出最常用组件组合
            item(key = "guide_config_recs") {
                WinlatorConfigRecsCard(deviceInfo = deviceInfo)
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

        }
    }
}

// ── 配置推荐（Winlator 社区热度聚合）─────────────────────────────
data class ComboRec(val combo: List<Pair<String, String>>, val count: Int, val share: Double)

/** 远程聚合配置源：CI 每日聚合两社区仓库后提交，App 远程拉取优先、内置资产兜底 */
private const val RECIPES_REMOTE_URL =
    "https://raw.githubusercontent.com/mihsian77/Emubox/main/app/src/main/assets/winlator-recipes.json"

fun loadWinlatorRecipes(context: Context): Pair<Map<String, List<ComboRec>>, Map<String, List<ComboRec>>> {
    return try {
        // 远程优先：拿到 CI 最新聚合结果；失败回退内置资产（离线可用）
        val remoteText = runCatching { RemoteData.fetchText(RECIPES_REMOTE_URL) }.getOrNull()
        val text = if (!remoteText.isNullOrBlank()) remoteText
            else context.assets.open("winlator-recipes.json").bufferedReader().use { it.readText() }
        val j = org.json.JSONObject(text)
        fun parse(obj: org.json.JSONObject): Map<String, List<ComboRec>> {
            val out = mutableMapOf<String, List<ComboRec>>()
            val names = obj.names() ?: return out
            for (i in 0 until names.length()) {
                val gpu = names.getString(i)
                val top = obj.getJSONObject(gpu).getJSONArray("top")
                val list = mutableListOf<ComboRec>()
                for (k in 0 until top.length()) {
                    val t = top.getJSONObject(k)
                    val comboArr = t.getJSONArray("combo")
                    val combo = mutableListOf<Pair<String, String>>()
                    for (m in 0 until comboArr.length()) {
                        val item = comboArr.getJSONArray(m)
                        combo.add(item.getString(0) to item.getString(1))
                    }
                    list.add(ComboRec(combo, t.getInt("count"), t.optDouble("share", 0.0)))
                }
                out[gpu] = list
            }
            return out
        }
        parse(j.getJSONObject("gpu_combos")) to parse(j.getJSONObject("mali_combos"))
    } catch (e: Exception) {
        emptyMap<String, List<ComboRec>>() to emptyMap<String, List<ComboRec>>()
    }
}

private fun comboText(combo: List<Pair<String, String>>): String =
    combo.joinToString(" · ") { (k, v) -> "$k $v" }

@Composable
private fun WinlatorConfigRecsCard(deviceInfo: DeviceInfo?) {
    val context = LocalContext.current
    // 不能在 composition 里同步取数：原来 remember 直接调 loadWinlatorRecipes，
    // 打开指南页会卡住主线程直到网络超时。改成进页面后异步拉，拿到再显示。
    var recipes by remember {
        mutableStateOf<Pair<Map<String, List<ComboRec>>, Map<String, List<ComboRec>>>?>(null)
    }
    LaunchedEffect(Unit) {
        recipes = withContext(Dispatchers.IO) { loadWinlatorRecipes(context) }
    }
    val (adrenoMap, maliMap) = recipes ?: return
    val gpuModel = deviceInfo?.gpuModel.orEmpty()
    val isMali = deviceInfo?.gpuVendor == GpuVendor.MALI
    // 设备 gpuModel 为纯数字（如 "740"），recipes 键为 "Adreno-740"/"Mali-G57"，需归一化；
    // 精确未命中再做 contains 兜底（应对键带后缀）。此前直接用纯数字取键恒 miss、永远走最热 fallback。
    val targetMap = if (isMali) maliMap else adrenoMap
    val gpuKey = if (gpuModel.isBlank() || gpuModel == "unknown") ""
        else if (isMali) "Mali-G$gpuModel" else "Adreno-$gpuModel"
    val matched = when {
        gpuKey.isBlank() -> null
        targetMap[gpuKey] != null -> targetMap[gpuKey]
        else -> targetMap.entries.firstOrNull { it.key.contains(gpuKey, ignoreCase = true) }?.value
    }
    val fallback = if (isMali) maliMap.values.maxByOrNull { it.sumOf { c -> c.count } } ?: emptyList()
        else adrenoMap.values.maxByOrNull { it.sumOf { c -> c.count } } ?: emptyList()
    val recs = matched ?: fallback
    val matchIsExact = matched != null && gpuKey.isNotBlank()
    if (recs.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.winlator_config_recs_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                appString(R.string.winlator_config_recs_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val gpuLabel = when {
                gpuModel.isBlank() || gpuModel == "unknown" -> if (isMali) "Mali" else "Adreno"
                isMali -> "Mali-G$gpuModel"
                else -> "Adreno $gpuModel"
            }
            Text(
                if (matchIsExact) "当前 GPU：$gpuLabel · 本机型号配置"
                else "当前 GPU：$gpuLabel · 暂无该型号数据，显示社区最热",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(8.dp))
            recs.take(3).forEachIndexed { idx, rec ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (idx == 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TOP${idx + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                comboText(rec.combo),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "社区配置 ${rec.count} 次 · 占比 ${rec.share}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (matched == null) "当前 GPU 无独立统计，显示全库最热组合" else "数据来源：bannerlator-game-configs + Winlator-Mali-Community-Configs（自动聚合）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 官方/社区入门配置层：按 GPU 厂商给出原则性起点（渲染器/Wrapper/分辨率等）
            val official = remember(gpuModel, isMali) { ConfigRecommendStore.find(gpuModel, context)?.official.orEmpty() }
            if (official.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                Text(
                    appString(R.string.winlator_official_recs_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                official.forEach { rec ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(rec.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (rec.summary.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(rec.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            rec.settings.forEach { (k, v) ->
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        k,
                                        modifier = Modifier.width(88.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(v, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
private fun QuickPickGuideCard(adrenoSeries: String?, driverMode: Boolean, deviceInfo: DeviceInfo? = null) {
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
                        if (driverMode) appString(R.string.quick_pick_driver_subtitle)
                        else if (adrenoSeries != null) appString(R.string.starting_points_adreno, adrenoSeries)
                        else appString(R.string.good_starting_points),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 智能设备识别：当前设备型号 + SoC 品牌徽章（文字+品牌色，避免官方 logo 版权）
            if (deviceInfo != null) {
                val socName = deviceInfo.socName
                val brand = if (socName.isNotBlank()) detectSocBrand(socName)
                    else detectGpuSocBrand(deviceInfo.gpuModel, deviceInfo.gpuVendor)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SocBrandBadge(brand = brand, text = brand.name)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOf(deviceInfo.deviceBrand, deviceInfo.deviceModel)
                            .filter { it.isNotBlank() }.distinct().joinToString(" ")
                            .ifBlank { socName.ifBlank { "—" } },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 驱动指南只列驱动相关快速选择，避免与组件指南内容重复
            // 按 GPU 厂商给驱动推荐：Adreno→Turnip、Mali→PanVK、PowerVR→厂商/实验
            val vendor = deviceInfo?.gpuVendor
            if (driverMode) {
                when (vendor) {
                    GpuVendor.ADRENO -> {
                        QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                        QuickPickRow("Turnip (Mesa)", "Adreno 专用开源驱动，推荐首选")
                        QuickPickRow("AdrenoTools", "驱动包实测：加载/扩展/实例对比")
                    }
                    GpuVendor.MALI -> {
                        QuickPickRow("PanVK", "Mali 专用 Vulkan 驱动（实验）")
                        QuickPickRow("厂商驱动", "优先使用系统自带 Mali 驱动")
                    }
                    GpuVendor.POWERVR -> {
                        QuickPickRow("厂商驱动", "PowerVR 无开源驱动，用系统自带")
                    }
                    else -> {
                        QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                        QuickPickRow("Turnip (Mesa)", "Adreno 专用开源驱动，推荐首选")
                    }
                }
            } else {
                QuickPickRow(appString(R.string.gpu_driver), appString(R.string.quick_gpu))
                if (!driverMode) {
                    QuickPickRow("DirectX 8–11", appString(R.string.quick_dx8_11))
                    QuickPickRow("DirectX 12", appString(R.string.quick_dx12))
                    QuickPickRow("Direct3D 3–7", appString(R.string.quick_d3d3_7))
                    QuickPickRow(appString(R.string.windows_runtime), appString(R.string.quick_windows))
                    QuickPickRow(appString(R.string.x86_64_arm64), appString(R.string.quick_cpu))
                }
            }
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
                    color = Fusion.SurfaceAlt
                ) {
                    // 图标本身给品牌色：底色已是中性灰，图标再用灰会整片糊在一起，
                    // 八个主题卡长得一模一样，扫描时找不到落点
                    Icon(
                        topic.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = Fusion.Primary
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

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 分类与徽章是标签，不是结论；收起时挂在卡片上只是白占一行高度
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(onClick = {}, label = { Text(appString(topic.categoryRes)) })
                        AssistChip(onClick = {}, label = { Text(appString(topic.badgeRes)) })
                    }
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

                    if (topic.stepsRes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                appString(R.string.useful_steps),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            topic.stepsRes.forEachIndexed { index, stepRes ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        appString(stepRes),
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
}

@Composable
private fun GuideDetailBlock(label: String, text: String, icon: ImageVector, extra: String? = null) {
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
            if (extra != null) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        extra,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** 设备指南：解释设备信息字段、跑分口径、SoC 知识库与驱动路线推荐 */
@Composable
private fun DeviceGuideDialog(
    onDismiss: () -> Unit,
    deviceInfo: DeviceInfo? = null,
    vulkanPayload: VulkanInfoPayload? = null,
    benchmarkResult: BenchmarkResult? = null
) {
    val guideContext = LocalContext.current
    // 动态附注：全部来自本机实测/本地规格库，不编造
    val vulkanExtra = vulkanPayload?.devices?.firstOrNull()?.let { d ->
        "本机实测：Vulkan ${d.apiVersion} · ${d.driverName}（${d.extensions.size} 个扩展）"
    } ?: "本机 Vulkan 信息暂未采集"
    val benchExtra = when {
        benchmarkResult?.success == true -> "本机实测总分 ${benchmarkResult.totalScore}（填充带宽 ${benchmarkResult.fillBandwidthGBs} GB/s）"
        benchmarkResult != null -> "实测受 App 进程环境限制（SELinux/容器），性能请在模拟器内验证"
        else -> "尚未实测；在「实测」里跑一次驱动实测后自动填充"
    }
    val socExtra = if (deviceInfo != null) {
        val spec = SocSpecStore.find(deviceInfo.socName, guideContext)
        val bench = BenchEngine.resolve(deviceInfo.socName, deviceInfo.gpuModel, guideContext)
        when {
            spec != null && bench?.antutuTotal != null ->
                "规格命中：${spec.totalCores} 核 · 最高 ${formatFreq(spec.maxFreqGhZ)}；安兔兔 ${bench.antutuTotal}"
            spec != null ->
                "规格命中：${spec.totalCores} 核 · 最高 ${formatFreq(spec.maxFreqGhZ)}（含各簇频率）"
            bench?.antutuTotal != null ->
                "跑分命中：安兔兔 ${bench.antutuTotal}（规格库暂无 CPU 明细）"
            else -> "规格库未收录当前芯片，按平台通用信息展示"
        }
    } else "未检测到设备信息"
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(appString(R.string.device_guide_title), style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    appString(R.string.device_guide_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 智能：当前设备摘要卡（品牌/型号 + SoC 品牌徽章）
                if (deviceInfo != null) {
                    val socName = deviceInfo.socName
                    val brand = if (socName.isNotBlank()) detectSocBrand(socName)
                        else detectGpuSocBrand(deviceInfo.gpuModel, deviceInfo.gpuVendor)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                listOf(deviceInfo.deviceBrand, deviceInfo.deviceModel)
                                    .filter { it.isNotBlank() }.distinct().joinToString(" ")
                                    .ifBlank { socName.ifBlank { "—" } },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(brand.color).copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(brand.label, style = MaterialTheme.typography.labelMedium, color = Color(brand.color))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    listOfNotNull(
                                        socName.ifBlank { null },
                                        deviceInfo.gpuModel.takeIf { it.isNotBlank() },
                                        deviceInfo.ram.takeIf { it.isNotBlank() }
                                    ).joinToString(" · ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                GuideDetailBlock(
                    appString(R.string.device_guide_vulkan),
                    appString(R.string.device_guide_vulkan_desc),
                    Icons.Default.Info,
                    extra = vulkanExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_bench),
                    appString(R.string.device_guide_bench_desc),
                    Icons.Default.Speed,
                    extra = benchExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_soc),
                    appString(R.string.device_guide_soc_desc),
                    Icons.Default.Memory,
                    extra = socExtra
                )
                GuideDetailBlock(
                    appString(R.string.device_guide_route),
                    appString(R.string.device_guide_route_desc),
                    Icons.Default.Navigation
                )
                DeviceGuideComponentRec(deviceInfo)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(appString(R.string.ok))
                }
            }
        }
    }
}

/** 设备指南-组件类型推荐：按当前 GPU 厂商给出组件组合与官方发布日志要点（动态生成，无官方 logo 依赖） */
@Composable
private fun DeviceGuideComponentRec(deviceInfo: DeviceInfo?) {
    val ctx = LocalContext.current
    val vendor = deviceInfo?.gpuVendor
    val isAdreno = vendor == GpuVendor.ADRENO
    val isMali = vendor == GpuVendor.MALI
    // 真实驱动匹配：driver-registry.json 按当前 GPU 评分排序，优先展示推荐条目
    val registryDrivers = remember(deviceInfo) {
        DriverRegistryRepository.loadFromAssets(ctx)
            ?.let { DriverRegistryRepository.match(deviceInfo, it) }
            ?.sortedByDescending { it.recommended }
            ?.take(3)
            ?: emptyList()
    }
    val recs = when {
        registryDrivers.isNotEmpty() -> registryDrivers.map { m ->
            val e = m.entry
            val tag = if (e.latestTag.isNotBlank()) "（${e.latestTag}）" else ""
            "驱动：${e.name}$tag — ${e.description}${if (m.recommended) "（按当前设备推荐）" else ""}"
        } + when {
            isAdreno -> listOf(
                "DXVK 2.x：D3D9/10/11 转译层，Adreno 7xx 推荐 2.5+；VKD3D-Proton 2.x 处理 D3D12",
                "Wine/Proton：WOW64 + Box64 基础组件，优先用各源最新发布"
            )
            isMali -> listOf(
                "DXVK 2.x + VKD3D-Proton：转译层与 PanVK 搭配，官方日志含 Mali 修复",
                "Box64/WOWBox64：x86 转译基础组件，按模拟器内置版本对应选择"
            )
            else -> listOf(
                "组件：DXVK 2.x 转译 D3D9/10/11，VKD3D-Proton 转译 D3D12，按游戏 API 需求安装",
                "Wine/Proton + Box64：Winlator 运行环境基础，跟随组件源最新版本"
            )
        }
        else -> when {
            isAdreno -> listOf(
                "驱动路线：Turnip（Mesa 开源）为 Winlator 首选，AdrenoTools 自定义驱动可解锁更高版本；备选高通官方驱动",
                "DXVK 2.x：D3D9/10/11 转译层，官方日志持续改进动态渲染与内存管理，Adreno 7xx 推荐 2.5+",
                "VKD3D-Proton 2.x：D3D12 转译层，支持光追后备；游戏要求 D3D12 时安装",
                "Wine/Proton：WOW64 组件配合 Box64 使用，优先用各源最新发布"
            )
            isMali -> listOf(
                "驱动路线：PanVK（Mesa Panfrost 开源 Mali 驱动）首选，备选 Mali 厂商驱动",
                "DXVK 2.x + VKD3D-Proton：转译层与 PanVK 搭配，官方日志含 Mali 相关修复",
                "Box64/WOWBox64：x86 转译基础组件，按模拟器内置版本对应选择"
            )
            else -> listOf(
                "驱动：按 GPU 厂商选择对应开源驱动（Adreno→Turnip，Mali→PanVK），或厂商官方驱动",
                "组件：DXVK 2.x 转译 D3D9/10/11，VKD3D-Proton 转译 D3D12，两者按游戏 API 需求安装",
                "Wine/Proton + Box64：Winlator 运行环境基础，建议跟随组件源最新版本"
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "组件类型推荐（按当前设备）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            recs.forEach { r ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        r,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
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


/**
 * 折叠状态的来源提示：来源名 + 距上次发布的时间。
 *
 * 驱动区分区默认折叠，一屏要放五六个分区。折叠时只留这两项，
 * 换源后不展开也能看出这个源多久没发新版。一周内发布的用主题色强调，
 * 更早的用次要色，避免满屏都在喊「新」。
 */
@Composable
private fun CollapsedSourceHint(name: String, latestPublishedAt: String?) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 源名可能很长（the412banner 的整合包名带一串包含项），不限宽会把
        // 右侧「更新时间」和展开箭头挤出屏幕
        modifier = Modifier.widthIn(max = 104.dp).padding(end = 6.dp)
    )
    if (!latestPublishedAt.isNullOrBlank()) {
        Spacer(Modifier.width(2.dp))
        val relTime = formatRelativeTime(latestPublishedAt)
        // formatRelativeTime 返回「刚刚 / N 分钟前 / N 小时前 / N 天前 / N 个月前」
        val fresh = relTime == "刚刚" || (relTime.endsWith("前") && !relTime.contains("天")
            && !relTime.contains("个月"))
        Text(
            "· $relTime",
            style = MaterialTheme.typography.labelSmall,
            color = if (fresh) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「给谁用 · 怎么装 · 注意」三行说明块。
 *
 * 驱动和组件页以前只给一行作者简介（「稳定版 Turnip 构建」这种），
 * 用户还得自己判断这包适不适合自己的机器、下完往哪儿放。
 * 这里把三件事并排摊开：适用机型/GPU、按顺序的安装步骤、什么时候别用。
 * 文案来自 assets/source-profiles.json，读不到就整块不渲染。
 */
@Composable
private fun UsageBlock(profile: SourceProfile?, modifier: Modifier = Modifier) {
    if (profile == null || profile.isEmpty) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (profile.fit.isNotBlank()) {
                UsageRow("给谁用", profile.fit, MaterialTheme.colorScheme.onSurface)
            }
            if (profile.how.isNotEmpty()) {
                UsageRow(
                    "怎么装",
                    profile.how.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                    MaterialTheme.colorScheme.onSurface
                )
            }
            if (profile.caution.isNotBlank()) {
                UsageRow("注意", profile.caution, MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(top = 1.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 专区大标题（驱动专区 / 组件专区）。
 *
 * 曾经右侧挂一个「实时来源 / 内置兜底」的 chip：数据来自远端还是内置。
 * 这个信息对用户没有决策价值——内置源同样是可用来源，看到「内置兜底」
 * 反而以为是降级状态。来源与更新时间已经在各分区的来源选择器里能查到，
 * 标题行只留标题本身。
 */
@Composable
private fun RegionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Fusion.OnSurface
        )
    }
}

@Composable
private fun DeviceConclusionBlock(
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    route: DriverRouteRecommendation?,
) {
    val context = LocalContext.current
    val gpuIsAdreno = deviceInfo?.gpuVendor == GpuVendor.ADRENO
    val resolvedSoc = remember(deviceInfo?.gpuModel, deviceInfo?.socName, gpuIsAdreno) {
        runCatching {
            SocResolver.resolveCurrent(
                context, deviceInfo?.gpuModel.orEmpty(), deviceInfo?.socName.orEmpty(),
                gpuIsAdreno
            )
        }.getOrNull()
    }

    // 芯片名只出现一次：中文营销名优先，无映射时退回原名
    val rawSoc = deviceInfo?.socName?.takeIf { it.isNotBlank() && !it.contains("未知") }
    val cnName = (resolvedSoc?.displayName?.takeIf { it.isNotBlank() }
        ?: deviceInfo?.let { socChineseName(it, context) }).orEmpty()
    val socPrimary = when {
        cnName.isNotBlank() && cnName != rawSoc -> cnName
        rawSoc != null -> rawSoc
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isLoading) {
            FusionCard {
                Text(
                    appString(R.string.detecting_hardware),
                    fontSize = 14.sp,
                    color = Fusion.OnSurfaceDim,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else if (deviceInfo == null) {
            FusionCard {
                Text(
                    appString(R.string.hardware_unavailable),
                    fontSize = 14.sp,
                    color = Fusion.OnSurfaceDim,
                )
            }
        } else {
            // 结论先行：先说「能跑成什么样」，参数只是支撑证据
            val tone = when (route?.turnipSupport) {
                TurnipSupport.FULL -> ConclusionTone.GOOD
                TurnipSupport.PARTIAL -> ConclusionTone.WARN
                TurnipSupport.NONE -> ConclusionTone.BAD
                null -> ConclusionTone.NEUTRAL
            }
            val headline = when (route?.turnipSupport) {
                TurnipSupport.FULL -> appString(R.string.device_conclusion_full)
                TurnipSupport.PARTIAL -> appString(R.string.device_conclusion_partial)
                TurnipSupport.NONE -> appString(R.string.device_conclusion_none)
                null -> appString(R.string.device_conclusion_unknown)
            }
            val badge = when (route?.turnipSupport) {
                TurnipSupport.FULL -> appString(R.string.device_badge_recommended)
                TurnipSupport.PARTIAL -> appString(R.string.device_badge_limited)
                TurnipSupport.NONE -> appString(R.string.device_badge_caution)
                null -> null
            }
            ConclusionCard(
                headline = headline,
                evidence = listOfNotNull(
                    socPrimary,
                    listOf(deviceInfo.deviceBrand, deviceMarketingName(deviceInfo.deviceModel, context))
                        .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null },
                ).joinToString(" · "),
                badge = badge,
                tone = tone,
            )
            // 参数横排：三项一行放完，不做「一卡一格」
            SpecStrip(
                listOf(
                    SpecItem(appString(R.string.android_label), deviceInfo.androidVersion),
                    SpecItem(appString(R.string.ram_label), deviceInfo.ram),
                    SpecItem(appString(R.string.gpu_label), gpuDisplayName(deviceInfo)),
                )
            )
        }
    }
}

/**
 * GPU 显示名。
 *
 * detectGpuModel 只返回数字（740/720），Mali、Immortalis 直接拼 "Adreno" 会误导，
 * 所以先从 renderer 里取完整型号（Mali-G720 / Immortalis-G720），取不到再按 Adreno 数字拼。
 */
private fun gpuDisplayName(info: DeviceInfo): String {
    val renderer = info.gpuRenderer.orEmpty()
    Regex("(?:Mali|Immortalis)[\\s-]*G\\d{3,4}[A-Z]*", RegexOption.IGNORE_CASE)
        .find(renderer)?.value?.replaceFirstChar { it.uppercase() }
        ?.let { return it }
    return when {
        // gpuModel 有时已是「Adreno 750」全名，直接拼会变成「Adreno Adreno 750」
        info.gpuModel != "unknown" ->
            if (info.gpuModel.contains("Adreno", true)) info.gpuModel else "Adreno ${info.gpuModel}"
        info.adrenoSeries != "unknown" -> "Adreno ${info.adrenoSeries}"
        else -> renderer.ifBlank { "—" }
    }
}

/** 厂商官方徽章资源（大图版）：识别失败返回 null（回退通用手机图标）。 */
private fun socBrandIconRes(brandName: String): Int? = when (brandName) {
    "Qualcomm", "高通" -> R.drawable.ic_soc_qualcomm
    "MediaTek", "联发科" -> R.drawable.ic_soc_mediatek
    "Samsung", "三星" -> R.drawable.ic_soc_samsung
    "Huawei", "华为" -> R.drawable.ic_soc_huawei
    "Google", "谷歌" -> R.drawable.ic_soc_google
    "ARM" -> R.drawable.ic_soc_arm
    else -> null
}

/**
 * 设备代号→营销名映射表只解析一次。
 *
 * 原实现把 assets 读取写在 deviceMarketingName() 里，而该函数在 DeviceSummaryCard
 * 的 Text 参数里调用——每次重组（滚动、切主题、进度刷新）都会读一遍文件。
 * 设备页一屏至少重组成百次，等于每帧一次磁盘 IO。
 */
private object DeviceModelNameStore {
    @Volatile
    private var map: Map<String, String>? = null

    fun lookup(context: Context, model: String): String {
        val table = map ?: synchronized(this) {
            map ?: runCatching {
                val root = org.json.JSONObject(
                    context.assets.open("device_models.json").bufferedReader().use { it.readText() }
                )
                val models = root.optJSONObject("models")
                buildMap<String, String> {
                    if (models != null) {
                        val keys = models.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            put(k, models.optString(k))
                        }
                    }
                }
            }.getOrDefault(emptyMap()).also { map = it }
        }
        return table[model]?.takeIf { it.isNotBlank() } ?: model
    }
}

/** 设备代号→营销名识别（vivo V2338A → iQOO Neo9）。映射表 assets/device_models.json，未收录返回原代号。 */
private fun deviceMarketingName(model: String, context: Context): String {
    if (model.isBlank()) return ""
    return DeviceModelNameStore.lookup(context, model)
}

/** 厂商徽章英文官方原名：高通→Qualcomm、联发科→MediaTek、三星→Samsung、华为→Huawei、谷歌→Google。 */
private fun socBrandEnglishName(name: String): String = when (name) {
    "高通", "Qualcomm" -> "Qualcomm"
    "联发科", "MediaTek" -> "MediaTek"
    "三星", "Samsung" -> "Samsung"
    "华为", "Huawei" -> "Huawei"
    "谷歌", "Google" -> "Google"
    "ARM", "Arm" -> "ARM"
    else -> name
}

/** 中文 SoC 名：知识库条目名优先（骁龙 8 Gen 2），内置中英映射兜底。 */
private fun socChineseName(deviceInfo: DeviceInfo, context: Context): String {
    if (deviceInfo.socName.isBlank()) return "—"
    // 已是中文（骁龙…/天玑…/麒麟…）直接返回
    if (deviceInfo.socName.any { it in "骁龙天玑麒麟" }) return deviceInfo.socName
    SocKnowledgeStore.find(deviceInfo.socName, deviceInfo.gpuModel, context)?.name?.let { return it }
    return deviceInfo.socName
}

@Composable
private fun SocBrandBadge(brand: SocBrand, text: String, modifier: Modifier = Modifier) {
    // 本地内置 PNG 图标（Simple Icons 转制），显示厂商全名；未收录厂商回退纯文字徽章
    val iconRes = when (brand.name) {
        "Qualcomm", "高通" -> R.drawable.ic_soc_qualcomm
        "MediaTek", "联发科" -> R.drawable.ic_soc_mediatek
        "Samsung", "三星" -> R.drawable.ic_soc_samsung
        "Huawei", "华为" -> R.drawable.ic_soc_huawei
        "Google", "谷歌" -> R.drawable.ic_soc_google
        "ARM" -> R.drawable.ic_soc_arm
        else -> null
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(brand.color).copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color(brand.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    onNavigateToDrivers: (() -> Unit)?,
    onOpenDriverFiles: ((String) -> Unit)? = null
) {
    var recommendations by remember(deviceInfo) { mutableStateOf<List<DriverMatchResult>?>(null) }
    var loadFailed by remember(deviceInfo) { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(deviceInfo) {
        loadFailed = false
        try {
            val result = withContext(Dispatchers.IO) {
                val entries = DriverRegistryRepository.load(context = context)
                val matched = DriverRegistryRepository.match(deviceInfo, entries)
                // 只留「完美适配」项：gpuModels 精确命中本机型号 + 架构兼容 + stable/beta/ci
                //（score>=100），按 star 取前 3，避免一堆「支持但非本机型号」的仓库堆在推荐卡
                val perfect = matched.filter { it.recommended }
                val picks = if (perfect.isNotEmpty()) perfect
                else matched.filter { it.score >= 100 }.take(1) // 实验性兜底：型号命中但非 stable，最多 1 条
                picks.sortedByDescending { it.entry.stars }.take(3)
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
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
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
                        key(match.entry.id) {
                            DriverRecommendRow(
                                match = match,
                                onOpenDriverFiles = onOpenDriverFiles,
                                onDownload = { asset ->
                                    // 用全局作用域：卡片滚出屏幕被回收/切分区时不能把下载一起取消
                                    AppDownloadScope.launch {
                                        runCatching {
                                            downloadDriverAsset(
                                                context,
                                                match.entry.name,
                                                asset.downloadUrl,
                                                asset.name
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

        }
    }
}

/**
 * 单个推荐驱动：上面一行来源说明，下面直接摊开最近两个 release 的可下载文件。
 *
 * 原实现只给一个「查看驱动文件」按钮，点进去还要再翻一层才知道该下哪个。
 * 这里把文件列表摊开，最新一个非预发布版打上推荐标记，点文件即下载。
 */
@Composable
private fun DriverRecommendRow(
    match: DriverMatchResult,
    onOpenDriverFiles: ((String) -> Unit)?,
    onDownload: (GithubAsset) -> Unit
) {
    val entry = match.entry
    var releases by remember(entry.id) { mutableStateOf<List<GithubRelease>?>(null) }
    LaunchedEffect(entry.id) {
        releases = runCatching { fetchGithubReleasesFromUrl(entry.apiUrl) }
            .getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 作者头像：和驱动区分区头一致，用户能一眼认出这是哪个作者/仓库
            RepoAvatar(
                avatarUrl = githubOwnerAvatar(entry.apiUrl),
                fallbackName = entry.name,
                size = 26.dp
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(match.matchReason)
                        entry.targetEmulator.takeIf { it.isNotBlank() && it != "generic" }?.let {
                            append(" · 适配 $it")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 来源简介：两行封顶。原来一行截断，稍长的说明只剩省略号，用户判断不了该下哪个
                if (entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
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

        val list = releases
        when {
            list == null -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
            list.isEmpty() -> Text(
                when (lastApiError) {
                    "RATE_LIMITED" -> appString(R.string.source_empty_rate_limited)
                    "NOT_FOUND" -> appString(R.string.source_empty_no_release)
                    else -> "没取到版本列表，可进驱动专区查看"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
            )
            else -> {
                // 最新一个非预发布版算推荐版本
                val recommendedTag = list.firstOrNull { !it.prerelease }?.tagName
                list.take(2).forEach { release ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            release.tagName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (release.tagName == recommendedTag) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    appString(R.string.category_recommended),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (release.publishedAt.length >= 10) {
                            Text(
                                release.publishedAt.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                    release.assets
                        .filter { isDownloadableAsset(it.name) }
                        .take(3)
                        .forEach { asset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onDownload(asset) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    asset.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (asset.sizeBytes > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        formatBytes(asset.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }
                }
                if (onOpenDriverFiles != null) {
                    TextButton(onClick = { onOpenDriverFiles(entry.id) }) {
                        Text("全部版本与说明", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 驱动 release 里值得列出的文件：安装包、压缩包、驱动 bin */
private fun isDownloadableAsset(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".7z") ||
        lower.endsWith(".tar.gz") || lower.endsWith(".so") || lower.endsWith(".adpkg") ||
        lower.endsWith(".wcp")
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                appString(R.string.source_format, section.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
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

/** 目录路径以 "/" 结尾（adrenotools 内部按 "dir + 文件名" 拼接，缺斜杠必加载失败） */
private fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"

/** 根据设备 Adreno 系列计算 Turnip 源优先级：匹配型号/系列 → 推荐，有白名单无匹配 → 专属，无白名单 → 通用 */
private fun turnipCategoryOf(source: TurnipSource, adrenoSeries: String?): String {
    // PanVK（Mali）驱动独立分区：与 Turnip（Adreno）/ 高通提取驱动完全不同的
    // 技术路线（Mesa Panfrost 前端），混在推荐区会让用户误以为不兼容。
    if (source.requiredVendor == "mali") return "panvk"
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
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    updatedAtMap: Map<String, Long> = emptyMap(),
    remoteStars: Map<String, Long> = emptyMap(),
    title: String? = null,
    description: String? = null,
    expandTrigger: String? = null,
    catalogItems: List<WrapperItem> = emptyList(),
    onCatalogDownload: ((WrapperItem) -> Unit)? = null,
    /** 下载按钮文案：Turnip / PanVK / Xclipse / Wrapper 各用各的，不共用「下载 Turnip」 */
    downloadLabel: String? = null
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    val profileCtx = LocalContext.current
    val profile = remember(currentSource?.id) {
        SourceProfiles.ofSource(profileCtx, currentSource?.id)
    }
    // 区块默认折叠：与组件专区一致，一屏可浏览全部驱动大区
    var expanded by rememberSaveable(selectionKey) { mutableStateOf(false) }
    // I1：外部触发展开（切换来源后自动打开对应文件列表）
    val lastTrigger = remember { mutableStateOf("") }
    LaunchedEffect(selectionKey, expandTrigger) {
        if (expandTrigger != null && expandTrigger != lastTrigger.value) {
            expanded = true
            lastTrigger.value = expandTrigger
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 区块头像：与高通区一致，显示当前源作者头像；加载失败回退首字母
                val srcAvatar = currentSource?.apiUrl?.let { githubOwnerAvatar(it) }
                RepoAvatar(
                    avatarUrl = srcAvatar,
                    fallbackName = currentSource?.name ?: (title ?: "D"),
                    size = 24.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title ?: appString(R.string.turnip_driver), style = MaterialTheme.typography.titleSmall)
                    // 折叠时也要能一眼看出「这包给谁用」：取人工核定文案，没有就退回作者简介
                    if (!expanded) {
                        val fitLine = profile?.fit?.takeIf { it.isNotBlank() }
                            ?: currentSource?.description
                        if (!fitLine.isNullOrBlank()) {
                            Text(
                                fitLine,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (expanded) {
                        // I2：简介可点击展开/收起，解决长简介截断无法查看全文
                        var descExpanded by rememberSaveable(selectionKey + ":desc") { mutableStateOf(false) }
                        Text(
                            description ?: when (adrenoSeries) {
                                "8xx" -> appString(R.string.turnip_desc_8xx)
                                "6xx", "7xx" -> appString(R.string.turnip_desc_6_7)
                                else -> appString(R.string.turnip_desc_generic)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { descExpanded = !descExpanded }
                                .padding(top = 4.dp)
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                }
                if (!expanded && currentSource != null) {
                    CollapsedSourceHint(
                        name = currentSource.name,
                        latestPublishedAt = releases.firstOrNull()?.publishedAt
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

            UsageBlock(profile)

            if (currentSource != null) {
                val currentCategory = turnipCategoryOf(currentSource, adrenoSeries)
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentArchived = currentSource.archived,
                    currentCategory = currentCategory,
                    avatarUrl = currentSource?.apiUrl?.let { githubOwnerAvatar(it) },
                    currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                        .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            archived = source.archived,
                            category = turnipCategoryOf(source, adrenoSeries),
                            updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                            preview = buildTurnipPreview(source, remoteStars),
                            avatarUrl = source.apiUrl?.let { githubOwnerAvatar(it) }
                        )
                    },
                    onSelected = { sourceId ->
                        if (sourceId != currentSourceId) onSourceChange(sourceId)
                    }
                )
            }

            if (catalogItems.isNotEmpty()) {
                // wrapper 清单型源（banner wrappers.json）：展开区直接列清单卡，
                // 不依赖 release 标签；条目即版本卡。
                WrapperCatalogSection(
                    items = catalogItems,
                    onDownload = { item ->
                        onCatalogDownload?.invoke(item)
                    }
                )
            } else {
                DriverReleasePicker(
                    releases = releases,
                    selectionKey = selectionKey,
                    onDownload = onDownload,
                    buttonLabel = downloadLabel ?: appString(R.string.download_turnip)
                )
            }
            }
            }
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
    val preview: List<PreviewItem> = emptyList(),
    /** 仓库作者头像 URL（GitHub 作者头像），弹窗条目区分仓库用；无则回退状态图标 */
    val avatarUrl: String? = null
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
    currentUpdatedHint: String? = null,
    /** 源作者头像 URL（GitHub 作者头像），无则显示默认源图标 */
    avatarUrl: String? = null
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
                if (avatarUrl != null) {
                    RepoAvatar(
                        avatarUrl = avatarUrl,
                        fallbackName = currentName
                    )
                } else {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            currentName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                        // 来源简介可点击展开/收起，避免长简介截断无法查看全文
                        var descExpanded by rememberSaveable("${title}:src-desc") { mutableStateOf(false) }
                        Text(
                            currentDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
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

            // 只有一个源时不显示换源按钮：禁用态仍占一整行，看着像坏了
            if (options.size > 1) {
                FilledTonalButton(
                    onClick = { showSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.change_source))
                }
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
                        appString(R.string.downloads_upstream),
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                    // 分组：推荐 / 通用 / 专属 / 已停更
                    val categoryLabel = mapOf(
                        "recommended" to appString(R.string.category_recommended),
                        "panvk" to "PanVK（Mali）",
                        "general" to appString(R.string.category_general),
                        "exclusive" to appString(R.string.category_exclusive),
                        "archived" to appString(R.string.source_archived)
                    )
                    val grouped = options.groupBy { opt ->
                        if (opt.archived) "archived" else opt.category.ifBlank { "general" }
                    }
                    val categoryOrder = listOf("recommended", "panvk", "general", "exclusive", "archived")
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
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
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

/** Turnip 来源预览：支持型号 / 目标模拟器 / 活跃度，star 优先用远程元数据覆盖 */
@Composable
private fun buildTurnipPreview(source: TurnipSource, remoteStars: Map<String, Long> = emptyMap()): List<PreviewItem> {
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
    val stars = remoteStars[source.id] ?: source.stars
    if (stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(stars)))
    return lines
}

/** Rutabaga Turnip 驱动卡片（banner 社区源 WCP 构建）：并入驱动区 Turnip 分区。
 *  条目来自 winlator-contents manifest type=Turnip（Rutabaga-* 版本），
 *  下载走组件下载通道（onDownloadComponent）。 */
@Composable
private fun RutabagaDriverCard(
    components: List<Component>,
    onDownload: (Component) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Rutabaga（Turnip 驱动，banner 社区源）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Text(
                "Mesa Turnip 的 Rutabaga 实验构建，以 WCP 打包分发，Adreno 6xx/7xx 适用。版本随上游发布更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            components.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.verName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onDownload(c) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("下载", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Qualcomm 来源预览：系统驱动说明 + 活跃度 + 停更状态，star 优先用远程元数据覆盖 */
@Composable
private fun buildQualcommPreview(source: QualcommSource, remoteStars: Map<String, Long> = emptyMap()): List<PreviewItem> {
    val lines = mutableListOf<PreviewItem>()
    lines += PreviewItem(Icons.Default.Memory, appString(R.string.preview_qualcomm_system))
    if (source.archived) lines += PreviewItem(Icons.Default.Warning, appString(R.string.preview_archived))
    val stars = remoteStars[source.id] ?: source.stars
    if (stars > 0) lines += PreviewItem(Icons.Default.Star, appString(R.string.preview_stars, formatStars(stars)))
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
                if (option.avatarUrl != null) {
                    RepoAvatar(
                        avatarUrl = option.avatarUrl,
                        fallbackName = option.name,
                        selected = selected
                    )
                } else {
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
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    option.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
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
                // 简介可点击展开/收起：不截断信息，避免长简介（wrapper 发布说明等）被过滤
                var descExpanded by rememberSaveable(option.id) { mutableStateOf(false) }
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { descExpanded = !descExpanded }
                )
                if (!descExpanded && option.description.length > 50) {
                    Text(
                        appString(R.string.expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { descExpanded = true }
                            .padding(top = 2.dp)
                    )
                }
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
                            // 长行（如支持型号列表 610/612/...）默认 2 行截断，点击展开全文
                            var previewExpanded by rememberSaveable(option.id, item.text) {
                                mutableStateOf(false)
                            }
                            val longLine = item.text.length > 40
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (previewExpanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .then(
                                            if (longLine) Modifier.clickable {
                                                previewExpanded = !previewExpanded
                                            } else Modifier
                                        )
                                )
                                if (longLine && !previewExpanded) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        appString(R.string.expand),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { previewExpanded = true }
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
// ── 游戏支持能力评估（驱动实测 → 转译游戏分级）────────────────────
data class GameSupportEval(val title: String, val level: String, val note: String)

/** 基于实测驱动类型 + 设备 GPU + 实测扩展列表，评估 Windows(D3D) 与 Switch 游戏转译支持等级。
 *  level: 基本能跑 / 可能能跑（缺条件）/ 跑不了
 *  依据：DXVK 按 D3D API 的成熟度（D3D8-11 成熟、D3D12 仍多问题）、
 *        PanVK/Turnip 驱动成熟度、Vulkan API 版本门槛，
 *        以及驱动实测上报的关键扩展覆盖（swapchain/maintenance/timeline/dynamic_rendering 等）。 */
private val CRITICAL_DXVK_EXTS = setOf(
    "VK_KHR_swapchain", "VK_KHR_maintenance1", "VK_KHR_maintenance2",
    "VK_KHR_maintenance3", "VK_KHR_timeline_semaphore", "VK_KHR_dynamic_rendering",
    "VK_EXT_memory_budget", "VK_KHR_descriptor_indexing", "VK_KHR_16bit_storage"
)

/** 关键扩展覆盖度：驱动实测扩展列表中命中关键集的占比 */
private fun criticalExtCoverage(extensions: List<String>): Float {
    if (extensions.isEmpty()) return 0f
    val set = extensions.toSet()
    return CRITICAL_DXVK_EXTS.count { it in set }.toFloat() / CRITICAL_DXVK_EXTS.size
}

fun assessGameSupport(
    driverType: String,          // turnip / panvk / qualcomm / system
    isAdrenoDevice: Boolean,
    isMaliDevice: Boolean,
    extensions: List<String>,
    apiVersion: String
): List<GameSupportEval> {
    val out = mutableListOf<GameSupportEval>()
    val vk13 = apiVersion.startsWith("1.3") || apiVersion.startsWith("1.4")
    val vk12 = apiVersion.startsWith("1.2") || vk13
    val coverage = criticalExtCoverage(extensions)
    // 扩展充足：关键集覆盖 >= 70% 且必备 swapchain/maintenance1 齐全
    val rich = coverage >= 0.7f &&
        extensions.contains("VK_KHR_swapchain") && extensions.contains("VK_KHR_maintenance1")
    val coverageNote = "关键扩展覆盖 ${(coverage * 100).toInt()}%（DXVK 依赖集）"
    when (driverType) {
        "turnip" -> {
            out += GameSupportEval("Windows D3D8/9（DXVK）", "基本能跑", "DXVK 2.7.3 转译成熟，社区 171+ 配置验证")
            out += if (rich) GameSupportEval("Windows D3D10/11（DXVK）", "基本能跑", "Turnip 扩展齐全，多数 3A 可玩（$coverageNote）")
                else GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "关键扩展覆盖不足（$coverageNote）")
            out += GameSupportEval("Windows D3D12（VKD3D）", if (vk13) "可能能跑" else "可能能跑", if (vk13) "Vulkan 1.3 支持，转译问题仍多" else "需 Vulkan 1.2+，黑屏概率高")
            out += if (isAdrenoDevice) GameSupportEval("Switch 游戏（Eden/Citron）", if (vk13 && rich) "基本能跑" else "可能能跑", "Eden 对 Adreno 优化，需完整 Vulkan 特性")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "GPU 非 Adreno，兼容性以实测为准")
        }
        "panvk" -> {
            out += GameSupportEval("Windows D3D8/9（DXVK）", "可能能跑", "PanVK 在开发中，部分游戏异常")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "依赖 PanVK 成熟度，建议社区配置")
            out += GameSupportEval("Windows D3D12（VKD3D）", "跑不了", "PanVK 缺 D3D12 所需特性，暂不建议")
            out += if (isMaliDevice) GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "PanVK 对 Mali 逐步支持，黑屏/贴图错误可能")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "非 Mali 设备装 PanVK 意义有限")
        }
        "radv" -> { // Mesa RADV / 三星 Xclipse 实验性驱动
            out += GameSupportEval("Windows D3D8/9（DXVK）", "可能能跑", "Xclipse 驱动实验性，DXVK 需实测")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "可能能跑", "RADV 移植起步阶段，社区配置稀缺")
            out += GameSupportEval("Windows D3D12（VKD3D）", "跑不了", "实验性驱动暂不建议 D3D12")
            out += GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "Xclipse 需专用 WinXclipse 分支，兼容性以实测为准")
        }
        else -> { // qualcomm 系统驱动
            out += GameSupportEval("Windows D3D8/9（DXVK）", "基本能跑", "系统驱动稳定，DXVK 成熟")
            out += GameSupportEval("Windows D3D10/11（DXVK）", "基本能跑", "高通官方 Vulkan 完整")
            out += GameSupportEval("Windows D3D12（VKD3D）", if (vk13) "可能能跑" else "可能能跑", "Vulkan 1.3 支持则 D3D12 可尝试")
            out += if (isAdrenoDevice) GameSupportEval("Switch 游戏（Eden/Citron）", "基本能跑", "系统驱动 + Eden 优化组合")
                else GameSupportEval("Switch 游戏（Eden/Citron）", "可能能跑", "视设备 GPU 支持而定")
        }
    }
    return out
}

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
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    updatedAtMap: Map<String, Long> = emptyMap(),
    remoteStars: Map<String, Long> = emptyMap(),
    expandTrigger: String? = null
) {
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    val profileCtx = LocalContext.current
    val profile = remember(currentSource?.id) {
        SourceProfiles.ofSource(profileCtx, currentSource?.id)
    }
    // 高通区块默认折叠：与 Turnip/PanVK 大区一致
    var expanded by rememberSaveable(selectionKey) { mutableStateOf(false) }
    // I1：外部触发展开（切换来源后自动打开对应文件列表）
    val lastTrigger = remember { mutableStateOf("") }
    LaunchedEffect(selectionKey, expandTrigger) {
        if (expandTrigger != null && expandTrigger != lastTrigger.value) {
            expanded = true
            lastTrigger.value = expandTrigger
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val srcAvatar = currentSource?.apiUrl?.let { githubOwnerAvatar(it) }
                RepoAvatar(
                    avatarUrl = srcAvatar,
                    fallbackName = currentSource?.name ?: title
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (!expanded) {
                        val fitLine = profile?.fit?.takeIf { it.isNotBlank() }
                            ?: currentSource?.description
                        if (!fitLine.isNullOrBlank()) {
                            Text(
                                fitLine,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (expanded) {
                        // I2：简介可点击展开/收起，解决长简介截断无法查看全文
                        var descExpanded by rememberSaveable(selectionKey + ":desc") { mutableStateOf(false) }
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { descExpanded = !descExpanded }
                                .padding(top = 4.dp)
                        )
                        Text(
                            if (descExpanded) appString(R.string.collapse) else appString(R.string.expand),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                }
                if (!expanded && currentSource != null) {
                    CollapsedSourceHint(
                        name = currentSource.name,
                        latestPublishedAt = releases.firstOrNull()?.publishedAt
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            UsageBlock(profile)

            if (currentSource != null) {
                SourcePickerCard(
                    title = appString(R.string.driver_source),
                    currentName = currentSource.name,
                    currentDescription = currentSource.description,
                    currentExperimental = currentSource.experimental,
                    currentArchived = currentSource.archived,
                    currentCategory = "general",
                    avatarUrl = currentSource?.apiUrl?.let { githubOwnerAvatar(it) },
                    currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                        .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                    options = sources.map { source ->
                        SourcePickerOption(
                            id = source.id,
                            name = source.name,
                            description = source.description,
                            experimental = source.experimental,
                            archived = source.archived,
                            category = "general",
                            updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                            preview = buildQualcommPreview(source, remoteStars),
                            avatarUrl = source.apiUrl?.let { githubOwnerAvatar(it) }
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReleaseChangelog(release: GithubRelease?) {
    val notes = remember(release?.tagName, release?.body) {
        release?.body?.let { cleanReleaseNotes(it) }.orEmpty()
    }
    if (notes.isBlank()) return
    // R4：官方日志按 Markdown 段落（## 标题）分类，用户自选感兴趣的段落查看
    val sections = remember(release?.tagName, notes) { splitChangelogSections(notes) }
    var selectedSection by remember(release?.tagName) { mutableStateOf(0) }
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
                                    val result = translateReleaseNotes(
                                        if (sections.size > 1) sections.getOrNull(selectedSection)?.second ?: notes else notes
                                    )
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
                // R4：段落分类自选——多段落日志按标题拆分，顶部 chips 选择
                if (sections.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sections.forEachIndexed { idx, sec ->
                            FilterChip(
                                selected = idx == selectedSection,
                                onClick = {
                                    selectedSection = idx
                                    translated = null
                                    showTranslated = false
                                },
                                label = {
                                    Text(
                                        sec.first ?: appString(R.string.changelog_section_body),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val currentText = if (sections.size > 1) sections.getOrNull(selectedSection)?.second ?: notes else notes
                val displayText = if (showTranslated) translated ?: currentText else currentText
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

/** R4：官方日志按 Markdown 标题段落拆分，返回 (段落标题, 段落正文) 列表 */
private fun splitChangelogSections(text: String): List<Pair<String?, String>> {
    val titleRe = Regex("^\\s*(#{1,6})\\s+(.*)$")
    val sections = mutableListOf<Pair<String?, String>>()
    var curTitle: String? = null
    val curBody = StringBuilder()
    for (line in text.lines()) {
        val m = titleRe.find(line)
        if (m != null) {
            if (curBody.isNotBlank() || sections.isNotEmpty()) {
                sections.add(curTitle to curBody.toString().trim())
            }
            curTitle = m.groupValues[2].trim()
            curBody.setLength(0)
        } else {
            if (line.isNotBlank()) {
                curBody.append(line).append("\n")
            }
        }
    }
    if (curBody.isNotBlank() || sections.isEmpty()) {
        sections.add(curTitle to curBody.toString().trim())
    }
    return sections.filter { it.second.isNotBlank() }.ifEmpty { listOf(null to text) }
}

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

    if ((selectedRelease?.assets?.size ?: 0) >= 1) {
        ExposedDropdownMenuBox(
            expanded = expandedAsset,
            onExpandedChange = { expandedAsset = !expandedAsset }
        ) {
            OutlinedTextField(
                value = selectedAsset?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    if ((selectedRelease?.assets?.size ?: 0) > 1) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAsset)
                    }
                },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(appString(R.string.file)) },
                shape = RoundedCornerShape(18.dp),
                maxLines = 1
            )
            if ((selectedRelease?.assets?.size ?: 0) > 1) {
                ExposedDropdownMenu(
                    expanded = expandedAsset,
                    onDismissRequest = { expandedAsset = false }
                ) {
                    selectedRelease?.assets?.forEach { asset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        asset.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
    }

    // 文件格式提示：按扩展名判断是否为模拟器标准驱动包（zip/adpkg）
    val assetName = selectedAsset?.name.orEmpty()
    val assetExt = assetName.substringAfterLast('.', "").lowercase()
    val standardPkg = assetExt == "zip" || assetExt == "adpkg"
    if (assetName.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (standardPkg) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    if (standardPkg) appString(R.string.file_format_standard, assetExt.uppercase())
                    else appString(R.string.file_format_special, assetExt.uppercase()),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (standardPkg) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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

/** 模拟器 APK 大小：HEAD 懒加载，格式化为可读文本后回调 */
@Composable
private fun EmulatorApkSize(apkUrl: String, content: @Composable (String) -> Unit) {
    var sizeText by remember(apkUrl) { mutableStateOf("") }
    LaunchedEffect(apkUrl) {
        if (sizeText.isBlank()) {
            val bytes = fetchRemoteSizeBytes(apkUrl)
            if (bytes != null && bytes > 0) {
                sizeText = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
                else "%.0f KB".format(bytes / 1024.0)
            }
        }
    }
    content(sizeText)
}

/** wrapper 清单区块（winlator-contents wrappers.json）：每个条目一张卡，
 *  简介为作者维护的发布说明（自动读取，不依赖 release 标签顺序），可折叠展开。 */
@Composable
private fun WrapperCatalogSection(
    items: List<WrapperItem>,
    onDownload: (WrapperItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            var descExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
            var useZh by rememberSaveable(item.id + ":zh") { mutableStateOf(true) }
            val displayName = if (useZh) wrapperNameZh(item.name) else item.name
            val displayDesc = if (useZh) wrapperDescZh(item.description) else item.description
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 与其他分区卡片一致的作者头像（author 为 GitHub 用户名时显示头像，否则首字母兜底）
                        ProjectAvatar(
                            logoUrl = "",
                            owner = item.author,
                            fallbackName = item.name,
                            size = 36.dp,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.version > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    "v${item.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // 译文/原文切换（记忆到条目级）
                        Text(
                            if (useZh) "译文" else "原文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { useZh = !useZh }
                                .padding(start = 6.dp)
                        )
                    }
                    if (!useZh && wrapperNameZh(item.name) != item.name) {
                        Text(
                            wrapperNameZh(item.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.author.isNotBlank() || item.license.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(
                                item.author.takeIf { it.isNotBlank() },
                                item.license.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            displayDesc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                        if (!descExpanded && displayDesc.length > 60) {
                            Text(
                                appString(R.string.expand),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { descExpanded = true }
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.url.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.fileSizeBytes > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatBytes(item.fileSizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onDownload(item) },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(appString(R.string.download), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
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

/** R5：来源级捆绑独立区块——列出全部组件源，一键下载各源最新一套文件。
 *  各源分发形态不同（release 仓库 / contents.json 清单），由
 *  DownloadUtils.downloadSourceBundle 按 manifestUrl 自动适配。 */
@Composable
private fun ComponentBundleSection(sources: List<ComponentSource>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busyIds = remember { mutableStateMapOf<String, Boolean>() }
    val results = remember { mutableStateMapOf<String, BundleResult>() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Fusion.GradientStart
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(appString(R.string.source_bundle_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        appString(R.string.source_bundle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            sources.forEach { source ->
                val busy = busyIds[source.id] == true
                val bundleDoneMsg = appString(R.string.source_bundle_done, source.name)
                val result = results[source.id]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = githubOwnerAvatar(source.manifestUrl),
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 最新整包信息：tag + 稳定/夜版徽标 + 资产数 + 发布时间
                        if (result != null && result.tag != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = result.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Fusion.GradientStart,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (result.prerelease) Fusion.SurfaceAlt.copy(alpha = 0.6f)
                                    else Fusion.GradientStart.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (result.prerelease) "夜版" else "稳定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (result.prerelease) Fusion.GradientStart else Fusion.GradientStart,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (result.assetCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${result.assetCount} 个文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // 下载结果汇总：成功/失败 + 失败项重试
                        if (result != null && (result.success.isNotEmpty() || result.failed.isNotEmpty())) {
                            if (result.failed.isEmpty()) {
                                Text(
                                    text = "已下载 ${result.success.size} 个文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Fusion.GradientStart
                                )
                            } else {
                                Text(
                                    text = "成功 ${result.success.size} / 失败 ${result.failed.size}，失败项：${result.failed.take(3).joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busyIds[source.id] = true
                            scope.launch {
                                try {
                                    results[source.id] = downloadSourceBundle(context, source.manifestUrl, source.name)
                                    val r = results[source.id]
                                    if (r != null && r.failed.isEmpty()) {
                                        Toast.makeText(context, bundleDoneMsg, Toast.LENGTH_LONG).show()
                                    } else if (r != null && r.failed.isNotEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "完成 ${r.success.size}，失败 ${r.failed.size} 个（可点击重试）",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "bundle failed", Toast.LENGTH_LONG).show()
                                } finally {
                                    busyIds[source.id] = false
                                }
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(appString(R.string.source_bundle_downloading), maxLines = 1)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (result != null && result.failed.isNotEmpty()) "重试" else appString(R.string.source_bundle_btn),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

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
    val profileCtx = LocalContext.current
    val typeProfile = remember(type) { SourceProfiles.ofComponentType(profileCtx, type) }
    val sourceProfile = remember(currentSource.id) {
        SourceProfiles.ofSource(profileCtx, currentSource.id)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Fusion.Surface),
        border = BorderStroke(1.dp, Fusion.Border)
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
                    // 「给谁用」压到折叠态：一眼能判断这个组件要不要装
                    val fitLine = typeProfile?.fit?.takeIf { it.isNotBlank() }
                        ?: componentSubtitle(type)
                    Text(
                        fitLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Text(
                        buildString {
                            append("${components.size} ${appString(R.string.version)} · ${currentSource.name}")
                            val hint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                                .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) }
                            if (hint != null) append(" · ").append(hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 单行：副标题折成两行会把卡片撑高，且「25 天/前」这种断行很丑
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded) {
                    Text(
                        text = selected?.verName ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
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

                    // 组件类型通用说明 + 当前来源的补充说明（没有文案就不占位）
                    UsageBlock(typeProfile)
                    UsageBlock(sourceProfile)

                    SourcePickerCard(
                        title = appString(R.string.source_type, type),
                        currentName = currentSource.name,
                        currentDescription = currentSource.description,
                        currentExperimental = currentSource.experimental,
                        avatarUrl = githubOwnerAvatar(currentSource.manifestUrl),
                        currentUpdatedHint = (updatedAtMap[currentSource.id] ?: currentSource.updatedAt)
                            .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                        options = sources.map { source ->
                            SourcePickerOption(
                                id = source.id,
                                name = source.name,
                                description = source.description,
                                experimental = source.experimental,
                                updatedHint = (updatedAtMap[source.id] ?: source.updatedAt)
                                    .takeIf { it > 0 }?.let { formatRelativeTimeMs(it) },
                                avatarUrl = githubOwnerAvatar(source.manifestUrl)
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
    onNavigateToDrivers: (() -> Unit)? = null,
    onOpenDriverFiles: ((String) -> Unit)? = null,
    /** 顶栏指南按钮的触发信号（自增计数）：>0 时打开指南 */
    guideSignal: Int = 0
) {
    var vulkanPayload by remember { mutableStateOf<VulkanInfoPayload?>(null) }
    var vulkanLoading by remember { mutableStateOf(true) }
    var vulkanError by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    // 最近一次驱动实测的跑分：由「实测」里的驱动实测卡回传，用于设备指南的实测脚注
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    var showDeviceGuide by remember { mutableStateOf(false) }
    // 渲染实测可换的目标驱动：只取已下载完成的驱动包（与驱动页同一套判定口径）。
    // 页面级 remember：设备页数据本来就是进入时采集一次，列表在页面存活期内固定，
    // 避免每次重组重建列表把卡片里选中的驱动重置掉。
    val driverTargets = remember {
        DownloadsManager.completedDownloads
            .filter {
                it.fileName.endsWith(".zip", true) || it.fileName.endsWith(".adpkg", true)
            }
            .filter { dl ->
                if (dl.subPath.isNotBlank()) dl.subPath.startsWith("Drivers", true)
                else listOf(
                    "turnip", "qualcomm", "adreno", "mali", "panvk", "driver", "vulkan", "mesa"
                ).any { dl.fileName.contains(it, true) }
            }
            .map { dl ->
                DriverTarget(
                    id = dl.id,
                    label = dl.fileName.substringBeforeLast('.').take(24),
                    packagePath = dl.filePath,
                )
            }
    }
    // 生态动态：eco 类新闻（芯片/手机/安卓，与设备检测呼应）
    var deviceNewsItems by remember { mutableStateOf<List<NewsItem>?>(null) }
    val deviceNewsContext = LocalContext.current
    LaunchedEffect(Unit) {
        deviceNewsItems = NewsRepository.loadNews(deviceNewsContext)
    }

    // 指南入口在顶栏（右上角图标），这里响应信号打开
    LaunchedEffect(guideSignal) {
        if (guideSignal > 0) showDeviceGuide = true
    }

    if (showDeviceGuide) {
        DeviceGuideDialog(
            onDismiss = { showDeviceGuide = false },
            deviceInfo = deviceInfo,
            vulkanPayload = vulkanPayload,
            benchmarkResult = benchmarkResult
        )
    }

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

    val driverRoute = deviceInfo?.let { recommendDriverRoute(it) }
    // 设备页子分区：0 概览 / 1 性能 / 2 实测 / 3 资讯
    // 单页原来把所有模块竖排（含底部新闻折叠区）要滑 6~8 屏，重排成一屏一个子分区。
    var deviceTab by remember { mutableStateOf(0) }
    val deviceListState = rememberLazyListState()
    val deviceScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 子分区条固定在列表上方：滚动时始终可见，切分区即换内容，不用一路滑
        SectionTabs(
            tabs = listOf(
                appString(R.string.device_tab_overview),
                appString(R.string.device_tab_perf),
                appString(R.string.device_tab_test),
                appString(R.string.device_tab_news),
            ),
            selected = deviceTab,
            onSelect = { index ->
                if (index != deviceTab) {
                    deviceTab = index
                    deviceScope.launch { deviceListState.scrollToItem(0) }
                }
            },
        )
        LazyColumn(
            state = deviceListState,
            modifier = Modifier.fillMaxSize(),
            // 底部留出固定操作条高度，否则最后一条会被压住
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 结论在参数之前：用户要的是判断，不是跑分
            if (deviceTab == 0) item(key = "device_conclusion") {
                DeviceConclusionBlock(
                    deviceInfo = deviceInfo,
                    isLoading = isLoading,
                    route = driverRoute,
                )
            }

            if (deviceTab == 0 && !isLoading && deviceInfo != null) {
                item(key = "emuready_compat") {
                    EmuReadyCard(deviceInfo = deviceInfo)
                }
                item(key = "driver_route") {
                    DriverRecommendationCard(
                        deviceInfo = deviceInfo,
                        onNavigateToDrivers = onNavigateToDrivers,
                        onOpenDriverFiles = onOpenDriverFiles
                    )
                }
                item(key = "soc_knowledge") {
                    SocKnowledgeCard(
                        context = LocalContext.current,
                        gpuModel = deviceInfo.gpuModel,
                        socHint = deviceInfo.socName,
                        gpuIsAdreno = deviceInfo.gpuVendor == GpuVendor.ADRENO
                    )
                }
            }

            if (deviceTab == 1 && !isLoading && deviceInfo != null) {
                item(key = "bench_engine") {
                    BenchEngineCard(context = LocalContext.current, gpuModel = deviceInfo.gpuModel)
                }
            }

        if (deviceTab == 1) {
        item(key = "vulkan_header") {
            SectionHeader(appString(R.string.vulkan_title))
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
                        Spacer(Modifier.height(12.dp))
                        Text("关键特性", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        VULKAN_KEY_FEATURES.forEach { (key, hint) ->
                            VulkanKeyFeatureRow(
                                enabled = device.features2[key] == true,
                                label = key,
                                hint = hint
                            )
                        }
                        if (device.features2.isEmpty()) {
                            Text("本机/驱动未上报 Vulkan 1.2+ 特性（旧 ROM 或旧驱动）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            if (device.pipelineCacheUUID.isNotEmpty()) {
                                InfoRow(appString(R.string.vulkan_driver_fingerprint), device.pipelineCacheUUID)
                            }
                            InfoRow(appString(R.string.vulkan_formats), device.formats.size.toString())
                            if (device.sparseProperties.isNotEmpty()) {
                                val sparseOn = device.sparseProperties.count { it.value }
                                InfoRow(
                                    appString(R.string.vulkan_sparse_support),
                                    "$sparseOn/${device.sparseProperties.size}"
                                )
                            }
                            InfoRow(appString(R.string.vulkan_extensions), device.extensions.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_types), device.memoryTypes.size.toString())
                            InfoRow(appString(R.string.vulkan_memory_heaps), device.memoryHeaps.size.toString())
                            InfoRow(appString(R.string.vulkan_queue_families), device.queueFamilies.size.toString())
                        }
                    )
                }

                item(key = "vulkan_key_exts") {
                    val extSet = device.extensions.map { it.name }.toSet()
                    val hitCount = CRITICAL_DXVK_EXTS.count { it in extSet }
                    VulkanDetailCard(
                        title = "关键扩展覆盖（DXVK/VKD3D）${hitCount}/${CRITICAL_DXVK_EXTS.size}",
                        content = {
                            CRITICAL_DXVK_EXTS.forEach { name ->
                                VulkanKeyExtensionRow(
                                    name = name,
                                    present = name in extSet,
                                    hint = VULKAN_KEY_EXT_HINTS[name] ?: ""
                                )
                            }
                        }
                    )
                }

                item(key = "vulkan_features2") {
                    val f2 = device.features2
                    VulkanDetailCard(
                        title = "Vulkan 1.2/1.3 特性（${f2.values.count { it }}/${f2.size}）",
                        content = {
                            if (f2.isEmpty()) {
                                Text("未上报（旧 ROM/驱动未导出 vkGetPhysicalDeviceFeatures2）", style = MaterialTheme.typography.bodySmall)
                            } else {
                                f2.toSortedMap().forEach { (key, value) ->
                                    VulkanKeyFeatureRow(enabled = value, label = key, hint = "")
                                }
                            }
                        }
                    )
                }

                item(key = "vulkan_formats") {
                    VulkanDetailCard(
                        title = "格式支持矩阵（${device.formats.size} 关键格式）",
                        content = {
                            if (device.formats.isEmpty()) {
                                Text("未采集（驱动未导出 vkGetPhysicalDeviceFormatProperties）", style = MaterialTheme.typography.bodySmall)
                            } else {
                                if (!device.formatFlags2) {
                                    Text(
                                        "驱动未提供 VkFormatProperties3（Vulkan 1.3 / VK_KHR_format_feature_flags2）：\n下列仅含 32 位特性位，高位特性显示为「—」不代表设备真的不支持。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                device.formats.forEach { fmt ->
                                    // 有 64 位值就用 64 位（VK_FORMAT_FEATURE_2_* 的高位标志），
                                    // 否则退回 32 位值，保证「不支持」与「读不出」可区分
                                    val effective = fmt.optimalFeatures64
                                        ?: fmt.optimalTilingFeatures.toULong()
                                    val ok = effective != 0uL
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                if (ok) "✓" else "—",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${fmt.format}  [0x${effective.toString(16)}]",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
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

        // GPU 跑分并入「实测」子分区的驱动实测卡：同一份驱动、同一次测试里
        // 既出图又出分，不再单独占一个入口（原来三个入口各自跑一遍，用户不知道点哪个）。
        }

        // 渲染实测（自研内核）：直接出图，而不是只给一行 FPS 文字
        // 有已下载驱动时可在卡片内切换「系统驱动 / 指定驱动」，实现换驱动实测
        if (deviceTab == 2) item(key = "driver_render_check") {
            DriverRenderCheckCard(
                drivers = driverTargets,
                onBenchResult = { benchmarkResult = it },
            )
        }

        // ── 已下载驱动检测：解压 .zip → dlopen .so → 采集 Vulkan 能力 → 与系统驱动对比 ──
        if (deviceTab == 2) item(key = "driver_detect_header") {
            SectionHeader(appString(R.string.driver_detection_title))
        }

        if (deviceTab == 2) item(key = "driver_detect_list") {
            DriverDetectionList(
                systemDevice = vulkanPayload?.devices?.firstOrNull()
            )
        }

            // 资讯是独立子分区，不再挂在页尾折叠（用户要看新闻不必先滑到底）
            if (deviceTab == 3) {
                item(key = "device_news") {
                    NewsZoneCard(
                        title = appString(R.string.news_eco_title),
                        items = deviceNewsItems,
                        category = "device",
                        showGeneralFold = true
                    )
                }
            }
        }
        }

        // 底部固定操作条：详情页的「下一步」只放一个主按钮
        if (!isLoading && deviceInfo != null) {
            DetailActionBar(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { onNavigateToDrivers?.invoke() },
                    enabled = onNavigateToDrivers != null,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.goto_driver_region))
                }
            }
        }
    }
}

/** EmuReady 兼容性卡片：本地目录精确匹配本机型号 → 按 deviceId 查各平台兼容得分（0-100）。
 *
 *  旧实现直接拿"品牌+型号"联网模糊搜，上游按包含匹配返回、首条常是别的机型
 *  （搜 Xiaomi 15 给 Xiaomi 15T Pro），SoC 兜底也从未命中，所以卡片长期空着。
 *  现在先在 assets 目录里匹配型号，命中才请求；本机未收录时用同 SoC 代表机型参考，
 *  并明确标注"同芯片参考"，不冒充本机实测。 */
@Composable
private fun EmuReadyCard(deviceInfo: DeviceInfo) {
    val context = LocalContext.current
    var compat by remember { mutableStateOf<EmuReadyCompat?>(null) }
    var match by remember { mutableStateOf<EmuReadyMatch?>(null) }
    var socCompat by remember { mutableStateOf<SocCompat?>(null) }
    // 本地 SoC 表是毫秒级同步读取：先出内容，再让网络结果覆盖，
    // 避免整张卡先转圈几秒（用户之前的体感是"刷新很久才出来"）
    var loading by remember { mutableStateOf(true) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo.deviceBrand, deviceInfo.deviceModel, deviceInfo.socName) {
        loading = true
        socCompat = withContext(Dispatchers.IO) {
            EmuReadySocStore.find(context, deviceInfo.socName)
        }
        if (socCompat != null) loading = false

        val marketing = deviceMarketingName(deviceInfo.deviceModel, context)
        val models = (EmuReadyStore.systemMarketNames() + listOf(marketing, deviceInfo.deviceModel))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val hit = withContext(Dispatchers.IO) {
            EmuReadyStore.match(context, deviceInfo.deviceBrand, models, deviceInfo.socName)
        }
        match = hit
        compat = if (hit != null) {
            withContext(Dispatchers.IO) { fetchEmuReadyCompatibility(hit.device) }
        } else null
        loading = false
    }

    // 展示口径：本机有报告 → 本机；否则用同 SoC 聚合表（不再引导去官网）
    val deviceSystems = compat?.systems.orEmpty()
    val socSystems = socCompat?.systems.orEmpty()
    val useDevice = deviceSystems.isNotEmpty()
    val rows = if (useDevice) deviceSystems.size else socSystems.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appString(R.string.emuready_title), style = MaterialTheme.typography.titleSmall)
                    val deviceLine = when {
                        loading && rows == 0 -> appString(R.string.emuready_loading)
                        useDevice -> "${compat!!.brandName} ${compat!!.modelName} · ${compat!!.socName}"
                        socCompat != null -> {
                            val n = socCompat!!.devices.size.coerceAtLeast(1)
                            appString(R.string.emuready_soc_line, socCompat!!.soc, n)
                        }
                        match != null -> "${match!!.device.brandName} ${match!!.device.modelName} · ${appString(R.string.emuready_no_report)}"
                        else -> appString(R.string.emuready_no_data)
                    }
                    Text(
                        deviceLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 数据口径徽章：本机实测 / 同芯片机型聚合，别让用户误以为都是本机数据
            Spacer(Modifier.height(6.dp))
            val (badge, tint) = when {
                useDevice -> appString(R.string.emuready_badge_device) to MaterialTheme.colorScheme.primary
                socCompat != null -> appString(R.string.emuready_badge_soc) to MaterialTheme.colorScheme.secondary
                else -> appString(R.string.emuready_badge_none) to MaterialTheme.colorScheme.outline
            }
            Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.14f)) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (useDevice) {
                val all = deviceSystems
                val shown = if (showAll) all else all.take(8)
                Spacer(Modifier.height(8.dp))
                shown.forEachIndexed { idx, s ->
                    if (idx > 0) HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    CompatRow(
                        name = emuSystemZh(s.name, ""),
                        emulator = s.bestEmulator,
                        score = s.score,
                        confidence = s.confidence,
                        source = s.dataSource,
                        listings = s.totalListings
                    )
                }
                if (all.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.emuready_expand_more, all.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (socSystems.isNotEmpty()) {
                val shown = if (showAll) socSystems else socSystems.take(8)
                Spacer(Modifier.height(8.dp))
                shown.forEachIndexed { idx, s ->
                    if (idx > 0) HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    CompatRow(
                        name = emuSystemZh(s.name, s.key),
                        emulator = s.bestEmulator,
                        score = s.score,
                        confidence = s.confidence,
                        source = s.dataSource,
                        listings = s.listings
                    )
                }
                if (socSystems.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.emuready_expand_more, socSystems.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    appString(R.string.emuready_source_note, EmuReadySocStore.dataGeneratedAt().take(10)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** EmuReady 平台名的中文对照：接口只给英文系统名，直接摆英文用户读不懂 */
private fun emuSystemZh(name: String, key: String): String {
    val k = key.ifBlank { name.lowercase() }
    val zh = when {
        // switch2 要在 switch 之前判：contains("switch") 会把 NS2 也吃掉
        k.contains("switch_2") || k.contains("switch2") || k.contains("switch 2") -> "Switch 2"
        k.contains("switch") -> "Switch"
        k.contains("windows") -> "Windows (Winlator)"
        k.contains("playstation_2") || k.contains("ps2") -> "PS2"
        k.contains("playstation_3") || k.contains("ps3") -> "PS3"
        k.contains("playstation_vita") || k.contains("vita") -> "PS Vita"
        k.contains("playstation_portable") || k.contains("psp") -> "PSP"
        k.contains("playstation") || k.contains("ps1") -> "PS1"
        k.contains("gamecube") -> "GameCube"
        k.contains("wii_u") -> "Wii U"
        k.contains("wii") -> "Wii"
        k.contains("nintendo_3ds") || k.contains("3ds") -> "3DS"
        k.contains("nintendo_ds") || k.contains("nds") -> "NDS"
        k.contains("nintendo_64") || k.contains("n64") -> "N64"
        k.contains("snes") || k.contains("super_nintendo") -> "SFC"
        k.contains("nes") -> "FC"
        k.contains("game_boy_advance") || k.contains("gba") -> "GBA"
        k.contains("game_boy") -> "GB"
        k.contains("xbox_360") -> "Xbox 360"
        k.contains("xbox") -> "Xbox"
        k.contains("dreamcast") -> "Dreamcast"
        k.contains("saturn") -> "Saturn"
        k.contains("arcade") -> "街机"
        k.contains("apple_ios") || k.contains("ios") -> "iOS"
        k.contains("android") -> "Android"
        k.contains("linux") -> "Linux"
        else -> name
    }
    return zh
}

@Composable
private fun CompatRow(
    name: String,
    emulator: String,
    score: Int,
    confidence: String,
    source: String,
    listings: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val conf = when (confidence) {
                "high" -> appString(R.string.emuready_conf_high)
                "medium" -> appString(R.string.emuready_conf_mid)
                else -> appString(R.string.emuready_conf_low)
            }
            val src = if (source == "soc") appString(R.string.emuready_src_soc) else appString(R.string.emuready_src_device)
            val parts = listOfNotNull(
                emulator.takeIf { it.isNotBlank() }?.let { appString(R.string.emuready_best_emu, it) },
                conf,
                src,
                if (listings > 0) appString(R.string.emuready_listings, listings) else null
            )
            Text(
                parts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when {
                score >= 80 -> Color(0xFF2E7D32)
                score >= 60 -> Color(0xFFF9A825)
                else -> Color(0xFFC62828)
            }
        ) {
            Text(
                "$score",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
                .background(Fusion.SurfaceAlt)
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

/** CPU 基准数值 + 进度条（整数满格 20000 MIPS / 浮点满格 8000 MFLOPS，对应 8 核旗舰）。 */
@Composable
private fun CpuRow(label: String, value: Double, unit: String, threads: Int) {
    val maxVal = if (unit == "MIPS") 20000.0 else 8000.0
    val fraction = (value / maxVal).coerceIn(0.0, 1.0)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value > 0) String.format("%.0f %s × %d 线程", value, unit, threads) else "不可用",
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
                .background(Fusion.SurfaceAlt)
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

/** 带宽数值 + 进度条（满格 64 GB/s，对应 Adreno 8 系带宽量级，低端机也能看出比例）。 */
@Composable
private fun BandwidthRow(label: String, valueGBs: Double) {
    val fraction = (valueGBs / 64.0).coerceIn(0.0, 1.0)
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
                .background(Fusion.SurfaceAlt)
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
    // AdrenoTools 实测（M4）：以模拟器同款加载路径（namespace 绕过+GPU 映射导入）
    // 加载 turnip/panvk，采集实例/设备扩展并跑分——替代裸 dlopen（App 内必然失败）。
    var adrenotoolsResults by remember { mutableStateOf<Map<String, Pair<VulkanInfoPayload, BenchmarkResult>>>(emptyMap()) }
    var renderResults by remember { mutableStateOf<Map<String, org.json.JSONObject>>(emptyMap()) }
    var adrenotoolsTestingId by remember { mutableStateOf<String?>(null) }
    // 双选实测对比：勾选两个已下载驱动，AdrenoTools 并排实测（扩展/设备/跑分）
    val compareSelection = remember { mutableStateListOf<String>() }

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

    // AdrenoTools 实测目录：tmpLibDir（memfd 不可用时 patched lib 落地，App 私有可写）
    // + hookLibDir（必须 = nativeLibraryDir，hook .so 随 APK 解压于此）。
    // 注意：adrenotools_open_libvulkan 内部按 "dir + 文件名" 直接拼接 stat，
    // 目录必须以 "/" 结尾，否则拼接出非法路径，加载必失败（ADRENOTOOLS_OPEN_FAILED）。
    val adrenotoolsDirs = run {
        val tmp = File(context.filesDir, "adrenotools_tmp").apply { mkdirs() }
        (tmp.absolutePath.ensureTrailingSlash()) to context.applicationInfo.nativeLibraryDir
    }

    // 独立进程实测（vktest 同构）：:drivertest service 进程系统全新启动，
    // 无主进程已初始化的系统 Vulkan/GPU 状态，adrenotools 加载 turnip/panvk
    // 不再受 fork 继承 GPU 状态冲突影响（DRIVER_CRASHED 根因修复）。
    // 驱动崩溃只杀 service 进程，主进程无感；结果经 files 下 JSON 回传轮询。
    var servicePollingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(servicePollingId) {
        val id = servicePollingId ?: return@LaunchedEffect
        val dl = downloadedDrivers.firstOrNull { it.id == id }
        if (dl == null) { servicePollingId = null; return@LaunchedEffect }
        val resultFile = DriverTestService.resultFile(context, id)
        val startedFile = File(context.filesDir, "driver_test_${id}.started")
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            resultFile.deleteOnExit()
            if (resultFile.exists()) {
                val raw = runCatching { resultFile.readText() }.getOrNull()
                if (raw != null && raw.isNotBlank()) {
                    try {
                        val result = org.json.JSONObject(raw)
                        if (result.has("collect")) {
                            val payload = VulkanInfoPayload.fromJson(result.getJSONObject("collect").toString())
                            // 分阶段写盘后 benchmark/render 可能缺失（后段崩溃），部分结果照常展示
                            val brJson = result.optJSONObject("benchmark")
                            val br = if (brJson != null) BenchmarkResult.fromJson(brJson.toString())
                                else BenchmarkResult(
                                    success = false, errorCode = "STAGE_CRASHED",
                                    errorMessage = "跑分/渲染阶段进程崩溃，采集结果已保留", deviceName = "",
                                    fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0, computeGFLOPS = 0.0,
                                    computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                                    totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0,
                                    timestampPeriodNs = 0.0
                                )
                            adrenotoolsResults = adrenotoolsResults + (id to (payload to br))
                            if (result.has("render")) {
                                renderResults = renderResults + (id to result.getJSONObject("render"))
                            }
                            detectionResults = detectionResults + (id to payload)
                        } else {
                            detectionResults = detectionResults + (id to VulkanInfoPayload(
                                success = false,
                                errorCode = result.optString("errorCode", "SERVICE_FAILED"),
                                errorMessage = result.optString("errorMessage", "独立进程实测失败"),
                                loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
                                deviceCount = 0, devices = emptyList()
                            ))
                        }
                        resultFile.delete()
                        adrenotoolsTestingId = null
                        servicePollingId = null
                        return@LaunchedEffect
                    } catch (e: Exception) {
                        // 结果未写完或格式异常，继续轮询
                    }
                }
            }
            delay(300)
        }
        // 超时判定：启动标记仍在 → 驱动加载崩溃把独立进程带走了（进程被杀）；
        // 启动标记不在 → 服务未启动/参数异常。
        val crashed = startedFile.exists()
        adrenotoolsTestingId = null
        servicePollingId = null
        detectionResults = detectionResults + (id to VulkanInfoPayload(
            success = false,
            errorCode = if (crashed) "DRIVER_CRASHED_IN_SERVICE" else "SERVICE_NOT_STARTED",
            errorMessage = if (crashed) {
                "驱动加载崩溃（独立进程被杀，App 未受影响）。可能为驱动包与 App 内置 AdrenoTools 运行时版本不匹配，或需在模拟器内验证"
            } else {
                "独立进程实测未启动，请重试"
            },
            loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
            deviceCount = 0, devices = emptyList()
        ))
    }

    // AdrenoTools 实测：分阶段独立进程（collect → benchmark → render）。
    // 每个阶段一个全新 :drivertest 进程、进程内只加载一次驱动（vktest 同构），
    // 规避同进程重复 mapping import 导致 turnip GSL 状态冲突崩溃（STAGE_CRASHED 根因）。
    // 单阶段崩溃只丢该阶段结果，其余阶段照常展示。
    fun startStageService(resultId: String, stage: String, dir: String, name: String, tmp: String, hook: String) {
        DriverTestService.resultFile(context, resultId).delete()
        File(context.filesDir, "driver_test_${resultId}.started").writeText("1")
        val intent = android.content.Intent(context, DriverTestService::class.java).apply {
            putExtra("driverDir", dir)
            putExtra("driverName", name)
            putExtra("tmpDir", tmp)
            putExtra("hookLibDir", hook)
            putExtra("resultId", resultId)
            putExtra("stage", stage)
        }
        context.startService(intent)
    }

    // 崩溃步骤日志（native 每步 append）：进程被杀后主进程读取，定位崩溃到具体步骤
    fun stepLogTail(resultId: String, lines: Int = 12): String {
        return runCatching {
            val f = File(context.filesDir, "driver_test_${resultId}.step")
            if (f.exists()) f.readLines().takeLast(lines).joinToString(" > ") else ""
        }.getOrNull().orEmpty()
    }

    // 轮询单个阶段结果文件（25s 窗口）。返回结果 JSON，超时/崩溃返回 null。
    fun pollStageResult(resultId: String): org.json.JSONObject? {
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
            val rf = DriverTestService.resultFile(context, resultId)
            if (rf.exists()) {
                val raw = runCatching { rf.readText() }.getOrNull()
                if (raw != null && raw.isNotBlank()) {
                    val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
                    if (json != null) {
                        rf.delete()
                        return json
                    }
                }
            }
            Thread.sleep(300)
        }
        return null
    }

    // 第三方运行时已退役：仅清理老版本解压残留，驱动加载回退内置路径
    fun ensureExternalRuntime() = RuntimeBootstrap.retireLegacy(context)

    val testWithAdrenoTools: (String, String) -> Unit = { id, filePath ->
        adrenotoolsTestingId = id
        ensureExternalRuntime()
        scope.launch(Dispatchers.IO) {
            try {
                val soFile = extractSoFor(id, filePath)
                if (soFile == null) {
                    val fail = VulkanInfoPayload(
                        success = false, errorCode = "EXTRACT_FAILED",
                        errorMessage = "驱动包解压失败，未找到 .so", loader = "",
                        instanceExtCount = 0, instanceExtensions = emptyList(),
                        deviceCount = 0, devices = emptyList()
                    )
                    detectionResults = detectionResults + (id to fail)
                    adrenotoolsTestingId = null
                } else {
                    val (tmpDir, hookLibDir) = adrenotoolsDirs
                    val dir = (soFile.parent ?: soFile.absolutePath).ensureTrailingSlash()
                    val name = soFile.name

                    // 阶段 1 · 采集（全新进程，单次加载）
                    startStageService(id, "collect", dir, name, tmpDir, hookLibDir)
                    val collectJson = pollStageResult(id)
                    if (collectJson == null) {
                        val crashed = File(context.filesDir, "driver_test_${id}.started").exists()
                        val steps = stepLogTail(id)
                        detectionResults = detectionResults + (id to VulkanInfoPayload(
                            success = false,
                            errorCode = if (crashed) "DRIVER_CRASHED_IN_SERVICE" else "SERVICE_NOT_STARTED",
                            errorMessage = (if (crashed) "驱动加载崩溃（独立进程被杀）" else "独立进程实测未启动，请重试") +
                                (if (steps.isNotEmpty()) "\n崩溃步骤：$steps" else ""),
                            loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
                            deviceCount = 0, devices = emptyList()
                        ))
                        adrenotoolsTestingId = null
                        return@launch
                    }
                    val payload = VulkanInfoPayload.fromJson(collectJson.getJSONObject("collect").toString())
                    detectionResults = detectionResults + (id to payload)

                    // 阶段 2 · 跑分（独立进程，单次加载）
                    val benchId = "${id}_b"
                    startStageService(benchId, "benchmark", dir, name, tmpDir, hookLibDir)
                    val benchJson = pollStageResult(benchId)
                    if (benchJson != null) {
                        val br = BenchmarkResult.fromJson(benchJson.getJSONObject("benchmark").toString())
                        adrenotoolsResults = adrenotoolsResults + (id to (payload to br))

                        // 阶段 3 · 渲染（独立进程，单次加载）
                        val renderId = "${id}_r"
                        startStageService(renderId, "render", dir, name, tmpDir, hookLibDir)
                        val renderJson = pollStageResult(renderId)
                        val renderSteps = stepLogTail(renderId)
                        renderResults = renderResults + (id to (renderJson?.getJSONObject("render")
                            ?: org.json.JSONObject().put("success", false).put("errorCode", "STAGE_CRASHED")
                                .put("errorMessage", "渲染阶段进程崩溃，跑分结果已保留" +
                                    (if (renderSteps.isNotEmpty()) "\n崩溃步骤：$renderSteps" else ""))))
                    } else {
                        val crashed = File(context.filesDir, "driver_test_${benchId}.started").exists()
                        val steps = stepLogTail(benchId)
                        adrenotoolsResults = adrenotoolsResults + (id to (payload to BenchmarkResult(
                            success = false,
                            errorCode = if (crashed) "DRIVER_CRASHED_IN_SERVICE" else "SERVICE_NOT_STARTED",
                            errorMessage = (if (crashed) "跑分阶段进程崩溃（驱动加载后执行负载被杀）" else "跑分独立进程未启动，请重试") +
                                (if (steps.isNotEmpty()) "\n崩溃步骤：$steps" else ""),
                            deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                            computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                            totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                        )))
                    }
                    adrenotoolsTestingId = null
                }
            } catch (e: Exception) {
                detectionResults = detectionResults + (id to VulkanInfoPayload(
                    success = false, errorCode = "ADT_EXCEPTION",
                    errorMessage = e.message ?: "unknown", loader = "",
                    instanceExtCount = 0, instanceExtensions = emptyList(),
                    deviceCount = 0, devices = emptyList()
                ))
                adrenotoolsTestingId = null
            }
        }
    }

    // 静态检查 .so 是否导出 vkGetInstanceProcAddr（懒触发，IO 线程，缓存结果）
    val checkExportable: (String, String) -> Unit = { id, filePath ->
        scope.launch(Dispatchers.IO) {
            val flag = DriverMetaParser.soExportsVkGetInstanceProcAddr(extractSoFor(id, filePath))
            exportFlags = exportFlags + (id to flag)
        }
    }

    // 实测跑分：与"驱动信息"同一条独立进程链路（service + AdrenoTools + 系统预热）。
    // 旧实现 fork 子进程裸 dlopen 驱动 .so 跑带宽：无 root 进程无法访问 GPU 节点，
    // 与 vktest 差异在缺系统 Vulkan 宿主上下文，实测必败；现统一走 service，
    // 结果文件独立命名（bench_ 前缀），不占用检测轮询位。
    val benchmarkDriver: (String, String) -> Unit = { id, filePath ->
        benchmarkingId = id
        ensureExternalRuntime()
        scope.launch(Dispatchers.IO) {
            try {
                val soFile = extractSoFor(id, filePath)
                exportFlags = exportFlags + (id to DriverMetaParser.soExportsVkGetInstanceProcAddr(soFile))
                if (soFile == null) {
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                        success = false, errorCode = "EXTRACT_FAILED",
                        errorMessage = "驱动包解压失败，未找到 .so",
                        deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                        computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                        totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                    ))
                    benchmarkingId = null
                } else {
                    val (tmpDir, hookLibDir) = adrenotoolsDirs
                    val dir = (soFile.parent ?: soFile.absolutePath).ensureTrailingSlash()
                    val benchResultId = "bench_$id"
                    DriverTestService.resultFile(context, benchResultId).delete()
                    val startedFile = File(context.filesDir, "driver_test_${benchResultId}.started")
                    File(context.filesDir, "driver_test_${benchResultId}.started").writeText("1")
                    val intent = android.content.Intent(context, DriverTestService::class.java).apply {
                        putExtra("driverDir", dir)
                        putExtra("driverName", soFile.name)
                        putExtra("tmpDir", tmpDir)
                        putExtra("hookLibDir", hookLibDir)
                        putExtra("resultId", benchResultId)
                        putExtra("stage", "benchmark")
                    }
                    context.startService(intent)
                    // 轮询结果（与检测共用 25s 窗口；native 加载+渲染在 service 内完成）
                    val deadline = System.currentTimeMillis() + 25_000L
                    while (System.currentTimeMillis() < deadline) {
                        val rf = DriverTestService.resultFile(context, benchResultId)
                        if (rf.exists()) {
                            val raw = runCatching { rf.readText() }.getOrNull()
                            if (raw != null && raw.isNotBlank()) {
                                val result = runCatching { org.json.JSONObject(raw) }.getOrNull()
                                if (result != null) {
                                    // 分阶段写盘：有 benchmark 直接用；仅 collect 说明跑分/渲染阶段崩溃
                                    val brJson = result.optJSONObject("benchmark")
                                    if (brJson != null) {
                                        benchmarkResults = benchmarkResults + (id to BenchmarkResult.fromJson(brJson.toString()))
                                    } else if (result.has("collect")) {
                                        benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                                            success = false, errorCode = "STAGE_CRASHED",
                                            errorMessage = "跑分/渲染阶段进程崩溃，采集结果已保留",
                                            deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                                            computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                                            totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                                        ))
                                    } else {
                                        continue
                                    }
                                    rf.delete()
                                    benchmarkingId = null
                                    return@launch
                                }
                            }
                        }
                        Thread.sleep(300)
                    }
                    // 超时/崩溃判定
                    val crashed = startedFile.exists()
                    val steps = stepLogTail(benchResultId)
                    benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                        success = false,
                        errorCode = if (crashed) "DRIVER_CRASHED_IN_SERVICE" else "SERVICE_NOT_STARTED",
                        errorMessage = (if (crashed) "驱动加载崩溃（独立进程被杀）。可能为驱动包与内置 AdrenoTools 运行时不匹配" else "独立进程实测未启动，请重试") +
                            (if (steps.isNotEmpty()) "\n崩溃步骤：$steps" else ""),
                        deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                        computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                        totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                    ))
                    benchmarkingId = null
                }
            } catch (e: Exception) {
                benchmarkResults = benchmarkResults + (id to BenchmarkResult(
                    success = false, errorCode = "BENCHMARK_EXCEPTION",
                    errorMessage = e.message ?: "unknown",
                    deviceName = "", fillBandwidthGBs = 0.0, copyBandwidthGBs = 0.0,
                    computeGFLOPS = 0.0, computeError = "", cpuIntMIPS = 0.0, cpuFloatMFLOPS = 0.0, cpuThreads = 1,
                    totalScore = 0, bufferSizeMB = 0, fillIterations = 0, copyIterations = 0, timestampPeriodNs = 0.0
                ))
                benchmarkingId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 检测覆盖面说明：明确 AdrenoTools 实测的能力边界
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    appString(R.string.detection_coverage_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    appString(R.string.detection_coverage_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                "${"%.1f".format(download.sizeBytes / 1024.0 / 1024.0)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDetecting || adrenotoolsTestingId == download.id) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = {
                                    // 只读 meta.json 判定打包格式：
                                    // - VENDOR_SYSTEM：厂商系统提取驱动（需 root/系统级安装），
                                    //   AdrenoTools 也无法加载，保留说明提示。
                                    // - ADRENOTOOLS_PACKAGE / 其他：AdrenoTools 实测——
                                    //   以模拟器同款路径在 App 内加载 turnip/panvk 驱动，
                                    //   采集实例/设备扩展 + 跑分（崩溃隔离在 fork 子进程）。
                                    val parsed = meta
                                        ?: runCatching { DriverMetaParser.parse(context, download.filePath) }.getOrNull()
                                    driverMetas = driverMetas + (download.id to parsed)
                                    val type = parsed?.driverType()
                                        ?: DriverMetaParser.DriverType.UNKNOWN
                                    if (type == DriverMetaParser.DriverType.VENDOR_SYSTEM) {
                                        val payload = VulkanInfoPayload(
                                            success = false,
                                            errorCode = "VENDOR_SYSTEM_DRIVER",
                                            errorMessage = "厂商系统提取驱动（需 root/系统级安装），无法在 App 内检测，请在模拟器内加载验证",
                                            loader = "", instanceExtCount = 0, instanceExtensions = emptyList(),
                                            deviceCount = 0,
                                            devices = emptyList()
                                        )
                                        detectionResults = detectionResults + (download.id to payload)
                                    } else {
                                        testWithAdrenoTools(download.id, download.filePath)
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
                        if (meta.description.isNotBlank())
                            InfoRow("驱动说明", meta.description)
                        InfoRow(
                            "驱动类型",
                            when (meta.driverType()) {
                                DriverMetaParser.DriverType.VENDOR_SYSTEM -> "厂商系统提取（需 root/系统级安装，App 内不可加载）"
                                DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> "AdrenoTools 打包（模拟器注入加载，App 内以 fork 隔离实测）"
                                else -> "未声明/其他（App 内按通用路径尝试）"
                            }
                        )
                    }

                    // ── AdrenoTools 实测结果（M4）：实例/设备扩展、设备属性、兼容、跑分 ──
                    // 加载路径与 Winlator/GameHub/Eden 一致，是当前最接近模拟器内真实
                    // 行为的 App 内驱动验证方式。
                    val adtTest = adrenotoolsResults[download.id]
                    if (adtTest != null) {
                        Spacer(Modifier.height(10.dp))
                        Divider()
                        Spacer(Modifier.height(6.dp))
                        val (adtCollect, adtBench) = adtTest
                        if (adtCollect.success) {
                            Text(
                                "AdrenoTools 实测",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            InfoRow("实例扩展", "${adtCollect.instanceExtCount} 个")
                            val dev = adtCollect.devices.firstOrNull()
                            if (dev != null) {
                                InfoRow("设备", dev.deviceName)
                                InfoRow("API 版本", dev.apiVersion)
                                InfoRow("驱动版本", dev.driverVersion)
                                InfoRow("设备扩展", "${dev.extensions.size} 个")
                                // 关键扩展缺失提示：DXVK/VKD3D 转译依赖的关键扩展逐项对照
                                val extNames = dev.extensions.map { it.name }
                                val missingCritical = CRITICAL_DXVK_EXTS.filter { it !in extNames }
                                if (missingCritical.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "缺失关键扩展 ${missingCritical.size}/${CRITICAL_DXVK_EXTS.size}（影响转译层功能）",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                missingCritical.joinToString("、"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                // 兼容徽章：驱动面向 GPU 厂商 vs 当前设备 GPU 厂商
                                val sysVendor = systemDevice?.vendorId
                                val compat = when (dev.vendorId) {
                                    sysVendor -> "与当前设备 GPU 同厂商（预计可加载）"
                                    else -> "驱动面向 ${vendorName(dev.vendorId)}，本机为 ${vendorName(sysVendor)}，加载结果以实测为准"
                                }
                                InfoRow("GPU 兼容", compat)
                            if (adtBench.success) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "综合 ${adtBench.totalScore}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(
                                        "带宽 ${"%.1f".format(adtBench.fillBandwidthGBs)} GB/s",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "拷贝 ${"%.1f".format(adtBench.copyBandwidthGBs)} GB/s",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    "跑分失败：${adtBench.errorCode ?: "?"} ${adtBench.errorMessage ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            // ── 渲染测试（vktest 同款）：离屏渲染三角形 60 帧测 FPS ──
                            val renderRes = renderResults[download.id]
                            if (renderRes != null) {
                                Spacer(Modifier.height(6.dp))
                                if (renderRes.optBoolean("success")) {
                                    val mode = renderRes.optString("mode", "graphics")
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            "渲染测试 ${"%.1f".format(renderRes.optDouble("fps", 0.0))} FPS",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                        )
                                        val avgMs = renderRes.optDouble("avgFrameMs", 0.0)
                                        if (avgMs > 0.0) {
                                            Text(
                                                "帧耗时 ${"%.2f".format(avgMs)} ms",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        val frames = renderRes.optInt("frameCount", -1)
                                        if (frames > 0) {
                                            Text("${frames} 帧", style = MaterialTheme.typography.bodySmall)
                                        }
                                        val gbs = renderRes.optDouble("copyBandwidthGBs", 0.0)
                                        if (gbs > 0.0) {
                                            Text(
                                                "拷贝 ${"%.1f".format(gbs)} GB/s",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (mode == "copy-render") {
                                        Text(
                                            "兼容模式：fill+copy 渲染链验证（turnip graphics pipeline 编译崩溃，已绕开），读回校验 " +
                                                (if (renderRes.optBoolean("readbackOk", false)) "通过" else "失败"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            "离屏渲染 256×256 彩色三角形（vktest 同款图形管线，每帧同步提交）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    var renderErrExpanded by remember { mutableStateOf(false) }
                                    val renderErrMsg = renderRes.optString("errorMessage", "")
                                    Text(
                                        "渲染测试失败：${renderRes.optString("errorCode", "?")} $renderErrMsg",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = if (renderErrExpanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { renderErrExpanded = !renderErrExpanded }
                                    )
                                    if (!renderErrExpanded && renderErrMsg.length > 48) {
                                        Text(
                                            "点击展开完整内容",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { renderErrExpanded = true }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // 游戏支持评估：按实测驱动类型分级（独立于跑分，实测成功即显示）
                            val evalDriverType = when (dev.vendorId) {
                                0x5143 -> "turnip"
                                0x13B5 -> "panvk"
                                else -> "system"
                            }
                            val evals = assessGameSupport(
                                driverType = evalDriverType,
                                isAdrenoDevice = (systemDevice?.vendorId ?: 0) == 0x5143,
                                isMaliDevice = (systemDevice?.vendorId ?: 0) == 0x13B5,
                                extensions = dev.extensions.map { it.name },
                                apiVersion = dev.apiVersion
                            )
                            Column(Modifier.padding(top = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("游戏支持评估", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Text("基于驱动能力静态评估，非实测运行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                evals.forEach { e ->
                                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val color = when (e.level) {
                                            "基本能跑" -> Color(0xFF2E7D32)
                                            "可能能跑" -> Color(0xFFF9A825)
                                            else -> Color(0xFFC62828)
                                        }
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                                        Spacer(Modifier.width(6.dp))
                                        Text(e.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.weight(1f))
                                        Text(e.level, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    }
                                }
                                Text(
                                    evals.firstOrNull()?.note ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                        } else {
                            // 实测不可用：驱动加载失败多为驱动与当前 GPU 不匹配或系统限制
                            // （SELinux/kgsl），并非包损坏——包信息已展示，可到模拟器内验证。
                            val adtErr = adtCollect.errorCode ?: "?"
                            Text(
                                if (adtErr == "ADRENOTOOLS_OPEN_FAILED")
                                    "实测不可用：驱动加载失败（多为驱动与当前 GPU 不匹配或系统权限限制）。包信息已展示，可在对应模拟器内加载验证"
                                else
                                    "AdrenoTools 实测失败：$adtErr ${adtCollect.errorMessage ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                    "该驱动实测（${br.deviceName}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "fork 隔离直载驱动跑分；失败多为 SELinux 限制 GPU 节点访问",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            // 静态检查未确认 ICD 入口（自写解析器对 turnip 有误判史），
                            // 不拦截实测——native AdrenoTools 加载才是权威判断。
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
                                appString(R.string.benchmark_static_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                "ADRENOTOOLS_OPEN_FAILED" -> "实测不可用：驱动加载失败（多为驱动与当前 GPU 不匹配或系统权限限制）。包信息已展示，可在对应模拟器内加载验证"
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

                    // ── 双选实测对比（M4）：勾选本驱动参与 AdrenoTools 并排实测 ──
                    Spacer(Modifier.height(8.dp))
                    val inCompare = download.id in compareSelection
                    OutlinedButton(
                        onClick = {
                            if (inCompare) {
                                compareSelection.remove(download.id)
                            } else {
                                if (compareSelection.size >= 2) compareSelection.removeAt(0)
                                compareSelection.add(download.id)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = if (inCompare) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (inCompare) "已选对比（点击取消）" else "加入对比",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // ── AdrenoTools 双选实测对比（M4）：同机同条件并排比跑分/扩展 ──
        if (compareSelection.size == 2) {
            val idA = compareSelection[0]
            val idB = compareSelection[1]
            val dlA = downloadedDrivers.firstOrNull { it.id == idA }
            val dlB = downloadedDrivers.firstOrNull { it.id == idB }
            if (dlA != null && dlB != null) {
                val resA = adrenotoolsResults[idA]
                val resB = adrenotoolsResults[idB]
                val shortName: (String) -> String = { s ->
                    if (s.length > 22) s.take(22) + "…" else s
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "驱动对比",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "有实测结果时并排展示采集+跑分；实测不可用时回退 meta.json 静态对比",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        if (resA != null && resB != null) {
                            val (cA, bA) = resA
                            val (cB, bB) = resB
                            val dA = cA.devices.firstOrNull()
                            val dB = cB.devices.firstOrNull()
                            CompareRow("驱动", shortName(dlA.fileName), shortName(dlB.fileName))
                            if (bA.success && bB.success) {
                                CompareRow("综合跑分", "${bA.totalScore}", "${bB.totalScore}")
                                CompareRow("填充带宽 GB/s", "%.1f".format(bA.fillBandwidthGBs), "%.1f".format(bB.fillBandwidthGBs))
                                CompareRow("拷贝带宽 GB/s", "%.1f".format(bA.copyBandwidthGBs), "%.1f".format(bB.copyBandwidthGBs))
                            } else {
                                CompareRow(
                                    "跑分",
                                    if (bA.success) "OK" else (bA.errorCode ?: "失败"),
                                    if (bB.success) "OK" else (bB.errorCode ?: "失败")
                                )
                            }
                            CompareRow("实例扩展", "${cA.instanceExtCount}", "${cB.instanceExtCount}")
                            CompareRow("设备扩展", "${dA?.extensions?.size ?: 0}", "${dB?.extensions?.size ?: 0}")
                            CompareRow("关键扩展覆盖", "${"%.0f".format(criticalExtCoverage(dA?.extensions?.map { it.name } ?: emptyList()) * 100)}%", "${"%.0f".format(criticalExtCoverage(dB?.extensions?.map { it.name } ?: emptyList()) * 100)}%")
                            CompareRow("API 版本", dA?.apiVersion ?: "—", dB?.apiVersion ?: "—")
                            CompareRow("GPU", dA?.deviceName ?: "—", dB?.deviceName ?: "—")
                            val scoreA = bA.totalScore
                            val scoreB = bB.totalScore
                            if (scoreA > 0 || scoreB > 0) {
                                Spacer(Modifier.height(6.dp))
                                val winner = when {
                                    scoreA > scoreB -> shortName(dlA.fileName)
                                    scoreB > scoreA -> shortName(dlB.fileName)
                                    else -> "持平"
                                }
                                Text(
                                    "综合跑分更高：$winner",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        } else {
                            // ── meta.json 静态对比：实测未跑/失败时也有真实信息可比 ──
                            val metaA = driverMetas[idA]
                            val metaB = driverMetas[idB]
                            val typeLabel: (DriverMetaParser.DriverType) -> String = { t ->
                                when (t) {
                                    DriverMetaParser.DriverType.VENDOR_SYSTEM -> "厂商系统提取"
                                    DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> "AdrenoTools 打包"
                                    else -> "未声明/其他"
                                }
                            }
                            CompareRow("驱动", shortName(dlA.fileName), shortName(dlB.fileName))
                            if (metaA != null && metaB != null) {
                                CompareRow("名称", metaA.name, metaB.name)
                                CompareRow("包版本", metaA.packageVersion, metaB.packageVersion)
                                CompareRow("驱动版本", metaA.driverVersion, metaB.driverVersion)
                                CompareRow("厂商", metaA.vendor, metaB.vendor)
                                CompareRow("作者", metaA.author, metaB.author)
                                CompareRow("驱动类型", typeLabel(metaA.driverType()), typeLabel(metaB.driverType()))
                            } else {
                                Text(
                                    "驱动包 meta.json 信息待解析（点击下方按钮触发解析+实测）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            val running = adrenotoolsTestingId != null
                            Button(
                                onClick = {
                                    // 先补解析 meta（若缺），再依次实测
                                    if (metaA == null) {
                                        val parsedA = runCatching { DriverMetaParser.parse(context, dlA.filePath) }.getOrNull()
                                        if (parsedA != null) driverMetas = driverMetas + (idA to parsedA)
                                    }
                                    if (metaB == null) {
                                        val parsedB = runCatching { DriverMetaParser.parse(context, dlB.filePath) }.getOrNull()
                                        if (parsedB != null) driverMetas = driverMetas + (idB to parsedB)
                                    }
                                    testWithAdrenoTools(idA, dlA.filePath)
                                    testWithAdrenoTools(idB, dlB.filePath)
                                },
                                enabled = !running
                            ) {
                                if (running) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("实测中…", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("解析+实测对比", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "实测受 App 进程环境限制（SELinux/glibc），失败时以上 meta 静态对比仍可作为版本/作者/类型依据；性能请在模拟器内验证",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { compareSelection.clear() }) {
                            Text("清除选择", style = MaterialTheme.typography.bodySmall)
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

/** Vulkan 厂商 ID → 名称（用于驱动与设备兼容性对比）。 */
private fun vendorName(vendorId: Int?): String {
    return when (vendorId) {
        0x5143 -> "Qualcomm Adreno"
        0x13B5 -> "ARM Mali"
        0x1002 -> "AMD"
        0x10DE -> "NVIDIA"
        0x8086 -> "Intel"
        0x106B -> "Apple"
        0x1AE0 -> "Google"
        else -> "未知 (0x${vendorId?.toString(16) ?: "?"})"
    }
}

/** Vulkan 关键特性（Vulkan 1.2/1.3 高价值项）中文说明。普通档+root 档共用。 */
private val VULKAN_KEY_FEATURES: List<Pair<String, String>> = listOf(
    "dynamicRendering" to "动态渲染（现代管线首选，DXVK 9+/Zink 依赖）",
    "timelineSemaphore" to "时间线信号量（VKD3D-Proton 常用）",
    "descriptorIndexing" to "描述符索引（现代渲染必需）",
    "synchronization2" to "同步2 API（新驱动首选同步方式）",
    "bufferDeviceAddress" to "缓冲设备地址（VKD3D/光追相关）",
    "shaderFloat16" to "FP16 着色器（DXVK 性能优化项）",
    "pipelineCreationCacheControl" to "管线缓存控制（重复构建提速）",
    "inlineUniformBlock" to "内联统一块（UBO 访问加速）"
)

/** 关键特性徽章行：✓ 高亮 / ✗ 灰显，右侧附用途说明。 */
@Composable
private fun VulkanKeyFeatureRow(enabled: Boolean, label: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                if (enabled) "✓" else "✗",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 关键扩展覆盖矩阵：DXVK/VKD3D 转译依赖的关键扩展命中情况。 */
@Composable
private fun VulkanKeyExtensionRow(name: String, present: Boolean, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                if (present) "✓" else "✗",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (present) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 关键扩展用途说明（DXVK/VKD3D 视角）。 */
private val VULKAN_KEY_EXT_HINTS = mapOf(
    "VK_KHR_swapchain" to "交换链：窗口呈现核心",
    "VK_KHR_maintenance1" to "命令缓冲维护（基础兼容）",
    "VK_KHR_maintenance2" to "维护2（几何着色器点大小等）",
    "VK_KHR_maintenance3" to "维护3（描述符集上限）",
    "VK_KHR_timeline_semaphore" to "时间线信号量（VKD3D 依赖）",
    "VK_KHR_dynamic_rendering" to "动态渲染（DXVK 9+ 依赖）",
    "VK_EXT_memory_budget" to "内存预算（进程内存管理）",
    "VK_KHR_descriptor_indexing" to "描述符索引（现代绑定）",
    "VK_KHR_16bit_storage" to "16 位存储（着色器优化）"
)

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
                    mapVendorDisplayName(deviceInfo.gpuRenderer.ifBlank { appString(R.string.unknown_gpu) }),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2
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

/** 驱动更新动态条目：源名 + 家族 + 最新 tag + 时间 + 摘要 + zip 文件名 */
private data class DriverNewsItem(
    val sourceName: String,
    val family: String,
    val tag: String,
    val date: String,
    val body: String,
    val zipName: String,
    val apiUrl: String
)

/** 驱动更新动态卡：全源最新 release 汇总（折叠，默认展示最近 6 条） */
@Composable
private fun DriverNewsCard(
    items: List<DriverNewsItem>,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<DriverNewsItem?>(null) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.driver_news_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = appString(R.string.refresh),
                        modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            } else if (items.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    appString(R.string.driver_news_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val shown = if (expanded) items else items.take(6)
                Spacer(Modifier.height(4.dp))
                shown.forEach { item ->
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { detailItem = item }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ownerAvatar = githubOwnerAvatar(item.apiUrl)
                        if (ownerAvatar != null) {
                            AsyncImage(
                                model = ownerAvatar,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when (item.family) {
                                        "panvk" -> Color(0xFF1E88E5).copy(alpha = 0.15f)
                                        "qualcomm" -> Color(0xFFF4511E).copy(alpha = 0.15f)
                                        else -> Color(0xFF43A047).copy(alpha = 0.15f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                when (item.family) {
                                    "panvk" -> appString(R.string.driver_family_panvk)
                                    "qualcomm" -> appString(R.string.driver_family_qualcomm)
                                    else -> appString(R.string.driver_family_turnip)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (item.family) {
                                    "panvk" -> Color(0xFF1E88E5)
                                    "qualcomm" -> Color(0xFFF4511E)
                                    else -> Color(0xFF43A047)
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${item.sourceName} · ${item.tag}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (item.zipName.isNotBlank()) {
                                Text(
                                    item.zipName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatRelativeTime(item.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (item != shown.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (!expanded && items.size > 6) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.driver_news_more, items.size - 6),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expanded = true }
                    )
                }
            }
        }
    }
    detailItem?.let { item ->
        DriverReleaseDialog(item = item, onDismiss = { detailItem = null })
    }
}

/** 驱动 release 日志弹窗（近全屏）：release 标题 + 日期 + 完整更新日志。
 *  日志英文时点"翻译"自动译成中文，可随时切回原文；"查看原文"跳 GitHub release 页。 */
@Composable
private fun DriverReleaseDialog(item: DriverNewsItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translated by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    val baseText = item.body.trim()
    val showText = translated ?: baseText
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${item.sourceName} · ${item.tag}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                    }
                }
                Text(
                    formatRelativeTime(item.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showText.isBlank()) {
                        Text(
                            appString(R.string.driver_news_no_log),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(showText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (baseText.isNotBlank()) {
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
                    }
                    OutlinedButton(onClick = { openNewsLink(context, item.apiUrl) }) {
                        Text(appString(R.string.view_original))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(appString(R.string.close)) }
                }
            }
        }
    }
}
/** 驱动对比入口卡：汇总可用驱动来源数，进入双选对比弹窗 */
@Composable
private fun DriverCompareEntryCard(driverCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    appString(R.string.driver_compare_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    appString(R.string.driver_compare_subtitle, driverCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class DriverCompareCandidate(
    val sourceId: String,
    val name: String,
    val type: String,
    val description: String,
    val experimental: Boolean,
    val supportedSeries: String,
    val targetRuntime: String,
    val stars: String
)

/** 驱动对比弹窗：候选双选 → 类型/版本/时间/支持 GPU/平台矩阵对比 */
@Composable
private fun DriverCompareDialog(
    turnipSources: List<TurnipSource>,
    panvkSources: List<TurnipSource>,
    qualcommSources: List<QualcommSource>,
    adrenoSeries: String,
    onDismiss: () -> Unit
) {
    val candidates = remember(turnipSources, panvkSources, qualcommSources) {
        buildList {
            turnipSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "Turnip",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.supportedSeries.joinToString("、").ifBlank { "Adreno 全系" },
                        targetRuntime = s.targetEmulator.ifBlank { "通用" }, stars = ""
                    )
                )
            }
            panvkSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "PanVK",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.supportedSeries.joinToString("、").ifBlank { "Mali 全系" },
                        targetRuntime = s.targetEmulator.ifBlank { "通用 (Mali)" }, stars = ""
                    )
                )
            }
            qualcommSources.forEach { s ->
                add(
                    DriverCompareCandidate(
                        sourceId = s.id, name = s.name, type = "Qualcomm",
                        description = s.description, experimental = s.experimental,
                        supportedSeries = s.filters.joinToString("、").ifBlank { "高通全系" },
                        targetRuntime = "系统级 / root", stars = if (s.stars > 0) "$s.stars ⭐" else ""
                    )
                )
            }
        }.sortedBy { it.name }
    }

    // 各源最新 release 的 tag + 发布时间（fetchXxxReleases 有缓存，打开时拉一次）
    val latestBySource = remember { mutableStateMapOf<String, Pair<String, String>>() }
    LaunchedEffect(candidates) {
        candidates.forEach { c ->
            val rel = when (c.type) {
                "Qualcomm" -> qualcommSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchQualcommReleases(it) }.getOrNull() }
                "PanVK" -> panvkSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchTurnipReleases(it, "default") }.getOrNull() }
                else -> turnipSources.firstOrNull { it.id == c.sourceId }
                    ?.let { runCatching { fetchTurnipReleases(it, adrenoSeries) }.getOrNull() }
            }
            val first = rel?.firstOrNull()
            latestBySource[c.sourceId] = if (first != null) {
                first.tagName to formatRelativeTime(first.publishedAt)
            } else {
                "" to ""
            }
        }
    }

    val selectedIds = remember { mutableStateListOf<String>() }
    val selected = selectedIds.mapNotNull { id -> candidates.firstOrNull { it.sourceId == id } }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 660.dp)
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(appString(R.string.driver_compare_title), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    appString(R.string.driver_compare_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                if (selected.size == 2) {
                    // ── 对比矩阵 ──
                    val a = selected[0]
                    val b = selected[1]
                    val latestA = latestBySource[a.sourceId] ?: ("" to "")
                    val latestB = latestBySource[b.sourceId] ?: ("" to "")
                    CompareRow(appString(R.string.driver_cmp_name), a.name, b.name)
                    CompareRow(appString(R.string.driver_cmp_type), a.type, b.type)
                    CompareRow(appString(R.string.driver_cmp_latest), latestA.first.ifBlank { "—" }, latestB.first.ifBlank { "—" })
                    CompareRow(appString(R.string.driver_cmp_updated), latestA.second.ifBlank { "—" }, latestB.second.ifBlank { "—" })
                    CompareRow(appString(R.string.driver_cmp_gpu), a.supportedSeries, b.supportedSeries)
                    CompareRow(appString(R.string.driver_cmp_platform), a.targetRuntime, b.targetRuntime)
                    CompareRow(
                        appString(R.string.driver_cmp_flag),
                        if (a.experimental) appString(R.string.experimental_tag) else appString(R.string.recommended_tag),
                        if (b.experimental) appString(R.string.experimental_tag) else appString(R.string.recommended_tag)
                    )
                    CompareRow(appString(R.string.driver_cmp_desc), a.description, b.description)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selectedIds.clear() }) {
                            Text(appString(R.string.driver_cmp_reselect))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(appString(R.string.close))
                        }
                    }
                } else {
                    // ── 候选选择（最多两个）──
                    candidates.forEach { c ->
                        val latest = latestBySource[c.sourceId] ?: ("" to "")
                        val checked = c.sourceId in selectedIds
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            onClick = {
                                when {
                                    checked -> selectedIds.remove(c.sourceId)
                                    selectedIds.size < 2 -> selectedIds.add(c.sourceId)
                                }
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        when {
                                            !on -> selectedIds.remove(c.sourceId)
                                            selectedIds.size < 2 -> selectedIds.add(c.sourceId)
                                        }
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                c.type,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        buildString {
                                            if (latest.first.isNotBlank()) append("${latest.first} · ")
                                            if (latest.second.isNotBlank()) append(latest.second)
                                            if (c.stars.isNotBlank()) append(" · ${c.stars}")
                                        }.ifBlank { c.targetRuntime },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(appString(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareRow(label: String, a: String, b: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    a,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Card(
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    b,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
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
    panvkSourceId: String,
    panvkSources: List<TurnipSource>,
    panvkReleases: List<GithubRelease>,
    qualcommSourceId: String,
    qualcommSources: List<QualcommSource>,
    qualcommReleases: List<GithubRelease>,
    xclipseSourceId: String,
    xclipseSources: List<TurnipSource>,
    xclipseReleases: List<GithubRelease>,
    wrapperSourceId: String,
    wrapperSources: List<TurnipSource>,
    wrapperReleases: List<GithubRelease>,
    wrapperItems: List<WrapperItem> = emptyList(),
    onTurnipSourceChange: (String) -> Unit,
    onPanvkSourceChange: (String) -> Unit,
    onQualcommSourceChange: (String) -> Unit,
    onXclipseSourceChange: (String) -> Unit,
    onWrapperSourceChange: (String) -> Unit,
    onDownloadAsset: (GithubRelease, GithubAsset) -> Unit,
    driverSourceUpdatedAt: Map<String, Long> = emptyMap(),
    driverSourceStars: Map<String, Long> = emptyMap(),
    pendingRepo: String? = null,
    onPendingRepoConsumed: () -> Unit = {}
) {
    // 高通提取驱动分区：有数据即展示（不因 8xx 等系列隐藏——高通官方提取
    // 驱动同样适用于最新 Adreno，用户应能看到全量可选项）
    val showQualcomm = qualcommReleases.isNotEmpty()

    // 按设备匹配驱动：开启时只显示与当前 GPU 厂商兼容的分区
    // （骁龙用户默认不显示 PanVK Mali 分区；Mali 用户默认不显示 Turnip/高通分区），
    // 关闭该功能后展示全部分区。防止无关分区干扰推荐，符合"按设备检测推荐"。
    val matchByDevice = remember { SettingsManager.getMatchDriversByDevice() }
    val gpuVendor = deviceInfo?.gpuVendor
    val isAdreno = gpuVendor == GpuVendor.ADRENO
    val isMali = gpuVendor == GpuVendor.MALI
    val showTurnipSection = !matchByDevice || !isMali
    val showPanvkSection = !matchByDevice || isMali
    val showQualcommSection = !matchByDevice || !isMali
    // Xclipse 只对三星 Exynos(RDNA) 设备有意义：按设备匹配时仅 Xclipse 设备显示
    val isXclipse = gpuVendor == GpuVendor.XCLIPSE
    val showXclipseSection = !matchByDevice || isXclipse
    // Wrapper 通用性强（wmali→Mali、compat/gamenative→通用），始终显示
    val showWrapperSection = true

    // 驱动局部搜索：按源名称/简介过滤三个区块
    var driverQuery by remember { mutableStateOf("") }
    val q = driverQuery.trim()
    val filteredTurnip = remember(turnipSources, q) {
        if (q.isBlank()) turnipSources
        else turnipSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredPanvk = remember(panvkSources, q) {
        if (q.isBlank()) panvkSources
        else panvkSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredQualcomm = remember(qualcommSources, q) {
        if (q.isBlank()) qualcommSources
        else qualcommSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredXclipse = remember(xclipseSources, q) {
        if (q.isBlank()) xclipseSources
        else xclipseSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }
    val filteredWrapper = remember(wrapperSources, q) {
        if (q.isBlank()) wrapperSources
        else wrapperSources.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    }

    // I1：切换来源后滚动定位到对应分区并自动展开其文件列表
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expandTrigger by remember { mutableStateOf<String?>(null) }
    fun sectionIndex(target: Int): Int { // 0=turnip 1=panvk 2=qualcomm 3=xclipse 4=wrapper
        var idx = 3
        if (deviceInfo != null) idx++
        if (isLoading && turnipReleases.isEmpty() && qualcommReleases.isEmpty()) idx++
        var off = 0
        if (target > 0 && showTurnipSection && filteredTurnip.isNotEmpty()) off++
        if (target > 1 && showPanvkSection && filteredPanvk.isNotEmpty()) off++
        if (target > 2 && showQualcommSection && filteredQualcomm.isNotEmpty()) off++
        if (target > 3 && showXclipseSection && filteredXclipse.isNotEmpty()) off++
        if (target > 4 && showWrapperSection && filteredWrapper.isNotEmpty()) off++
        return idx + off
    }

    // 设备页推荐卡「查看驱动文件」→ 文件级跳转：切来源 + 展开 + 滚动到对应分区
    val pendingContext = LocalContext.current
    LaunchedEffect(pendingRepo) {
        val target = pendingRepo ?: return@LaunchedEffect
        val entry = DriverRegistryRepository.loadFromAssets(pendingContext)
            ?.firstOrNull { it.id == target }
        if (entry != null) {
            val prefix = when {
                entry.driverType == "qualcomm" -> "qualcomm"
                entry.gpuVendor == "mali" -> "panvk"
                entry.gpuVendor == "xclipse" -> "xclipse"
                entry.driverType == "wrapper" -> "wrapper"
                else -> "turnip"
            }
            val sectionIdx = when (prefix) {
                "qualcomm" -> 2
                "xclipse" -> 3
                "wrapper" -> 4
                "panvk" -> 1
                else -> 0
            }
            val hasTarget = when (prefix) {
                "turnip" -> filteredTurnip.any { it.id == target }
                "panvk" -> filteredPanvk.any { it.id == target }
                "qualcomm" -> filteredQualcomm.any { it.id == target }
                "xclipse" -> filteredXclipse.any { it.id == target }
                else -> filteredWrapper.any { it.id == target }
            }
            if (hasTarget) {
                val current = when (prefix) {
                    "turnip" -> turnipSourceId
                    "panvk" -> panvkSourceId
                    "qualcomm" -> qualcommSourceId
                    "xclipse" -> xclipseSourceId
                    else -> wrapperSourceId
                }
                if (current != target) {
                    when (prefix) {
                        "turnip" -> onTurnipSourceChange(target)
                        "panvk" -> onPanvkSourceChange(target)
                        "qualcomm" -> onQualcommSourceChange(target)
                        "xclipse" -> onXclipseSourceChange(target)
                        else -> onWrapperSourceChange(target)
                    }
                }
            }
            expandTrigger = "$prefix:$target"
            scope.launch { listState.animateScrollToItem(sectionIndex(sectionIdx)) }
        }
        onPendingRepoConsumed()
    }

    // 头部文案在 Composable 上下文取好（LazyColumn content 内不能调 appString）
    val headerTitle = appString(R.string.driver_region)
    val headerHint = appString(R.string.driver_search_hint)

    // 五类驱动分开展示：原来 5 个区块全竖排，一屏只够看一个源，找另一个系列要滑很久。
    // 0 全部 / 1 Turnip / 2 高通 / 3 PanVK / 4 Xclipse / 5 Wrapper
    var driverTab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
    SectionTabs(
        tabs = listOf(
            appString(R.string.driver_tab_all),
            "Turnip",
            appString(R.string.qualcomm_driver),
            "PanVK",
            "Xclipse",
            "Wrapper",
        ),
        selected = driverTab,
        onSelect = { driverTab = it },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // 模板化头部（排版规格 v1）：标题 → 搜索 → 结果计数，控件不超过三层。
        // 「N 个驱动源」是搜索/过滤是否生效的唯一反馈，缺了它用户只能靠数卡片。
        // 文案在页面级取好：LazyColumn 的 content 不是 Composable 上下文，里面不能调 appString。
        listPageHeader(
            title = headerTitle,
            icon = Icons.Default.Memory,
            query = driverQuery,
            onQueryChange = { v ->
                driverQuery = v
                // 搜索词精确匹配源名 → 自动切换该源并加载其版本列表
                // （修复"搜索后仅该来源但可下载文件不刷新"：仅过滤源列表不切换源的问题）
                val key = v.trim()
                if (key.isNotBlank()) {
                    turnipSources.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let {
                        onTurnipSourceChange(it.id)
                    }
                }
            },
            headerContext = if (deviceInfo != null) {
                { DriverDeviceSummaryCard(deviceInfo = deviceInfo) }
            } else null,
            searchPlaceholder = headerHint,
            // 一条源都没有时不显示「0 个驱动源」——下面已有专门的空态卡，重复说两次没有意义
            resultCount = (filteredTurnip.size + filteredPanvk.size + filteredQualcomm.size +
                filteredXclipse.size + filteredWrapper.size).takeIf { it > 0 },
            resultLabel = { appString(R.string.driver_result_count, it) },
        )

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
        if (filteredTurnip.isNotEmpty() && showTurnipSection && (driverTab == 0 || driverTab == 1)) {
            item(key = "turnip_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredTurnip,
                    currentSourceId = turnipSourceId,
                    onSourceChange = { id ->
                        onTurnipSourceChange(id)
                        expandTrigger = "turnip:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = turnipReleases,
                    selectionKey = "turnip:$turnipSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("turnip:") }
                )
            }
        }

        // ── PanVK 驱动专区：Mesa Panfrost 开源 Mali 驱动独立区块 ──
        if (filteredPanvk.isNotEmpty() && showPanvkSection && (driverTab == 0 || driverTab == 3)) {
            item(key = "panvk_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredPanvk,
                    currentSourceId = panvkSourceId,
                    onSourceChange = { id ->
                        onPanvkSourceChange(id)
                        expandTrigger = "panvk:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = panvkReleases,
                    selectionKey = "panvk:$panvkSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("panvk:") },
                    title = "PanVK 驱动专区",
                    description = "Mesa Panfrost 开源 Mali 驱动（G57–G720 全系），在 Winlator/Eden 等模拟器中为 Mali 设备提供 Vulkan 支持",
                    downloadLabel = appString(R.string.download_panvk)
                )
            }
        }

        if (showQualcomm && showQualcommSection && filteredQualcomm.isNotEmpty() && (driverTab == 0 || driverTab == 2)) {
            item(key = "qualcomm_section") {
                DriverCardDynamic(
                    title = appString(R.string.qualcomm_driver),
                    description = appString(R.string.qualcomm_driver_desc),
                    icon = Icons.Default.Memory,
                    sources = filteredQualcomm,
                    currentSourceId = qualcommSourceId,
                    onSourceChange = { id ->
                        onQualcommSourceChange(id)
                        expandTrigger = "qualcomm:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = qualcommReleases,
                    selectionKey = "qualcomm:$qualcommSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("qualcomm:") }
                )
            }
        }

        // ── 三星 Xclipse 驱动专区：Exynos RDNA GPU（Exynos 2200/2400）独立区块 ──
        if (filteredXclipse.isNotEmpty() && showXclipseSection && (driverTab == 0 || driverTab == 4)) {
            item(key = "xclipse_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredXclipse,
                    currentSourceId = xclipseSourceId,
                    onSourceChange = { id ->
                        onXclipseSourceChange(id)
                        expandTrigger = "xclipse:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = xclipseReleases,
                    selectionKey = "xclipse:$xclipseSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("xclipse:") },
                    title = "三星 Xclipse 驱动专区",
                    description = "Mesa RADV / WinXclipse 驱动，为三星 Exynos（RDNA Xclipse GPU）在 Winlator 等模拟器提供 Vulkan 支持",
                    downloadLabel = appString(R.string.download_xclipse)
                )
            }
        }

        // ── 驱动包装器专区：Vulkan/GL 包装层（wmali、compat-bcn、gamenative 等）──
        // Wrapper 专区：三源（banner/fristoner/winx）单区块并列，源切换弹窗可选。
        // banner 源为 wrappers.json 清单型：展开区显示清单卡（集成在 TurnipDriverSection
        // 的 catalogItems 分支），不占满页面；fristoner/winx 为 release 型展开显示版本卡。
        if (filteredWrapper.isNotEmpty() && showWrapperSection && (driverTab == 0 || driverTab == 5)) {
            item(key = "wrapper_section") {
                TurnipDriverSection(
                    adrenoSeries = deviceInfo?.adrenoSeries,
                    sources = filteredWrapper,
                    currentSourceId = wrapperSourceId,
                    onSourceChange = { id ->
                        onWrapperSourceChange(id)
                        expandTrigger = "wrapper:$id"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    releases = wrapperReleases,
                    selectionKey = "wrapper:$wrapperSourceId",
                    onDownload = onDownloadAsset,
                    updatedAtMap = driverSourceUpdatedAt,
                    remoteStars = driverSourceStars,
                    expandTrigger = expandTrigger?.takeIf { it.startsWith("wrapper:") },
                    title = "Wrapper",
                    description = "Vulkan/GL 包装层与兼容层（wmali-wrapper、compat-bcn、gamenative、FristOneRR、WinXclipse 等），配合对应驱动使用提升兼容性",
                    downloadLabel = appString(R.string.download_wrapper),
                    catalogItems = wrapperItems,
                    onCatalogDownload = { item ->
                        val fileName = item.url.substringAfterLast('/')
                        onDownloadAsset(
                            GithubRelease(
                                tagName = item.id,
                                name = item.name,
                                assets = listOf(
                                    GithubAsset(
                                        name = fileName,
                                        downloadUrl = item.url,
                                        sizeBytes = item.fileSizeBytes
                                    )
                                ),
                                publishedAt = "",
                                body = item.description
                            ),
                            GithubAsset(
                                name = fileName,
                                downloadUrl = item.url,
                                sizeBytes = item.fileSizeBytes
                            )
                        )
                    }
                )
            }
        }

        if (q.isNotBlank() && filteredTurnip.isEmpty() && filteredPanvk.isEmpty() && filteredQualcomm.isEmpty()) {
            item(key = "driver_search_empty") {
                Text(
                    appString(R.string.search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
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
    onDownloadComponent: (Component) -> Unit,
    onOpenGuide: () -> Unit = {}
) {
    val preferredComponentOrder = listOf("Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D", "D7VK")
    // 组件资讯：component 类新闻（DXVK/VKD3D 等转换层上游）
    var componentNewsItems by remember { mutableStateOf<List<NewsItem>?>(null) }
    val componentNewsContext = LocalContext.current
    LaunchedEffect(Unit) {
        componentNewsItems = NewsRepository.loadNews(componentNewsContext)
    }
    // Turnip 组件（Rutabaga 等 WCP 构建）已移至驱动专区 Turnip 分区展示，组件区不再显示该类型
    val discoveredComponentTypes = componentCatalogs.values
        .flatMap { it.keys }
        .distinct()
        .filterNot { it in preferredComponentOrder || it.equals("Turnip", ignoreCase = true) }
        .sorted()
    val componentOrder = preferredComponentOrder + discoveredComponentTypes
    val componentSourceSelections = remember { mutableStateMapOf<String, String>() }
    var newsExpanded by remember { mutableStateOf(false) }
    var bundleExpanded by remember { mutableStateOf(false) }

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

    // LazyListScope 不是 @Composable 上下文，文案必须先在函数体取好
    val guideLabel = appString(R.string.component_guide)
    val componentRegionTitle = appString(R.string.component_region)
    val bundleTitle = appString(R.string.source_bundle_title)

    // 子分区：0 组件 / 1 资讯
    var componentTab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
    SectionTabs(
        tabs = listOf(appString(R.string.component_region), appString(R.string.tab_news_short)),
        selected = componentTab,
        onSelect = { componentTab = it },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        listPageHeader(
            title = componentRegionTitle,
            trailing = {
                IconButton(onClick = onOpenGuide) {
                    Icon(Icons.Default.Info, contentDescription = guideLabel)
                }
            },
        )

        // R5：来源级捆绑——次要功能，默认收起，别把组件分类挤出首屏
        item(key = "component_bundle_collapse") {
            CollapseRow(
                title = bundleTitle,
                expanded = bundleExpanded,
                onToggle = { bundleExpanded = !bundleExpanded },
            )
        }
        if (bundleExpanded) {
            item(key = "component_bundle_region") {
                ComponentBundleSection(sources = componentSources)
            }
        }


        var hasAnyComponent = false
        if (componentTab == 0) componentOrder.forEach { type ->
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

        if (componentTab == 1) {
            item(key = "component_news") {
                NewsZoneCard(
                    title = appString(R.string.news_component_title),
                    items = componentNewsItems,
                    category = "component"
                )
            }
        }

        if (componentTab == 0) item(key = "footer") {
            Text(
                text = appString(R.string.versions_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
    // 重试令牌：LaunchedEffect 的 key。原来重试只改状态、不换 key，
    // 副作用不会重跑，点"重试"后永远停在转圈。
    var reloadToken by remember { mutableStateOf(0) }
    // 运行库资讯：runtime 类新闻（Wine/Box64/Proton 运行时）
    var runtimeNewsItems by remember { mutableStateOf<List<NewsItem>?>(null) }
    val runtimeNewsContext = LocalContext.current
    LaunchedEffect(Unit) {
        runtimeNewsItems = NewsRepository.loadNews(runtimeNewsContext)
    }
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

    LaunchedEffect(reloadToken) {
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
    // 子分区：0 全部运行库 / 1 资讯。资讯原来挂在页尾折叠区（最后一屏 + 还要再点一次）
    var runtimeTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
    SectionTabs(
        tabs = listOf(appString(R.string.runtime_tab_all), appString(R.string.tab_news_short)),
        selected = runtimeTab,
        onSelect = { runtimeTab = it },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitleRow(
            title = appString(R.string.runtime_title),
            subtitle = appString(R.string.runtime_subtitle),
            trailing = {
                IconButton(onClick = { showGuide = true }) {
                    Icon(Icons.Default.Info, contentDescription = appString(R.string.runtime_guide_title))
                }
            },
        )

        if (runtimeTab == 0) {
        // 搜索框
        FusionSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = appString(R.string.runtime_search_hint)
        )

        // 加载中不报数：0 会被读成「一个都没有」
        if (!isLoading && !loadError) {
            ResultCountRow(
                count = filtered.size,
                label = { appString(R.string.runtime_result_count, it) },
            )
        }

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
                // 换 key 触发上面那个 LaunchedEffect 真正重新拉一次
                reloadToken++
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

        // 资讯与来源声明：独立子分区
        if (runtimeTab == 1) {
            // 运行库资讯：Wine/Box64/Proton 运行时新闻
            NewsZoneCard(
                title = appString(R.string.news_runtime_title),
                items = runtimeNewsItems,
                category = "runtime"
            )

            // 数据来源与版权免责声明
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = appString(R.string.runtime_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                    if (component.sourceInfo.url.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "来源·${component.sourceInfo.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(component.sourceInfo.url))
                                    )
                                }
                            }
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
                // 社区清单基本不带摘要：明确标注「没校验」而不是让用户误以为已校验
                if (!component.isBundle && component.downloadUrl.isNotBlank() &&
                    component.checksum.isBlank()
                ) {
                    Text(
                        appString(R.string.runtime_checksum_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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
private val EMULATOR_TOPIC_NOISE = setOf(
    "android", "emulator", "emulators", "emulation", "windows", "linux", "macos",
    "ios", "java", "kotlin", "cpp", "c", "python", "rust", "open-source",
    "mit-license", "gpl-3.0", "game", "games", "gaming", "app", "tool", "tools"
)

/** 平台标签：数据里是 ps2 / switch 这种 key，界面要给中文 */
private fun emulatorPlatformLabel(platform: String): String = when (platform) {
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

private fun emulatorCategoryLabel(category: String): String = when (category) {
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
private fun openNewsLink(context: Context, url: String) {
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
private fun NewsRowItem(item: NewsItem) {
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
                        "· ${item.commentCount}评",
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
                            val shownBody = remember(item.id, translated, baseText) {
                                if (translated != null) translated!!.trim()
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

/** 新闻列表弹窗（近全屏）：某分区的完整新闻列表。
 *  类型筛选 chips（官方组件/运行库）与分页（8/页）都在此弹窗内，
 *  分区卡片本体只保留 3 条预览，避免占用页面空间。 */
@Composable
private fun NewsListDialog(
    title: String,
    items: List<NewsItem>,
    category: String,
    onDismiss: () -> Unit
) {
    val catItemsAll = items.filter { newsMatches(it, category) }
    // 标签筛选：官方组件的 type（Wine / Proton / Turnip）与资讯的 tag（芯片 / 系统 / 手机 / AI）
    // 合并成一排，按出现条数从多到少取前 12 个——这就是「各标签的分类页」入口。
    // 原来只有驱动区能按 type 筛，科技资讯只能从头翻到尾。
    val tagOptions = remember(items, category) {
        catItemsAll
            .flatMap { listOf(it.type, it.tag) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(12)
    }
    var selectedTag by remember(category) { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    val catItems = if (selectedTag.isNotBlank()) {
        catItemsAll.filter { it.type == selectedTag || it.tag == selectedTag }
    } else catItemsAll
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = appString(R.string.cancel))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (tagOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (listOf("") + tagOptions).forEach { t ->
                            val label = if (t.isBlank()) appString(R.string.news_type_all) else t
                            val selected = t == selectedTag
                            SuggestionChip(
                                onClick = { selectedTag = t; page = 0 },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    labelColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (catItems.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.news_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val pageSize = 8
                    val maxPage = ((catItems.size + pageSize - 1) / pageSize).coerceAtMost(20)
                    val current = page.coerceIn(0, maxPage - 1)
                    val from = current * pageSize
                    val to = ((current + 1) * pageSize).coerceAtMost(catItems.size)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(catItems.subList(from, to), key = { it.id }) { item ->
                            NewsRowItem(item)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                    NewsPager(total = catItems.size, page = current, pageSize = pageSize, onPage = { page = it })
                }
            }
        }
    }
}

/** 分页控制条：首页 / 上一页 / 页码 / 下一页（每页 pageSize，最大 20 页封顶） */
@Composable
private fun NewsPager(
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
private fun isRuntimeType(type: String): Boolean {
    val t = type.lowercase()
    return t.contains("dxvk") || t.contains("vkd3d") || t.contains("d7vk") ||
        t.contains("mono") || t.contains("gecko")
}

private fun newsMatches(item: NewsItem, category: String): Boolean = when (category) {
    "runtime" -> item.category == "component" && isRuntimeType(item.type)
    "eco" -> item.category == "device" || item.category == "tech"
    else -> item.category == category
}

/** 分区新闻卡：按固定分类展示资讯新闻（无筛选，分区即分类）。
 *  @param showGeneralFold 是否在底部折叠展示泛科技资讯（保底不丢 IT 无关新闻）。 */
@Composable
private fun NewsZoneCard(
    title: String,
    items: List<NewsItem>?,
    category: String,
    showGeneralFold: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "共 ${items?.count { newsMatches(it, category) } ?: 0} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            when {
                items == null -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                items.isEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(if (category in setOf("component", "runtime", "driver")) {
                            R.string.news_empty_official
                        } else {
                            R.string.news_empty
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // 卡片内只展示 3 条预览（瘦身，不再整页铺 8 条 + 分页条），
                    // 完整列表/类型筛选/分页都在"查看全部"打开的列表弹窗里。
                    val catItemsAll = items.filter { newsMatches(it, category) }
                    var showList by remember(category) { mutableStateOf(false) }
                    if (catItemsAll.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            appString(R.string.news_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val preview = catItemsAll.take(3)
                        preview.forEachIndexed { i, item ->
                            NewsRowItem(item)
                            if (i != preview.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                        if (catItemsAll.size > 3) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showList = true }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "查看全部 ${catItemsAll.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (showList) {
                        NewsListDialog(
                            title = title,
                            items = items,
                            category = category,
                            onDismiss = { showList = false }
                        )
                    }
                    if (showGeneralFold) {
                        var generalExpanded by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { generalExpanded = !generalExpanded }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                appString(R.string.news_general_fold),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "共 ${items.count { it.category in setOf("general", "tech") }} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (generalExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (generalExpanded) {
                            val gItems = items.filter { it.category in setOf("general", "tech") }
                            var gPage by remember { mutableStateOf(0) }
                            if (gItems.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    appString(R.string.news_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                val pageSize = 8
                                val gMax = ((gItems.size + pageSize - 1) / pageSize).coerceAtMost(20)
                                val gCur = gPage.coerceIn(0, gMax - 1)
                                gItems.subList(gCur * pageSize, ((gCur + 1) * pageSize).coerceAtMost(gItems.size))
                                    .forEachIndexed { i, item ->
                                        NewsRowItem(item)
                                        if (i != gItems.subList(gCur * pageSize, ((gCur + 1) * pageSize).coerceAtMost(gItems.size)).lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        }
                                    }
                                NewsPager(total = gItems.size, page = gCur, pageSize = pageSize, onPage = { gPage = it })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 新作发布：模拟器类新闻列表 */
@Composable
private fun NewsReleaseCard(items: List<NewsItem>?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(appString(R.string.news_new_releases), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            when {
                items == null -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                items.none { it.category == "emulator" } -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appString(R.string.news_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val emuItems = items.filter { it.category == "emulator" }
                    var showList by remember { mutableStateOf(false) }
                    val preview = emuItems.take(3)
                    preview.forEachIndexed { i, item ->
                        NewsRowItem(item)
                        if (i != preview.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                    if (emuItems.size > 3) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showList = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "查看全部 ${emuItems.size} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (showList) {
                        NewsListDialog(
                            title = appString(R.string.news_new_releases),
                            items = items,
                            category = "emulator",
                            onDismiss = { showList = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmulatorScreen(
    modifier: Modifier = Modifier,
    entries: List<EmulatorEntry>,
    insights: EmulatorInsights = EmulatorInsights(),
    onDownload: (EmulatorEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 新闻模块：业界动态（CI 每日刷新 news-data.json，无网走内置离线副本）
    var newsItems by remember { mutableStateOf<List<NewsItem>?>(null) }
    LaunchedEffect(Unit) {
        newsItems = NewsRepository.loadNews(context)
    }
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
    // 二级维度用「模拟的目标平台」：主机模拟里 200 条，用户真正想筛的是 PS2 / Switch / 3DS，
    // 而不是再筛一次「主机模拟」。
    var filterPlatform by rememberSaveable { mutableStateOf("all") }
    val platformOrder = remember(entries, filterSection) {
        val count = HashMap<String, Int>()
        entries.filter { filterSection == "all" || it.section == filterSection }
            .forEach { e -> e.platforms.forEach { count[it] = (count[it] ?: 0) + 1 } }
        count.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(18)
    }

    val filtered = remember(entries, filterSection, filterPlatform, sortMode, searchQuery, onlyFav, favorites) {
        val base = entries
            .filter { filterSection == "all" || it.section == filterSection }
            .filter { filterPlatform == "all" || it.platforms.contains(filterPlatform) }
        val favFiltered = if (onlyFav) base.filter { it.id in favorites } else base
        val searched = if (searchQuery.isBlank()) favFiltered
        else favFiltered.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.summary.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true) ||
                emulatorCategoryLabel(it.category).contains(searchQuery, ignoreCase = true)
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
                                // 切分区时平台重置，否则会停在上一个分区才有的平台上
                                filterPlatform = "all"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 二级：该分区里出现的模拟平台（按条目数排序，取前 18 个）
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
        flatItems.forEach { entry ->
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
                        if (emuTab == 1 && !newsItems.isNullOrEmpty()) {
                            NewsReleaseCard(items = newsItems.orEmpty())
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

/** 版本更新对比（t3st31 renderReleaseComparison 移植）：选两个版本，diff 更新日志 added/removed */
@Composable
private fun VersionDiffDialog(
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
                    commits != null && commits!!.isNotEmpty() -> {
                        // 摘要行
                        Text(
                            appString(R.string.version_diff_commits, commits!!.size, from.tag, to.tag),
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
                                        val joined = commits!!.joinToString("\n") { it.message }
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
                        val shown = translated ?: commits!!.joinToString("\n") { it.message }
                        if (translated != null) {
                            Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            commits!!.forEach { c ->
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
private fun changelogLines(body: String): List<String> =
    body.split('\n')
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
private fun EmulatorInsightsCard(insights: EmulatorInsights) {
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
private fun EmulatorNewsCard(
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
                            Text("${e.name} · ${e.latestTag}", style = MaterialTheme.typography.labelMedium,
                                color = Fusion.OnSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                e.latestBody.trim().lines().firstOrNull()?.take(90) ?: appString(R.string.emulator_news_no_body),
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
private fun EmulatorTrendingCard(
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
private fun EmulatorCategoryShareCard(categories: List<CategoryDownloads>) {
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
private fun EmulatorRecipesCard(recipes: List<EmulatorRecipe>) {
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

@Composable
private fun FusionFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) Brush.linearGradient(colors = listOf(Fusion.GradientStart, Fusion.GradientEnd))
                else androidx.compose.ui.graphics.SolidColor(Fusion.SurfaceAlt)
            )
            .border(1.dp, if (selected) Color.Transparent else Fusion.BorderAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) Color.White else Fusion.OnSurfaceDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FusionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 副色原先写死深色 0xFF1C2030：亮色主题下搜索条右半截会变成一块深灰板，
            // 与浅色页面完全割裂。改为跟随当前色板的 surface，亮/暗两套都自洽。
            .background(Brush.linearGradient(colors = listOf(Fusion.SurfaceAlt, Fusion.Surface)))
            .border(1.dp, Fusion.BorderAlt, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Fusion.OnSurfaceDim,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Fusion.OnSurface, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Fusion.GradientStart),
            modifier = Modifier.weight(1f)
        ) { inner ->
            if (query.isEmpty()) {
                Text(placeholder, color = Fusion.OnSurfaceDim, fontSize = 14.sp)
            }
            inner()
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
private fun heroReasonText(entry: EmulatorEntry, total: Int): String {
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
private fun EmulatorHeroCard(
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
                    "$categoryLabel · ${entry.latestTag.ifBlank { "latest" }}",
                    fontSize = 12.sp,
                    color = Fusion.OnSurfaceDim,
                    maxLines = 1,
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

@Composable
private fun EmulatorCard(
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
                if (entry.official) {
                    Spacer(Modifier.width(4.dp))
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
                    "${entry.latestTag} · ${shortDate(entry.latestDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 桌面模拟器在安卓端装不了，必须标出来；多生态保留，但不假装能装
            if (entry.hosts.isNotEmpty() && !entry.hosts.contains("android")) {
                Spacer(Modifier.height(2.dp))
                Text(
                    appString(R.string.emulator_hosts_desktop_only) + " · " + entry.hosts.joinToString("/") {
                        when (it) {
                            "windows" -> "Windows"
                            "macos" -> "macOS"
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
            // 用户看不出什么。换成 README 摘要，一眼知道这东西是干什么的。
            val cardBlurb = entry.summary.ifBlank { entry.description }
            if (cardBlurb.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    cardBlurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
private fun CompareBar(
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
private fun EmulatorCompareDialog(
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
                            s.latestTag.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (stats.maxByOrNull { it.daysSinceUpdate }?.daysSinceUpdate == s.daysSinceUpdate && s.daysSinceUpdate >= 0)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
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
private fun EmulatorListRow(
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
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 社区下载量名次：只给有统计的项目挂，前三是金银铜
                    if (entry.rank in 1..99) {
                        Spacer(Modifier.width(5.dp))
                        val rankColor = when (entry.rank) {
                            1 -> Color(0xFFE0A82E)
                            2 -> Color(0xFFA8B4C4)
                            3 -> Color(0xFFC0764A)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = rankColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                "#${entry.rank}",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = rankColor
                            )
                        }
                    }
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
                                "已停更",
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
                // 能跑什么：平台标签 + 家族（分支/移植来源）。
                // 之前只有「桌面 / 预发布 / 官方」这类状态标签，用户看不出项目是干嘛的。
                val goalTags = buildList {
                    entry.platforms.take(3).forEach { p ->
                        emulatorPlatformLabel(p).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    if (entry.hosts.isNotEmpty() && "android" !in entry.hosts) {
                        add(
                            entry.hosts.joinToString("/") { h ->
                                when (h.lowercase()) {
                                    "windows" -> "Windows"
                                    "macos" -> "macOS"
                                    "linux" -> "Linux"
                                    else -> h
                                }
                            }
                        )
                    }
                    entry.topics
                        .filter { t -> t.length in 2..18 && t.lowercase() !in EMULATOR_TOPIC_NOISE }
                        .take(2)
                        .forEach { add("#$it") }
                }
                if (goalTags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        goalTags.take(4).forEach { tag ->
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
                    buildString {
                        append(entry.latestTag.ifBlank { "暂无版本" })
                        if (entry.latestDate.isNotBlank()) append(" · ${entry.latestDate.take(10)}")
                        append(" · 下载 ${emulatorDownloadCount(entry.downloads)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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

private fun emulatorDownloadCount(d: Long): String = when {
    d >= 1_000_000 -> "%.1fM".format(d / 1_000_000.0)
    d >= 1_000 -> "%.1fK".format(d / 1_000.0)
    else -> "$d"
}

private fun assetSizeText(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
    size >= 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "$size B"
}

private fun assetTypeLabel(name: String): String = when {
    name.endsWith(".apk", true) -> "APK"
    name.endsWith(".zip", true) || name.endsWith(".7z", true) || name.endsWith(".tar.gz", true) ||
        name.endsWith(".tgz", true) || name.endsWith(".tar", true) -> "压缩包"
    name.endsWith(".patch", true) || name.endsWith(".diff", true) -> "补丁"
    name.endsWith(".so", true) -> "库文件"
    name.endsWith(".exe", true) -> "安装程序"
    else -> "文件"
}

/** 模拟器详情：全部 releases 全部文件，每个文件可下载，日志可翻译 */
@Composable
private fun EmulatorDetailDialog(
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
                            "已停止维护：${entry.deadReason.ifBlank { "仓库已失效/被移除" }}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
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
                // 优先展示 README 提炼的摘要（说清「这是什么、能跑什么」），
                // 没有再用作者在仓库 About 里写的原文
                val blurb = entry.summary.ifBlank { entry.description }
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
                if (!emuReadyLoading && emuReadyInfo != null && emuReadyInfo!!.listingsCount > 0) {
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
                                    "该模拟器在 EmuReady 有 ${emuReadyInfo!!.listingsCount} 条兼容性报告 · 点击查看推荐设置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
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
                            Text(
                                appString(R.string.emulator_no_releases),
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
                    val totalSize = rel.assets.sumOf { it.size }
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
                                    Text(
                                        rel.tag,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        buildString {
                                            append(rel.date)
                                            if (rel.assets.isNotEmpty()) {
                                                append(" · ${rel.assets.size} 个文件")
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
                                    Text(
                                        translatedMap[rel.tag] ?: rel.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (rel.assets.isEmpty()) {
                                    Text(
                                        appString(R.string.emulator_no_assets),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                rel.assets.forEach { asset ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                            Text(
                                                asset.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                assetSizeText(asset.size),
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
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** 主表分数（纯数字）→ 万位展示；非数字原样返回 */
private fun socScoreText(raw: String): String {
    val v = raw.toLongOrNull() ?: return raw
    return if (v >= 10_000) String.format("%.1f万", v / 10_000.0) else raw
}

/** 制程写法转中文：TSMC N3E → 台积电 3nm，Samsung 4LPP+ → 三星 4nm。 */
private fun formatProcess(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw.replace("TSMC", "台积电").replace("Samsung", "三星")
        .replace("SMIC", "中芯国际").replace("Intel", "英特尔")
    Regex("N(\\d)([A-Z]*)").find(s)?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    Regex("(\\d+)LPP\\+?").find(s)?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    Regex("(\\d+)LP\\+?").find(s)?.let { s = s.replace(it.value, it.groupValues[1] + "nm") }
    return s.trim()
}

private fun gpuArchOf(gpuKey: String): String = when {
    gpuKey.startsWith("adreno") -> "adreno"
    gpuKey.startsWith("mali") -> "mali"
    gpuKey.startsWith("xclipse") -> "xclipse"
    else -> ""
}

/** 芯片知识卡：按部件号识别当前 SoC，展示工艺/CPU/GPU/参考跑分/常见机型 */
@Composable
private fun SocKnowledgeCard(
    context: Context,
    gpuModel: String = "",
    socHint: String = "",
    gpuIsAdreno: Boolean = false
) {
    // 主表按部件号识别（SM8750-AB / MT6989 / sun）。拿不到时整段退回旧知识库，
    // 旧链路只认营销名，遇到厂商级代号（qcom）会显示未收录，但不会误报。
    val resolved = remember(gpuModel, socHint, gpuIsAdreno) {
        runCatching {
            SocResolver.resolveCurrent(context, gpuModel, socHint, gpuIsAdreno)
        }.getOrNull()
    }
    val socName = resolved?.displayName
        ?: SocNameMapper.getCurrentSocName(context, gpuModel, gpuIsAdreno)
    val knowledge = remember(resolved, socName, gpuModel) {
        if (resolved != null) SocKnowledge(
            name = resolved.displayName,
            process = formatProcess(resolved.process),
            cpu = resolved.cpu,
            gpu = resolved.gpu,
            gpuArch = gpuArchOf(resolved.gpuKey),
            score = socScoreText(resolved.antutu),
            devices = resolved.devices
        ) else SocKnowledgeStore.find(socName, gpuModel, context)
    }
    // 实测规格（安兔兔 clusters）：主表有核心构成时直接用，省一次 JSON 查找
    val spec = remember(resolved, socName) {
        if (resolved != null && resolved.cores > 0) {
            SocSpec(
                cpuSummary = resolved.cpu,
                totalCores = resolved.cores,
                maxFreqGhZ = resolved.maxGhz,
                cpuScore = resolved.antutuCpu,
                gpuScore = resolved.antutuGpu,
                totalScore = resolved.antutu
            )
        } else SocSpecStore.find(socName, context)
    }
    // 跑分引擎命中时隐藏知识库文案分数，避免与真实跑分冲突
    val benchInfo = remember(socName, gpuModel) { BenchEngine.resolve(socName, gpuModel, context) }
    val hasBenchScore = benchInfo?.antutuTotal != null || benchInfo?.ludashiScore != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RegionHeader(
            title = appString(R.string.soc_knowledge_title),
            icon = Icons.Default.Memory,
        )
        if (knowledge == null) {
            // 认不出型号也要认到厂：展锐上报 ums9230、华为上报 kirin9020，
            // 直接显示裸代号对用户没有信息量，显示「紫光展锐 · 型号未收录（ums9230）」才有。
            // 取「真正能定位到厂商」的那个字段：有些 ROM 把 ro.soc.model 写成 unknown，
            // 此时厂商是从 ro.board.platform 认出来的，代号就该显示 platform
            val rawCode = remember(gpuModel, socHint) {
                val id = SocNameMapper.getRawIdentity()
                listOf(id.socModel, id.platform, id.hardware)
                    .firstOrNull { it.isNotBlank() && !it.equals("unknown", true) }
                    .orEmpty()
            }
            val vendor = remember(rawCode) {
                runCatching {
                    SocResolver.vendorOf(context, SocNameMapper.getRawIdentity())
                }.getOrDefault("")
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val brand = detectSocBrand(vendor.ifBlank { socName })
                        if (brand.name != "SoC") {
                            SocBrandBadge(brand = brand, text = brand.name)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            vendor.ifBlank { socName.ifBlank { "SOC: unknown" } },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (vendor.isNotBlank() && rawCode.isNotBlank()) {
                            appString(R.string.soc_vendor_only, rawCode)
                        } else {
                            appString(R.string.soc_knowledge_not_found)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 厂商专属图标徽章（品牌色+图形，与设备身份卡一致）
                        val brand = detectSocBrand(knowledge.name)
                        if (brand.name != "SoC") {
                            SocBrandBadge(brand = brand, text = brand.name)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            knowledge.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!hasBenchScore && knowledge.score.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    knowledge.score,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!hasBenchScore) {
                        InfoRow(
                            label = appString(R.string.soc_score_label),
                            value = knowledge.score.ifBlank { appString(R.string.soc_score_unknown) }
                        )
                    }
                    // 主表有的芯片工艺/常见机型为空（极客湾未填），空值行会显示成一条横线，直接不画
                    if (knowledge.process.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_process), value = knowledge.process)
                    }
                    val cpuText = spec?.cpuSummary?.takeIf { it.isNotBlank() } ?: knowledge.cpu
                    if (cpuText.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_cpu), value = cpuText)
                    }
                    // 缓存（L2/SLC）：极客湾只有旗舰标，有值才画
                    if (resolved != null && resolved.caches.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_caches), value = resolved.caches)
                    }
                    if (spec != null && spec.totalCores > 0) {
                        val freq = if (spec.maxFreqGhZ > 0) " · 最高 ${formatFreq(spec.maxFreqGhZ)}" else ""
                        InfoRow(label = appString(R.string.soc_cpu_cores), value = "${spec.totalCores} 核$freq")
                    }
                    // GPU 规格合成一行：Adreno 840 · 1536ALUs · 1200MHz
                    val gpuSpec = if (resolved != null) {
                        listOfNotNull(
                            knowledge.gpu.takeIf { it.isNotBlank() },
                            resolved.gpuCores.takeIf { it.isNotBlank() },
                            resolved.gpuClock.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    } else knowledge.gpu
                    if (gpuSpec.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_gpu_spec), value = gpuSpec)
                    }
                    if (resolved != null && resolved.memory.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_memory), value = resolved.memory)
                    }
                    // 集成功能：基带 / Wi-Fi / 蓝牙 / NPU（极客湾 features 串）
                    if (resolved != null && resolved.features.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_features), value = resolved.features)
                    }
                    if (resolved != null && resolved.gb6Single > 0) {
                        InfoRow(
                            label = appString(R.string.soc_gb6),
                            value = "${resolved.gb6Single} / ${resolved.gb6Multi}"
                        )
                    }
                    // 型号名称：设备上报的那个部件号，用户最需要核对的字段
                    if (resolved != null && resolved.codes.isNotEmpty()) {
                        InfoRow(
                            label = appString(R.string.soc_model_name),
                            value = resolved.codes.first()
                        )
                    }
                    if (knowledge.devices.isNotBlank()) {
                        InfoRow(label = appString(R.string.soc_devices), value = knowledge.devices)
                    }
                    // 时效性与口径：哪年发布、什么档位、是不是嵌入式件。
                    // 主表里 QCS/QCM 是车机与 IoT 模组，跑分比同期手机芯片高，
                    // 不标出来会被当成手机芯片看。
                    val metaLine = remember(resolved) {
                        if (resolved == null) "" else buildList {
                            if (resolved.year.isNotBlank()) add("${resolved.year} 年发布")
                            when (resolved.tier) {
                                "flagship" -> add("旗舰")
                                "sub-flagship" -> add("次旗舰")
                                "mid" -> add("中端")
                                "low" -> add("入门")
                            }
                            val embedded = resolved.codes.any { c ->
                                c.startsWith("QCS", true) || c.startsWith("QCM", true)
                            } || resolved.id.startsWith("qcs", true) || resolved.id.startsWith("qcm", true)
                            if (embedded) add("嵌入式 / 车机版")
                        }.joinToString(" · ")
                    }
                    if (metaLine.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        appString(R.string.soc_source_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/** 常见机型太长（「小米 13、iQOO Neo9、一加 11、红魔 8」），卡片里只留前三款 */
private fun compactDevices(devices: String, max: Int = 3): String {
    val parts = devices.split('、', '，', ',', '/').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return devices
    return if (parts.size <= max) parts.joinToString("、") else parts.take(max).joinToString("、") + " 等"
}

/** SoC 跑分引擎：等效 PC / 模拟器体验预估 / 升级建议 / 世代演进 / 同芯机型。
 *  数据来自安兔兔 SoC 天梯 + 鲁大师五榜（assets/bench，CI 每日刷新）。
 *  匹配失败或数据缺失一律展示空态，不编造分数。 */
@Composable
private fun BenchEngineCard(context: Context, gpuModel: String = "") {
    val socName = remember { SocNameMapper.getCurrentSocName(context) }
    val info = remember(socName, gpuModel) { BenchEngine.resolve(socName, gpuModel, context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RegionHeader(
            title = appString(R.string.bench_engine_title),
            icon = Icons.Default.TrendingUp,
        )
        if (info == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        socName.ifBlank { "SOC: unknown" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        // 芯片身份行：厂商徽章 + 芯片名 + 安兔兔总分（无则隐藏总分）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val brand = detectSocBrand(info.name)
                    if (brand.name != "SoC") {
                        SocBrandBadge(brand = brand, text = brand.name)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (info.antutuTotal != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "安兔兔 ${info.antutuTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
                // 本机机型跑分（安兔兔 V10 手机榜按具体机型计分，含配置差异）
                if (info.deviceBench != null) {
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    val fmt = { v: Long -> String.format("%,d", v) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本机机型跑分",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${info.deviceBench.name}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            fmt(info.deviceBench.totalScore!!),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val db = info.deviceBench
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        db.cpuScore?.let { Text("CPU ${fmt(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        db.gpuScore?.let { Text("GPU ${fmt(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("配置 ${db.soc}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "安兔兔 V10 手机榜按具体机型计分；处理器分为 SoC 天梯口径，两者口径不同",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // GPU 算力对照：只比物理量（FP32 TFLOPS / 带宽），不比跑分。
        // 旧实现用「鲁大师同分制最近邻」把手机 GPU 分和桌面 GPU 分对齐，
        // 两张榜口径不同却数值相近，8 Gen 2 被算成 RTX 2060 SUPER（实际差 8~10 倍）。
        val gpuPhys = info.gpuPhys
        if (gpuPhys != null) {
            val g = gpuPhys
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gpu_phys_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_local),
                        value = "${g.gpuName} · FP32 ${g.tf32} TFLOPS · ${g.bwGbs} GB/s"
                    )
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_desk),
                        value = "${g.deskName}（${g.deskTf32} TFLOPS / ${g.deskBwGbs} GB/s）"
                    )
                    InfoRow(
                        label = appString(R.string.bench_gpu_phys_rel),
                        value = appString(R.string.bench_gpu_phys_rel, g.tfPct, g.bwPct)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_gpu_phys_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        } else if (info.antutuTotal != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gpu_phys_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_gpu_phys_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 模拟器体验：改用 EmuReady 真实社区报告，不再用「安兔兔总分阈值」自估。
        // 旧阈值（Winlator 流畅 280 万 / 不建议 120 万）把 8 Gen 2 判成「不建议」，
        // 而 EmuReady 上 8 Gen 2 跑 Winlator 有 800+ 条报告、均分 73 —— 自估阈值不可信。
        val socCompat = remember(socName) { EmuReadySocStore.find(context, socName) }
        val emuSystems = socCompat?.systems.orEmpty()
        if (emuSystems.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_emu_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        appString(R.string.bench_emu_source, emuSystems.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    emuSystems.take(6).forEachIndexed { idx, sys ->
                        if (idx > 0) HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        CompatRow(
                            name = emuSystemZh(sys.name, sys.key),
                            emulator = sys.bestEmulator,
                            score = sys.score,
                            confidence = sys.confidence,
                            source = sys.dataSource,
                            listings = sys.listings
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        appString(R.string.bench_emu_calib),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }
        } else if (info.antutuTotal != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_emu_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appString(R.string.bench_emu_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 升级建议
        info.upgrade?.let { u ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_upgrade_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = if (u.altName != null) "同厂商首选" else appString(R.string.bench_upgrade_next),
                        value = "${u.nextName}（+${u.gainPct}%）"
                    )
                    // 常见机型来自 soc_master 的 devices 字段：告诉用户「换到这颗芯片，
                    // 对应的机器是哪些」，比只给一个芯片名有用
                    if (u.nextDevices.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        InfoRow(label = "常见机型", value = compactDevices(u.nextDevices))
                    }
                    if (u.altName != null) {
                        Spacer(Modifier.height(4.dp))
                        InfoRow(
                            label = "次选（跨厂商）",
                            value = "${u.altName}（+${u.altGainPct}%）"
                        )
                        if (u.altDevices.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            InfoRow(label = "常见机型", value = compactDevices(u.altDevices))
                        }
                    }
                }
            }
        }

        // 世代演进（同家族按发布年柱状）
        if (info.generation.size >= 3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        appString(R.string.bench_gen_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    BenchGenerationBars(info.generation)
                }
            }
        }

        // 同芯机型跑分（榜单未收录机型折叠为说明，不刷屏）
        info.sameChip?.let { list ->
            val scored = list.filter { it.score != null }
            if (scored.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            appString(R.string.bench_samechip_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        scored.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    d.score?.let { "%,d".format(it) } ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (scored.size < list.size) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                appString(R.string.bench_samechip_partial),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 模拟器档位行：名称 + 语义色档位 */
@Composable
private fun BenchTierRow(tier: EmulatorTier) {
    val color = when (tier.tier) {
        "流畅" -> Color(0xFF2E7D32)
        "可玩" -> Color(0xFF1565C0)
        "吃力" -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Text(
            tier.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.14f)) {
            Text(
                tier.tier,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/** 世代演进列表（同家族逐年，按发布年排序，分数条归一化；信息完整不截断） */
@Composable
private fun BenchGenerationBars(data: List<Triple<String, String, Long>>) {
    val max = data.maxOf { it.third }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.forEachIndexed { idx, (year, name, score) ->
            val pct = ((score.toFloat() / max.toFloat()) * 100f).coerceAtLeast(2f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    year,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format("%,d", score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = 0f)
                    .then(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (idx == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                    )
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "同家族 ${data.size} 代按发布年排序（分数条按总分归一化）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
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
                    val q = query.trim().lowercase()
                    val emuHits = emulators.filter {
                        it.name.lowercase().contains(q) || it.category.lowercase().contains(q) ||
                            it.description.lowercase().contains(q)
                    }
                    val drvHits = drivers.filter {
                        it.name.lowercase().contains(q) || it.description.lowercase().contains(q) ||
                            it.notes.lowercase().contains(q) || it.gpuVendor.lowercase().contains(q)
                    }
                    val compHits = componentNames.filter { it.lowercase().contains(q) }
                    val rtHits = runtimes.filter {
                        it.name.lowercase().contains(q) || it.description.lowercase().contains(q) ||
                            it.category.name.lowercase().contains(q)
                    }
                    val dlHits = downloads.filter {
                        it.fileName.lowercase().contains(q) || it.subPath.lowercase().contains(q)
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

private fun mimeTypeForFileName(name: String): String {
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
