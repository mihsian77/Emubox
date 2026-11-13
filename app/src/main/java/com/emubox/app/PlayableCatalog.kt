package com.emubox.app

import android.content.Context
import kotlin.math.roundToInt
import org.json.JSONObject

/** 一档可玩性。TIGHT 是「能进游戏但基本不能玩」，仍列表是因为用户需要知道差在哪。 */
enum class PlayGrade { SMOOTH, PLAYABLE, TIGHT, UNKNOWN }

/**
 * 同芯片的社区实测（来自 EmuReady 按 SoC 聚合的报告）。
 *
 * 它和档位是两套口径：档位由 GPU 分 + 内存推出，这里的 score 是玩家对
 * 「这台机器跑这个平台」的主观打分（0~100）。两者并列的意义是互相校验，
 * 冲突时以实测为准——档位不知道散热、驱动和具体游戏。
 */
data class PlayEvidence(
    val listings: Int,
    val score: Int,
    val bestEmulator: String,
    /** 报告来自哪台同芯片机型，说明同名 SoC 也有个体差异 */
    val device: String,
    val confidence: String
)

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
    val ramLimited: Boolean = false,
    /** 同芯片社区实测；没有足够报告时为 null，界面不显示这一行 */
    val evidence: PlayEvidence? = null
) {
    /** 距离下一档还差多少分：已达标为 0。用于「差 111 分」这类可执行提示。 */
    fun gapToNextTier(score: Double?): Int? {
        if (score == null) return null
        return when {
            grade == PlayGrade.SMOOTH -> 0
            grade == PlayGrade.PLAYABLE -> (smooth - score).roundToInt().coerceAtLeast(0)
            grade == PlayGrade.TIGHT -> (ok - score).roundToInt().coerceAtLeast(0)
            else -> null
        }
    }

    /** 本机分数是门槛的几倍；用于行右侧的「4.1×」。无分返回 null。 */
    fun ratioToSmooth(score: Double?): Double? {
        if (score == null || smooth <= 0) return null
        return score / smooth
    }

    /** 距离流畅线还差多少分（已达标为 0）。 */
    fun pointsToSmooth(score: Double): Int =
        (smooth - score).roundToInt().coerceAtLeast(0)
}

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
    val disclaimer: String,
    /** 命中社区实测的芯片名；空串表示这颗芯片在社区库里也没有 */
    val evidenceSoc: String = "",
    /** 社区库覆盖的机型数，用来解释「为什么同芯片的报告能借用」 */
    val evidenceDevices: List<String> = emptyList(),
    /** GPU 家族（adreno / mali / xclipse），决定能推哪个开源驱动 */
    val gpuFamily: String = ""
) {
    /** 跑得动 = 流畅 + 勉强；吃力与无分不算。 */
    val runnableCount: Int get() = platforms.count { it.grade == PlayGrade.SMOOTH || it.grade == PlayGrade.PLAYABLE }

    /** 吃力组里最接近达标线的平台：结论句说「差得最少」的那个才有用。 */
    val nearTight: PlayablePlatform? get() = platforms
        .filter { it.grade == PlayGrade.TIGHT }
        .minByOrNull { (it.gapToNextTier(score) ?: Int.MAX_VALUE) }

    /**
     * 所有没到流畅线的平台里最接近的那个。
     *
     * 和 nearTight 分开：旗舰机上「没有吃力平台」很常见，但「差点跑不满帧」的平台
     * 仍然值得点出来——那正是换驱动可能补上的那一段。
     */
    val nearBelowSmooth: PlayablePlatform? get() = score?.let { s ->
        platforms
            .filter { it.pointsToSmooth(s) > 0 }
            .minByOrNull { it.pointsToSmooth(s) }
    }

    /**
     * 能否靠换驱动补：只有 Adreno（Turnip）与 ARM Mali / Immortalis（PanVK）有开源实现。
     * 没有「未达流畅线」的平台时返回 null，卡片不出现。
     */
    val driverHint: String?
        get() {
            if (score == null) return null
            if (nearBelowSmooth == null) return null
            val g = gpuFamily.lowercase()
            return when {
                g.contains("adreno") -> "Turnip"
                g.contains("mali") || g.contains("immortalis") || g.contains("xclipse") -> "PanVK"
                else -> null
            }
        }
}

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

    /**
     * 平台 key → EmuReady system key。两套命名各管一段（前者是构建脚本的门槛表，
     * 后者是上游目录），改任一边都要同步这里。没有对应项的（复古合集）留空。
     */
    private val COMPAT_KEYS = mapOf(
        "ps2" to "sony_playstation_2",
        "gamecube" to "nintendo_gamecube",
        "wii" to "nintendo_wii",
        "3ds" to "nintendo_3ds",
        "psvita" to "sony_playstation_vita",
        "switch" to "nintendo_switch",
        "wiiu" to "nintendo_wii_u",
        "psp" to "sony_playstation_portable",
        "dreamcast" to "sega_dreamcast",
        "saturn" to "sega_saturn",
        "n64" to "nintendo_64",
        "nds" to "nintendo_ds",
        "ps1" to "sony_playstation",
        "pc" to "microsoft_windows"
    )

    /**
     * 低于这个报告条数就不展示：3 条报告得出的分数会误导人，
     * 而「没有数据」比「有 3 个人的意见」更诚实。
     */
    private const val MIN_EVIDENCE_LISTINGS = 20

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
        }
        return matrix ?: Matrix("", "", "", emptyList(), emptyList(), emptyMap())
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

        // 同芯片社区实测：EmuReady 按机型累积，这里借用同 SoC 其他机型的报告。
        // 匹配不上（新芯片 / 冷门芯片）时整块不显示，而不是显示一堆空行。
        val compat = soc?.let { s ->
            EmuReadySocStore.find(context, s.name) ?: EmuReadySocStore.find(context, s.nameEn)
        }
        val evidenceBySystem = compat?.systems?.associateBy { it.key }.orEmpty()

        val out = m.platforms.map { rule ->
            val ramOk = ramMb <= 0 || ramMb >= rule.minRamMb
            val (grade, ramLimited) = when {
                score == null -> PlayGrade.UNKNOWN to false
                score >= rule.smooth && ramOk -> PlayGrade.SMOOTH to false
                score >= rule.smooth -> PlayGrade.PLAYABLE to true
                score >= rule.ok -> PlayGrade.PLAYABLE to false
                else -> PlayGrade.TIGHT to false
            }
            val sys = COMPAT_KEYS[rule.key]?.let { evidenceBySystem[it] }
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
                ramLimited = ramLimited,
                evidence = sys?.takeIf { it.listings >= MIN_EVIDENCE_LISTINGS }?.let {
                    PlayEvidence(
                        listings = it.listings,
                        score = it.score,
                        bestEmulator = it.bestEmulator,
                        device = it.device,
                        confidence = it.confidence
                    )
                }
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
            disclaimer = m.disclaimer,
            evidenceSoc = compat?.soc.orEmpty(),
            evidenceDevices = compat?.devices.orEmpty(),
            gpuFamily = soc?.gpu.orEmpty()
        )
    }
}
