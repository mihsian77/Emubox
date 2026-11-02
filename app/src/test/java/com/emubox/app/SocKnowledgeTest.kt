package com.emubox.app

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 芯片知识库。
 *
 * 匹配逻辑是「设备上报的 SoC 名去空格/横线、英文厂牌换中文后，包含某个 key」，
 * 所以 key 里一旦带空格（"snapdragon 8 elite"）或横线（"mali-g710"），
 * 就永远命不中——旧数据 168 个 key 里有 70 个是这种死键，
 * 能显示只是碰巧靠 GPU 型号键兜住了。
 *
 * 这里锁住：key 形态合法、老机型能命中、新补条目不自称有跑分。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocKnowledgeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun raw(): List<JSONObject> {
        val text = context.assets.open("soc_knowledge.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("socs")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun `匹配键不含空格与横线`() {
        val bad = mutableListOf<String>()
        for (soc in raw()) {
            val keys = soc.getJSONArray("keys")
            for (i in 0 until keys.length()) {
                val k = keys.getString(i)
                if (k.contains(' ') || k.contains('-') || k.contains('_')) {
                    bad.add("${soc.getString("name")}: $k")
                }
            }
        }
        assertEquals("这些键在去空格的 haystack 里永远命中不了：$bad", 0, bad.size)
    }

    @Test
    fun `老旗舰按设备上报名能命中`() {
        // 设备上报的是 "Qualcomm Snapdragon 855" 这类名字，走 SocKnowledgeStore 的归一化
        val cases = mapOf(
            "Qualcomm Snapdragon 855" to "骁龙 855",
            "Snapdragon 855 Plus" to "骁龙 855+",
            "Qualcomm SDM845" to "骁龙 845",
            "Qualcomm Snapdragon 765G" to "骁龙 765G",
            "MediaTek Dimensity 1000L" to "天玑 1000L",
            "Samsung Exynos 2100" to "Exynos 2100",
            "MediaTek Helio G90T" to "Helio G90T",
            "HUAWEI Kirin 980" to "麒麟 980"
        )
        for ((reported, expected) in cases) {
            val hit = SocKnowledgeStore.find(reported, "", context)
            assertNotNull("$reported 应命中 $expected", hit)
            assertEquals(reported, expected, hit!!.name)
        }
    }

    @Test
    fun `GPU 型号兜底仍可命中`() {
        // SoC 名识别不出来时，靠渲染器解析的 GPU 型号兜底
        assertEquals("骁龙 855", SocKnowledgeStore.find("", "640", context)?.name)
        assertEquals("骁龙 845", SocKnowledgeStore.find("", "630", context)?.name)
        assertEquals("骁龙 835", SocKnowledgeStore.find("", "540", context)?.name)
    }

    @Test
    fun `新增条目不自称有参考跑分`() {
        // 跑分只写能核实的数字；没核实的一律留空，界面显示「未收录」
        // 老机型这批没有可靠的同口径数据，不该编一个数字出来
        val legacy = setOf(
            "骁龙 855", "骁龙 855+", "骁龙 845", "骁龙 835", "骁龙 765G",
            "骁龙 750G", "骁龙 720G", "骁龙 480", "天玑 1000L",
            "Exynos 990", "Exynos 2100", "Helio G90T", "麒麟 980"
        )
        for (soc in raw()) {
            if (soc.getString("name") !in legacy) continue
            assertEquals(
                "${soc.getString("name")} 的参考跑分应为空",
                "", soc.optString("score")
            )
            assertTrue(soc.getString("process").isNotBlank())
            assertTrue(soc.getString("gpu").isNotBlank())
            assertTrue(soc.getString("devices").isNotBlank())
        }
    }

    @Test
    fun `每个条目都有可用的匹配键`() {
        for (soc in raw()) {
            val keys = soc.getJSONArray("keys")
            val usable = (0 until keys.length()).count { keys.getString(it).length >= 5 }
            assertTrue("${soc.getString("name")} 没有长度达标的匹配键", usable >= 1)
        }
    }
}
