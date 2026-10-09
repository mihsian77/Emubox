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

        /**
         * 驱动类型分类，决定检测方式。
         * 实测结论：通过 zip/adpkg 分发的 Mesa Turnip / PanVK 驱动全部是 adrenotools
         * 打包格式（K11MCH1、MrPurple666、s1mptom 等），其 ICD 依赖模拟器（Eden/Winlator）
         * 通过 adrenotools 注入的钩子与运行环境，App 进程直接 dlopen 必然失败
         * （报 glibc / 符号缺失）。因此这类包不再尝试加载，只解析 meta.json 展示信息。
         */
        fun driverType(): DriverType {
            val lib = libraryName.lowercase()
            val ven = vendor.lowercase()
            val nm = name.lowercase()
            val desc = description.lowercase()
            val haystack = "$lib $ven $nm $desc"
            return when {
                // 厂商 ROM 系统驱动：依赖 hwservicemanager 等系统服务
                lib.contains("qualcomm") || ven.contains("qualcomm") ->
                    DriverType.VENDOR_SYSTEM
                // adrenotools 打包的 Mesa 驱动（Turnip/PanVK），模拟器专用
                lib.contains("adreno") || ven.contains("adreno") ||
                        lib.contains("turnip") || ven.contains("mesa") || ven.contains("turnip") ||
                        nm.contains("turnip") || nm.contains("panvk") || ven.contains("panvk") ||
                        lib.contains("vulkan_driver") || lib.contains("panvk") ||
                        lib == "libvulkan.so" || haystack.contains("adrenotools") ->
                    DriverType.ADRENOTOOLS_PACKAGE
                else -> DriverType.UNKNOWN
            }
        }
    }

    enum class DriverType {
        ADRENOTOOLS_PACKAGE, // adrenotools/mesa 打包（Turnip/PanVK），模拟器专用，仅解析 meta
        VENDOR_SYSTEM,       // 厂商系统提取驱动，App 内无法检测
        UNKNOWN              // 无法识别，不尝试加载
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
