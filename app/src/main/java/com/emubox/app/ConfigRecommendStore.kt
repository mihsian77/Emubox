package com.emubox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 社区配置组合条目：组件版本组合 + 使用次数/占比 */
data class ConfigCombo(
    val combo: List<Pair<String, String>>,  // [("DXVK","2.7.3"), ...]
    val count: Int,
    val share: Double
)

/** GPU 匹配到的社区配置：统计层（top 组合）+ 官方层（入门配置原则） */
data class GpuConfigRecommend(
    val gpuKey: String,           // 命中的 recipes 键，如 Adreno-830 / Mali-G57
    val configs: Int,             // 该 GPU 收录配置总数
    val top: List<ConfigCombo>,   // top 组合（按 count/share）
    val official: List<OfficialRecipe>
)

/** 官方/社区入门配置：原则性设置 + 校验要点 */
data class OfficialRecipe(
    val title: String,
    val summary: String,
    val settings: List<Pair<String, String>>,
    val checks: List<String>
)

/**
 * 社区主流配置推荐库：assets/winlator-recipes.json
 * - 统计层：gpu_combos / mali_combos（社区配置聚合，按 GPU 热度排行）
 * - 官方层：official_recipes（入门配置指引，来自 emulator-ranking 数据源）
 */
object ConfigRecommendStore {
    private var cache: JSONObject? = null

    private fun load(context: Context): JSONObject? {
        cache?.let { return it }
        val obj = runCatching {
            val text = context.assets.open("winlator-recipes.json")
                .bufferedReader().use { it.readText() }
            JSONObject(text)
        }.getOrNull()
        cache = obj
        return obj
    }

    private fun parseOfficial(arr: JSONArray?): List<OfficialRecipe> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val settings = buildList {
                    val s = o.optJSONArray("settings")
                    if (s != null) for (j in 0 until s.length()) {
                        val x = s.getJSONObject(j)
                        add(x.optString("key") to x.optString("value"))
                    }
                }
                val checks = buildList {
                    val c = o.optJSONArray("checks")
                    if (c != null) for (j in 0 until c.length()) {
                        add(c.getJSONObject(j).optString("title"))
                    }
                }
                add(
                    OfficialRecipe(
                        title = o.optString("title"),
                        summary = o.optString("summary"),
                        settings = settings,
                        checks = checks
                    )
                )
            }
        }
    }

    private fun parseCombos(arr: JSONArray?): List<ConfigCombo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val combo = buildList {
                    val c = o.optJSONArray("combo")
                    if (c != null) for (j in 0 until c.length()) {
                        val pair = c.getJSONArray(j)
                        if (pair.length() >= 2) add(pair.getString(0) to pair.getString(1))
                    }
                }
                add(ConfigCombo(combo, o.optInt("count"), o.optDouble("share")))
            }
        }
    }

    /**
     * 按 GPU 型号匹配社区配置推荐。
     * gpuModel 形如 "740" / "G57" / "adreno 740"；命中规则：
     * 含 mali 字样走 mali_combos，其余走 gpu_combos；键用型号数字段匹配。
     */
    fun find(gpuModel: String, context: Context): GpuConfigRecommend? {
        val root = load(context) ?: return null
        if (gpuModel.isBlank() || gpuModel == "unknown") return null
        val m = gpuModel.lowercase().replace(" ", "")
        val pool = if (m.contains("mali")) {
            root.optJSONObject("mali_combos")
        } else {
            root.optJSONObject("gpu_combos")
        } ?: return null
        if (pool.length() == 0) return null

        // 型号数字段：提取 gpuModel 中的 Adreno 数字或 Mali G 编号
        val num = Regex("""(?:adreno|g)?(\d{3,4})""").find(m)?.groupValues?.get(1)
            ?: Regex("""g(\d{2,3})""").find(m)?.groupValues?.get(1)
        var matched: Pair<String, JSONObject>? = null
        val names = pool.names() ?: return null
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val k = key.lowercase().replace(" ", "")
            // 键含型号数字即命中（Adreno-740 ↔ "740"；Mali-G57 ↔ "g57"）
            if (num != null && k.contains(num)) { matched = key to pool.getJSONObject(key); break }
            if (m.contains(k) || k.contains(m)) { matched = key to pool.getJSONObject(key); break }
        }
        if (matched == null) return null
        val (key, o) = matched
        // 官方层：同 GPU 厂商的入门配置（Adreno/Mali 各一条）
        val isMali = key.startsWith("Mali", ignoreCase = true)
        val official = parseOfficial(root.optJSONArray("official_recipes"))
            .filter { (isMali && it.title.contains("Mali")) || (!isMali && it.title.contains("Adreno")) }

        return GpuConfigRecommend(
            gpuKey = key,
            configs = o.optInt("configs"),
            top = parseCombos(o.optJSONArray("top")),
            official = official
        )
    }
}
