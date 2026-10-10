package com.emuhub.cn

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "EmuHubDownload"
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
private const val PROGRESS_UPDATE_INTERVAL_MS = 120L

/** 默认下载根目录名（在系统 Download 下创建），自定义路径时不套这一层 */
private const val DEFAULT_ROOT_FOLDER = "EmuHub-CN"

// Reuse the HTTP client instead of creating a new connection pool for every file.
private val downloadClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

// Used when resuming a persisted download after the Activity/app UI was recreated.
private val persistedDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private data class DownloadControl(
    @Volatile var paused: Boolean = false,
    @Volatile var cancelled: Boolean = false,
    @Volatile var call: Call? = null
)

private class DownloadPausedException : IOException("Download paused")

private val downloadControls = ConcurrentHashMap<String, DownloadControl>()

/**
 * Pause by closing the current HTTP call. The partial file and metadata stay on disk.
 * Resume opens a new HTTP Range request, so pausing also survives process death.
 */
fun pauseActiveDownload(fileName: String) {
    val control = downloadControls[fileName] ?: return
    control.paused = true
    DownloadsManager.pauseDownload(fileName)
    control.call?.cancel()
}

/** Resume either an in-memory pause or a download restored after relaunching the app. */
fun resumeActiveDownload(context: Context, fileName: String) {
    val restored = DownloadsManager.activeDownloads[fileName] ?: return
    if (restored.status != DownloadStatus.PAUSED) return

    DownloadsManager.resumeDownload(fileName)
    val appContext = context.applicationContext

    persistedDownloadScope.launch {
        // If Pause just cancelled the previous call, let its finally block release this name first.
        while (downloadControls.containsKey(fileName)) {
            delay(50)
        }

        val current = withContext(Dispatchers.Main) {
            DownloadsManager.activeDownloads[fileName]
        } ?: return@launch

        if (current.status == DownloadStatus.CANCELLING) return@launch
        transferExistingDownload(appContext, current, resume = true)
    }
}

/** Cancel immediately and remove both the persisted state and partial file. */
fun cancelActiveDownload(context: Context, fileName: String) {
    val state = DownloadsManager.activeDownloads[fileName] ?: return
    DownloadsManager.markCancelling(fileName)

    val control = downloadControls[fileName]
    if (control != null) {
        control.cancelled = true
        control.call?.cancel()
        return
    }

    // Restored paused downloads have no live OkHttp call, so clean them up directly.
    val appContext = context.applicationContext
    persistedDownloadScope.launch {
        deletePartialOutput(appContext, state.outputUri.takeIf { it.isNotBlank() }?.let(Uri::parse))
        withContext(Dispatchers.Main) {
            DownloadsManager.failDownload(fileName)
            Toast.makeText(appContext, "Download cancelled: $fileName", Toast.LENGTH_SHORT).show()
        }
    }
}

suspend fun downloadAsset(context: Context, release: GithubRelease, asset: GithubAsset) {
    val desiredName = sanitizeFileName("${release.tagName}_${asset.name}")
    if (ensureFileNotExists(context, desiredName, "Drivers")) return
    val acceleratedUrl = Accelerator.rewriteUrl(asset.downloadUrl)
    downloadFileWithProgress(context.applicationContext, acceleratedUrl, desiredName, "Drivers")
}

suspend fun downloadComponent(context: Context, component: Component) {
    val fileName = sanitizeFileName(
        Uri.decode(component.remoteUrl.substringAfterLast("/"))
    )
    val category = "Components/${component.type}"
    if (ensureFileNotExists(context, fileName, category)) return
    val acceleratedUrl = Accelerator.rewriteUrl(component.remoteUrl)
    downloadFileWithProgress(context.applicationContext, acceleratedUrl, fileName, category)
}

/** 下载 Windows 运行库文件，按分类存入 Download/Runtime/<分类>/ */
suspend fun downloadRuntimeLibrary(context: Context, component: RuntimeComponent) {
    val file = component.primaryFile ?: return
    val fileName = sanitizeFileName(file.rename.ifBlank { file.fileName })
    val category = "Runtime/${component.category.labelRes}"
    if (ensureFileNotExists(context, fileName, category)) return
    val acceleratedUrl = Accelerator.rewriteUrl(file.url)
    downloadFileWithProgress(context.applicationContext, acceleratedUrl, fileName, category)
}

