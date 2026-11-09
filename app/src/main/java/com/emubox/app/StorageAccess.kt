package com.emubox.app

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 存储访问权限的统一判定与引导。
 *
 * 背景：此前权限只在 MainActivity.onCreate 里「静默跳一次系统设置页」，
 * 用户如果直接返回、或者没找到开关，App 不会给任何提示，只会表现为
 * 「下载点了没反应 / 下载库扫不到文件 / 删除无效」。测试第一关最容易卡在这里。
 *
 * 现在把判定收敛到这一个对象，供三处复用：
 *   1. MainActivity 启动与 onResume —— 决定是否显示全局提示条、是否弹说明；
 *   2. 下载流程 createOutputUri 失败时 —— 明确告诉用户「是权限问题，点这里去开」；
 *   3. 下载库扫描 / 删除失败时 —— 同上。
 */
object StorageAccess {

    /** Android 11+ 的「所有文件访问」权限页（部分 ROM 没有应用专属页，回退到总列表页） */
    private const val ACTION_MANAGE_ALL_FILES = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION

    /**
     * 当前是否具备「直读/直写 Download/EmuBox」的能力。
     *
     * - Android 10（Q）：分区存储下 App 通过 MediaStore 写自己的下载条目无需权限，视为可用。
     * - Android 11+（R）：需要 MANAGE_EXTERNAL_STORAGE（所有文件访问），或用户已用 SAF 指定目录。
     * - Android 9 及以下：需要 READ/WRITE_EXTERNAL_STORAGE 运行时权限。
     */
    fun hasAccess(context: Context): Boolean {
        // SAF 指定目录永远算可用：写入走 DocumentFile，不依赖系统权限
        if (SettingsManager.getDownloadFolderUri() != null) return true

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true
            else -> {
                val read = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                val write = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                read && write
            }
        }
    }

    /** 是否应该弹运行时权限请求（仅 Android 9 及以下需要） */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * 打开系统的授权页面。返回 false 表示当前系统没有可用的设置页
     * （极少数定制 ROM 裁掉了该 Activity），调用方应回退到 SAF 选目录。
     */
    @SuppressLint("InlinedApi") // 常量是字符串，低版本调用失败会走 runCatching 兜底
    fun openSettings(context: Context): Boolean {
        if (needsRuntimePermission()) return false

        val candidates = listOf(
            Intent(ACTION_MANAGE_ALL_FILES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** 缺权限时给用户看的一句话（中英由调用方选择资源，这里给中性描述） */
    fun missingHint(): String =
        "未授予「所有文件访问」权限，下载与本地文件扫描会失败"
}
