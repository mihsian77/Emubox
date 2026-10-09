package com.emuhub.cn

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
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

    /**
     * 静态检查驱动 .so 是否导出 vkGetInstanceProcAddr（ICD 入口符号）。
     * 纯文件解析，不加载执行，主进程安全；结果决定该驱动能否被
     * fork 子进程 dlopen 实测跑分（VkDriverLab 同款判定：驱动必须导出该符号）。
     * 返回 true/false；so 文件不存在或解析失败返回 null。
     */
    fun soExportsVkGetInstanceProcAddr(soFile: File?): Boolean? {
        if (soFile == null || !soFile.exists()) return null
        return try {
            RandomAccessFile(soFile, "r").use { raf ->
                if (raf.length() < 0x40) return@use null
                raf.seek(0)
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (!(magic[0] == 0x7f.toByte() && magic[1] == 'E'.toByte() &&
                        magic[2] == 'L'.toByte() && magic[3] == 'F'.toByte())) return@use null
                val is64 = raf.readByte().toInt() == 2
                // ELF section header table 定位（ELF32/64 偏移固定）
                val shOff = if (is64) { raf.seek(0x28); readLeLong(raf) } else { raf.seek(0x20); readLeInt(raf).toLong() }
                val shEntSize = if (is64) { raf.seek(0x3A); readLeShort(raf).toInt() } else { raf.seek(0x2E); readLeShort(raf).toInt() }
                val shNum = if (is64) { raf.seek(0x3C); readLeShort(raf).toInt() } else { raf.seek(0x30); readLeShort(raf).toInt() }
                val shSize = if (is64) 64 else 40
                if (shOff <= 0 || shEntSize < shSize || shNum <= 0) return@use null

                // 第一遍：记录 dynsym 段位置
                // shdr 布局：name(4) type(4) | flags(64:8/32:4) addr(64:8/32:4) | offset size | link info | align | entsize
                var dynSymOff = 0L; var dynSymSize = 0L; var dynSymEnt = 0L; var dynSymLink = -1
                for (i in 0 until shNum) {
                    raf.seek(shOff + i * shEntSize.toLong())
                    val type = readLeInt(raf)
                    if (type != 11) continue // SHT_DYNSYM
                    raf.skipBytes(if (is64) 16 else 8) // flags + addr
                    dynSymOff = if (is64) readLeLong(raf) else readLeInt(raf).toLong()
                    dynSymSize = if (is64) readLeLong(raf) else readLeInt(raf).toLong()
                    dynSymLink = readLeInt(raf)         // link → strtab 段索引
                    readLeInt(raf)                       // info
                    raf.skipBytes(if (is64) 8 else 4)    // align
                    dynSymEnt = if (is64) readLeLong(raf) else readLeInt(raf).toLong()
                    break
                }
                if (dynSymOff <= 0 || dynSymLink < 0) return@use false

                // 第二遍：读 dynsym 链接的 strtab 段
                var strOff = 0L; var strSize = 0L
                for (i in 0 until shNum) {
                    if (i != dynSymLink) continue
                    raf.seek(shOff + i * shEntSize.toLong())
                    val type = readLeInt(raf)
                    if (type != 3) return@use false // SHT_STRTAB
                    raf.skipBytes(if (is64) 16 else 8) // flags + addr
                    strOff = if (is64) readLeLong(raf) else readLeInt(raf).toLong()
                    strSize = if (is64) readLeLong(raf) else readLeInt(raf).toLong()
                    break
                }
                if (strSize <= 0 || strSize > 8 * 1024 * 1024) return@use false
                val strTab = ByteArray(strSize.toInt())
                raf.seek(strOff); raf.readFully(strTab)

                // 遍历 dynsym：全局/弱符号中查找 vkGetInstanceProcAddr
                val symEnt = if (dynSymEnt > 0) dynSymEnt else if (is64) 24 else 16
                val symCount = (dynSymSize / symEnt).toInt()
                for (i in 0 until symCount) {
                    raf.seek(dynSymOff + i * symEnt)
                    val stName = readLeInt(raf)
                    val stInfo = raf.readByte().toInt() and 0xff
                    val bind = stInfo and 0x0f // STB_LOCAL=0, STB_GLOBAL=1, STB_WEAK=2
                    if (bind == 0) continue
                    if (stName <= 0 || stName >= strTab.size) continue
                    val name = readCString(strTab, stName) ?: continue
                    if (name == "vkGetInstanceProcAddr") return@use true
                }
                false
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readCString(buf: ByteArray, start: Int): String? {
        var end = start
        while (end < buf.size && buf[end] != 0.toByte()) end++
        if (end == start) return null
        return String(buf, start, end - start, Charsets.UTF_8)
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
    }

    private fun readLeShort(raf: RandomAccessFile): Short {
        val b = ByteArray(2); raf.readFully(b)
        return ((b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)).toShort()
    }

    private fun readLeLong(raf: RandomAccessFile): Long {
        val lo = readLeInt(raf).toLong() and 0xffffffffL
        val hi = readLeInt(raf).toLong() and 0xffffffffL
        return lo or (hi shl 32)
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
