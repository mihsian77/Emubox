package com.emubox.app

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 驱动检测「没等到结果」的归因。放在独立文件里，不占 DriverDetectionUi 的行数预算
 * （结构闸门要求那个文件只许变小）。
 *
 * 背景：25 秒轮询超时只说明没有结果文件，不能直接断言「驱动崩溃」。低端机加载
 * 大驱动、系统 IO 抖动时进程活得好好的。此前把「慢」和「死」塞进同一个判据
 * （.started 标记存在 = 崩溃），等于诬陷驱动不兼容——用户据此换驱动、刷机。
 */
internal data class NoResult(
    val code: String,
    val message: String,
    val crashed: Boolean,
    val timedOut: Boolean
)

/**
 * :drivertest 进程是否还在。
 *
 * 返回 null 表示查不到：Android 8+ 的 runningAppProcesses 只对本应用（同 uid）
 * 可见，理论上包含 :drivertest，但部分 ROM 会裁剪这个列表。查不到时调用方
 * 必须按「无法确认」处理，不能当成崩溃。
 */
internal fun driverTestProcessAlive(context: Context): Boolean? = runCatching {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        ?: return@runCatching null
    val procs = am.runningAppProcesses ?: return@runCatching null
    procs.any { it.processName == "${context.packageName}:drivertest" }
}.getOrNull()

/** 超时但仍活着的进程要主动收掉，否则下一轮检测会和它抢同一个结果文件 */
internal fun stopDriverTestService(context: Context) {
    runCatching { context.stopService(Intent(context, DriverTestService::class.java)) }
}

/**
 * 把「没结果」拆成三种：进程没起来 / 进程被杀死 / 进程还活着或状态未知。
 * 只有第二种是驱动的问题，另外两种不该让用户去换驱动。
 */
internal fun classifyNoResult(context: Context, stage: String, resultId: String): NoResult {
    val started = File(context.filesDir, "driver_test_${resultId}.started").exists()
    if (!started) {
        return NoResult(
            code = "SERVICE_NOT_STARTED",
            message = "${stage}阶段独立进程未启动（多为系统后台限制或组件被禁用），与驱动本身无关，请重试",
            crashed = false, timedOut = false
        )
    }
    return when (driverTestProcessAlive(context)) {
        true -> NoResult(
            code = "DETECT_TIMEOUT",
            message = "${stage}阶段超时：25 秒内未返回结果，独立进程仍在运行（可能是驱动加载慢或系统繁忙），请重试",
            crashed = false, timedOut = true
        )
        null -> NoResult(
            code = "DETECT_TIMEOUT",
            message = "${stage}阶段超时：25 秒内未返回结果，进程状态无法确认（可能是驱动加载慢或被系统限制），请重试",
            crashed = false, timedOut = true
        )
        false -> NoResult(
            code = "DRIVER_CRASHED_IN_SERVICE",
            message = "${stage}阶段进程被系统杀死——驱动与本机 GPU 不兼容或驱动包损坏的明确信号",
            crashed = true, timedOut = false
        )
    }
}
