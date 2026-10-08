package com.emuhub.cn

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * SOC 型号名称映射。
 *
 * 从 assets/soc_name_map.json（331 条，来自 driverscope）加载，
 * 将 Build.SOC（如 "kalama"、"anor"）映射为用户友好的名称（如 "Snapdragon 8 Gen 2"）。
 * 找不到时回退到 Build.HARDWARE 或 Build.SOC 原值。
 */
object SocNameMapper {

    @Volatile
    private var nameMap: Map<String, String>? = null

    /** 初始化（从 assets 加载，线程安全） */
    fun init(context: Context) {
        if (nameMap != null) return
        synchronized(this) {
            if (nameMap != null) return
            try {
                val json = context.assets.open("soc_name_map.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val map = HashMap<String, String>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.getString(key)
                }
                nameMap = map
            } catch (e: Exception) {
                nameMap = emptyMap()
            }
        }
    }

    /** 查询 SOC 友好名称，找不到返回 null */
    fun lookup(soc: String): String? {
        return nameMap?.get(soc)
    }

    /**
     * 获取当前设备的 SOC 友好名称。
     * 优先级：Build.SOC 映射 → Build.HARDWARE 映射 → Build.SOC 原值 → "未知"
     */
    fun getCurrentSocName(context: Context): String {
        init(context)
        val soc = Build.SOC
        if (soc.isNotEmpty()) {
            lookup(soc)?.let { return it }
        }
        val hardware = Build.HARDWARE
        if (hardware.isNotEmpty()) {
            lookup(hardware)?.let { return it }
        }
        return soc.ifEmpty { hardware.ifEmpty { "未知" } }
    }
}
