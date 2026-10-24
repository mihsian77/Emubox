package com.emubox.app

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

    // 小写键索引：Build.SOC/HARDWARE 大小写不统一（qcom/QCOM），精确未命中时兜底
    @Volatile
    private var lowerMap: Map<String, String>? = null

    /** 初始化（从 assets 加载，线程安全） */
    fun init(context: Context) {
        if (nameMap != null) return
        synchronized(this) {
            if (nameMap != null) return
            try {
                val json = context.assets.open("soc_name_map.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val map = HashMap<String, String>(obj.length())
                val lower = HashMap<String, String>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.getString(key)
                    map[key] = value
                    lower.putIfAbsent(key.lowercase(), value)
                }
                nameMap = map
                lowerMap = lower
            } catch (e: Exception) {
                nameMap = emptyMap()
            }
        }
    }

    /** 查询 SOC 友好名称，找不到返回 null；精确未命中时按小写键兜底（防 QCOM/qcom 大小写不一致） */
    fun lookup(soc: String): String? {
        return nameMap?.get(soc) ?: lowerMap?.get(soc.lowercase())
    }

    /** 英文官方名 → 芯片代号（SM8550/MT6895/Kirin 9000 等）：反查 nameMap 键。 */
    fun lookupCode(englishName: String): String? {
        if (englishName.isBlank()) return null
        val n = englishName.trim()
        val key = nameMap?.entries?.firstOrNull { (k, v) ->
            v.equals(n, ignoreCase = true) || k.equals(n, ignoreCase = true)
        }?.key ?: lowerMap?.entries?.firstOrNull { (k, v) ->
            v.equals(n, ignoreCase = true) || k.equals(n, ignoreCase = true)
        }?.key
        if (key == null) return null
        val m = Regex("(SM\\d{4}|MT\\d{4}|Kirin\\s*\\d+|[A-Z]\\d{2,4})",
            RegexOption.IGNORE_CASE).find(key) ?: return null
        return m.value.uppercase()
    }

    /**
     * GPU 型号 → 具体 SoC 官方名（英文原名）。用于两条链路：
     * 1. Build.SOC 反射失败、HARDWARE 只给到厂商级代号（qcom）时反查具体芯片；
     * 2. 各数据源（规格库/跑分引擎）gpuModel 兜底匹配。
     * 均为官方公开对应关系（Adreno 型号与骁龙平台一一对应）。
     */
    private val gpuToSoc: Map<String, String> = mapOf(
        "618" to "Snapdragon 765G", "620" to "Snapdragon 750G", "642" to "Snapdragon 690",
        "650" to "Snapdragon 865", "660" to "Snapdragon 888",
        "730" to "Snapdragon 8 Gen 1", "740" to "Snapdragon 8 Gen 2",
        "750" to "Snapdragon 8 Gen 3", "830" to "Snapdragon 8 Elite"
    )

    /** 按 GPU 型号反查 SoC 官方名；unknown/空返回 null。 */
    fun resolveByGpu(gpuModel: String): String? {
        if (gpuModel.isBlank() || gpuModel == "unknown") return null
        return gpuToSoc[gpuModel.trim()]
    }

    /** 名称是否只有厂商粒度（"高通骁龙"/"qcom"/"联发科" 等，无具体型号）。 */
    private fun isVendorGranular(name: String): Boolean {
        val t = name.lowercase()
        return t == "高通骁龙" || t == "qcom" || t == "qualcomm" ||
            t == "联发科" || t == "mediatek" || t == "海思" || t == "hisilicon"
    }

    /**
     * 获取当前设备的 SOC 友好名称。
     * Build.SOC 仅 API 31+ 可用，用反射获取；失败回退 Build.HARDWARE。
     * 若结果停留在厂商粒度（SOC/HARDWARE 只有 qcom 等代号）且 GPU 型号可反查，
     * 用 GPU 反查补全到具体芯片官方名，保证规格库/跑分引擎能精确命中。
     * 优先级：SOC 映射 → HARDWARE 映射 → GPU 反查 → SOC/HARDWARE 原值 → "未知"
     */
    fun getCurrentSocName(context: Context, gpuModel: String = ""): String {
        init(context)
        val soc = try {
            Build::class.java.getField("SOC").get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
        val hard = Build.HARDWARE
        val mapped = listOfNotNull(
            soc.takeIf { it.isNotEmpty() }?.let { lookup(it) },
            hard.takeIf { it.isNotEmpty() }?.let { lookup(hard) }
        ).firstOrNull()
        if (mapped != null && !isVendorGranular(mapped)) return mapped
        // 厂商粒度或未命中：GPU 反查具体芯片
        resolveByGpu(gpuModel)?.let { return it }
        return when {
            mapped != null -> mapped
            soc.isNotEmpty() -> soc
            hard.isNotEmpty() -> hard
            else -> "未知"
        }
    }
}
