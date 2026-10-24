package com.emubox.app

import org.json.JSONObject

/**
 * Vulkan 采集引擎的 JNI 桥。
 *
 * native 层通过 dlopen 动态加载 libvulkan（或指定驱动库），全函数指针解析，
 * 采集物理设备属性/特性/扩展/限制/内存/队列族，返回 JSON 字符串。
 *
 * 不链接系统 libvulkan.so，这样 M2 可以传入下载的驱动 .so 路径，
 * 采集"该驱动"暴露的 Vulkan 能力（驱动商店→检测闭环）。
 */
object NativeVulkanBridge {

    init {
        System.loadLibrary("vulkan_collector")
    }

    /**
     * 采集 Vulkan 设备信息。
     *
     * @param driverPath 驱动库路径；null 或空时使用系统 libvulkan.so。
     * @return JSON 字符串，结构见 [VulkanInfoPayload.fromJson]。
     */
    external fun collectVulkanInfo(driverPath: String?): String

    /** 便捷方法：采集系统默认 Vulkan 设备信息。 */
    fun collectSystemVulkanInfo(): VulkanInfoPayload {
        return VulkanInfoPayload.fromJson(collectVulkanInfo(null))
    }

    /**
     * GPU 基准测试：fill bandwidth + copy bandwidth，timestamp query 精确计时。
     *
     * @param driverPath 驱动库路径；null 或空时测系统 Vulkan 硬件底力。
     * @return JSON 字符串，结构见 [BenchmarkResult.fromJson]。
     */
    external fun benchmarkVulkan(driverPath: String?): String

    /** 便捷方法：测系统 Vulkan 硬件底力。 */
    fun benchmarkSystemVulkan(): BenchmarkResult {
        return BenchmarkResult.fromJson(benchmarkVulkan(null))
    }

    // ── AdrenoTools 实测（M4）──────────────────────────────────────────────
    // 以 Winlator/GameHub/Eden 同款加载路径（linker namespace 绕过 + GPU 映射
    // 导入）加载自定义 turnip/panvk 驱动，采集实例扩展/设备属性并跑分。
    // 全部在 fork 子进程中执行，驱动崩溃不会带崩 App。
    //
    // @param driverDir    驱动 .so 所在目录（App 私有目录，不能是 sdcard）
    // @param driverName   驱动 .so 文件名（如 vulkan.turnip.so）
    // @param tmpDir       可写临时目录（memfd 不可用时的 patched lib 落地）
    // @param hookLibDir   getApplicationInfo().nativeLibraryDir
    external fun collectDriverAdrenotools(
        driverDir: String, driverName: String, tmpDir: String, hookLibDir: String
    ): String

    external fun benchmarkDriverAdrenotools(
        driverDir: String, driverName: String, tmpDir: String, hookLibDir: String
    ): String

    /** 便捷方法：AdrenoTools 路径实测一个驱动（采集+跑分一起返回）。 */
    fun testDriverAdrenotools(
        driverDir: String, driverName: String, tmpDir: String, hookLibDir: String
    ): Pair<VulkanInfoPayload, BenchmarkResult> {
        val collect = VulkanInfoPayload.fromJson(
            collectDriverAdrenotools(driverDir, driverName, tmpDir, hookLibDir)
        )
        val bench = BenchmarkResult.fromJson(
            benchmarkDriverAdrenotools(driverDir, driverName, tmpDir, hookLibDir)
        )
        return collect to bench
    }
}

/** 顶层采集响应。 */
data class VulkanInfoPayload(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val loader: String,
    val instanceExtCount: Int,
    val instanceExtensions: List<ExtensionInfo>,
    val deviceCount: Int,
    val devices: List<VulkanDeviceInfo>
) {
    companion object {
        fun fromJson(json: String): VulkanInfoPayload {
            return try {
                val obj = JSONObject(json)
                val devicesArray = obj.optJSONArray("devices")
                val devices = mutableListOf<VulkanDeviceInfo>()
                if (devicesArray != null) {
                    for (i in 0 until devicesArray.length()) {
                        devices.add(VulkanDeviceInfo.fromJson(devicesArray.getJSONObject(i)))
                    }
                }
                val instExtArray = obj.optJSONArray("instanceExtensions")
                val instExts = mutableListOf<ExtensionInfo>()
                if (instExtArray != null) {
                    for (i in 0 until instExtArray.length()) {
                        val ext = instExtArray.getJSONObject(i)
                        instExts.add(
                            ExtensionInfo(
                                name = ext.optString("name", ""),
                                specVersion = ext.optInt("specVersion", 0)
                            )
                        )
                    }
                }
                VulkanInfoPayload(
                    success = obj.optBoolean("success", false),
                    errorCode = obj.optString("errorCode", "").takeIf { it.isNotEmpty() },
                    errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotEmpty() },
                    loader = obj.optString("loader", ""),
                    instanceExtCount = obj.optInt("instanceExtCount", 0),
                    instanceExtensions = instExts,
                    deviceCount = obj.optInt("deviceCount", 0),
                    devices = devices
                )
            } catch (e: Exception) {
                VulkanInfoPayload(
                    success = false,
                    errorCode = "JSON_PARSE_ERROR",
                    errorMessage = e.message ?: "unknown",
                    loader = "",
                    instanceExtCount = 0,
                    instanceExtensions = emptyList(),
                    deviceCount = 0,
                    devices = emptyList()
                )
            }
        }
    }
}

