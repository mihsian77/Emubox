package com.emubox.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import java.io.BufferedReader
import java.io.FileReader
import kotlin.math.abs

/** GPU 厂商枚举 */
enum class GpuVendor {
    ADRENO,      // 高通 Adreno
    MALI,        // ARM Mali（天玑/麒麟/Exynos）
    POWERVR,     // Imagination PowerVR（紫光展锐等）
    XCLIPSE,     // 三星 Xclipse（Exynos + AMD RDNA）
    UNKNOWN;

    val displayName: String get() = when (this) {
        ADRENO -> "高通 Adreno"
        MALI -> "ARM Mali"
        POWERVR -> "Imagination PowerVR"
        XCLIPSE -> "三星 Xclipse"
        UNKNOWN -> "未知"
    }
}

data class DeviceInfo(
    val androidVersion: String,
    val ram: String,
    val gpuRenderer: String,
    val adrenoSeries: String,
    val gpuModel: String,
    val gpuVendor: GpuVendor,
    val deviceBrand: String = "",
    val deviceModel: String = "",
    val socName: String = ""
) {
    companion object {
        suspend fun collect(context: Context): DeviceInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val renderer = getGpuRenderer()
            val gpuModel = detectGpuModel(renderer)
            DeviceInfo(
                "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                getTotalRam(context),
                renderer,
                detectAdrenoSeries(renderer),
                gpuModel,
                detectGpuVendor(renderer),
                deviceBrand = Build.MANUFACTURER.ifBlank { Build.BRAND },
                deviceModel = Build.MODEL.ifBlank { Build.DEVICE },
                socName = SocNameMapper.getCurrentSocName(context, gpuModel)
            )
        }
    }
}

/** 识别 GPU 厂商 */
fun detectGpuVendor(gpuString: String): GpuVendor = when {
    gpuString.contains("Adreno", ignoreCase = true) -> GpuVendor.ADRENO
    gpuString.contains("Mali", ignoreCase = true) -> GpuVendor.MALI
    gpuString.contains("PowerVR", ignoreCase = true) ||
            gpuString.contains("IMG", ignoreCase = true) -> GpuVendor.POWERVR
    gpuString.contains("Xclipse", ignoreCase = true) -> GpuVendor.XCLIPSE
    else -> GpuVendor.UNKNOWN
}

/** 厂商原文 → 中文厂商名优先（qcom/Qualcomm 等股票代码/缩写 → 厂商全名） */
fun mapVendorDisplayName(raw: String): String = when {
    raw.startsWith("qcom", ignoreCase = true) -> raw.replaceFirst("qcom", "高通", ignoreCase = true)
    raw.startsWith("Qualcomm", ignoreCase = true) -> raw.replaceFirst("Qualcomm", "高通", ignoreCase = true)
    raw.startsWith("MediaTek", ignoreCase = true) ->
        Regex("^(MediaTek|mediatek)", RegexOption.IGNORE_CASE).replaceFirst(raw, "联发科")
    raw.startsWith("Mali", ignoreCase = true) && !raw.startsWith("ARM Mali", ignoreCase = true) ->
        raw.replaceFirst("Mali", "ARM Mali", ignoreCase = true)
    else -> raw
}

fun detectAdrenoSeries(gpuString: String): String {
    val model = Regex("Adreno.*?(\\d{3,4})", RegexOption.IGNORE_CASE).find(gpuString)?.groupValues?.get(1) ?: return "unknown"
    return when { model.startsWith("8") -> "8xx"; model.startsWith("7") -> "7xx"; model.startsWith("6") -> "6xx"; else -> "unknown" }
}

/**
 * Extracts the specific GPU model number.
 * Adreno: "740", "810", "830"
 * Mali: "G720", "G610", "G710" (提取 G 后面的数字)
 * Returns "unknown" if the model cannot be parsed.
 */
fun detectGpuModel(gpuString: String): String {
    // Adreno: Adreno 740
    Regex("Adreno.*?(\\d{3,4})", RegexOption.IGNORE_CASE)
        .find(gpuString)?.groupValues?.get(1)?.let { return it }
    // Mali: Mali-G720, Mali G610, Immortalis-G720
    Regex("(?:Mali|Immortalis)[\\s-]*G(\\d{3,4})", RegexOption.IGNORE_CASE)
        .find(gpuString)?.groupValues?.get(1)?.let { return it }
    return "unknown"
}

private fun getTotalRam(context: Context): String {
    val memTotalKB = readMemTotalFromProc()
    val totalRamMB = if (memTotalKB > 0) memTotalKB / 1024
    else {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        (memoryInfo.totalMem / (1024 * 1024)).toInt()
    }
    val totalRamGB = totalRamMB.toDouble() / 1024.0
    val roundedGB = roundToManufacturerRam(totalRamGB)
    return "$roundedGB GB"
}

private fun roundToManufacturerRam(valueGB: Double): Int {
    val commonSizes = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)
    var best = commonSizes[0]
    var bestDiff = abs(valueGB - best)
    for (size in commonSizes) {
        val diff = abs(valueGB - size)
        if (diff < bestDiff - 0.01) {
            bestDiff = diff
            best = size
        } else if (abs(diff - bestDiff) < 0.01 && size > best) best = size
    }
    return best
}

