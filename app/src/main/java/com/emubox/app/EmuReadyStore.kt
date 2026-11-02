package com.emubox.app

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * EmuReady 离线设备目录：解决"兼容性卡片查不到/查错设备"的根本问题。
 *
 * 背景：EmuReady 的 devices.get 是**模糊搜索**且按内部顺序返回——搜「Xiaomi 15」
 * 第一条给的是「Xiaomi 15T Pro」，于是旧实现把别的机器的兼容分当成用户本机显示；
 * 搜 SoC 名（Snapdragon 8 Gen 3）则 0 命中，整条兜底链路失效。上游也没有
 * "按 SoC 查兼容性"的接口，只能按设备查。
 *
 * 现在：assets/emuready-catalog.json 内置全部 803 台设备（含预算好的匹配键），
 * 本地精确匹配拿到 deviceId 再去查兼容性，一次请求、结果对得上机器；本机未收录时
 * 按 SoC 找同芯片的代表机型做参考，并如实标注"同芯片参考"，不冒充本机实测。
 *
 * 数据更新：scripts/refresh-emuready.py（CI 每日跑），本类只读不写。
 */

/** 目录中的一台设备（字段与 assets/emuready-catalog.json 对应） */
data class EmuReadyCatalogDevice(
    val id: String,
    val modelName: String,
    val brandName: String,
    val socName: String,
    val listingsCount: Int,
    val keys: List<String>
)

/** EmuReady 平台条目（systems 数组） */
data class EmuReadySystem(val key: String, val name: String)

/** EmuReady 模拟器条目（含社区报告数，供模拟器区展示热度） */
data class EmuReadyCatalogEmulator(
    val name: String,
    val logo: String,
    val repositoryUrl: String,
    val officialUrl: String,
    val listings: Int,
    val systems: List<String>
)

/**
 * 本地匹配结果。
 * @param how exact=型号键精确命中；prefix=前缀近似；brand-soc=同品牌同芯片代表机型；
 *            soc=仅同芯片（跨品牌）代表机型
 */
data class EmuReadyMatch(val device: EmuReadyCatalogDevice, val how: String) {
    val isSocFallback: Boolean get() = how == "soc" || how == "brand-soc"
}

object EmuReadyStore {

    private var devices: List<EmuReadyCatalogDevice>? = null
    private var systems: List<EmuReadySystem>? = null
    private var emulators: List<EmuReadyCatalogEmulator>? = null
    private var loaded = false

    /** 归一化：全角转半角、小写、去括号补充说明、只留字母数字与汉字（与抓取脚本一致） */
    fun normalizeKey(raw: String): String {
        if (raw.isBlank()) return ""
        var t = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        t = t.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        return t.replace(Regex("[^0-9a-z\\u4e00-\\u9fff]+"), "")
    }

    /** SoC 名归一化：中英厂商名统一，便于和上游英文型号对齐（骁龙↔Snapdragon 等） */
    fun normalizeSoc(raw: String): String {
        var t = normalizeKey(raw)
        if (t.isEmpty()) return ""
        t = t.replace("骁龙", "snapdragon")
            .replace("天玑", "dimensity")
            .replace("麒麟", "kirin")
            .replace("猎户座", "exynos")
            .replace("qualcomm", "")
            .replace("高通", "")
            .replace("mediatek", "")
            .replace("联发科", "")
        return t
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val text = context.assets.open("emuready-catalog.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            val devArr = root.optJSONArray("devices") ?: return@runCatching
            devices = buildList {
                for (i in 0 until devArr.length()) {
                    val o = devArr.getJSONObject(i)
                    val keysArr = o.optJSONArray("keys")
                    val keys = buildList {
                        if (keysArr != null) for (j in 0 until keysArr.length()) add(keysArr.optString(j))
                    }
                    add(
                        EmuReadyCatalogDevice(
                            id = o.optString("id"),
                            modelName = o.optString("modelName"),
                            brandName = o.optString("brandName"),
                            socName = o.optString("socName"),
                            listingsCount = o.optInt("listingsCount"),
                            keys = keys
                        )
                    )
                }
            }

            val sysArr = root.optJSONArray("systems") ?: org.json.JSONArray()
            systems = buildList {
                for (i in 0 until sysArr.length()) {
                    val o = sysArr.getJSONObject(i)
                    add(EmuReadySystem(o.optString("key"), o.optString("name")))
                }
            }

            val emuArr = root.optJSONArray("emulators") ?: org.json.JSONArray()
            emulators = buildList {
                for (i in 0 until emuArr.length()) {
                    val o = emuArr.getJSONObject(i)
                    val ss = o.optJSONArray("systems")
                    add(
                        EmuReadyCatalogEmulator(
                            name = o.optString("name"),
                            logo = o.optString("logo"),
                            repositoryUrl = o.optString("repositoryUrl"),
                            officialUrl = o.optString("officialUrl"),
                            listings = o.optInt("listings"),
                            systems = buildList {
                                if (ss != null) for (j in 0 until ss.length()) add(ss.optString(j))
                            }
                        )
                    )
                }
            }
        }
    }