/** 单个物理设备的 Vulkan 信息。 */
data class VulkanDeviceInfo(
    val deviceName: String,
    val deviceType: String,
    val vendorId: Int,
    val deviceId: Int,
    val apiVersion: String,
    val driverVersion: String,
    val driverName: String,
    val driverInfo: String,
    val features: Map<String, Boolean>,
    val features2: Map<String, Boolean>,
    val formats: List<FormatSupportInfo>,
    val extensions: List<ExtensionInfo>,
    val memoryTypes: List<MemoryTypeInfo>,
    val memoryHeaps: List<MemoryHeapInfo>,
    val queueFamilies: List<QueueFamilyInfo>,
    val limits: Map<String, Any>
) {
    companion object {
        fun fromJson(obj: JSONObject): VulkanDeviceInfo {
            val featuresObj = obj.optJSONObject("features")
            val features = mutableMapOf<String, Boolean>()
            if (featuresObj != null) {
                val keys = featuresObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    features[key] = featuresObj.optBoolean(key, false)
                }
            }

            val features2Obj = obj.optJSONObject("features2")
            val features2 = mutableMapOf<String, Boolean>()
            if (features2Obj != null) {
                val keys = features2Obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    features2[key] = features2Obj.optBoolean(key, false)
                }
            }

            val formatsArray = obj.optJSONArray("formats")
            val formats = mutableListOf<FormatSupportInfo>()
            if (formatsArray != null) {
                for (i in 0 until formatsArray.length()) {
                    val fmt = formatsArray.getJSONObject(i)
                    formats.add(
                        FormatSupportInfo(
                            format = fmt.optString("format", ""),
                            linearTilingFeatures = fmt.optLong("linear", 0),
                            optimalTilingFeatures = fmt.optLong("optimal", 0),
                            bufferFeatures = fmt.optLong("buffer", 0)
                        )
                    )
                }
            }

            val extensionsArray = obj.optJSONArray("extensions")
            val extensions = mutableListOf<ExtensionInfo>()
            if (extensionsArray != null) {
                for (i in 0 until extensionsArray.length()) {
                    val ext = extensionsArray.getJSONObject(i)
                    extensions.add(
                        ExtensionInfo(
                            name = ext.optString("name", ""),
                            specVersion = ext.optInt("specVersion", 0)
                        )
                    )
                }
            }

            val memoryTypesArray = obj.optJSONArray("memoryTypes")
            val memoryTypes = mutableListOf<MemoryTypeInfo>()
            if (memoryTypesArray != null) {
                for (i in 0 until memoryTypesArray.length()) {
                    val mt = memoryTypesArray.getJSONObject(i)
                    memoryTypes.add(
                        MemoryTypeInfo(
                            propertyFlags = mt.optString("propertyFlags", ""),
                            heapIndex = mt.optInt("heapIndex", 0)
                        )
                    )
                }
            }

            val memoryHeapsArray = obj.optJSONArray("memoryHeaps")
            val memoryHeaps = mutableListOf<MemoryHeapInfo>()
            if (memoryHeapsArray != null) {
                for (i in 0 until memoryHeapsArray.length()) {
                    val mh = memoryHeapsArray.getJSONObject(i)
                    memoryHeaps.add(
                        MemoryHeapInfo(
                            size = mh.optLong("size", 0),
                            flags = mh.optString("flags", "")
                        )
                    )
                }
            }

            val queueFamiliesArray = obj.optJSONArray("queueFamilies")
            val queueFamilies = mutableListOf<QueueFamilyInfo>()
            if (queueFamiliesArray != null) {
                for (i in 0 until queueFamiliesArray.length()) {
                    val qf = queueFamiliesArray.getJSONObject(i)
                    val granularityArray = qf.optJSONArray("minImageTransferGranularity")
                    val granularity = if (granularityArray != null && granularityArray.length() >= 3) {
                        listOf(
                            granularityArray.optInt(0, 0),
                            granularityArray.optInt(1, 0),
                            granularityArray.optInt(2, 0)
                        )
                    } else {
                        listOf(0, 0, 0)
                    }
                    queueFamilies.add(
                        QueueFamilyInfo(
                            queueFlags = qf.optString("queueFlags", ""),
                            queueCount = qf.optInt("queueCount", 0),
                            timestampValidBits = qf.optInt("timestampValidBits", 0),
                            minImageTransferGranularity = granularity
                        )
                    )
                }
            }

            val limitsObj = obj.optJSONObject("limits")
            val limits = mutableMapOf<String, Any>()
            if (limitsObj != null) {
                val keys = limitsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = limitsObj.get(key)
                    // 数组转 List
                    if (value is org.json.JSONArray) {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            list.add(value.get(i))
                        }
                        limits[key] = list
                    } else {
                        limits[key] = value
                    }
                }
            }

            return VulkanDeviceInfo(
                deviceName = obj.optString("deviceName", ""),
                deviceType = obj.optString("deviceType", ""),
                vendorId = obj.optInt("vendorId", 0),
                deviceId = obj.optInt("deviceId", 0),
                apiVersion = obj.optString("apiVersion", ""),
                driverVersion = obj.optString("driverVersion", ""),
                driverName = obj.optString("driverName", ""),
                driverInfo = obj.optString("driverInfo", ""),
                features = features,
                features2 = features2,
                formats = formats,
                extensions = extensions,
                memoryTypes = memoryTypes,
                memoryHeaps = memoryHeaps,
                queueFamilies = queueFamilies,
                limits = limits
            )
        }
    }
}