/**
 * 下载前查重：completed 记录或磁盘已存在同名文件时提示，不静默生成 (1)(2) 后缀。
 * 返回 true 表示文件已存在、应终止本次下载。
 */
private suspend fun ensureFileNotExists(context: Context, desiredName: String, subPath: String): Boolean {
    val existed = withContext(Dispatchers.IO) {
        if (DownloadsManager.existsCompleted(desiredName, subPath)) {
            true
        } else {
            fileExistsOnDisk(context, desiredName, subPath)
        }
    }
    if (existed) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "文件已存在：$desiredName（如需重新下载请先删除旧文件）",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    return existed
}

/** 下载目录中是否已存在同名文件（SAF 树 / MediaStore / 普通目录三种路径）。UI 下载按钮据此显示"已下载" */
fun fileExistsOnDisk(context: Context, desiredName: String, subPath: String): Boolean {
    val folderUri = SettingsManager.getDownloadFolderUri()?.let(Uri::parse)
    val defaultRelativePath = "$DEFAULT_ROOT_FOLDER/$subPath"
    return if (folderUri != null && DocumentsContract.isTreeUri(folderUri)) {
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
        val subDoc = if (subPath.isBlank()) rootDoc else rootDoc?.let { findSubDirectory(it, subPath) }
        subDoc?.findFile(desiredName) != null
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(desiredName, "%$defaultRelativePath%"),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
    } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(File(downloadsDir, "$DEFAULT_ROOT_FOLDER/$subPath"), desiredName).exists()
    }
}

/** 文件名清洗：替换路径分隔符/通配符等非法字符。下载与"已下载"判断共用同一规则 */
fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

/** 在 DocumentFile 树中按相对路径查找子目录（不创建），不存在返回 null */
private fun findSubDirectory(root: DocumentFile, subPath: String): DocumentFile? {
    var current: DocumentFile = root
    for (segment in subPath.split("/").filter { it.isNotBlank() }) {
        current = current.findFile(segment) ?: return null
        if (!current.isDirectory) return null
    }
    return current
}

/** 在 DocumentFile 树中按相对路径递归创建子目录并返回 */
private fun getOrCreateSubDirectory(root: DocumentFile, subPath: String): DocumentFile? {
    var current: DocumentFile = root
    for (segment in subPath.split("/").filter { it.isNotBlank() }) {
        current = current.findFile(segment)
            ?.takeIf { it.isDirectory }
            ?: current.createDirectory(segment)
            ?: return null
    }
    return current
}

private suspend fun getUniqueFileName(
    context: Context,
    folderUri: Uri?,
    desiredName: String,
    subPath: String
): String = withContext(Dispatchers.IO) {
    val lastDot = desiredName.lastIndexOf('.')
    val nameWithoutExt = if (lastDot > 0) desiredName.substring(0, lastDot) else desiredName
    val extension = if (lastDot > 0) desiredName.substring(lastDot) else ""

    var counter = 1
    var newName = desiredName

    // 默认路径（MediaStore）下的完整相对路径，用于查重限定
    val defaultRelativePath = "$DEFAULT_ROOT_FOLDER/$subPath"

    while (true) {
        val exists = if (folderUri != null && DocumentsContract.isTreeUri(folderUri)) {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
            val subDoc = if (subPath.isBlank()) rootDoc else rootDoc?.let { findSubDirectory(it, subPath) }
            subDoc?.findFile(newName) != null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(newName, "%$defaultRelativePath%"),
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "$DEFAULT_ROOT_FOLDER/$subPath")
            File(targetDir, newName).exists()
        }

        if (!exists) break
        newName = "$nameWithoutExt ($counter)$extension"
        counter++
    }

    newName
}

