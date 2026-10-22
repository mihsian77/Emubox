package com.emubox.app

import android.content.Context
import java.io.File

/**
 * 注册表磁盘缓存：远程 registry 拉取成功后落盘，冷启动秒开（本地优先），
 * 后台刷新有更新才覆盖。key 区分模拟器/排行/驱动等不同数据文件。
 */
object DiskRegistryCache {
    private const val TAG = "DiskRegistryCache"

    private fun file(context: Context, key: String): File =
        File(context.filesDir, "registry_cache_${key.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")

    /** 读缓存；文件缺失/损坏/超龄（默认 7 天）返回 null */
    fun read(context: Context, key: String, maxAgeDays: Int = 7): String? {
        return try {
            val f = file(context, key)
            if (!f.exists()) return null
            if (System.currentTimeMillis() - f.lastModified() > maxAgeDays * 24L * 3600_000L) return null
            f.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 写缓存；失败静默（不影响主流程） */
    fun write(context: Context, key: String, body: String) {
        try {
            val f = file(context, key)
            f.parentFile?.mkdirs()
            f.writeText(body)
        } catch (_: Exception) {
        }
    }
}
