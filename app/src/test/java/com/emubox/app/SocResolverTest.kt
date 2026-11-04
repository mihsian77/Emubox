package com.emubox.app

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 部件号主表识别。
 *
 * 锁住四件事：
 * 1. 部件号精确命中，且 -AB/-AC 不被当成同一颗（SM8250=865、-AB=865+、-AC=870）；
 * 2. 联发科封装后缀能剥到平台（MT6769V/CB → MT6769），剥完有歧义时给族名而不是猜；
 * 3. 平台代号（sun/kalama）与谷歌单词代号（zuma）能命中；
 * 4. 安兔兔译名（「至尊版」）能归一化到极客湾写法（Elite）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocResolverTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resolve(
        soc: String = "",
        platform: String = "",
        gpu: String = "",
        hint: String = ""
    ): ResolvedSoc? = SocResolver.resolve(
        context,
        SocIdentity(socModel = soc, platform = platform, hardware = ""),
        gpuModel = gpu,
        socNameHint = hint
    )

    @Test
    fun `部件号精确命中`() {
        assertEquals("骁龙 8 Elite", resolve("SM8750-AB")?.name)
        assertEquals("天玑 9300", resolve("MT6989")?.name)
        assertEquals("Exynos 2400", resolve("S5E9945")?.name)
    }

    @Test
    fun `提频后缀不被合并`() {
        // SM8250 是 865，SM8250-AB 是 865+，SM8250-AC 是 870：剥错就会把三颗并成一颗
        assertEquals("骁龙 865", resolve("SM8250")?.name)
        assertEquals("骁龙 865+", resolve("SM8250-AB")?.name)
        assertEquals("骁龙 870", resolve("SM8250-AC")?.name)
        assertEquals(SocConfidence.CODE_EXACT, resolve("SM8250-AB")?.confidence)
    }

    @Test
    fun `联发科封装后缀能命中`() {
        // MT6769V/CB 是红米 9 的 G80。完整料号在映射表里时精确命中，
        // 不在时剥到 MT6769 也能认出（该裸号对应 G80/G81/G85，会标歧义）。
        val r = resolve("MT6769V/CB")
        assertNotNull(r)
        assertTrue("应命中 G8x：${r?.name}", r!!.displayName.contains("G8"))
        assertTrue(
            "精确或剥后缀都算合理：${r.confidence}",
            r.confidence == SocConfidence.CODE_EXACT || r.confidence == SocConfidence.CODE_LOOSE
        )
    }

    @Test
    fun `加号型号不被并进基础款`() {
        // 「+」若在归一化时直接删掉，8+ Gen1 会等于 8 Gen1、888+ 会等于 888
        assertEquals("骁龙 888", resolve("SM8350")?.name)
        assertEquals("骁龙 888+", resolve("SM8350-AC")?.name)
        assertEquals("骁龙 8 Gen1", resolve("SM8450")?.name)
        assertEquals("骁龙 8+ Gen1", resolve("SM8475")?.name)
    }

    @Test
    fun `冲突代号不落到被拒条目`() {
        // SM8450 归 8 Gen1、SM8475 归 8+ Gen1；冲突时被拒的那条不能还留着别人的号
        val gen1 = resolve("SM8450")
        val gen1Plus = resolve("SM8475")
        assertEquals("骁龙 8 Gen1", gen1?.name)
        assertEquals("骁龙 8+ Gen1", gen1Plus?.name)
        assertTrue("8 Gen1 不该带 SM8475：${gen1?.codes}", gen1?.codes?.none { it.contains("8475") } == true)
        assertTrue("8+ Gen1 不该带 SM8450：${gen1Plus?.codes}", gen1Plus?.codes?.none { it.contains("8450") } == true)
    }

    @Test
    fun `5G 后缀不影响识别`() {
        // 主表里同芯可能写作「骁龙 780G」或「骁龙 780G 5G」，归一化要能合并
        assertEquals("骁龙 780G", resolve(hint = "高通 骁龙780G 5G")?.name)
    }

    @Test
    fun `认不出型号时至少认出厂`() {
        // 展锐与华为没有公开完整料号表，只能落到厂商级，但不能把裸代号当芯片名
        assertEquals(
            "紫光展锐",
            SocResolver.vendorOf(context, SocIdentity("ums9230", "", ""))
        )
        assertEquals(
            "华为",
            SocResolver.vendorOf(context, SocIdentity("kirin9020", "", ""))
        )
        assertEquals("联发科", SocResolver.vendorOf(context, SocIdentity("", "mt6765", "")))
        assertTrue(SocResolver.vendorOf(context, SocIdentity("XYZ", "", "")).isBlank())
    }

    @Test
    fun `一码多名给出族名而不是猜一个`() {
        val r = resolve("MT6769")
        assertNotNull(r)
        assertTrue("MT6769 对应 G80/G85，必须标歧义", r!!.ambiguous)
        assertTrue(r.displayName.contains(" / "))
        assertTrue(r.alternates.size >= 2)
    }

    @Test
    fun `平台代号命中`() {
        assertEquals("骁龙 8 Elite", resolve(platform = "sun")?.name)
        assertEquals("骁龙 8 Gen3", resolve(platform = "pineapple")?.name)
        assertEquals("天玑 9300", resolve(platform = "mt6989")?.name)
        assertEquals(SocConfidence.PLATFORM, resolve(platform = "sun")?.confidence)
    }

    @Test
    fun `谷歌单词代号不串代`() {
        // zuma 是 Tensor G3，zuma_pro 才是 G4；拆代号时把 Zuma 单列会让 G4 抢走 G3
        assertEquals("Tensor G3", resolve("zuma")?.name)
        assertEquals("Tensor G4", resolve("zuma_pro")?.name)
    }

    @Test
    fun `安兔兔译名能归一化`() {
        // 安兔兔把 Elite 译作「至尊版」、Gen5 紧贴；极客湾写作「骁龙 8 Elite Gen5」
        val r = resolve(hint = "高通 骁龙8 至尊版 Gen5")
        assertNotNull(r)
        assertEquals("骁龙 8 Elite Gen5", r!!.name)
        assertEquals(SocConfidence.NAME, r.confidence)
    }

    @Test
    fun `大小写与下划线容错`() {
        assertEquals("骁龙 8 Gen3", resolve("sm8650-ab")?.name)
        assertEquals("天玑 9300", resolve("mt_6989")?.name)
    }

    @Test
    fun `无法识别时返回空而不是瞎猜`() {
        assertNull(resolve())
        assertNull(resolve("qcom"))
        assertNull(resolve("未知芯片 XYZ"))
    }

    @Test
    fun `主表条目字段完整`() {
        val text = context.assets.open("soc_master.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("socs")
        // 极客湾 157 + 安兔兔补的中低端，少于 350 说明有一路数据没并进来
        assertTrue("主表条目过少：${arr.length()}", arr.length() >= 350)
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue("id 为空", o.getString("id").isNotBlank())
            assertTrue("id 重复：${o.getString("id")}", ids.add(o.getString("id")))
            assertTrue("name 为空", o.getString("name").isNotBlank())
        }
    }

    @Test
    fun `展锐板级后缀不影响识别`() {
        // 展锐把主板型号拼在平台值里：ums9230_6h10、ums9620_2h10，下划线前才是芯片号。
        // 这四种形式在 deviceinfohw 设备库里都是实测值，不是造出来的。
        val a = resolve("", "ums9230_6h10")
        assertNotNull("ums9230 未命中", a)
        assertEquals("紫光", a!!.brand)
        assertTrue("一码多名应给族名而不是猜一颗：${a.displayName}", a.displayName.contains("T6"))
        assertEquals("Exynos 1080", resolve("", "s5e9815")?.name)
    }

    @Test
    fun `展锐无分隔符后缀靠前缀命中`() {
        // s9863a1h10 = SC9863A + 1h10，没有下划线可截，只能按前缀回查
        assertEquals("SC9863A", resolve("s9863a1h10")?.name)
    }

    @Test
    fun `华为平台名即型号`() {
        // 华为不公开部件号，但 ro.board.platform 恒为 kirin+型号，主表按名字推导
        assertEquals("麒麟 970", resolve("kirin970")?.name)
        assertEquals("麒麟 8000", resolve("", "kirin8000")?.name)
    }

    @Test
    fun `三星用 exynos 平台名而非 s5e 料号`() {
        // 实测：Galaxy Tab A 2016 报 samsungexyos7870（HARDWARE）/ exynos7870（platform），
        // 与 S5E7870 那套料号完全不搭，只挂 s5e 号会永远认不出。
        assertEquals("Exynos 7870", resolve("", "exynos7870")?.name)
        assertEquals("Exynos 7870", resolve("samsungexynos7870")?.name)
    }

    @Test
    fun `无代号条目不超过上限`() {
        val text = context.assets.open("soc_master.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("socs")
        var noCode = 0
        for (i in 0 until arr.length()) {
            val codes = arr.getJSONObject(i).optJSONArray("codes")
            if (codes == null || codes.length() == 0) noCode++
        }
        // 剩下的应只有 IoT/车机/电视盒子（QCM/QCS/全志/瑞芯微）与展锐未公开号的变体。
        // 超过 105 说明整批芯片又失去代号，设备上会退回「型号未收录」。
        assertTrue("无代号条目 $noCode 条，超出上限", noCode <= 90)
    }
}
