package com.emubox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 等效 PC 配置：同分制最近邻换算结果 */
data class PcEquiv(val cpuName: String, val cpuScore: Long, val gpuName: String, val gpuScore: Long)

/** 模拟器体验档位 */
data class EmulatorTier(val id: String, val name: String, val tier: String, val note: String)

/** 升级建议：当前芯片的下一性能档 */
/** 升级建议：首选同厂商更高档位；次选跨厂商（增益 15%~50% 内最近） */
data class UpgradeSuggestion(
    val nextName: String,
    val nextScore: Long,
    val gainPct: Int,
    /** 跨厂商次选（同厂商无候选或增益过近时给出；无则 null） */
    val altName: String? = null,
    val altScore: Long? = null,
    val altGainPct: Int? = null
)

/** 同芯机型条目：榜单收录才有分，未收录为 null（如实展示） */
data class SameChipDevice(val name: String, val score: Long?)

/** 本机机型跑分（安兔兔 V10 手机榜，按具体机型而非处理器） */
data class DeviceBench(
    val name: String,
    val soc: String,
    val cpuScore: Long?,
    val gpuScore: Long?,
    val totalScore: Long?
)

/** 驱动实测 ↔ PC 显卡物理量对比（带宽/算力，跨体系但单位可比） */
data class PcGpuCompare(
    val bandwidthName: String, val bandwidthGBs: Double, val bandwidthPct: Int,
    val computeName: String, val computeTFLOPS: Double, val computePct: Int
)

/** SoC 跑分综合信息 */
data class SocBenchInfo(
    val name: String,
    val family: String,
    val year: String,
    val antutuName: String?,
    val antutuTotal: Long?,
    val antutuCpu: Long?,
    val antutuGpu: Long?,
    val antutuClusters: String?,
    val ludashiScore: Long?,
    val pcEquiv: PcEquiv?,
    val gpuEquivName: String?,
    val gpuEquivNvidia: String?,
    val gpuEquivScore: Long?,
    val gpuEquivRatio: Double?,
    val emulatorTiers: List<EmulatorTier>,
    val upgrade: UpgradeSuggestion?,
    val sameChip: List<SameChipDevice>?,
    val generation: List<Triple<String, String, Long>>, // (年份, 名称, 总分)
    val deviceBench: DeviceBench?
)

/**
 * 跑分引擎：基于 assets/bench/bench_meta.json（curated 别名映射 + 阈值表）
 * 与 ludashi.json（同分制 PC 榜）的最近邻换算。
 *
 * 数据来源：安兔兔 SoC 天梯、鲁大师五榜，由 CI 每日刷新。
 * 匹配失败/数据缺失一律返回可展示的空态，不编造。
 */
object BenchEngine {

    private var metaCache: JSONObject? = null
    private var ludashiCache: JSONObject? = null

    /** 归一化：去空格/连字符/下划线 + 厂商英文名转中文（设备上报多英文，数据表多中文）。 */
    private fun normalizeSoc(s: String): String {
        var t = s.lowercase().replace(" ", "").replace("-", "").replace("_", "")
        t = t.replace("snapdragon", "骁龙")
            .replace("dimensity", "天玑")
            .replace("kirin", "麒麟")
            .replace("exynos", "exynos")
        t = t.replace("qualcomm", "高通")
        return t
    }

