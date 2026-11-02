package com.emubox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 匹配到芯片的把握程度，决定界面是否要标注「推断」。 */
enum class SocConfidence {
    /** 部件号精确命中（SM8750-AB） */
    CODE_EXACT,
    /** 部件号剥掉封装后缀后命中（MT6769V/CB → MT6769） */
    CODE_LOOSE,
    /** 平台代号命中（sun / kalama / mt6989） */
    PLATFORM,
    /** 渲染器 GPU 型号唯一命中（Adreno 750 → 8 Gen 3） */
    GPU,
    /** 营销名归一化后命中 */
    NAME
}

/**
 * 解析结果。name 是展示名；ambiguous 为真表示该部件号对应多个市场名
 * （联发科一颗芯片挂多个名字很常见，如 MT6769 = G70/G80/G85），
 * 此时 alternates 给出全部候选，界面应显示「G80 / G85」而不是猜一个。
 */
data class ResolvedSoc(
    val id: String,
    val name: String,
    val nameEn: String,
    val brand: String,
    val codes: List<String>,
    val process: String,
    val cpu: String,
    val cores: Int,
    val maxGhz: Double,
    val gpu: String,
    val gpuKey: String,
    val year: String,
    val tier: String,
    val antutu: String,
    val antutuCpu: String,
    val antutuGpu: String,
    val ludashi: String,
    val devices: String,
    val confidence: SocConfidence,
    val ambiguous: Boolean = false,
    val alternates: List<String> = emptyList()
) {
    val displayName: String
        get() = when {
            !ambiguous || alternates.size < 2 -> name
            // 展锐 ums9230 横跨 T606/T612/T616/T7250，四五个名字全列出来会把卡片撑爆
            alternates.size <= 3 -> alternates.joinToString(" / ")
            else -> alternates.take(2).joinToString(" / ") + " 等 ${alternates.size} 款"
        }
}

/** 设备上报的原始标识：全部是代号，没有一个是市场名。 */
data class SocIdentity(
    val socModel: String,
    val platform: String,
    val hardware: String
)

/**
 * 芯片识别：以部件号为主键查 assets/soc_master.json。
 *
 * 为什么不能只按名字匹配：同一颗芯片在极客湾/安兔兔/鲁大师写法互不相同，
 * 安兔兔把 Elite 译作「至尊版」、Gen5 紧贴，而英文站写作 Gen 5；
 * 设备侧上报的又只有 SM8750-AB 这类部件号。名字归一化只作最后兜底。
 *
 * 匹配顺序：部件号精确 → 部件号剥封装 → 平台代号 → GPU 型号 → 营销名。
 * 归一化规则与 scripts/soc/build_master.py 严格同构，改一边必须同步另一边。
 */
object SocResolver {

    // 长词在前：「超级至尊版」若晚于「至尊版」替换会残留「超级」
    private val SYNONYMS = listOf(
        "超级至尊版" to "eliteextreme", "超级至尊" to "eliteextreme",
        "至尊版" to "elite", "至尊" to "elite",
        "骁龙" to "snapdragon", "天玑" to "dimensity", "麒麟" to "kirin",
        "联发科技" to "mediatek", "联发科" to "mediatek",
        "高通" to "qualcomm", "三星" to "samsung", "华为" to "hisilicon",
        "海思" to "hisilicon", "曦力" to "helio", "迅鲲" to "kompanio",
        "谷歌" to "google", "小米" to "xiaomi", "玄戒" to "xring",
        "澎湃" to "pinecone", "苹果" to "apple",
        "紫光展锐" to "unisoc", "展锐" to "unisoc", "联发科技" to "mediatek"
    )
    private val VENDOR_WORDS = setOf(
        "qualcomm", "mediatek", "samsung", "hisilicon", "google", "xiaomi", "apple",
        "unisoc", "arm", "amd", "nvidia", "broadcom", "amlogic", "allwinner", "rockchip"
    )
    private val SEP = Regex("[()（）\\[\\]{}、,，/\\\\:：]")
    private val DROP = Regex("[\\s\\-_*]")
    private val QCOM_TAIL = Regex("(-Q-[A-Z]{2}|-\\d(-[A-Z]{2})?)$")
    private val MTK_TAIL = Regex("^(MT\\d{4})[A-Z]?(/[A-Z]{1,2})?$")

