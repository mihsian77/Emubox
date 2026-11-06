package com.emubox.app

import android.content.Context
import java.io.File

/**
 * 已退役的第三方运行时引导。
 *
 * 历史：此类曾把 FauxVkTest 1.11 同款运行时（新版 adrenotools hook 库 + Vulkan-Samples
 * 修改版，共 13.9 MB）从 assets 解压到 filesDir，再通知 native dlopen，用于修复内置
 * 2021 版 adrenotools 在现代 turnip 上的 mapping import 崩溃。
 *
 * 移除原因：
 * 1. 这些 .so 从闭源 APK 中提取，其中 libvulkan_samples.so 是 Vulkan-Samples 的
 *    "MODIFIED build"（Apache-2.0），上游补丁仓已 404、修改说明缺失，随包分发有授权瑕疵；
 * 2. 体积占 APK 近四分之一，而它的能力（换驱动渲染实测）正由自研 DriverCheck 接管。
 *
 * 现在本类只做两件收尾：清掉老版本已解压到 filesDir 的残留，并把 native 侧外部运行时
 * 目录置空，让驱动加载回到内置路径。调用点保持原样，幂等且失败静默。
 */
object RuntimeBootstrap {

    private const val LEGACY_DIR = "fauxvktest-runtime"

    fun retireLegacy(context: Context) {
        // 老版本升级上来的机器：filesDir 里可能还躺着上次解压的第三方 .so
        runCatching {
            val dir = File(context.filesDir, LEGACY_DIR)
            if (dir.exists()) dir.deleteRecursively()
        }
        // 置空外部运行时路径 → native 回退内置加载；native 未加载时忽略
        runCatching { NativeVulkanBridge.setExternalRuntimeDir(null) }
    }
}