    private fun meta(context: Context): JSONObject? {
        metaCache?.let { return it }
        metaCache = runCatching {
            JSONObject(context.assets.open("bench/bench_meta.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        return metaCache
    }

    private fun ludashi(context: Context): JSONObject? {
        ludashiCache?.let { return it }
        ludashiCache = runCatching {
            JSONObject(context.assets.open("bench/ludashi.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        return ludashiCache
    }

    /** 入口：按 SoC 显示名 + GPU 型号解析综合信息 */    fun resolve(socName: String, gpuModel: String, context: Context): SocBenchInfo? {
        val m = meta(context) ?: return null
        val arr = m.optJSONArray("aliases") ?: return null
        val query = buildList {
            if (socName.isNotBlank()) add(normalizeSoc(socName))
            if (gpuModel.isNotBlank() && gpuModel != "unknown") {
                add("adreno$gpuModel")
                add("mali$gpuModel")
            }
            // GPU 反查具体芯片名（Adreno 740 → 骁龙8gen2）：厂商粒度名或 GPU 代号兜底必中
            SocNameMapper.resolveByGpu(gpuModel)?.let { add(normalizeSoc(it)) }
        }
        if (query.isEmpty()) return null
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val keys = o.optJSONArray("keys") ?: continue
            for (j in 0 until keys.length()) {
                val k = normalizeSoc(keys.optString(j))
                // 双向包含：数据表键为中文（骁龙8 Gen2）、设备上报为英文（Snapdragon 8 Gen 2）
                // 归一化后统一为「骁龙8gen2」双向命中；SM8550 类代号同理
                if (query.any { q -> q.contains(k) || k.contains(q) }) { best = o; break }
            }
            if (best != null) break
        }
        val entry = best ?: return null
        val name = entry.optString("keys").let { JSONArray(it).optString(0) }
        val antutuTotal = entry.optLong("antutu_total", -1L).takeIf { it > 0 }
        val antutuCpu = entry.optLong("antutu_cpu", -1L).takeIf { it > 0 }
        val antutuGpu = entry.optLong("antutu_gpu", -1L).takeIf { it > 0 }
        val ludashiScore = entry.optLong("ludashi_score", -1L).takeIf { it > 0 }
        val family = entry.optString("family").ifBlank { "其他" }
        val year = entry.optString("year")

        val pcEquiv = ludashiScore?.let { equivalentPc(it, context) }
        val gpuEquiv = entry.optJSONObject("gpu_equiv")
        val tiers = emulatorTiers(antutuTotal, m.optJSONObject("emulator_tiers"))
        val selfBrand = entry.optString("keys").let { k ->
            when {
                JSONArray(k).optString(0).contains("骁龙") -> "骁龙"
                JSONArray(k).optString(0).contains("天玑") -> "天玑"
                JSONArray(k).optString(0).contains("Exynos") -> "Exynos"
                else -> ""
            }
        }
        val upgrade = antutuTotal?.let { upgradeFrom(it, arr, selfBrand) }
        val sameChip = sameChipDevices(family, m.optJSONArray("same_chip_devices"))
        val gen = generation(family, arr, name, context)
        val deviceBench = deviceBench(context)

        return SocBenchInfo(
            name = name, family = family, year = year,
            antutuName = entry.optString("antutu_name").ifBlank { null },
            antutuTotal = antutuTotal, antutuCpu = antutuCpu, antutuGpu = antutuGpu,
            antutuClusters = entry.optString("antutu_clusters").ifBlank { null },
            ludashiScore = ludashiScore,
            pcEquiv = pcEquiv,
            gpuEquivName = gpuEquiv?.optString("name")?.ifBlank { null },
            gpuEquivNvidia = gpuEquiv?.optString("nvidia_name")?.ifBlank { null },
            gpuEquivScore = gpuEquiv?.optLong("score", -1)?.takeIf { it > 0 },
            gpuEquivRatio = gpuEquiv?.optDouble("ratio", -1.0)?.takeIf { it > 0 },
            emulatorTiers = tiers, upgrade = upgrade,
            sameChip = sameChip, generation = gen,
            deviceBench = deviceBench
        )
    }

    private var deviceBenchCache: DeviceBench? = null
    private var marketingNameCache: String? = null

    /** 本机营销名：device_models.json 映射（V2338A→iQOO Neo9），未收录回退 Build.MODEL。 */
    fun marketingName(context: Context): String {
        marketingNameCache?.let { return it }
        val model = runCatching { android.os.Build.MODEL }.getOrDefault("")
        val mapped = runCatching {
            val root = JSONObject(
                context.assets.open("device_models.json").bufferedReader().use { it.readText() }
            )
            root.optJSONObject("models")?.optString(model)?.ifBlank { null }
        }.getOrNull()
        val brand = runCatching { android.os.Build.BRAND }.getOrDefault("")
        marketingNameCache = buildString {
            if (!brand.isBlank()) append(brand)
            append(' ')
            append(mapped ?: model)
        }
        return marketingNameCache ?: ""
    }

    /** 本机机型跑分（安兔兔 V10 手机榜 device_bench.json）：机型名匹配，未收录返回 null。 */
    fun deviceBench(context: Context): DeviceBench? {
        deviceBenchCache?.let { return it }
        val arr = runCatching {
            JSONObject(context.assets.open("bench/device_bench.json").bufferedReader().use { it.readText() })
                .optJSONArray("devices")
        }.getOrNull() ?: return null
        val mn = marketingName(context)
        if (mn.isBlank()) return null
        val norm = { s: String -> s.lowercase().replace(" ", "").replace("-", "") }
        var hit: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name")
            // 机型名归一化后精确相等或包含（营销名 → 榜单名）
            if (norm(n) == norm(mn)) { hit = o; break }
            if (norm(n).contains(norm(mn)) || norm(mn).contains(norm(n))) {
                // 包含匹配取最短名（最接近本机型号），避免"Neo9"误中"Neo9 Pro"
                val cand = o
                if (hit == null || norm(cand.optString("name")).length < norm(hit.optString("name")).length) {
                    hit = cand
                }
            }
        }
        if (hit == null) return null
        val total = hit.optLong("total_score", -1).takeIf { it > 0 }
        if (total == null) return null
        deviceBenchCache = DeviceBench(
            name = hit.optString("name"),
            soc = hit.optString("soc"),
            cpuScore = hit.optLong("cpu_score", -1).takeIf { it > 0 },
            gpuScore = hit.optLong("gpu_score", -1).takeIf { it > 0 },
            totalScore = total
        )
        return deviceBenchCache
    }

    /** 等效 PC：鲁大师同分制最近邻（手机芯片分 ↔ 台式机 CPU / GPU 榜分） */
    private fun equivalentPc(score: Long, context: Context): PcEquiv? {
        val l = ludashi(context) ?: return null
        val cpuArr = l.optJSONArray("desktop_cpu") ?: return null
        val gpuArr = l.optJSONArray("desktop_gpu") ?: return null
        var cpuBest: Pair<String, Long>? = null
        var cpuDiff = Long.MAX_VALUE
        for (i in 0 until cpuArr.length()) {
            val s = cpuArr.getJSONObject(i).optLong("score", -1)
            if (s <= 0) continue
            val d = kotlin.math.abs(s - score)
            if (d < cpuDiff) { cpuDiff = d; cpuBest = cpuArr.getJSONObject(i).optString("name") to s }
        }
        var gpuBest: Pair<String, Long>? = null
        var gpuDiff = Long.MAX_VALUE
        for (i in 0 until gpuArr.length()) {
            val s = gpuArr.getJSONObject(i).optLong("score", -1)
            if (s <= 0) continue
            val d = kotlin.math.abs(s - score)
            if (d < gpuDiff) { gpuDiff = d; gpuBest = gpuArr.getJSONObject(i).optString("name") to s }
        }
        val c = cpuBest ?: return null
        val g = gpuBest ?: return null
        return PcEquiv(cpuName = c.first, cpuScore = c.second, gpuName = g.first, gpuScore = g.second)
    }

    /** 模拟器体验分级：安兔兔总分 → 档位（阈值来自 bench_meta，经验值待实测校准） */
    private fun emulatorTiers(score: Long?, tierObj: JSONObject?): List<EmulatorTier> {
        if (score == null || tierObj == null) return emptyList()
        val names = mapOf(
            "winlator" to "Winlator", "ns" to "Switch", "ps2" to "PS2",
            "gc_wii" to "NGC/Wii", "ds3ds" to "3DS", "psp" to "PSP"
        )
        return buildList {
            val it = tierObj.keys()
            while (it.hasNext()) {
                val id = it.next()
                val o = tierObj.optJSONObject(id) ?: continue
                val tier = when {
                    score >= o.optLong("smooth", Long.MAX_VALUE) -> "流畅"
                    score >= o.optLong("playable", Long.MAX_VALUE) -> "可玩"
                    score >= o.optLong("heavy", Long.MAX_VALUE) -> "吃力"
                    else -> "不建议"
                }
                add(EmulatorTier(id, names[id] ?: id, tier, o.optString("note")))
            }
        }.sortedBy { it.id }
    }

    /** 升级建议：全部别名条目按安兔兔总分排序，找下一个更高档（提升 15%~50% 内的最近条） */
    private fun upgradeFrom(score: Long, arr: JSONArray, selfBrand: String = ""): UpgradeSuggestion? {
        val sorted = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.optLong("antutu_total", -1).takeIf { it > 0 }
                if (s != null) add(o to s)
            }
        }.sortedBy { it.second }
        // 同厂商优先（骁龙→骁龙、天玑→天玑）：升级建议首推同品牌更高档位；
        // 同厂商增益可达 60%+（骁龙8Gen2→8Gen3 约 65%），同厂商上限放宽到 0.85；
        // 跨厂商候选（增益 15%~50%）作为次选列出，不作为首选。
        fun sameBrand(name: String): Boolean =
            selfBrand.isNotBlank() && name.contains(selfBrand)

        var best: Pair<JSONObject, Long>? = null      // 首选：同厂商优先
        var alt: Pair<JSONObject, Long>? = null       // 次选：跨厂商
        for ((o, s) in sorted) {
            if (s <= score) continue
            val gain = (s - score).toDouble() / score.toDouble()
            val name = JSONArray(o.optString("keys")).optString(0)
            if (sameBrand(name)) {
                if (gain >= 0.15 && gain <= 0.85 &&
                    (best == null || (s - score) < (best!!.second - score))
                ) best = o to s
            } else if (gain >= 0.15 && gain <= 0.5 &&
                (alt == null || (s - score) < (alt!!.second - score))
            ) {
                alt = o to s
            }
        }
        // 同厂商有候选 → 首选同厂商；无 → 首选降级为跨厂商最近条
        if (best == null) {
            best = alt
            alt = null
        }
        val b = best ?: return null
        val a = alt
        return UpgradeSuggestion(
            nextName = JSONArray(b.first.optString("keys")).optString(0),
            nextScore = b.second,
            gainPct = (((b.second - score).toDouble() / score.toDouble()) * 100).toInt(),
            altName = a?.let { JSONArray(it.first.optString("keys")).optString(0) },
            altScore = a?.second,
            altGainPct = a?.let { (((it.second - score).toDouble() / score.toDouble()) * 100).toInt() }
        )
    }

    /** 同芯机型：curated 表匹配当前家族，榜单未收录机型分数为 null */
    private fun sameChipDevices(family: String, arr: JSONArray?): List<SameChipDevice>? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("soc") != family) continue
            val devs = o.optJSONArray("devices") ?: return null
            return buildList {
                for (j in 0 until devs.length()) {
                    val d = devs.getJSONObject(j)
                    add(SameChipDevice(d.optString("name"), d.optLong("score", -1).takeIf { it > 0 }))
                }
            }.ifEmpty { null }
        }
        return null
    }

    /** 世代演进：同家族按发布年排序（年份, 名称, 总分） */
    private fun generation(family: String, arr: JSONArray, selfName: String, context: Context): List<Triple<String, String, Long>> {
        // 骁龙8 家族：直接读安兔兔 SoC 天梯全量（26 条，含 8 超级至尊版 Gen 6 等最新旗舰），
        // 名称前缀过滤保证同家族；年份为公开发布年 curated 映射（2025 起的 Gen 5/Gen 6 按
        // 发布序列推断，非官方年号，UI 侧标注口径）。
        if (family == "骁龙8") {
            val snap = snapdragonLine(context) ?: return buildList {}
            val yearMap = snapYearMap()
            return buildList {
                for (i in 0 until snap.length()) {
                    val o = snap.getJSONObject(i)
                    val n = o.optString("name")
                    if (!n.startsWith("高通 骁龙8")) continue
                    val s = o.optLong("total_score", -1).takeIf { it > 0 } ?: continue
                    val y = yearMap[n] ?: continue
                    add(Triple(y, n.removePrefix("高通 "), s))
                }
            }.sortedBy { it.first }
        }
        // 其他家族：沿用 aliases 的 family 字段 + year 字段
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("family") != family) continue
                val s = o.optLong("antutu_total", -1).takeIf { it > 0 } ?: continue
                val y = o.optString("year").takeIf { it.length >= 4 }?.substring(0, 4) ?: continue
                val n = JSONArray(o.optString("keys")).optString(0)
                add(Triple(y, n, s))
            }
        }.sortedBy { it.first }
    }

    private var antutuCache: JSONArray? = null
    private fun snapdragonLine(context: Context): JSONArray? {
        antutuCache?.let { return it }
        val arr = runCatching {
            val root = JSONObject(
                context.assets.open("bench/antutu_soc.json").bufferedReader().use { it.readText() }
            )
            root.optJSONArray("level1")
        }.getOrNull() ?: return null
        antutuCache = arr
        return arr
    }

    /** 骁龙8 家族公开发布年 curated 映射；2025 起的条目按发布序列推断。 */
    private fun snapYearMap(): Map<String, String> = mapOf(
        "高通 骁龙810" to "2015", "高通 骁龙820" to "2016", "高通 骁龙821" to "2016",
        "高通 骁龙835" to "2017", "高通 骁龙845" to "2018", "高通 骁龙855" to "2019",
        "高通 骁龙855 Plus" to "2019", "高通 骁龙865" to "2020", "高通 骁龙865 Plus" to "2020",
        "高通 骁龙870" to "2021", "高通 骁龙860" to "2021", "高通 骁龙888" to "2021",
        "高通 骁龙888 Plus" to "2021", "高通 骁龙8 Gen 1" to "2022", "高通 骁龙8+ Gen 1" to "2022",
        "高通 骁龙8+ Gen 1 UC" to "2022", "高通 骁龙8 Gen 2" to "2022", "高通 骁龙8 Gen 3" to "2023",
        "高通 骁龙8s Gen 3" to "2024", "高通 骁龙8s Gen 4" to "2025", "高通 骁龙8 至尊版" to "2024",
        "高通 骁龙8 Gen 5" to "2025", "高通 骁龙8 至尊版 Gen5" to "2025",
        "高通 骁龙8 至尊版 Gen 6" to "2026", "高通 骁龙8 超级至尊版 Gen 6" to "2026"
    )

    /**
     * 驱动实测 ↔ PC 显卡：带宽（GB/s）与算力（TFLOPS）是物理量，跨设备可比。
     * 用内置 PC 显卡规格表（显存带宽/FP32 算力）最近邻，展示"≈ 台式机某显卡的 x%"。
     * 注意：这是物理量级对比，不是同一跑分体系换算。
     */
    fun driverVsPc(fillBandwidthGBs: Double, computeGFLOPS: Double, context: Context): PcGpuCompare? {
        if (fillBandwidthGBs <= 0 && computeGFLOPS <= 0) return null
        val m = meta(context) ?: return null
        val specs = m.optJSONArray("pc_gpu_specs") ?: return null
        if (specs.length() == 0) return null
        val computeTFLOPS = computeGFLOPS / 1000.0

        var bwName: String? = null; var bwGBs = 0.0; var bwPct = 0
        var cpName: String? = null; var cpTF = 0.0; var cpPct = 0
        var bwDiff = Double.MAX_VALUE; var cpDiff = Double.MAX_VALUE
        for (i in 0 until specs.length()) {
            val o = specs.getJSONArray(i)
            if (o.length() < 3) continue
            val name = o.optString(0)
            val bw = o.optDouble(1, 0.0)
            val tf = o.optDouble(2, 0.0)
            if (bw <= 0 || tf <= 0) continue
            if (fillBandwidthGBs > 0) {
                val d = kotlin.math.abs(bw - fillBandwidthGBs)
                if (d < bwDiff) {
                    bwDiff = d; bwName = name; bwGBs = bw
                    bwPct = ((fillBandwidthGBs / bw) * 100).toInt().coerceAtMost(100)
                }
            }
            if (computeTFLOPS > 0) {
                val d = kotlin.math.abs(tf - computeTFLOPS)
                if (d < cpDiff) {
                    cpDiff = d; cpName = name; cpTF = tf
                    cpPct = ((computeTFLOPS / tf) * 100).toInt().coerceAtMost(100)
                }
            }
        }
        val n1 = bwName; val n2 = cpName
        if ((fillBandwidthGBs > 0 && n1 == null) || (computeTFLOPS > 0 && n2 == null)) return null
        return PcGpuCompare(
            bandwidthName = n1 ?: "—", bandwidthGBs = bwGBs, bandwidthPct = bwPct,
            computeName = n2 ?: "—", computeTFLOPS = cpTF, computePct = cpPct
        )
    }
}