    private class Table(
        val socs: Map<String, ResolvedSoc>,
        val exact: Map<String, List<String>>,
        val loose: Map<String, List<String>>,
        val platforms: Map<String, String>,
        val byGpuNum: Map<String, List<String>>,
        val byName: Map<String, List<String>>,
        val vendors: Map<String, String>,
        val src: Map<String, String>
    )

    private var table: Table? = null

    private fun normalize(s: String): String {
        var t = s.lowercase()
        t = SEP.replace(t, " ")
        // 「+」不能直接删：删了之后「骁龙 8+ Gen1」与「骁龙 8 Gen1」结果相同，
        // 865+ / 888+ / 765G 会被并进基础款。与 build_master.py 保持同构。
        t = t.replace("+", "plus")
        t = DROP.replace(t, "")
        t = Regex("5g$").replace(t, "")
        for ((a, b) in SYNONYMS) t = t.replace(a, b)
        return t
    }

    private fun stripVendor(s: String): String {
        val parts = s.trim().split(' ', '\t').filter { it.isNotBlank() }
        return if (parts.size > 1 && normalize(parts[0]) in VENDOR_WORDS) {
            normalize(parts.drop(1).joinToString(" "))
        } else normalize(s)
    }

    private fun codeKey(s: String) = s.uppercase().replace(Regex("[\\s_]"), "")

    /** 展锐把板级后缀拼进平台值：ums9230_6h10 / ums9620_2h10 / s9863a1h10。
     *  下划线、点之后全是主板型号，不是芯片的一部分，先截掉。 */
    private fun boardKey(s: String): String {
        val t = s.trim().lowercase()
        val head = t.substringBefore('_').substringBefore('.')
        // 「mt_6989」「zuma_pro」是拿下划线当分隔符的老写法，截完只剩 mt/zuma。
        // 只有前半段本身像芯片号（≥5 位且带数字）才认定是主板后缀。
        return if (head.length >= 5 && head.any { it.isDigit() }) head else t
    }

    /** 剥到平台级：SM8650-Q-AB→SM8650，MT6769V/CB→MT6769；-AB/-AC 不剥（865+ 与 870 靠它区分）。 */
    private fun looseKey(s: String): String {
        var t = codeKey(s)
        while (true) {
            val m = QCOM_TAIL.find(t) ?: break
            t = t.removeRange(m.range)
        }
        val m = MTK_TAIL.find(t)
        if (m != null && m.groupValues[2].isNotEmpty()) t = m.groupValues[1]
        return t
    }

