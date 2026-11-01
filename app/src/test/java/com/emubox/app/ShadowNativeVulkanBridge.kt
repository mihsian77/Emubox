package com.emubox.app

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * 测试替身：真实设备检测依赖原生 Vulkan 采集库（.so），
 * 在电脑上无法加载，这里用固定数据代替，只用于界面版式验证。
 */
@Implements(NativeVulkanBridge::class)
class ShadowNativeVulkanBridge {

    @Implementation
    fun collectVulkanInfo(driverPath: String?): String = """
        {"ok":true,"deviceName":"Adreno (TM) 750","apiVersion":"1.3.275",
         "driverVersion":"512.744.0","vendorId":20803,"deviceId":0,
         "deviceType":"integrated-gpu","extensions":[],"limits":{},
         "memoryHeaps":[],"queueFamilies":[]}
    """.trimIndent()

    @Implementation
    fun benchmarkVulkan(driverPath: String?): String = """
        {"ok":true,"fillBandwidth":128.4,"copyBandwidth":96.2,"durationMs":20}
    """.trimIndent()
}
