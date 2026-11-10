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

/**
 * 芯片知识库：以 assets/soc_master.json（389 颗，随跑分管线刷新）为主，
 * assets/soc_knowledge.json（58 条人工校对）为覆盖层。
 *
 * 之前只有手写那 58 条能出知识卡，主表里有、手写里没有的芯片（绝大多数中端/新芯片）
 * 一律落到「未收录」，同一颗芯片在「跑分」卡里有数据、在「知识」卡里却认不出，
 * 这就是两个数据源各说各话。现在统一成一份：主表提供全部字段，
 * 手写条目按芯片名覆盖（人工核对过的机型/工艺更可信），匹配键取并集。
 */
object SocKnowledgeStore {
    /**
     * 一条知识：名称键（芯片名/别名/部件号）与 GPU 键分开存。
     * 分开的原因：Adreno 650 这种 GPU 被 865/865+/870 多颗芯片共用，
     * 混在一起按「键最长」取会随机命中隔壁芯片（865 被判成 870）。
     */
    private class Entry(
        val nameKeys: List<String>,
        val gpuKeys: List<String>,
        val soc: SocKnowledge
    )

    private var cache: List<Entry>? = null

    /** 与 find() 的 haystack 同规则：小写、去分隔符、英文厂牌转中文 */
    private fun norm(s: String): String {
        var t = s.lowercase().replace(" ", "").replace("-", "").replace("_", "")
        t = t.replace("snapdragon", "骁龙")
            .replace("dimensity", "天玑")
            .replace("kirin", "麒麟")
            .replace("qualcomm", "高通")
        return t
    }

    /** 安兔兔总分 → 「约 280 万」；无数据留空，界面按「未收录」处理，不编造 */
    private fun scoreText(raw: String): String {
        val v = raw.toLongOrNull() ?: return ""
        if (v <= 0) return ""
        val wan = v / 10000.0
        return if (wan >= 100) "约 ${wan.toInt()} 万" else "约 ${"%.1f".format(wan)} 万"
    }

    private fun gpuArchOf(gpu: String): String {
        val g = gpu.lowercase()
        return when {
            g.contains("adreno") -> "adreno"
            g.contains("mali") || g.contains("immortalis") -> "mali"
            g.contains("xclipse") -> "xclipse"
            g.contains("powervr") -> "powervr"
            else -> ""
        }
    }

