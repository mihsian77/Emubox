package com.emuhub.cn

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 解析驱动包（zip/adpkg）内的 meta.json，展示包信息。
 * 支持普通文件路径和 content:// URI（SAF）。
 */
object DriverMetaParser {

    data class DriverMeta(
        val name: String,
        val description: String,
        val author: String,
        val packageVersion: String,
        val vendor: String,
        val driverVersion: String,
        val minApi: String,
        val libraryName: String,
        val raw: String
    ) {
        fun isEmpty(): Boolean = name.isBlank() && driverVersion.isBlank()
    }

    /** 判断文件是否可能是驱动包（zip/adpkg） */
    fun isDriverPackage(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".adpkg") ||
            lower.contains("turnip") || lower.contains("panvk") ||
            lower.contains("vulkan") || lower.contains("driver")
    }

    /** 解析驱动包 meta.json，失败返回 null */
    fun parse(context: Context, filePath: String): DriverMeta? {
        return try {
            val json = if (filePath.startsWith("content://")) {
                readMetaFromUri(context, Uri.parse(filePath))
            } else {
                readMetaFromFile(filePath)
            }
            if (json == null) return null
            val obj = JSONObject(json)
            DriverMeta(
                name = obj.optString("name", ""),
                description = obj.optString("description", ""),
                author = obj.optString("author", ""),
                packageVersion = obj.optString("packageVersion", ""),
                vendor = obj.optString("vendor", ""),
                driverVersion = obj.optString("driverVersion", ""),
                minApi = obj.optString("minApi", ""),
                libraryName = obj.optString("libraryName", ""),
                raw = json
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readMetaFromFile(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("meta.json") ?: return null
            zip.getInputStream(entry).bufferedReader().use { return it.readText() }
        }
    }

    private fun readMetaFromUri(context: Context, uri: Uri): String? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith("meta.json") || entry.name == "meta.json") {
                        return zis.bufferedReader().readText()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }
}
