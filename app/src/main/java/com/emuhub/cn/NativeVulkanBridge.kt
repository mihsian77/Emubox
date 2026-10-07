package com.emuhub.cn

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
}

/** 顶层采集响应。 */
data class VulkanInfoPayload(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
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
                VulkanInfoPayload(
                    success = obj.optBoolean("success", false),
                    errorCode = obj.optString("errorCode", null).takeIf { it.isNotEmpty() },
                    errorMessage = obj.optString("errorMessage", null).takeIf { it.isNotEmpty() },
                    deviceCount = obj.optInt("deviceCount", 0),
                    devices = devices
                )
            } catch (e: Exception) {
                VulkanInfoPayload(
                    success = false,
                    errorCode = "JSON_PARSE_ERROR",
                    errorMessage = e.message ?: "unknown",
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
                extensions = extensions,
                memoryTypes = memoryTypes,
                memoryHeaps = memoryHeaps,
                queueFamilies = queueFamilies,
                limits = limits
            )
        }
    }
}

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