    /** 主表派生：名称/英文名/别名/部件号做名称键，GPU 型号单独做兜底键 */
    private fun masterEntries(context: Context): List<Entry> = runCatching {
        val arr = JSONObject(
            context.assets.open("soc_master.json").bufferedReader().use { it.readText() }
        ).getJSONArray("socs")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                val raw = buildList {
                    add(name)
                    add(o.optString("name_en"))
                    o.optJSONArray("names")?.let { ns ->
                        for (j in 0 until ns.length()) add(ns.optString(j))
                    }
                    o.optJSONArray("codes")?.let { cs ->
                        for (j in 0 until cs.length()) add(cs.optString(j))
                    }
                }
                val nameKeys = raw.map { norm(it) }.filter { it.length >= 5 }.distinct()
                val gpuKeys = listOf(norm(o.optString("gpu_key"))).filter { it.length >= 5 }
                if (nameKeys.isEmpty()) continue
                val gpu = o.optString("gpu")
                add(
                    Entry(
                        nameKeys = nameKeys,
                        gpuKeys = gpuKeys,
                        soc = SocKnowledge(
                            name = name,
                            process = o.optString("process"),
                            cpu = o.optString("cpu"),
                            gpu = gpu,
                            gpuArch = gpuArchOf(gpu),
                            score = scoreText(o.optString("antutu")),
                            devices = o.optString("devices")
                        )
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    /** 手写条目：字段经过人工核对，命中同名芯片时覆盖主表 */
    private fun manualEntries(context: Context): List<Entry> = runCatching {
        val arr = JSONObject(
            context.assets.open("soc_knowledge.json").bufferedReader().use { it.readText() }
        ).getJSONArray("socs")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val keys = buildList {
                    val raw = o.optJSONArray("keys")
                    if (raw != null) for (j in 0 until raw.length()) add(norm(raw.optString(j)))
                }.filter { it.isNotBlank() }
                // 手写表把 adreno740 这类 GPU 键混在 keys 里，按前缀拆回去
                add(
                    Entry(
                        nameKeys = keys.filterNot { it.startsWith("adreno") || it.startsWith("mali") },
                        gpuKeys = keys.filter { it.startsWith("adreno") || it.startsWith("mali") },
                        soc = SocKnowledge(
                            name = o.optString("name"),
                            process = o.optString("process"),
                            cpu = o.optString("cpu"),
                            gpu = o.optString("gpu"),
                            gpuArch = o.optString("gpu_arch"),
                            score = o.optString("score"),
                            devices = o.optString("devices")
                        )
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun load(context: Context): List<Entry> {
        cache?.let { return it }
        // 以归一化芯片名为身份合并；手写条目覆盖同名主表条目，匹配键取并集
        val byName = LinkedHashMap<String, Entry>()
        for (e in masterEntries(context)) byName[norm(e.soc.name)] = e
        for (e in manualEntries(context)) {
            val id = norm(e.soc.name)
            val old = byName[id]
            byName[id] = Entry(
                nameKeys = ((old?.nameKeys ?: emptyList()) + e.nameKeys).distinct(),
                gpuKeys = ((old?.gpuKeys ?: emptyList()) + e.gpuKeys).distinct(),
                soc = e.soc
            )
        }
        val entries = byName.values.toList()
        // 空结果不缓存：首次调用早于 AssetManager 就绪时会把空表固定在内存里，
        // 之后整场都显示「未收录」
        if (entries.isNotEmpty()) cache = entries
        return entries
    }

    /**
     * 按 SoC 显示名 + GPU 型号匹配知识条目。
     *
     * 两级匹配：
     * 1. 名称键（芯片名/别名/部件号）——取命中键最长者，区分 855 与 855 Plus、
     *    865 与 865 IoT 这类派生型号；
     * 2. 名称认不出时才用 GPU 型号兜底，且**只有唯一候选**才采用：
     *    Adreno 650 对应 865/865+/870 三颗芯片，此时给任何一颗都是错的，
     *    宁可显示「未收录」也不显示错的芯片。
     */
    fun find(socName: String, gpuModel: String, context: Context): SocKnowledge? {
        val socHay = socName.takeIf { it.isNotBlank() }?.let { norm(it) }
        val gpuHay = gpuModel
            .takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { listOf("adreno$it", "mali$it") }
            .orEmpty()
        if (socHay == null && gpuHay.isEmpty()) return null
        val entries = load(context)
        // 1) 名称键：socName 包含某个键即命中，键越长越具体
        if (socHay != null) {
            val named = entries
                .mapNotNull { e ->
                    val hit = e.nameKeys
                        .filter { k -> k.length >= 5 && socHay.contains(k) }
                        .maxOfOrNull { it.length }
                    if (hit != null) hit to e.soc else null
                }
                .maxByOrNull { it.first }
                ?.second
            if (named != null) return named
        }
        // 2) GPU 兜底：唯一候选才算命中
        val gpuHits = entries.filter { e ->
            e.gpuKeys.any { k -> gpuHay.any { h -> h == k || h.contains(k) } }
        }
        return gpuHits.singleOrNull()?.soc
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
        // Qualcomm 前缀与中文「高通」统一成「高通」，避免「qualcomm骁龙8gen2」整体 miss
        t = t.replace("qualcomm", "高通")
        return t
    }

    /** 按 SoC 名匹配规格（antutu 全名如「高通 骁龙888」；socName 如「骁龙888/Snapdragon 888」）。
     *  匹配策略：精确相等优先；其次数据表键包含查询名（要求查询名足够长，≥6 字符）；
     *  禁止反向包含——「高通骁龙」这类厂商粒度名会把全部高通条目当候选，
     *  HashMap 无序首命中导致显示随机错误芯片（曾命中骁龙 4 Gen 1）。 */
    fun find(socName: String, context: Context): SocSpec? {
        if (socName.isBlank()) return null
        val h = normalizeSoc(socName)
        val specs = load(context)
        specs.forEach { (k, v) ->
            val kk = normalizeSoc(k)
            if (kk == h) return v
        }
        if (h.length >= 6) {
            specs.forEach { (k, v) ->
                val kk = normalizeSoc(k)
                if (kk.contains(h)) return v
            }
        }
        return null
    }
}
