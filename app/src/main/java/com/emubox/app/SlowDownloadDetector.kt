package com.emubox.app

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 慢下载检测器：监控下载速度，持续低速时推荐开启加速。
 *
 * 逻辑（参考 komi-store）：
 * - 持续 30 秒以上速度低于 100 KB/s → 记录一次"慢事件"
 * - 10 分钟内出现 3 次 → 触发推荐
 * - 如果当前已经在用加速（非直连）→ 不推荐
 */
object SlowDownloadDetector {

    private const val SUSTAINED_MS = 30L * 1000          // 持续 30 秒
    private const val THRESHOLD_BYTES_PER_SEC = 100L * 1024  // 100 KB/s
    private const val WINDOW_MS = 10L * 60 * 1000        // 10 分钟窗口
    private const val TRIGGER_COUNT = 3                    // 3 次触发

    private val mutex = Mutex()
    private val speedSamples = ArrayDeque<Pair<Long, Long>>()  // (时间戳, 已下载字节)
    private val slowEvents = ArrayDeque<Long>()                  // 慢事件时间戳

    // 推荐开启加速的事件流（UI 层观察此 Flow 弹出对话框）
    private val _suggestAccelerator = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val suggestAccelerator = _suggestAccelerator.asSharedFlow()

    // 用户是否已选择"不再提示"
    @Volatile
    var dismissed: Boolean = false

    // 暂停推荐直到某个时间戳（"稍后提醒"）
    @Volatile
    private var snoozeUntil: Long = 0L

    /**
     * 记录下载进度，检测是否持续低速。
     * @param fileName 下载文件名
     * @param downloadedBytes 已下载字节数
     * @param totalBytes 总字节数（用于判断是否快完成）
     */
    suspend fun onProgress(fileName: String, downloadedBytes: Long, totalBytes: Long) {
        // 快完成了（>90%）就不检测了，避免最后阶段误报
        if (totalBytes > 0 && downloadedBytes > totalBytes * 0.9) return

        // 如果已经在用加速，不推荐
        if (Accelerator.getMode() != Accelerator.Mode.OFF) return

        // 用户已关闭推荐或在暂停期内
        if (dismissed) return
        if (SystemClock.elapsedRealtime() < snoozeUntil) return

        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            speedSamples.addLast(now to downloadedBytes)

            // 清理超过持续窗口的样本
            while (speedSamples.isNotEmpty() && speedSamples.first().first < now - SUSTAINED_MS) {
                speedSamples.removeFirst()
            }

            // 窗口内有足够样本，计算平均速度
            if (speedSamples.size >= 2) {
                val first = speedSamples.first()
                val last = speedSamples.last()
                val elapsedMs = (last.first - first.first).coerceAtLeast(1L)
                val elapsedSec = elapsedMs / 1000.0
                val deltaBytes = (last.second - first.second).coerceAtLeast(0L)
                val bytesPerSec = (deltaBytes / elapsedSec).toLong()

                val windowFull = elapsedMs >= SUSTAINED_MS - 500
                if (windowFull && bytesPerSec < THRESHOLD_BYTES_PER_SEC) {
                    recordSlowEvent(now)
                }
            }
        }
    }

    private fun recordSlowEvent(timestampMs: Long) {
        slowEvents.addLast(timestampMs)

        // 清理超过时间窗口的慢事件
        while (slowEvents.isNotEmpty() && slowEvents.first() < timestampMs - WINDOW_MS) {
            slowEvents.removeFirst()
        }

        // 达到触发次数
        if (slowEvents.size >= TRIGGER_COUNT) {
            slowEvents.clear()
            speedSamples.clear()
            _suggestAccelerator.tryEmit(Unit)
        }
    }

    /** 重置检测状态（切换加速模式、用户手动操作时调用） */
    suspend fun reset() {
        mutex.withLock {
            speedSamples.clear()
            slowEvents.clear()
        }
    }

    /** 用户选择"稍后提醒"，暂停 N 分钟 */
    fun snooze(minutes: Int = 30) {
        snoozeUntil = SystemClock.elapsedRealtime() + minutes * 60 * 1000L
    }

    /** 用户选择"不再提示" */
    fun dismissForever() {
        dismissed = true
    }
}
