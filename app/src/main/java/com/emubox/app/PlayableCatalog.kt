package com.emubox.app

import android.content.Context
import org.json.JSONObject

/** 一档可玩性。TIGHT 是「能进游戏但基本不能玩」，仍列表是因为用户需要知道差在哪。 */
enum class PlayGrade { SMOOTH, PLAYABLE, TIGHT, UNKNOWN }

data class PlayablePlatform(
    val key: String,
    val label: String,
    val grade: PlayGrade,
    val smooth: Int,
    val ok: Int,
    val minRamMb: Int,
    val note: String,
    /** 对应模拟器数据里的 platforms 值，用于计数与跳转筛选 */
    val match: List<String>,
    /** 库里能装在这台机器上的条目数（hosts 含 android） */
    val appCount: Int,
    /** GPU 够但内存不够：档位被内存拖下来 */
    val ramLimited: Boolean = false
)

data class PlayableReport(
    val socName: String,
    /** 本机 GPU 参考分；null 表示这颗芯片没有收录 */
    val score: Double?,
    /** socpk（实测）/ family（同族）/ antutu（折算）/ ""（无） */
    val scoreSrc: String,
    val platforms: List<PlayablePlatform>,
    /** 只有一个「实验性」标记、不给档位的平台 */
    val experimental: List<ExperimentalPlatform>,
    val benchmarkName: String,
    val benchmarkBase: String,
    val disclaimer: String
)

data class ExperimentalPlatform(val key: String, val label: String, val reason: String)

/**
 * 可玩库：把「这台机器」换算成「哪些平台跑得动」。
 *
 * 分数来自 assets/playable-matrix.json（scripts/build_playable.py 生成），
 * 口径是极客湾手机芯片 GPU 排行（骁龙 865 = 100）。选 GPU 不选 CPU：
 * 模拟器的瓶颈几乎都在 GPU 与驱动，CPU 够用是前提而不是分档依据。
 *
 * 判档只看两个输入：芯片 GPU 分、物理内存。平台门槛写在数据里，
 * 调整门槛不需要改这段代码。
 */
object PlayableCatalog {

    private var matrix: Matrix? = null

    private class Matrix(
        val benchmarkName: String,
        val benchmarkBase: String,
        val disclaimer: String,
        val platforms: List<PlatformRule>,
        val experimental: List<ExperimentalPlatform>,
        val scores: Map<String, Score>
    )

    private class PlatformRule(
        val key: String,
        val label: String,
        val smooth: Int,
        val ok: Int,
        val minRamMb: Int,
        val note: String,
        val match: List<String>
    )

    private class Score(val value: Double, val src: String)

    private fun load(context: Context): Matrix {
        matrix?.let { return it }
        synchronized(this) {
            matrix?.let { return it }
            val text = context.assets.open("playable-matrix.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val bench = root.optJSONObject("benchmark")

            val rules = ArrayList<PlatformRule>()
            root.optJSONArray("platforms")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    rules.add(
                        PlatformRule(
                            key = o.optString("key"),
                            label = o.optString("label"),
                            smooth = o.optInt("smooth"),
                            ok = o.optInt("ok"),
                            minRamMb = o.optInt("minRamMb"),
                            note = o.optString("note"),
                            match = o.optJSONArray("match")?.let { m ->
                                (0 until m.length()).map { m.optString(it) }
                            } ?: emptyList()
                        )
                    )
                }
            }

            val exp = ArrayList<ExperimentalPlatform>()
            root.optJSONArray("experimental")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    exp.add(
                        ExperimentalPlatform(
                            key = o.optString("key"),
                            label = o.optString("label"),
                            reason = o.optString("reason")
                        )
                    )
                }
            }

            val scores = HashMap<String, Score>()
            root.optJSONObject("scores")?.let { obj ->
                for (id in obj.keys()) {
                    val o = obj.optJSONObject(id) ?: continue
                    scores[id] = Score(o.optDouble("s", Double.NaN), o.optString("src"))
                }
            }

            Matrix(
                benchmarkName = bench?.optString("name").orEmpty(),
                benchmarkBase = bench?.optString("base").orEmpty(),
                disclaimer = bench?.optString("note").orEmpty(),
                platforms = rules,
                experimental = exp,
                scores = scores
            ).also { matrix = it }
            return matrix!!
        }
    }

    /**
     * @param soc 解析出的芯片（可能为 null：设备上报的代号完全认不出）
     * @param ramMb 物理内存，用于内存硬门槛
     * @param entries 主库条目，用于统计「这个平台库里有几个能装的」
     */
    fun evaluate(
        context: Context,
        soc: ResolvedSoc?,
        ramMb: Long,
        entries: List<EmulatorEntry>
    ): PlayableReport {
        val m = load(context)
        val sc = soc?.let { m.scores[it.id] }
        val score = sc?.value?.takeIf { !it.isNaN() }

        // Android 侧可装的条目：hosts 为空视为未知，计入；明确不含 android 的排除
        val androidEntries = entries.filter { it.hosts.isEmpty() || it.hosts.contains("android") }

        val out = m.platforms.map { rule ->
            val ramOk = ramMb <= 0 || ramMb >= rule.minRamMb
            val (grade, ramLimited) = when {
                score == null -> PlayGrade.UNKNOWN to false
                score >= rule.smooth && ramOk -> PlayGrade.SMOOTH to false
                score >= rule.smooth -> PlayGrade.PLAYABLE to true
                score >= rule.ok -> PlayGrade.PLAYABLE to false
                else -> PlayGrade.TIGHT to false
            }
            PlayablePlatform(
                key = rule.key,
                label = rule.label,
                grade = grade,
                smooth = rule.smooth,
                ok = rule.ok,
                minRamMb = rule.minRamMb,
                note = rule.note,
                match = rule.match,
                appCount = androidEntries.count { e -> e.platforms.any { it in rule.match } },
                ramLimited = ramLimited
            )
        }

        return PlayableReport(
            socName = soc?.displayName.orEmpty(),
            score = score,
            scoreSrc = sc?.src.orEmpty(),
            platforms = out,
            experimental = m.experimental,
            benchmarkName = m.benchmarkName,
            benchmarkBase = m.benchmarkBase,
            disclaimer = m.disclaimer
        )
    }
}