private fun readMemTotalFromProc(): Long {
    return try {
        val reader = BufferedReader(FileReader("/proc/meminfo"))
        val line = reader.readLine()
        reader.close()
        if (line != null && line.startsWith("MemTotal:")) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) parts[1].toLong() else 0L
        } else 0L
    } catch (e: Exception) { 0L }
}

/** SoC 品牌识别（用于设备页品牌色徽章）。返回 (品牌名, 品牌色, 徽章文案) */
data class SocBrand(val name: String, val color: Long, val label: String)

fun detectSocBrand(socName: String): SocBrand = when {
    // 中英文双识别：设备可能上报 Snapdragon 8 Gen 2（英文）或骁龙8 Gen 2（已汉化）
    socName.contains("Snapdragon", ignoreCase = true) ||
        socName.contains("骁龙", ignoreCase = true) ||
        socName.contains("高通", ignoreCase = true) ->
        SocBrand("高通", 0xFF0A6ED1, "骁龙")
    socName.contains("Dimensity", ignoreCase = true) ||
        socName.contains("天玑", ignoreCase = true) ||
        socName.contains("Helio", ignoreCase = true) ->
        SocBrand("联发科", 0xFF1B6AC7, "天玑")
    socName.contains("Exynos", ignoreCase = true) ->
        SocBrand("三星", 0xFF1428A0, "Exynos")
    socName.contains("Kirin", ignoreCase = true) || socName.contains("麒麟", ignoreCase = true) ->
        SocBrand("华为", 0xFFC7000B, "麒麟")
    socName.contains("Tensor", ignoreCase = true) ->
        SocBrand("谷歌", 0xFF4285F4, "Tensor")
    socName.contains("Unisoc", ignoreCase = true) || socName.contains("展锐", ignoreCase = true) ||
    socName.contains("Xclipse", ignoreCase = true) ->
        SocBrand("展锐", 0xFF00A0E9, "展锐")
    else -> when {
        // 厂商代号回退识别（Build.HARDWARE 常见值为 qcom/mediatek 等，此前直接显示原文）
        socName.contains("qcom", ignoreCase = true) || socName.contains("Qualcomm", ignoreCase = true) ->
            SocBrand("高通", 0xFF0A6ED1, "高通")
        socName.contains("mediatek", ignoreCase = true) ->
            SocBrand("联发科", 0xFF1B6AC7, "联发科")
        else -> SocBrand("SoC", 0xFF546E7A, socName.take(12))
    }
}

/**
 * SoC/GPU 品牌 → Simple Icons 图形图标（免费开源图标库，社区/资讯站常用风格）。
 * 返回 null 的品牌回退为文字徽章。颜色与 SocBrand.color 一致。
 */
fun socBrandIconUrl(brandName: String): String? = when (brandName) {
    "Qualcomm", "高通" -> "https://cdn.simpleicons.org/qualcomm/0A6ED1"
    "MediaTek", "联发科" -> "https://cdn.simpleicons.org/mediatek/1B6AC7"
    "Samsung", "三星" -> "https://cdn.simpleicons.org/samsung/1428A0"
    "Huawei", "华为" -> "https://cdn.simpleicons.org/huawei/C7000B"
    "Google", "谷歌" -> "https://cdn.simpleicons.org/google/4285F4"
    "ARM" -> "https://cdn.simpleicons.org/arm/1B6AC7"
    "Unisoc", "展锐" -> "https://cdn.simpleicons.org/unisoc/00A0E9"
    else -> null
}

/** GPU 型号 → SoC 品牌名（设备页徽章用） */
fun detectGpuSocBrand(gpuModel: String, gpuVendor: GpuVendor): SocBrand = when (gpuVendor) {
    GpuVendor.ADRENO -> SocBrand("高通", 0xFF0A6ED1, "骁龙")
    GpuVendor.MALI -> SocBrand("ARM", 0xFF1B6AC7, "Mali")
    GpuVendor.POWERVR -> SocBrand("Imagination", 0xFF546E7A, "PowerVR")
    GpuVendor.XCLIPSE -> SocBrand("展锐", 0xFF00A0E9, "展锐")
    GpuVendor.UNKNOWN -> SocBrand("SoC", 0xFF546E7A, gpuModel.take(12))
}

private fun getGpuRenderer(): String {
    var eglDisplay: android.opengl.EGLDisplay? = null
    var eglContext: android.opengl.EGLContext? = null
    var eglSurface: android.opengl.EGLSurface? = null
    return try {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return "Error: no display"
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return "Error: EGL init failed"
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0)
            return "Error: no EGL config"
        val config = configs[0] ?: return "Error: null config"
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return "Error: context creation failed"
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return "Error: surface creation failed"
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glGetString(GLES20.GL_RENDERER)?.trim() ?: "Unknown"
    } catch (e: Exception) { "Error: ${e.message}" }
    finally {
        eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglDisplay?.let { EGL14.eglTerminate(it) }
    }
}