package com.emubox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载保活前台服务。
 *
 * 问题：此前下载跑在 Activity 的 lifecycleScope / 进程内的 IO 协程上，
 * App 切后台或锁屏后进程被系统降级为 cached，GB 级驱动下载会被冻结，
 * 表现为「锁屏回来显示暂停，得手动点继续」，长下载几乎必然被打断。
 *
 * 方案：只要有活动下载，就常驻一个 dataSync 类型的前台服务并挂常驻通知。
 * 前台服务所在进程不会被当作可回收的缓存进程，下载可跨后台/锁屏持续。
 *
 * 刻意不做的：把下载逻辑搬进 Service。下载状态目前是 DownloadsManager 的
 * 内存状态 + 磁盘 partial 断点，改造收益有限、回归风险大；前台服务只需要
 * 「让进程活着」，网络传输仍在原协程里跑。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val started = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(this))
        }.isSuccess
        if (!started) {
            // 系统拒绝（后台限制 / 通知被禁用等）：不强行驻留，下载仍在原协程继续
            Log.w(TAG, "startForeground 被拒绝，跳过保活")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startTicker()
        return START_NOT_STICKY
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val active = DownloadsManager.activeDownloads.values.toList()
                if (active.isEmpty()) break
                runCatching {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(this@DownloadService))
                }
                delay(1_000L)
            }
            // 没有活动下载了：主动收工，不在后台赖着。
            // 用 lastStartId 而非 stopSelf()：避免把期间新启动的实例一起杀掉
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EmuBoxDownloadSvc"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4101

        /** 有活动下载就确保服务在跑，没有就停掉。可由任意线程调用。 */
        fun sync(context: Context) {
            val app = context.applicationContext
            val running = DownloadsManager.activeDownloads.isNotEmpty()
            val intent = Intent(app, DownloadService::class.java)
            if (running) {
                // minSdk 28：startForegroundService 永远可用，无需版本分支
                runCatching { app.startForegroundService(intent) }.onFailure {
                    // Android 12+ 后台启动前台服务会被拒：此时 App 已不在前台，
                    // 下载仍继续跑（只是失去保活），不阻断流程。
                    Log.w(TAG, "前台服务启动被系统拒绝", it)
                }
            } else {
                runCatching { app.stopService(intent) }
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keep driver downloads alive in background"
                    setShowBadge(false)
                }
            )
        }

        private fun buildNotification(context: Context): Notification {
            val active = DownloadsManager.activeDownloads.values.toList()
            val count = active.size
            val lang = runCatching { SettingsManager.getAppLanguage() }.getOrDefault(AppLanguage.SYSTEM)
            val running = active.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }

            val title = if (count == 0) {
                appStringFor(context, lang, R.string.download_service_done)
            } else {
                appStringFor(
                    context, lang, R.string.download_service_title,
                    if (running > 0) running else count
                )
            }
            val body = active.take(3).joinToString("\n") { d ->
                "${d.fileName}  ${if (d.progress in 1..100) "${d.progress}%" else ""}".trim()
            }.ifBlank { appStringFor(context, lang, R.string.download_service_idle) }

            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // minSdk 28：通知渠道构造器永远可用
            val builder = Notification.Builder(context, CHANNEL_ID)

            val totalBytes = active.sumOf { it.totalBytes }
            val doneBytes = active.sumOf { it.downloadedBytes }
            if (totalBytes > 0L) {
                builder.setProgress(100, ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }

            return builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(active.firstOrNull()?.fileName ?: body.lineSequence().firstOrNull() ?: "")
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
        }
    }
}