    fun systems(context: Context): List<EmuReadySystem> {
        ensureLoaded(context)
        return systems ?: emptyList()
    }

    fun emulators(context: Context): List<EmuReadyCatalogEmulator> {
        ensureLoaded(context)
        return emulators ?: emptyList()
    }

    fun devices(context: Context): List<EmuReadyCatalogDevice> {
        ensureLoaded(context)
        return devices ?: emptyList()
    }

    /**
     * 本地匹配设备：三种候选键（型号、品牌+型号、营销名及其组合）逐级降级。
     *
     * @param brand   厂商名（Build.MANUFACTURER，如 "Xiaomi"/"vivo"）
     * @param models  型号候选，按可信度排序（营销名优先，其次 Build.MODEL 代号）
     * @param socName 本机 SoC 名，最后兜底用
     */
    fun match(
        context: Context,
        brand: String,
        models: List<String>,
        socName: String
    ): EmuReadyMatch? {
        ensureLoaded(context)
        val list = devices ?: return null
        if (list.isEmpty()) return null

        // 候选键：每个型号 × {单独, 品牌+型号}
        val candidates = buildList {
            models.map { it.trim() }.filter { it.isNotBlank() }.forEach { m ->
                normalizeKey(m).takeIf { it.isNotEmpty() }?.let { add(it) }
                normalizeKey(brand + m).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.distinct()

        // ① 型号键精确命中（devices 已按报告数降序，首个即最有代表性的同型号设备）
        for (k in candidates) {
            list.firstOrNull { k in it.keys }?.let { return EmuReadyMatch(it, "exact") }
        }
        // ② 前缀近似：型号够长才比较，避免「13」这种纯数字键乱命中
        for (k in candidates) {
            if (k.length < 4) continue
            list.firstOrNull { d -> d.keys.any { dk -> dk.length >= 4 && (dk.startsWith(k) || k.startsWith(dk)) } }
                ?.let { return EmuReadyMatch(it, "prefix") }
        }
        // ③ 同品牌同芯片：国产机上报的多是内部代号（OnePlus PJZ110、小米 23127PN0CC），
        //    代号不在任何公开表里，但"品牌 + 芯片"已足够定位代表机型，比跨品牌参考可信。
        val target = normalizeSoc(socName)
        val nb = normalizeKey(brand)
        if (target.length >= 4 && nb.isNotEmpty()) {
            list.firstOrNull { normalizeKey(it.brandName) == nb && normalizeSoc(it.socName) == target }
                ?.let { return EmuReadyMatch(it, "brand-soc") }
        }
        // ④ 同 SoC 兜底（跨品牌）：同芯片机型报告数最多的那台（上游无按 SoC 查询接口）
        if (target.length >= 4) {
            list.firstOrNull { normalizeSoc(it.socName) == target }
                ?.let { return EmuReadyMatch(it, "soc") }
        }
        return null
    }

    /**
     * 系统内置的营销名：部分厂商（vivo/OPPO/小米/一加）在 build.prop 里留了
     * ro.product.marketname 之类属性，比 Build.MODEL 的内部代号（PJZ110）更接近
     * 上游目录里的写法。取不到就返回空，不影响主流程。
     */
    fun systemMarketNames(): List<String> {
        val props = listOf(
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.product.market_name",
            "ro.config.marketing_name",
            "ro.vendor.product.display"
        )
        val out = LinkedHashSet<String>()
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            props.forEach { p ->
                val v = runCatching { get.invoke(null, p) as? String }.getOrNull()
                if (!v.isNullOrBlank()) out.add(v.trim())
            }
        }
        return out.toList()
    }
}
