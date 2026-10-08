package com.emuhub.cn

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 驱动包解压与 .so 提取。
 *
 * adpkg 格式（K11MCH1/MrPurple/StevenMXZ 等 turnip 驱动）是 zip 归档，
 * 内含 meta.json（描述 libraryName）和主驱动 .so。
 * 提取到应用私有目录 filesDir/drivers/<driverId>/ 后，
 * native 层可直接 dlopen 该 .so 采集 Vulkan 能力。
 */
object DriverExtractor {

    private const val DRIVERS_DIR = "drivers"

    /** 驱动提取后的根目录：filesDir/drivers/ */
    fun driversRoot(context: Context): File = File(context.filesDir, DRIVERS_DIR)

    /** 指定驱动的提取目录：filesDir/drivers/<driverId>/ */
    fun driverDir(context: Context, driverId: String): File = File(driversRoot(context), sanitizeId(driverId))

    /** 判断该驱动是否已提取 .so */
    fun isExtracted(context: Context, driverId: String): Boolean {
        return getSoFile(context, driverId)?.exists() == true
    }

    /** 获取已提取的 .so 文件路径，未提取返回 null */
    fun getSoFile(context: Context, driverId: String): File? {
        val dir = driverDir(context, driverId)
        if (!dir.exists()) return null
        // 优先读 meta.json 记录的 libraryName
        val metaFile = File(dir, "meta.json")
        if (metaFile.exists()) {
            try {
                val meta = JSONObject(metaFile.readText())
                val libraryName = meta.optString("libraryName", "")
                if (libraryName.isNotEmpty()) {
                    val so = File(dir, libraryName)
                    if (so.exists()) return so
                }
            } catch (_: Exception) { }
        }
        // 兜底：目录下第一个 .so 文件
        return dir.listFiles { f -> f.extension == "so" }?.firstOrNull()
    }

    /**
     * 从已下载的驱动 .zip 中提取 .so。
     *
     * @param context 上下文
     * @param zipUri 下载文件的 content:// URI（从 DownloadsManager 获取）
     * @param driverId 驱动唯一标识（用于目录命名，建议用 release tag + asset name）
     * @return 提取后的 .so 文件，失败返回 null
     */
    fun extract(context: Context, zipUri: Uri, driverId: String): File? {
        val dir = driverDir(context, driverId)
        dir.mkdirs()

        var libraryName: String? = null
        var soFile: File? = null

        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/')

                        if (name == "meta.json") {
                            val metaText = zip.readBytes().toString(Charsets.UTF_8)
                            File(dir, "meta.json").writeText(metaText)
                            try {
                                val meta = JSONObject(metaText)
                                libraryName = meta.optString("libraryName", "").ifEmpty { null }
                            } catch (_: Exception) { }
                        } else if (name.endsWith(".so")) {
                            val outFile = File(dir, name)
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out)
                            }
                            // 设置可执行权限（native dlopen 需要）
                            outFile.setReadable(true, false)
                            outFile.setExecutable(true, false)
                            soFile = outFile
                        }

                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 清理部分提取的文件
            dir.deleteRecursively()
            return null
        }

        // 如果 meta.json 指定了 libraryName 但提取的 .so 文件名不同，重命名
        if (libraryName != null && soFile != null && soFile!!.name != libraryName) {
            val renamed = File(dir, libraryName!!)
            soFile!!.renameTo(renamed)
            soFile = renamed
        }

        return soFile
    }

    /** 删除已提取的驱动文件 */
    fun delete(context: Context, driverId: String) {
        driverDir(context, driverId).deleteRecursively()
    }

    /** 清理所有已提取的驱动 */
    fun clearAll(context: Context) {
        driversRoot(context).deleteRecursively()
    }

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
    }
}
