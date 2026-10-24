package com.emubox.app

import android.content.Context
import org.json.JSONObject

/** SoC 知识条目：工艺/CPU/GPU/参考跑分/常见机型 */
data class SocKnowledge(
    val name: String,
    val process: String,
    val cpu: String,
    val gpu: String,
    val gpuArch: String,
    val score: String,
    val devices: String
)

/** 芯片实测规格：CPU 架构/频率（来自安兔兔天梯内置字段，随跑分管线自动刷新） */
data class SocSpec(
    val cpuSummary: String,
    val totalCores: Int,
    val maxFreqGhZ: Double,
    val cpuScore: String,
    val gpuScore: String,
    val totalScore: String
)

/** 芯片知识库：assets/soc_knowledge.json 前缀/包含匹配 */
object SocKnowledgeStore {
    private var cache: List<Pair<List<String>, SocKnowledge>>? = null

    private fun load(context: Context): List<Pair<List<String>, SocKnowledge>> {
        cache?.let { return it }
        val entries = runCatching {
            val text = context.assets.open("soc_knowledge.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("socs")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val keys = buildList {
                        val raw = o.optJSONArray("keys")
                        if (raw != null) for (j in 0 until raw.length()) add(raw.optString(j).lowercase())
                    }
                    add(
                        keys to SocKnowledge(
                            name = o.optString("name"),
                            process = o.optString("process"),
                            cpu = o.optString("cpu"),
                            gpu = o.optString("gpu"),
                            gpuArch = o.optString("gpu_arch"),
                            score = o.optString("score"),
                            devices = o.optString("devices")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
        cache = entries
        return entries
    }

    /**
     * 按 SoC 显示名 + GPU 型号匹配知识条目。
     * GPU 型号兜底：SOC 识别链路（Build.SOC → 映射 → 匹配）任一环断裂时，
     * 用渲染器解析出的 GPU 型号（如 "740" → "adreno740"）直接命中，保证
     * Adreno/Mali 常见芯片一定能显示知识卡。
     */
    fun find(socName: String, gpuModel: String, context: Context): SocKnowledge? {
        val haystacks = buildList {
            if (socName.isNotBlank()) add(socName.lowercase())
            if (gpuModel.isNotBlank() && gpuModel != "unknown") {
                add("adreno$gpuModel")
                add("mali$gpuModel")
            }
        }
        if (haystacks.isEmpty()) return null
        return load(context).firstOrNull { (keys, _) ->
            keys.any { k -> haystacks.any { h -> h.contains(k) } }
        }?.second
    }
}

/** 芯片规格库：assets/bench/soc_specs.json（安兔兔 clusters 字段解析，随跑分数据刷新） */
object SocSpecStore {
    private var cache: Map<String, SocSpec>? = null

    private fun load(context: Context): Map<String, SocSpec> {
        cache?.let { return it }
        val map = runCatching {
            val text = context.assets.open("bench/soc_specs.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(text).getJSONObject("socs")
            val out = mutableMapOf<String, SocSpec>()
            val names = obj.names() ?: return@runCatching emptyMap<String, SocSpec>()
            for (i in 0 until names.length()) {
                val key = names.getString(i)
                val o = obj.getJSONObject(key)
                if (!o.has("cpu_summary")) continue
                out[key] = SocSpec(
                    cpuSummary = o.getString("cpu_summary"),
                    totalCores = o.optInt("total_cores"),
                    maxFreqGhZ = o.optDouble("max_freq_ghz"),
                    cpuScore = o.optString("cpu_score"),
                    gpuScore = o.optString("gpu_score"),
                    totalScore = o.optString("total_score")
                )
            }
            out
        }.getOrElse { emptyMap() }
        cache = map
        return map
    }

    /** 归一化：去空格/连字符/下划线 + 厂商英文名转中文（设备上报多英文，数据表多中文）。 */
    private fun normalizeSoc(s: String): String {
        var t = s.lowercase().replace(" ", "").replace("-", "").replace("_", "")
        t = t.replace("snapdragon", "骁龙")
            .replace("dimensity", "天玑")
            .replace("kirin", "麒麟")
            .replace("exynos", "exynos")
        return t
    }

    /** 按 SoC 名匹配规格（antutu 全名如「高通 骁龙888」；socName 如「骁龙888/Snapdragon 888」双向归一化匹配） */
    fun find(socName: String, context: Context): SocSpec? {
        if (socName.isBlank()) return null
        val h = normalizeSoc(socName)
        val specs = load(context)
        specs.forEach { (k, v) ->
            val kk = normalizeSoc(k)
            if (kk.contains(h) || h.contains(kk)) return v
        }
        return null
    }
}
