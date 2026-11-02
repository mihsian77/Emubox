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
    fun `联发科封装后缀剥到平台`() {
        // MT6769V/CB 是红米 9 的 G80，主表只收录了 V/CU 与 V/CZ，需剥到 MT6769 才认得出
        val r = resolve("MT6769V/CB")
        assertNotNull(r)
        assertTrue("应命中 G80/G85 中的一颗：${r?.name}", r!!.name.contains("G8"))
        assertEquals(SocConfidence.CODE_LOOSE, r.confidence)
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
        assertTrue("主表条目过少：${arr.length()}", arr.length() >= 150)
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue("id 为空", o.getString("id").isNotBlank())
            assertTrue("id 重复：${o.getString("id")}", ids.add(o.getString("id")))
            assertTrue("name 为空", o.getString("name").isNotBlank())
        }
    }
}
