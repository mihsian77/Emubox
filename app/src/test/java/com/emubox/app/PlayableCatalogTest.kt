package com.emubox.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 可玩库判档。
 *
 * 锁四件事：
 * 1. 旗舰（8 Elite）在 PS2 上是流畅，Switch 上是勉强——门槛不能整体偏松或偏紧；
 * 2. 老中端（骁龙 680）跑不了 PS2，但经典平台必须给流畅；
 * 3. 内存不足时档位要降，而不是只看 GPU 分；
 * 4. 认不出的芯片给「无参考分」，不能默认当成能跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayableCatalogTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun soc(code: String): ResolvedSoc? = SocResolver.resolve(
        context,
        SocIdentity(socModel = code, platform = "", hardware = "")
    )

    private fun report(code: String, ramMb: Long = 12288): PlayableReport {
        val s = soc(code)
        assertNotNull("芯片应能识别：$code", s)
        return PlayableCatalog.evaluate(context, s, ramMb, emptyList())
    }

    private fun grade(r: PlayableReport, key: String): PlayGrade =
        r.platforms.first { it.key == key }.grade

    @Test
    fun `旗舰的档位分布`() {
        val r = report("SM8750-AB") // 骁龙 8 Elite，极客湾 GPU 分 489
        assertEquals(PlayGrade.SMOOTH, grade(r, "ps2"))
        // Switch / Wii U 门槛按「多数游戏稳定 60 帧」设，8 Elite 也只在「可玩」档
        assertEquals(PlayGrade.PLAYABLE, grade(r, "switch"))
        assertEquals(PlayGrade.SMOOTH, grade(r, "pc"))
        assertEquals(PlayGrade.SMOOTH, grade(r, "classic"))
    }

    @Test
    fun `老中端的档位分布`() {
        val r = report("SM6225") // 骁龙 680，GPU 分 18.5
        assertEquals(PlayGrade.TIGHT, grade(r, "ps2"))
        assertEquals(PlayGrade.TIGHT, grade(r, "switch"))
        assertEquals(PlayGrade.SMOOTH, grade(r, "classic"))
        // N64 门槛只有 12，680 的 18.5 够流畅；PSP 的流畅线是 28，落在「勉强」
        assertEquals(PlayGrade.SMOOTH, grade(r, "n64"))
        assertEquals(PlayGrade.PLAYABLE, grade(r, "psp"))
    }

    @Test
    fun `内存不足时降档并标记`() {
        val rich = report("SM8750-AB", ramMb = 12288)
        val poor = report("SM8750-AB", ramMb = 3072)
        assertEquals(PlayGrade.SMOOTH, grade(rich, "ps2"))
        // PS2 门槛要求 4GB 内存，3GB 会把档位从「流畅」压到「勉强」并标出原因
        assertEquals(PlayGrade.PLAYABLE, grade(poor, "ps2"))
        assertTrue(poor.platforms.first { it.key == "ps2" }.ramLimited)
    }

    @Test
    fun `认不出的芯片给无参考分`() {
        val r = PlayableCatalog.evaluate(context, null, 8192, emptyList())
        assertEquals(null, r.score)
        assertTrue(r.platforms.all { it.grade == PlayGrade.UNKNOWN })
    }

    @Test
    fun `平台条目计数只算安卓可装`() {
        val entries = listOf(
            EmulatorEntry(id = "a", name = "A", category = "emulator", description = "", owner = "", repo = "", platforms = listOf("ps2"), hosts = listOf("android")),
            EmulatorEntry(id = "b", name = "B", category = "emulator", description = "", owner = "", repo = "", platforms = listOf("ps2"), hosts = listOf("windows")),
            EmulatorEntry(id = "c", name = "C", category = "emulator", description = "", owner = "", repo = "", platforms = listOf("switch"), hosts = listOf("android")),
        )
        val r = PlayableCatalog.evaluate(context, soc("SM8750-AB"), 12288, entries)
        assertEquals(1, r.platforms.first { it.key == "ps2" }.appCount)
        assertEquals(1, r.platforms.first { it.key == "switch" }.appCount)
        assertEquals(0, r.platforms.first { it.key == "gamecube" }.appCount)
    }

    /** 差距要能算成「再补多少分」，这是界面上最可执行的一个数字。 */
    @Test
    fun `差距按门槛四舍五入`() {
        val r = report("SM8750-AB") // 489.2 分
        val sw = r.platforms.first { it.key == "switch" } // 流畅线 600
        assertEquals(111, sw.gapToNextTier(r.score))
        assertEquals(111, sw.pointsToSmooth(r.score!!))
        // PS2 已达标，差距必须是 0 而不是负数
        val ps2 = r.platforms.first { it.key == "ps2" }
        assertEquals(0, ps2.gapToNextTier(r.score))
        assertEquals(0, ps2.pointsToSmooth(r.score!!))
    }

    /**
     * 结论句与「下一步」都挂在「离流畅线最近的平台」上：
     * 旗舰机没有吃力平台，但 Switch 仍差一口，这条链路不能空。
     */
    @Test
    fun `旗舰的最近未达标平台与驱动建议`() {
        val r = report("SM8750-AB")
        assertEquals(null, r.nearTight) // 489 分下没有平台掉到「吃力」
        assertEquals("switch", r.nearBelowSmooth?.key)
        assertEquals("Turnip", r.driverHint)
        // 天玑 9300 是 Immortalis（ARM Mali 系），开源驱动走 PanVK
        assertEquals("PanVK", report("MT6989").driverHint)
    }

    /** 同芯片社区实测：8 Elite 能对上 EmuReady，且只在该平台有足够报告时才挂上去。 */
    @Test
    fun `同芯片社区实测挂到平台行`() {
        val r = report("SM8750-AB")
        assertEquals("Snapdragon 8 Elite", r.evidenceSoc)
        val sw = r.platforms.first { it.key == "switch" }
        assertNotNull("Switch 在同芯片下有上千条报告，必须挂上", sw.evidence)
        assertTrue(sw.evidence!!.listings >= 20)
        // 报告太少（8 Elite 的 PS2 只有 12 条）时宁可不显示：3 个人的意见不算证据
        assertEquals(null, r.platforms.first { it.key == "ps2" }.evidence)
        // 复古合集没有对应的 EmuReady 平台，不能硬塞一条空证据
        assertEquals(null, r.platforms.first { it.key == "classic" }.evidence)
    }

    /**
     * 实测只做对照，不改档位。
     *
     * 实测里 Helio P35 的 Switch 是 75 分、骁龙 8 Elite 只有 58 分；若按实测判档，
     * P35 会比 8 Elite 更「能跑 Switch」。分数是「同芯片机型上被测游戏的平均评价」，
     * 各机型测的游戏集合不同，所以只能当参考展示。这条锁住「不上调、不下调」两侧。
     */
    @Test
    fun `实测折算的档位不改变判档`() {
        val p = PlayablePlatform(
            key = "switch",
            label = "Switch",
            grade = PlayGrade.TIGHT,
            smooth = 600,
            ok = 350,
            minRamMb = 6144,
            note = "",
            match = emptyList(),
            appCount = 0,
            evidence = PlayEvidence(
                listings = 22,
                uniqueGames = 16,
                score = 75,
                bestEmulator = "Yuzu",
                device = "Redmi 10C",
                confidence = "medium"
            )
        )
        // 75 分按色阶折算成「勉强」，但档位必须还是「吃力」
        assertEquals(PlayGrade.PLAYABLE, p.evidenceGrade)
        assertTrue(p.evidenceDiffers)
        assertEquals(PlayGrade.TIGHT, p.grade)
    }

    /** 真实数据：入门芯片也能挂上实测，且档位不被它拉高。 */
    @Test
    fun `入门芯片的实测不会把档位拉高`() {
        val r = report("MT6765") // 曦力 P35，GPU 分 7.4
        val sw = r.platforms.first { it.key == "switch" }
        assertEquals(PlayGrade.TIGHT, sw.grade)
        assertNotNull("P35 在同芯片下有 22 条 Switch 报告", sw.evidence)
        assertEquals(PlayGrade.PLAYABLE, sw.evidenceGrade)
        assertTrue(sw.evidenceDiffers)
    }
}