private suspend fun createOutputUri(
    context: Context,
    folderUri: Uri?,
    uniqueFileName: String,
    subPath: String
): Uri? = withContext(Dispatchers.IO) {
    if (folderUri != null) {
        // 自定义路径：用户选的文件夹即根，直接在其下建分类子目录
        val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
        if (folderDoc != null && folderDoc.canWrite()) {
            val targetDoc = if (subPath.isBlank()) folderDoc else getOrCreateSubDirectory(folderDoc, subPath)
            targetDoc?.createFile("application/octet-stream", uniqueFileName)?.uri
        } else {
            Log.e(TAG, "Cannot write to custom folder: $folderUri")
            null
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 默认路径：Download/EmuHub-CN/<分类>/
        val relativePath = if (subPath.isBlank()) {
            "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_ROOT_FOLDER"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_ROOT_FOLDER/$subPath"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "$DEFAULT_ROOT_FOLDER/$subPath")
        if (!targetDir.exists() && !targetDir.mkdirs()) return@withContext null
        Uri.fromFile(File(targetDir, uniqueFileName))
    }
}

private fun openOutputStream(context: Context, uri: Uri, append: Boolean): OutputStream {
    return if (uri.scheme == "content") {
        val mode = if (append) "wa" else "w"
        context.contentResolver.openOutputStream(uri, mode)
            ?: throw IOException("Cannot open output stream")
    } else {
        val file = File(uri.path ?: throw IOException("Invalid output path"))
        FileOutputStream(file, append)
    }
}

private suspend fun persistAndThrowPause(
    output: OutputStream,
    fileName: String,
    downloadedBytes: Long
): Nothing {
    runCatching { output.flush() }
    withContext(Dispatchers.Main) {
        DownloadsManager.updateProgress(fileName, downloadedBytes)
        DownloadsManager.pauseDownload(fileName)
    }
    throw DownloadPausedException()
}

private suspend fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    fileName: String,
    control: DownloadControl,
    startingBytes: Long
): Long {
    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
    var totalRead = startingBytes.coerceAtLeast(0L)
    var lastUiUpdate = 0L

    // 速度计算：滑动窗口记录最近的 (时间戳, 已下载字节)
    val speedSamples = ArrayDeque<Pair<Long, Long>>()
    val SPEED_WINDOW_MS = 3000L

    while (true) {
        currentCoroutineContext().ensureActive()

        if (control.cancelled) throw CancellationException("Download cancelled")
        if (control.paused) persistAndThrowPause(output, fileName, totalRead)

        val bytesRead = try {
            input.read(buffer)
        } catch (error: IOException) {
            when {
                control.cancelled -> throw CancellationException("Download cancelled")
                control.paused -> persistAndThrowPause(output, fileName, totalRead)
                else -> throw error
            }
        }
        if (bytesRead == -1) break

        output.write(buffer, 0, bytesRead)
        totalRead += bytesRead

        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
            // 计算滑动窗口平均速度
            speedSamples.addLast(now to totalRead)
            while (speedSamples.isNotEmpty() && speedSamples.first().first < now - SPEED_WINDOW_MS) {
                speedSamples.removeFirst()
            }
            if (speedSamples.size >= 2) {
                val first = speedSamples.first()
                val last = speedSamples.last()
                val elapsedSec = (last.first - first.first).coerceAtLeast(1L) / 1000.0
                val deltaBytes = (last.second - first.second).coerceAtLeast(0L)
                val speed = (deltaBytes / elapsedSec).toLong()
                withContext(Dispatchers.Main) {
                    DownloadsManager.updateSpeed(fileName, speed)
                }
            }

            withContext(Dispatchers.Main) {
                DownloadsManager.updateProgress(fileName, totalRead)
            }
            // 慢下载检测：传入当前进度
            val currentTotal = withContext(Dispatchers.Main) {
                DownloadsManager.activeDownloads[fileName]?.totalBytes ?: 0L
            }
            SlowDownloadDetector.onProgress(fileName, totalRead, currentTotal)
            lastUiUpdate = now
        }
    }

    output.flush()
    withContext(Dispatchers.Main) {
        DownloadsManager.updateProgress(fileName, totalRead)
        DownloadsManager.updateSpeed(fileName, 0L)
    }
    return totalRead
}

private fun publishMediaStoreFile(context: Context, uri: Uri, usesMediaStore: Boolean) {
    if (usesMediaStore && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }
}

private fun deletePartialOutput(context: Context, uri: Uri?) {
    if (uri == null) return
    runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null)
        } else {
            File(uri.path.orEmpty()).delete()
        }
    }.onFailure { Log.w(TAG, "Could not remove partial download", it) }
}

