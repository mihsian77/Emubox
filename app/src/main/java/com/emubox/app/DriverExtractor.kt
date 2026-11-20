package com.emubox.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    private const val TAG = "EmuBoxDriverExtractor"

    private const val DRIVERS_DIR = "drivers"

    /**
     * 单个驱动包的安全上限。
     * 正常 Turnip / PanVK 驱动包解压后一般几十 MB，512MB 足够覆盖；
     * 超过即判定为异常包（zip bomb），中止并清理，避免撑爆用户存储。
     */
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /** meta.json 体积上限：正常只有几百字节，超过视为异常包 */
    private const val MAX_META_BYTES = 64 * 1024

    /** 条目数上限：正常驱动包 10~50 个条目，一万条足够覆盖，超出视为异常包 */
    private const val MAX_ENTRIES = 10_000

    /**
     * 非本机 ABI 的目录标记。本 App 只出 arm64，包里同时带 32 位库时不能收：
     * 条目名会扁平化成同一个文件名，后写覆盖先写，最终可能只剩 32 位那份，
     * dlopen 必失败，而报错只会说「驱动崩溃」。
     * 只排除明确的外部 ABI 名，不要求必须出现 arm64——中性目录（如 lib/）照收。
     */
    private val FOREIGN_ABI = Regex(
        """(^|/)(armeabi|armeabi-v7a|armv7|armv7a|x86|x86_64|mips|mips64|riscv32|riscv64)(/|$)""",
        RegexOption.IGNORE_CASE
    )

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
                val libraryName = sanitizeLibraryName(meta.optString("libraryName", ""))
                if (libraryName != null) {
                    val so = File(dir, libraryName)
                    if (so.exists()) return so
                }
            } catch (e: Exception) {
                // meta.json 坏了不阻断加载，但要留证：兜底会取目录下第一个 .so，
                // 包里有多份驱动时可能挑错，表现为进游戏花屏而日志一片空白。
                Log.w(TAG, "读取驱动 meta.json 失败，退回按扩展名取 .so：$driverId", e)
            }
        }
        // 兜底：优先名字带 vulkan 的，再按文件名排序取第一个。
        // 不能用 firstOrNull()：listFiles 的返回顺序由文件系统决定，
        // 同一份驱动在不同次启动可能挑到不同的 .so（依赖库 vs 主驱动）。
        val candidates = dir.listFiles { f -> f.extension == "so" }?.sortedBy { it.name }.orEmpty()
        return candidates.firstOrNull { it.name.contains("vulkan", true) } ?: candidates.firstOrNull()
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
        val root = driversRoot(context).apply { mkdirs() }
        val safeId = sanitizeId(driverId)
        val dir = File(root, safeId)
        // 先写同级 staging，成功后再整体换掉 dir。
        // 直接往 dir 里覆盖写有两个后果：旧 .so 残留（挑主驱动时可能挑到上一版的），
        // 中途失败留下半截目录。staging 保证「要么新版、要么旧版」。
        val staging = File(root, "$safeId.staging")
        val backup = File(root, "$safeId.old")
        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!staging.mkdirs()) {
            Log.w(TAG, "无法创建驱动提取临时目录：$driverId")
            return null
        }

        var libraryName: String? = null
        var soFile: File? = null
        // 包里常常有多个 .so（主驱动 + libbinder_ndk / libhardware 之类依赖）。
        // 原来用「最后遍历到的那个」当主驱动，顺序一变就指向依赖库，加载必失败。
        val extractedSos = mutableListOf<File>()
        // 已解压字节累计，用于拦截 zip bomb
        var totalWritten = 0L

        try {
            val stream = context.contentResolver.openInputStream(zipUri)
            if (stream == null) {
                Log.w(TAG, "驱动包无法打开（URI 打不开或已失效）：$driverId")
                staging.deleteRecursively()
                return null
            }
            stream.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var entryCount = 0
                    while (entry != null) {
                        // 条目数上限：总字节数拦截拦不住"百万个 0 字节条目"的包
                        if (++entryCount > MAX_ENTRIES) {
                            throw IOException("驱动包条目数超限（>$MAX_ENTRIES），已中止")
                        }
                        val entryPath = entry.name
                        val name = entryPath.substringAfterLast('/')

                        if (name == "meta.json") {
                            // 限长读取：防止异常包用超大 meta.json 占满内存
                            val metaText = readLimited(zip, MAX_META_BYTES).toString(Charsets.UTF_8)
                            File(staging, "meta.json").writeText(metaText)
                            try {
                                val meta = JSONObject(metaText)
                                libraryName = sanitizeLibraryName(meta.optString("libraryName", ""))
                            } catch (e: Exception) {
                                // 解析失败时 libraryName 保持 null，主驱动只能按「名字含 vulkan」推断。
                                // 包本身可能仍可用，所以只报警不中断，但必须留下可追的证据。
                                Log.w(TAG, "驱动包 meta.json 解析失败，主驱动将按文件名推断：$driverId", e)
                            }
                        } else if (name.endsWith(".so")) {
                            // 只收本机 ABI：本 App 只出 arm64，加载 32 位库必失败。
                            // 条目名扁平化后 armeabi-v7a/libvulkan.so 与 arm64-v8a/libvulkan.so 同名，
                            // 后写覆盖先写，最终可能只剩 32 位那份。
                            if (FOREIGN_ABI.containsMatchIn(entryPath)) {
                                Log.i(TAG, "跳过非 arm64 条目：$entryPath")
                            } else {
                                // 文件名同样净化：只允许单层文件名，避免路径穿越
                                val safeName = sanitizeLibraryName(name)
                                if (safeName != null) {
                                    val outFile = File(staging, safeName)
                                    FileOutputStream(outFile).use { out ->
                                        totalWritten = copyLimited(zip, out, totalWritten)
                                    }
                                    // 设置可执行权限（native dlopen 需要）
                                    outFile.setReadable(true, false)
                                    outFile.setExecutable(true, false)
                                    extractedSos += outFile
                                }
                            }
                        }

                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            // 失败只清 staging，绝不动已装好的驱动目录：
            // 以前这里 deleteRecursively(dir)，用户装好 v891.14 后任何一次失败的重测
            // 都会把它删掉，之后所有测试都报「未找到 .so」。
            Log.w(TAG, "驱动包提取失败，保留已装版本：$driverId", e)
            staging.deleteRecursively()
            return null
        }

        // 主驱动选择：meta.libraryName 精确命中 → 名字带 vulkan 的 → 第一个
        soFile = libraryName?.let { lib -> extractedSos.firstOrNull { it.name == lib } }
            ?: extractedSos.firstOrNull { it.name.contains("vulkan", true) }
            ?: extractedSos.firstOrNull()

        // 如果 meta.json 指定了 libraryName 但提取的 .so 文件名不同，重命名
        val targetName = libraryName
        val extracted = soFile
        if (targetName != null && extracted != null && extracted.name != targetName) {
            val renamed = File(staging, targetName)
            // 重命名失败时保留原文件，避免把 soFile 指向不存在的路径
            if (extracted.renameTo(renamed)) soFile = renamed
        }

        val picked = soFile ?: run {
            Log.w(TAG, "驱动包里没有可用的 .so：$driverId")
            staging.deleteRecursively()
            return null
        }

        // staging → dir 原子替换：要么新版，要么旧版，不出现半截状态
        if (dir.exists() && !dir.renameTo(backup)) {
            Log.w(TAG, "旧驱动目录无法让位，放弃本次提取：$driverId")
            staging.deleteRecursively()
            return null
        }
        if (!staging.renameTo(dir)) {
            // 换不上去就把旧版放回来，不能让用户手里两个都没有
            if (backup.exists()) backup.renameTo(dir)
            Log.w(TAG, "驱动目录替换失败，已回滚旧版：$driverId")
            staging.deleteRecursively()
            return null
        }
        backup.deleteRecursively()

        return File(dir, picked.name).takeIf { it.exists() }
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

    /**
     * 净化驱动包内的文件名（meta.json 的 libraryName、.so 条目名）。
     *
     * 驱动包来自第三方分发，meta.json 内容不可信：
     * 若直接 File(dir, libraryName)，传入 "../../shared_prefs/xxx.xml"
     * 会写到应用私有目录之外。这里只允许单层文件名。
     *
     * @return 合法文件名；无法净化（空、只有 . 或 ..、仍含上跳语义）时返回 null
     */
    private fun sanitizeLibraryName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        return cleaned.takeUnless { it.isEmpty() || it.contains("..") }
    }

    /** 限长读取：最多读 [limit] 字节，超出立即抛异常（防御超大 meta.json） */
    /**
     * 限长读取当前 zip 条目。
     *
     * 语义分三档：
     * - 未超限：完整返回；
     * - 略超限但仍在硬上限内：**截断返回**，并把 entry 剩余字节读完（否则会打乱 ZipInputStream 的流位置，
     *   导致后续条目解析失败、整个驱动包被判为损坏）；
     * - 超过硬上限：视为恶意包，抛异常由调用方整体拒绝。
     */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        // 硬上限：留出 8 倍余量，超过说明不是正常元数据而是攻击载荷
        val hardLimit = limit.toLong() * 8
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > hardLimit) {
                throw java.io.IOException("meta.json 异常：超过 $hardLimit 字节")
            }
            if (out.size() < limit) {
                out.write(buffer, 0, minOf(limit - out.size(), read))
            }
            // 超出 limit 的部分丢弃，但继续读空当前条目
        }
        return out.toByteArray()
    }

    /** 边复制边累计解压总量，超过 [MAX_TOTAL_BYTES] 立即抛异常（防御 zip bomb） */
    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        alreadyWritten: Long
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = alreadyWritten
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_TOTAL_BYTES) {
                throw java.io.IOException("驱动包解压内容超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB 上限，已中止")
            }
            output.write(buffer, 0, read)
        }
        return total
    }
}
