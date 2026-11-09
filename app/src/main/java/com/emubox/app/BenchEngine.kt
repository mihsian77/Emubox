package com.emubox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 等效 PC 配置：同分制最近邻换算结果 */
data class PcEquiv(val cpuName: String, val cpuScore: Long, val gpuName: String, val gpuScore: Long)

/** 模拟器体验档位 */
data class EmulatorTier(val id: String, val name: String, val tier: String, val note: String)

/** 升级建议：当前芯片的下一性能档 */
data class UpgradeSuggestion(
    val nextName: String,
    val nextScore: Long,
    val gainPct: Int,
    /** 该芯片的常见机型（来自 soc_master 的 devices 字段，可能为空） */
    val nextDevices: String = "",
    /** 跨厂商次选（同厂商无候选或增益过近时给出；无则 null） */
    val altName: String? = null,
    val altScore: Long? = null,
    val altGainPct: Int? = null,
    val altDevices: String = ""
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

/** 手机 GPU 物理算力 vs 最近桌面卡（FP32/带宽，单位可比；不做跑分换算） */
data class GpuPhys(
    val gpuName: String,
    val tf32: Double,
    val bwGbs: Double,
    val soc: String,
    val deskName: String,
    val deskTf32: Double,
    val deskBwGbs: Double,
    /** 本机算力相当于该桌面卡的百分比 */
    val tfPct: Int,
    val bwPct: Int
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
    /** GPU 物理算力对照（TFLOPS/带宽）；null 表示该型号未收录算力数据 */
    val gpuPhys: GpuPhys? = null,
    val emulatorTiers: List<EmulatorTier>,
    val upgrade: UpgradeSuggestion?,
    val sameChip: List<SameChipDevice>?,
    val generation: List<Triple<String, String, Long>>, // (年份, 名称, 总分)
    val deviceBench: DeviceBench?
)

/**
 * 芯片主表（assets/soc_master.json，389 颗，来自 socpk）。
 *
 * 升级建议的候选池。原来只用手写的 28 条 aliases，导致「下一档」常年只有
 * 骁龙 8 Gen3 / 天玑 9300 几条，国内在售的新芯片根本进不来。
 * 这里同时提供 brand（芯片厂商）与 devices（常见机型），用来按国内厂商排序。
 */
private object SocMasterCatalog {

    class Row(
        val name: String,
        val brand: String,
        val devices: String,
        val antutu: Long,
        val form: String,
        /** 全部可匹配写法（中文名 / 英文名 / 部件号），用 | 连接 */
        val keys: String
    )

    private var cache: List<Row>? = null

    fun rows(context: Context): List<Row> {
        cache?.let { return it }
        val list = runCatching {
            val arr = JSONObject(
                context.assets.open("soc_master.json").bufferedReader().use { it.readText() }
            ).getJSONArray("socs")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    // 跑分是排序与增益的唯一依据，没有分的条目不参与候选
                    val score = o.optString("antutu").toLongOrNull() ?: 0L
                    if (score <= 0) continue
                    val keys = buildList {
                        add(o.optString("name"))
                        o.optJSONArray("names")?.let { ns ->
                            for (j in 0 until ns.length()) add(ns.optString(j))
                        }
                        o.optJSONArray("codes")?.let { cs ->
                            for (j in 0 until cs.length()) add(cs.optString(j))
                        }
                    }.filter { it.isNotBlank() }.distinct()
                    if (keys.isEmpty()) continue
                    add(
                        Row(
                            name = o.optString("name"),
                            brand = o.optString("brand"),
                            devices = o.optString("devices"),
                            antutu = score,
                            form = o.optString("form"),
                            keys = keys.joinToString("|")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
        cache = list
        return list
    }

    /** 按显示名/代号反查主表条目；先精确、再按命中键最长（区分 865 与 865 IoT 这类变体） */
    fun find(context: Context, socName: String): Row? {
        if (socName.isBlank()) return null
        val q = normSoc(socName)
        if (q.length < 4) return null
        var best: Row? = null
        var bestLen = 0
        for (r in rows(context)) {
            for (k in r.keys.split('|')) {
                val nk = normSoc(k)
                if (nk.length < 4) continue
                // 精确同名直接命中：主表里「骁龙865」和「骁龙865 IoT」并存，
                // 只按包含匹配会把后者选走，等于给用户换了一颗不同用途的芯片
                if (q == nk) return r
                if (q.contains(nk) || nk.contains(q)) {
                    if (nk.length > bestLen) {
                        bestLen = nk.length
                        best = r
                    }
                }
            }
        }
        return best
    }
}

/** 芯片名归一化：去空格/连字符，厂商名统一成中文，供主表与别名表共用 */
private fun normSoc(s: String): String {
    var t = s.lowercase().replace(" ", "").replace("-", "").replace("_", "")
    t = t.replace("snapdragon", "骁龙")
        .replace("dimensity", "天玑")
        .replace("kirin", "麒麟")
        .replace("qualcomm", "高通")
    return t
}

/**
 * 国内品牌热度权重：按国内出货量粗排，只用于升级建议的排序，不是销量统计。
 * 用途是「同样 +30% 的两颗芯片，优先推国内买得到、售后方便的那颗」。
 */
private val CN_BRAND_WEIGHT = listOf(
    "小米" to 10, "红米" to 10, "REDMI" to 10, "华为" to 9, "荣耀" to 8,
    "vivo" to 8, "iQOO" to 7, "OPPO" to 7, "一加" to 6,
    "真我" to 6, "realme" to 6, "红魔" to 5, "努比亚" to 4,
    "魅族" to 3, "摩托罗拉" to 3, "中兴" to 2
)

/** 该芯片的常见机型里最热门的国产品牌权重；0 表示国内基本买不到（如 Exynos / Tensor） */
private fun cnPresence(devices: String): Int {
    if (devices.isBlank()) return 0
    return CN_BRAND_WEIGHT.filter { devices.contains(it.first, ignoreCase = true) }
        .maxOfOrNull { it.second } ?: 0
}

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
    private fun normalizeSoc(s: String): String = normSoc(s)

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
        val upgrade = antutuTotal?.let { upgradeFrom(it, arr, selfBrand, name, context) }
        val sameChip = sameChipDevices(family, m.optJSONArray("same_chip_devices"))
        val gen = generation(family, arr, name, context)
        val deviceBench = deviceBench(context)
        val gpuPhys = gpuPhysique(entry, name, gpuModel, m, context)

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
            gpuPhys = gpuPhys,
            emulatorTiers = tiers, upgrade = upgrade,
            sameChip = sameChip, generation = gen,
            deviceBench = deviceBench
        )
    }

    /**
     * GPU 物理算力对照。
     *
     * 为什么不再用「分数最近邻」：安兔兔 GPU 分（手机口径）与鲁大师桌面 GPU 分
     * 不是同一张榜，数值却同量级（42 万 vs 33 万），最近邻会把 Adreno 740 匹配成
     * RTX 2060 SUPER —— 实际相差 8~10 倍。这里改用 FP32 TFLOPS 与内存带宽这两个
     * 跨平台可直接比较的物理量，只做量级对齐，并如实给出百分比。
     */
    private fun gpuPhysique(
        entry: JSONObject,
        socName: String,
        gpuModel: String,
        meta: JSONObject,
        context: Context
    ): GpuPhys? {
        val table = runCatching {
            JSONObject(
                context.assets.open("gpu_tflops.json").bufferedReader().use { it.readText() }
            ).optJSONArray("gpus")
        }.getOrNull() ?: return null

        val socKey = normalizeSoc(socName)
        val gpuKey = when {
            gpuModel.isBlank() || gpuModel == "unknown" -> ""
            gpuModel.any { it.isDigit() } && gpuModel.first().isDigit() -> "adreno$gpuModel"
            else -> gpuModel.lowercase().replace(" ", "")
        }

        var hit: JSONObject? = null
        for (i in 0 until table.length()) {
            val o = table.getJSONObject(i) ?: continue
            val k = o.optString("key")
            val s = normalizeSoc(o.optString("soc"))
            if (gpuKey.isNotBlank() && (k.equals(gpuKey, true) || k.replace("-", "").equals(gpuKey.replace("-", ""), true))) {
                hit = o; break
            }
            if (socKey.isNotBlank() && (s.contains(socKey) || socKey.contains(s))) {
                hit = o; break
            }
        }
        val row = hit ?: return null
        val tf32 = row.optDouble("tf32", 0.0)
        val bw = row.optDouble("bw", 0.0)
        if (tf32 <= 0.0) return null

        // 桌面卡：pc_gpu_specs = [[name, bandwidthGBs, tflops], ...]，按 TFLOPS 最近邻
        val desk = meta.optJSONArray("pc_gpu_specs") ?: return null
        var bestName = ""
        var bestTf = 0.0
        var bestBw = 0.0
        var bestDiff = Double.MAX_VALUE
        for (i in 0 until desk.length()) {
            val a = desk.optJSONArray(i) ?: continue
            val tf = a.optDouble(2, 0.0)
            if (tf <= 0.0) continue
            val d = kotlin.math.abs(tf - tf32)
            if (d < bestDiff) {
                bestDiff = d
                bestName = a.optString(0)
                bestBw = a.optDouble(1, 0.0)
                bestTf = tf
            }
        }
        if (bestName.isBlank()) return null
        return GpuPhys(
            gpuName = row.optString("name"),
            tf32 = tf32,
            bwGbs = bw,
            soc = row.optString("soc"),
            deskName = bestName,
            deskTf32 = bestTf,
            deskBwGbs = bestBw,
            tfPct = ((tf32 / bestTf) * 100).toInt().coerceIn(1, 999),
            bwPct = if (bestBw > 0) ((bw / bestBw) * 100).toInt().coerceIn(1, 999) else 0
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

    /**
     * 升级建议：优先同芯片厂商，其次按「国内买不买得到」排序。
     *
     * 候选池用 soc_master 全表（389 颗），不再用 28 条手写别名——
     * 那份表只有骁龙/天玑少数几档，国内在售的新芯片全都漏了。
     * 排序规则（同一增益区间内）：
     *   1. 同厂商（骁龙→骁龙、天玑→天玑、麒麟→麒麟）：换平台要重做适配，不该首推；
     *   2. 常见机型含国内主流品牌（小米/红米/华为/荣耀/vivo/iQOO/OPPO/一加/真我…）；
     *   3. 增益更接近下限（换机成本对应收益）。
     * 国内基本买不到的（Exynos、Tensor 这类）只会出现在「次选」之后，且不优先。
     */
    private fun upgradeFrom(
        score: Long,
        arr: JSONArray,
        selfBrand: String = "",
        selfName: String = "",
        context: Context? = null
    ): UpgradeSuggestion? {
        context?.let { ctx ->
            val master = SocMasterCatalog.rows(ctx)
            if (master.isNotEmpty()) {
                val selfRow = SocMasterCatalog.find(ctx, selfName)
                val selfBrandZh = selfRow?.brand?.takeIf { it.isNotBlank() } ?: selfBrand
                // 同厂商上限放宽到 0.85（8 Gen2→8 Gen3 这段本来就有 40%+ 的跳档）
                val candidates = master
                    .asSequence()
                    .filter { it.antutu > score }
                    .map { Triple(it, ((it.antutu - score).toDouble() / score), cnPresence(it.devices)) }
                    .filter { (_, gain, _) -> gain in 0.15..0.85 }
                    .toList()
                if (candidates.isNotEmpty()) {
                    val same = candidates.filter { it.first.brand == selfBrandZh && selfBrandZh.isNotBlank() }
                    val cross = candidates.filterNot { it.first.brand == selfBrandZh }
                    // 同组内：国内热度高的优先，其次增益更近
                    fun rank(list: List<Triple<SocMasterCatalog.Row, Double, Int>>) =
                        list.sortedWith(compareByDescending<Triple<SocMasterCatalog.Row, Double, Int>> { it.third }.thenBy { it.second })
                    val sameBest = rank(same).firstOrNull()
                    // 次选：跨厂商、国内在售、增益 15%~50%
                    val crossBest = rank(cross.filter { it.second <= 0.5 && it.third > 0 }).firstOrNull()
                    val best = sameBest ?: crossBest ?: return@let
                    val alt = if (best === sameBest) crossBest else null
                    return UpgradeSuggestion(
                        nextName = best.first.name,
                        nextScore = best.first.antutu,
                        gainPct = (best.second * 100).toInt(),
                        nextDevices = best.first.devices,
                        altName = alt?.first?.name,
                        altScore = alt?.first?.antutu,
                        altGainPct = alt?.let { (it.second * 100).toInt() },
                        altDevices = alt?.first?.devices.orEmpty()
                    )
                }
            }
        }

        // 主表不可用（首次启动 asset 缺失）时退回别名表：口径与主表一致，只是候选少
        val sorted = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.optLong("antutu_total", -1).takeIf { it > 0 }
                if (s != null) add(o to s)
            }
        }.sortedBy { it.second }

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