private fun parseContentRangeTotal(contentRange: String?): Long {
    if (contentRange.isNullOrBlank()) return 0L
    val totalPart = contentRange.substringAfter('/', "").trim()
    return totalPart.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
}

private fun outputPath(uri: Uri): String {
    return if (uri.scheme == "content") {
        uri.toString()
    } else {
        File(uri.path ?: throw IOException("Invalid output path")).absolutePath
    }
}

/**
 * 从加速 URL 中还原原始 GitHub URL。
 * 加速 URL 格式：https://proxy-domain/https://github.com/...
 * 如果不是加速 URL（不包含代理域名），返回 null。
 */
private fun extractOriginalUrlIfAccelerated(acceleratedUrl: String): String? {
    // 检查是否包含代理域名前缀（第二个 https:// 之前的部分是代理域名）
    val httpsIndex = acceleratedUrl.indexOf("https://", 1)
    val httpIndex = acceleratedUrl.indexOf("http://", 1)
    val originalIndex = when {
        httpsIndex > 0 -> httpsIndex
        httpIndex > 0 -> httpIndex
        else -> return null
    }
    val originalUrl = acceleratedUrl.substring(originalIndex)
    // 验证还原后的 URL 确实是 GitHub 域名
    return if (Accelerator.isGithubUrl(originalUrl)) originalUrl else null
}

