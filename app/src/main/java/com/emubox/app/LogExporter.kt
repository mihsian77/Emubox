package com.emubox.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.text.format.DateFormat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Date

/**
 * 日志导出：直接落盘到用户可见目录，不再走「分享 / ACTION_SEND」。
 *
 * 旧实现用 `Intent.ACTION_SEND` + `text/plain` 发一段长文本，部分 ROM 的分享面板
 * 会把纯文本当成「用浏览器搜索」，点导出就跳去浏览器，日志既没保存也没法发。
 *
 * 落盘位置与下载目录共用同一套规则：
 *   - 设置里选过文件夹（SAF）→ 在该文件夹下建 `EmuBox/logs`
 *   - 否则 → `Download/EmuBox/logs`
 * 没有存储权限时退到应用私有外部目录（`Android/data/<pkg>/files/logs`），
 * 至少不会丢，并在提示里说明位置。
 */
object LogExporter {

    data class Result(val ok: Boolean, val displayPath: String, val error: String? = null)

    fun save(context: Context, baseName: String, content: String): Result {
        val stamp = DateFormat.format("yyyyMMdd-HHmmss", Date()).toString()
        val fileName = "${baseName}-$stamp.log"
        val folderUri = SettingsManager.getDownloadFolderUri()?.let(Uri::parse)
        if (folderUri != null) {
            saveToTree(context, folderUri, fileName, content)?.let { return it }
        }
        // 没有 SAF 授权：走公共 Download/EmuBox/logs（需要「所有文件访问」）
        if (StorageAccess.hasAccess(context)) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "EmuBox/logs"
            )
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeText(content)
                return Result(true, f.absolutePath)
            }
        }
        // 最后兜底：应用私有目录，用户仍可从此处取走
        val fallback = File(context.getExternalFilesDir(null), "logs")
        return runCatching {
            if (!fallback.exists()) fallback.mkdirs()
            val f = File(fallback, fileName)
            f.writeText(content)
            Result(true, f.absolutePath)
        }.getOrElse {
            Result(false, "", it.message ?: "write failed")
        }
    }

    private fun saveToTree(
        context: Context,
        folderUri: Uri,
        fileName: String,
        content: String
    ): Result? = runCatching {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        val box = root.findFile("EmuBox")?.takeIf { it.isDirectory }
            ?: root.createDirectory("EmuBox")
        val logs = box?.findFile("logs")?.takeIf { it.isDirectory }
            ?: box?.createDirectory("logs")
        val target = logs?.createFile("text/plain", fileName)
        if (target == null || logs == null || box == null) return null
        context.contentResolver.openOutputStream(target.uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: return null
        // 展示给用户的是「所选文件夹/EmuBox/logs/文件名」，比 content:// 好懂
        Result(true, "${root.name ?: "所选文件夹"}/EmuBox/logs/$fileName")
    }.getOrNull()
}