data class FormatSupportInfo(
    val format: String,
    val linearTilingFeatures: Long,
    val optimalTilingFeatures: Long,
    val bufferFeatures: Long
)

data class ExtensionInfo(
    val name: String,
    val specVersion: Int
)

data class MemoryTypeInfo(
    val propertyFlags: String,
    val heapIndex: Int
)

data class MemoryHeapInfo(
    val size: Long,
    val flags: String
)

data class QueueFamilyInfo(
    val queueFlags: String,
    val queueCount: Int,
    val timestampValidBits: Int,
    val minImageTransferGranularity: List<Int>
)

/** GPU 基准测试结果。 */
data class BenchmarkResult(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val deviceName: String,
    val fillBandwidthGBs: Double,
    val copyBandwidthGBs: Double,
    val computeGFLOPS: Double,
    val computeError: String,
    val cpuIntMIPS: Double,
    val cpuFloatMFLOPS: Double,
    val cpuThreads: Int,
    val totalScore: Int,
    val bufferSizeMB: Int,
    val fillIterations: Int,
    val copyIterations: Int,
    val timestampPeriodNs: Double
) {
    companion object {
        fun fromJson(json: String): BenchmarkResult {
            return try {
                val obj = JSONObject(json)
                BenchmarkResult(
                    success = obj.optBoolean("success", false),
                    errorCode = obj.optString("errorCode", "").takeIf { it.isNotEmpty() },
                    errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotEmpty() },
                    deviceName = obj.optString("deviceName", ""),
                    fillBandwidthGBs = obj.optDouble("fillBandwidthGBs", 0.0),
                    copyBandwidthGBs = obj.optDouble("copyBandwidthGBs", 0.0),
                    computeGFLOPS = obj.optDouble("computeGFLOPS", 0.0),
                    computeError = obj.optString("computeError", ""),
                    cpuIntMIPS = obj.optDouble("cpuIntMIPS", 0.0),
                    cpuFloatMFLOPS = obj.optDouble("cpuFloatMFLOPS", 0.0),
                    cpuThreads = obj.optInt("cpuThreads", 1),
                    totalScore = obj.optInt("totalScore", 0),
                    bufferSizeMB = obj.optInt("bufferSizeMB", 0),
                    fillIterations = obj.optInt("fillIterations", 0),
                    copyIterations = obj.optInt("copyIterations", 0),
                    timestampPeriodNs = obj.optDouble("timestampPeriodNs", 0.0)
                )
            } catch (e: Exception) {
                // 带上 native 原始返回片段，避免错误被吞成无信息的 "unknown"
                val snippet = json.take(120).replace("\n", " ")
                BenchmarkResult(
                    success = false,
                    errorCode = "JSON_PARSE_ERROR",
                    errorMessage = if (snippet.isBlank()) "native 返回为空" else "解析失败: $snippet",
                    deviceName = "",
                    fillBandwidthGBs = 0.0,
                    copyBandwidthGBs = 0.0,
                    computeGFLOPS = 0.0,
                    computeError = "",
                    cpuIntMIPS = 0.0,
                    cpuFloatMFLOPS = 0.0,
                    cpuThreads = 1,
                    totalScore = 0,
                    bufferSizeMB = 0,
                    fillIterations = 0,
                    copyIterations = 0,
                    timestampPeriodNs = 0.0
                )
            }
        }
    }
}
