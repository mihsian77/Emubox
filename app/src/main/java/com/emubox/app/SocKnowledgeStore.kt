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
