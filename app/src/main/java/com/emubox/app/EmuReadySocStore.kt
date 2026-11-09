package com.emubox.app

import android.content.Context
import org.json.JSONObject

/** 同 SoC 聚合的单个平台兼容条目（来自 assets/emuready-soc-compat.json） */
data class SocCompatSystem(
    val key: String,
    val name: String,
    val score: Int,
    val confidence: String,
    val dataSource: String,
    val bestEmulator: String,
    val listings: Int,
    val uniqueGames: Int,
    /** 这条报告来自哪台设备（同芯片的其他机型） */
    val device: String
)

/** 一个 SoC 的兼容报告集合 */
data class SocCompat(
    val soc: String,
    val key: String,
    val devices: List<String>,
    val systems: List<SocCompatSystem>,
    val generatedAt: String
)

/**
 * 离线 SoC 兼容库。
 *
 * 为什么需要：EmuReady 的报告按**具体机型**累积，本机 deviceId 往往只有 1~2 个平台
 * 有数据（用户看到的"只显示 Switch 和 Windows"）。同芯片的其他机型报告很多，
 * 但接口不提供"按 SoC 查"。scripts/refresh-emuready-soc.py 在 CI 里按 SoC 把
 * 前 3 台机型的报告并集聚合成本文件，App 直接读，不需要跳官网、不需要联网。
 */
object EmuReadySocStore {

    private var entries: List<SocCompat>? = null
    private var generatedAt: String = ""

    private fun load(context: Context): List<SocCompat> {
        entries?.let { return it }
        val list = runCatching {
            val text = context.assets.open("emuready-soc-compat.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            generatedAt = root.optString("generated_at")
            val arr = root.optJSONArray("socs") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sysArr = o.optJSONArray("systems") ?: continue
                    val systems = buildList {
                        for (j in 0 until sysArr.length()) {
                            val s = sysArr.getJSONObject(j)
                            add(
                                SocCompatSystem(
                                    key = s.optString("key"),
                                    name = s.optString("name"),
                                    score = s.optInt("score"),
                                    confidence = s.optString("confidence", "low"),
                                    dataSource = s.optString("dataSource", "device"),
                                    bestEmulator = s.optString("bestEmulator"),
                                    listings = s.optInt("listings"),
                                    uniqueGames = s.optInt("uniqueGames"),
                                    device = s.optString("device")
                                )
                            )
                        }
                    }
                    val devArr = o.optJSONArray("devices")
                    val devices = buildList {
                        if (devArr != null) for (j in 0 until devArr.length()) add(devArr.optString(j))
                    }
                    add(
                        SocCompat(
                            soc = o.optString("soc"),
                            key = o.optString("key"),
                            devices = devices,
                            systems = systems,
                            generatedAt = generatedAt
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
        entries = list
        return list
    }

    fun dataGeneratedAt(): String = generatedAt

    /**
     * 按本机 SoC 名匹配。
     *
     * 设备上报的可能是 "SM8550"、"Snapdragon 8 Gen 2"、"骁龙8 Gen 2"；
     * 统一归一化后先精确命中，再退化为互相包含（"8gen2" ⊂ "snapdragon8gen2"）。
     */
    fun find(context: Context, socName: String): SocCompat? {
        val all = load(context)
        if (all.isEmpty()) return null
        val key = EmuReadyStore.normalizeSoc(socName)
        if (key.isBlank()) return null
        all.firstOrNull { it.key == key }?.let { return it }
        // 互相包含：取最长键，避免 "8gen2" 命中 "8gen2s" 这类派生型号
        return all
            .filter { it.key.isNotEmpty() && (it.key.contains(key) || key.contains(it.key)) }
            .maxByOrNull { it.key.length }
    }
}
