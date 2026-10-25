package com.emubox.app

import android.content.Context
import java.io.File

/**
 * 外部新版 adrenotools 运行时（FauxVkTest 1.11 同款机制）引导。
 *
 * 内置 adrenotools 为 2021 linkernsbypass 旧版，其 GSL hook 对现代 turnip
 * （mesa 25+/27）的 GPU mapping import 初始化不兼容：warmup 通过后打开驱动
 * 瞬间被杀（崩溃步骤停在 mapping_import_try）。FauxVkTest 内置新版运行时
 * （android_dlopen_ext hook + /dev/kgsl 直连），本类把其运行时组件从 assets
 * 解压到 filesDir 并通知 native dlopen；失败静默回退内置旧版（不中断功能）。
 */
object RuntimeBootstrap {

    private const val RUNTIME_ASSET_DIR = "fauxvktest-runtime"
    private const val READY_MARKER = ".ready"

    private val soNames = listOf(
        "libvulkan_samples.so", "libhook_impl.so", "libmain_hook.so",
        "libgsl_alloc_hook.so", "libfile_redirect_hook.so"
    )

    fun runtimeDir(context: Context): File = File(context.filesDir, RUNTIME_ASSET_DIR)

    /** 解压运行时（幂等，带 ready 标记）+ 通知 native。主进程与 :drivertest 独立进程共用。 */
    fun ensure(context: Context) {
        val dir = runtimeDir(context)
        if (File(dir, READY_MARKER).exists()) {
            NativeVulkanBridge.setExternalRuntimeDir(dir.absolutePath)
            return
        }
        try {
            dir.mkdirs()
            for (name in soNames) {
                context.assets.open("$RUNTIME_ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            File(dir, READY_MARKER).writeText("1")
            NativeVulkanBridge.setExternalRuntimeDir(dir.absolutePath)
        } catch (e: Exception) {
            // assets 缺失 / IO 失败：静默回退内置旧版运行时
        }
    }
}