private suspend fun transferExistingDownload(
    context: Context,
    state: DownloadsManager.ActiveDownload,
    resume: Boolean
): Boolean = withContext(Dispatchers.IO) transfer@{
    val fileName = state.fileName
    val outputUri = state.outputUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        ?: run {
            withContext(Dispatchers.Main) {
                DownloadsManager.failDownload(fileName)
                Toast.makeText(context, "Cannot resume $fileName: partial file is missing", Toast.LENGTH_LONG).show()
            }
            return@transfer false
        }

    val control = DownloadControl()
    downloadControls[fileName] = control

    try {
        var requestedOffset = if (resume) state.downloadedBytes.coerceAtLeast(0L) else 0L
        val requestBuilder = Request.Builder()
            .url(state.url)
            .header("User-Agent", "EmuHub-Android/1.0")
            .header("Accept-Encoding", "identity")

        if (requestedOffset > 0L) {
            requestBuilder.header("Range", "bytes=$requestedOffset-")
        }

        withContext(Dispatchers.Main) {
            DownloadsManager.markConnecting(fileName)
        }

        val call = downloadClient.newCall(requestBuilder.build())
        control.call = call

        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("No data received")
            val serverAcceptedResume = requestedOffset > 0L && response.code == 206

            // Some hosts ignore Range and return 200. Truncate and safely restart instead of
            // appending a full response to an existing partial file.
            if (requestedOffset > 0L && !serverAcceptedResume) {
                requestedOffset = 0L
                withContext(Dispatchers.Main) {
                    DownloadsManager.updateProgress(fileName, 0L)
                    Toast.makeText(
                        context,
                        "This source cannot resume $fileName; restarting from 0%",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val bodyLength = body.contentLength().coerceAtLeast(0L)
            val contentRangeTotal = parseContentRangeTotal(response.header("Content-Range"))
            val totalBytes = when {
                contentRangeTotal > 0L -> contentRangeTotal
                bodyLength > 0L && requestedOffset > 0L -> requestedOffset + bodyLength
                else -> bodyLength
            }

            withContext(Dispatchers.Main) {
                DownloadsManager.updateTotalBytes(fileName, totalBytes)
                DownloadsManager.markDownloading(fileName)
            }

            val totalWritten = body.byteStream().use { input ->
                openOutputStream(context, outputUri, append = requestedOffset > 0L).use { output ->
                    copyWithProgress(
                        input = input,
                        output = output,
                        fileName = fileName,
                        control = control,
                        startingBytes = requestedOffset
                    )
                }
            }

            if (control.cancelled) throw CancellationException("Download cancelled")
            if (control.paused) throw DownloadPausedException()

            publishMediaStoreFile(context, outputUri, state.usesMediaStore)
            withContext(Dispatchers.Main) {
                DownloadsManager.completeDownload(fileName, outputPath(outputUri), totalWritten, state.subPath)
                Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_LONG).show()
            }
            return@transfer true
        }
    } catch (_: DownloadPausedException) {
        // Keep the partial URI + byte count. It can be resumed after app/process recreation.
        withContext(NonCancellable + Dispatchers.Main) {
            DownloadsManager.pauseDownload(fileName)
        }
        return@transfer false
    } catch (cancelled: CancellationException) {
        if (control.cancelled) {
            withContext(NonCancellable + Dispatchers.IO) {
                deletePartialOutput(context, outputUri)
            }
            withContext(NonCancellable + Dispatchers.Main) {
                DownloadsManager.failDownload(fileName)
                Toast.makeText(context, "Download cancelled: $fileName", Toast.LENGTH_SHORT).show()
            }
            return@transfer false
        } else {
            // Activity/process lifecycle cancellation should preserve the partial download.
            withContext(NonCancellable + Dispatchers.Main) {
                DownloadsManager.pauseDownload(fileName)
            }
            throw cancelled
        }
    } catch (error: Exception) {
        when {
            control.paused -> {
                withContext(NonCancellable + Dispatchers.Main) {
                    DownloadsManager.pauseDownload(fileName)
                }
                return@transfer false
            }

            control.cancelled -> {
                deletePartialOutput(context, outputUri)
                withContext(Dispatchers.Main) {
                    DownloadsManager.failDownload(fileName)
                    Toast.makeText(context, "Download cancelled: $fileName", Toast.LENGTH_SHORT).show()
                }
                return@transfer false
            }

            else -> {
                Log.e(TAG, "Download failed: ${state.url}", error)
                deletePartialOutput(context, outputUri)
                withContext(Dispatchers.Main) {
                    DownloadsManager.failDownload(fileName)
                }
                return@transfer false
            }
        }
    } finally {
        control.call = null
        downloadControls.remove(fileName, control)
    }
    return@transfer false
}

private suspend fun downloadFileWithProgress(
    context: Context,
    url: String,
    originalDesiredName: String,
    subPath: String
) = withContext(Dispatchers.IO) download@{
    val folderUri = SettingsManager.getDownloadFolderUri()?.let(Uri::parse)
    val uniqueFileName = getUniqueFileName(context, folderUri, originalDesiredName, subPath)
    val usesMediaStore = folderUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val outputUri = createOutputUri(context, folderUri, uniqueFileName, subPath)

    if (outputUri == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Cannot create output file", Toast.LENGTH_LONG).show()
        }
        return@download
    }

    withContext(Dispatchers.Main) {
        DownloadsManager.startDownload(
            fileName = uniqueFileName,
            url = url,
            outputUri = outputUri.toString(),
            usesMediaStore = usesMediaStore,
            subPath = subPath
        )
        Toast.makeText(context, "Download started: $uniqueFileName", Toast.LENGTH_SHORT).show()
    }

    val state = withContext(Dispatchers.Main) {
        DownloadsManager.activeDownloads[uniqueFileName]
    } ?: return@download

    val success = transferExistingDownload(context, state, resume = false)

    // 自动回退：如果加速下载失败，自动切换到直连重试
    if (!success) {
        val directUrl = extractOriginalUrlIfAccelerated(url)
        if (directUrl != null && !url.equals(directUrl, ignoreCase = true)) {
            Log.i(TAG, "加速下载失败，自动回退到直连: $directUrl")

            // 删除旧的部分文件
            deletePartialOutput(context, outputUri)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_accelerator_fallback),
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 重新创建输出文件
            val newOutputUri = createOutputUri(context, folderUri, uniqueFileName, subPath)
            if (newOutputUri != null) {
                withContext(Dispatchers.Main) {
                    DownloadsManager.startDownload(
                        fileName = uniqueFileName,
                        url = directUrl,
                        outputUri = newOutputUri.toString(),
                        usesMediaStore = usesMediaStore,
                        subPath = subPath
                    )
                }
                val directState = withContext(Dispatchers.Main) {
                    DownloadsManager.activeDownloads[uniqueFileName]
                }
                if (directState != null) {
                    val directSuccess = transferExistingDownload(context, directState, resume = false)
                    if (!directSuccess) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_download_failed, uniqueFileName), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } else {
            // 不是加速 URL，已经是直连失败
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "下载失败: $uniqueFileName", Toast.LENGTH_LONG).show()
            }
        }
    }
}