    private fun load(context: Context): Table {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val socs = LinkedHashMap<String, ResolvedSoc>()
            val exact = HashMap<String, MutableList<String>>()
            val loose = HashMap<String, MutableList<String>>()
            val platforms = HashMap<String, String>()
            val byGpuNum = HashMap<String, MutableList<String>>()
            val byName = HashMap<String, MutableList<String>>()
            val srcMap = HashMap<String, String>()
            val vendors = HashMap<String, String>()
            runCatching {
                val text = context.assets.open("soc_master.json")
                    .bufferedReader().use { it.readText() }
                val root = JSONObject(text)

                val arr = root.getJSONArray("socs")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val codes = o.optJSONArray("codes").toStringList()
                    val s = ResolvedSoc(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        nameEn = o.optString("name_en"),
                        brand = o.optString("brand"),
                        codes = codes,
                        process = o.optString("process"),
                        cpu = o.optString("cpu"),
                        cores = o.optInt("cores"),
                        maxGhz = o.optDouble("max_ghz", 0.0),
                        gpu = o.optString("gpu"),
                        gpuKey = o.optString("gpu_key"),
                        year = o.optString("year"),
                        tier = o.optString("tier"),
                        antutu = o.optString("antutu"),
                        antutuCpu = o.optString("antutu_cpu"),
                        antutuGpu = o.optString("antutu_gpu"),
                        ludashi = o.optString("ludashi"),
                        devices = o.optString("devices"),
                        confidence = SocConfidence.NAME
                    )
                    socs[s.id] = s
                    srcMap[s.id] = o.optString("src")
                    // GPU 兜底只按数字建索引：detectGpuModel 对 Adreno 与 Mali 都只返回数字，
                    // 前缀靠不住，数字在两家之间不重叠（Adreno 6xx-8xx / Mali G5x-G9x）
                    val num = s.gpuKey.filter { it.isDigit() }
                    if (num.isNotEmpty()) {
                        byGpuNum.getOrPut(num) { mutableListOf() }.add(s.id)
                    }
                    for (n in listOf(s.name, s.nameEn)) {
                        if (n.isNotBlank()) {
                            byName.getOrPut(normalize(n)) { mutableListOf() }.add(s.id)
                            byName.getOrPut(stripVendor(n)) { mutableListOf() }.add(s.id)
                        }
                    }
                }

                val match = root.optJSONObject("match")
                match?.optJSONObject("exact")?.let { readIndex(it, exact) }
                match?.optJSONObject("loose")?.let { readIndex(it, loose) }
                root.optJSONObject("platforms")?.let { o ->
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        platforms[k.lowercase()] = o.getString(k)
                    }
                }
                root.optJSONObject("vendors")?.let { o ->
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.startsWith("_")) continue
                        vendors[k.lowercase()] = o.optString(k)
                    }
                }
            }
            val t = Table(socs, exact, loose, platforms, byGpuNum, byName, vendors, srcMap)
            table = t
            return t
        }
    }

    private fun readIndex(o: JSONObject, into: HashMap<String, MutableList<String>>) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            into[k] = o.optJSONArray(k).toStringList().toMutableList()
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val v = optString(i)
                if (v.isNotBlank()) add(v)
            }
        }
    }

    private fun Table.pick(ids: List<String>, confidence: SocConfidence, gpuModel: String): ResolvedSoc? {
        val rows = ids.mapNotNull { socs[it] }
        if (rows.isEmpty()) return null
        if (rows.size == 1) return rows[0].copy(confidence = confidence)
        // 一码多名：先按 GPU 收窄（MD5 那颗 MT6769 的 G80 与 G85 GPU 同为 G52 MC2，收不窄就展示族名）
        val byGpuRows = if (gpuModel.isNotBlank()) {
            rows.filter { it.gpuKey.filter { c -> c.isDigit() } == gpuModel }
        } else emptyList()
        // 一码多名时优先极客湾那一条：它的名字是官方写法，安兔兔那边
        // 会拆成「天玑8400-Max / 8400-Ultra / 8400-Turbo」好几行
        val ranked = (if (byGpuRows.isNotEmpty()) byGpuRows else rows)
            .sortedBy { if (src[it.id] == "socpk") 0 else 1 }
        val chosen = ranked.first()
        val names = (if (byGpuRows.isNotEmpty()) byGpuRows else rows).map { it.name }.distinct()
        return chosen.copy(
            confidence = confidence,
            ambiguous = names.size > 1,
            alternates = names
        )
    }

    /**
     * 解析设备芯片。[socNameHint] 可传已经是营销名的值（老调用方拿到的就是营销名），
     * 此时走最后的名称匹配，仍能命中主表。
     */
    fun resolve(
        context: Context,
        identity: SocIdentity,
        gpuModel: String = "",
        socNameHint: String = "",
        gpuIsAdreno: Boolean = false
    ): ResolvedSoc? {
        val t = load(context)

        val codeCandidates = listOf(identity.socModel, identity.platform, socNameHint)
            .filter { it.isNotBlank() }
        for (raw in codeCandidates) {
            t.exact[codeKey(boardKey(raw))]?.let { return t.pick(it, SocConfidence.CODE_EXACT, gpuModel) }
        }
        // 板级后缀没有分隔符时直接拼在芯片号后面（s9863a1h10 = SC9863A + 主板号）。
        // 用 5~8 位前缀回查精确表：芯片号最短 5 位（SM8xxx 6 位、MT 6 位、SC 6 位），
        // 命中即认，长度差不超过 4 位，避免把 SM8650AB 认成别的。
        for (raw in codeCandidates) {
            val k = codeKey(boardKey(raw))
            if (k.length >= 7) {
                for (len in minOf(8, k.length) downTo 5) {
                    if (k.length - len > 4) break
                    t.exact[k.substring(0, len)]?.let {
                        return t.pick(it, SocConfidence.CODE_LOOSE, gpuModel)
                    }
                }
            }
        }
        for (raw in codeCandidates) {
            t.loose[looseKey(boardKey(raw))]?.let { return t.pick(it, SocConfidence.CODE_LOOSE, gpuModel) }
        }
        for (raw in codeCandidates) {
            t.platforms[boardKey(raw)]?.let { id ->
                return t.socs[id]?.copy(confidence = SocConfidence.PLATFORM)
            }
        }
        for (raw in codeCandidates) {
            t.byName[normalize(raw)]?.let { return t.pick(it, SocConfidence.NAME, gpuModel) }
            t.byName[stripVendor(raw)]?.let { return t.pick(it, SocConfidence.NAME, gpuModel) }
        }
        // GPU 兜底放最后，且只认 Adreno。
        //
        // 原因：Mali 与 Adreno 的型号数字会撞车（Mali-G610 与 Adreno 610），
        // 而一颗 Mali 往往对应几十款 SoC（G57 覆盖天玑 700/810/930…）。
        // 主表恰好只收了一颗时「全表唯一」是假象——展锐设备曾因此被判成天玑 6100。
        // Adreno 由高通独占，且 6xx 以上基本一对一，才敢用来兜底。
        val gpuNum = gpuModel.filter { it.isDigit() }
        if (gpuIsAdreno && gpuNum.isNotEmpty()) {
            t.byGpuNum[gpuNum]?.let { ids ->
                if (ids.size == 1 && t.socs[ids[0]]?.brand == "高通") {
                    return t.socs[ids[0]]?.copy(confidence = SocConfidence.GPU)
                }
            }
        }
        return null
    }

    /**
     * 只认出厂商、认不出型号时返回厂商名。
     *
     * 展锐设备上报 ums9230、华为上报 kirin9020、联发科老平台上报 mt6765，
     * 这三类没有公开的完整料号表，硬猜型号不如老实说「紫光展锐 · 型号未收录」。
     * 前缀按长度倒序匹配，避免 'm' 这种短前缀抢先。
     */
    fun vendorOf(context: Context, identity: SocIdentity): String {
        val t = load(context)
        val raws = listOf(identity.socModel, identity.platform, identity.hardware)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        for (raw in raws) {
            t.vendors.entries
                .filter { raw.startsWith(it.key) }
                .maxByOrNull { it.key.length }
                ?.let { return it.value }
        }
        return ""
    }

    /** 便捷入口：直接从当前设备读原始标识再解析。 */
    fun resolveCurrent(
        context: Context,
        gpuModel: String = "",
        socHint: String = "",
        gpuIsAdreno: Boolean = false
    ): ResolvedSoc? {
        val id = SocNameMapper.getRawIdentity()
        return resolve(
            context, id,
            gpuModel = gpuModel,
            socNameHint = socHint,
            gpuIsAdreno = gpuIsAdreno
        )
    }
}
