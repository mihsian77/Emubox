package com.emubox.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EmuReady 离线目录匹配：这张卡"完全显示不出信息"的根因是旧实现联网模糊搜设备名，
 * 上游按包含匹配返回首条（搜 Xiaomi 15 给的是 Xiaomi 15T Pro），SoC 兜底又从未命中。
 * 这里锁住三点：目录完整性、型号精确匹配不串到相邻机型、未收录时走同芯片参考。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmuReadyStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun 目录加载完整() {
        assertTrue("设备目录应不少于 700 台", EmuReadyStore.devices(context).size >= 700)
        assertEquals(20, EmuReadyStore.systems(context).size)
        assertTrue("模拟器应不少于 40 个", EmuReadyStore.emulators(context).size >= 40)
    }

    @Test
    fun 型号精确匹配不串到相邻机型() {
        // 旧实现联网搜 "Xiaomi 15" 首条是 "Xiaomi 15T Pro"，这里必须命中 15 本体
        // （上游型号名只存 "15"，品牌单列，展示时由界面拼接）
        val hit = EmuReadyStore.match(context, "Xiaomi", listOf("Xiaomi 15"), "Snapdragon 8 Elite")
        assertNotNull(hit)
        assertEquals("15", hit!!.device.modelName)
        assertEquals("Xiaomi", hit.device.brandName)
        assertEquals("exact", hit.how)
    }

    @Test
    fun 营销名与品牌拼接也能命中() {
        // 设备代号表里 23127PN0CC → "13 Pro"，界面拼厂商名后是「Xiaomi 13 Pro」
        val hit = EmuReadyStore.match(context, "Xiaomi", listOf("13 Pro", "23127PN0CC"), "")
        assertNotNull(hit)
        assertTrue(hit!!.device.modelName.contains("13"))
    }

    @Test
    fun 内部代号按同品牌同芯片定位() {
        // 一加 13 上报的是 PJZ110 这类内部代号，公开表里查不到；
        // 但同品牌同芯片要落在 OnePlus 13 上，而不是跨品牌的 Samsung S25 Ultra
        val hit = EmuReadyStore.match(context, "OnePlus", listOf("PJZ110"), "Snapdragon 8 Elite")
        assertNotNull(hit)
        assertEquals("brand-soc", hit!!.how)
        assertEquals("OnePlus", hit.device.brandName)
        assertTrue(hit.device.modelName.startsWith("13"))
    }

    @Test
    fun 未收录机型走同芯片参考() {
        // 魅族 21 Note 不在 EmuReady 目录里，但要能按 SoC 找到同芯片代表机，而不是空着
        val hit = EmuReadyStore.match(context, "Meizu", listOf("21 Note"), "Snapdragon 8 Gen 2")
        assertNotNull(hit)
        assertEquals("soc", hit!!.how)
        assertEquals("Snapdragon 8 Gen 2", hit.device.socName)
        assertTrue("兜底机型应有足够报告数才值得参考", hit.device.listingsCount >= 10)
    }

    @Test
    fun 中英SoC名归一化一致() {
        assertEquals(
            EmuReadyStore.normalizeSoc("Snapdragon 8 Gen 2"),
            EmuReadyStore.normalizeSoc("骁龙8 Gen 2")
        )
        assertEquals(
            EmuReadyStore.normalizeSoc("Dimensity 9300"),
            EmuReadyStore.normalizeSoc("天玑9300")
        )
    }

    @Test
    fun 型号键去括号与符号() {
        assertEquals("konkrpocketfit", EmuReadyStore.normalizeKey("KONKR Pocket FIT (G3 Gen 3)"))
        assertEquals("oneplus13r", EmuReadyStore.normalizeKey("OnePlus 13R"))
    }

    @Test
    fun 完全无关的设备不误报() {
        // 品牌型号都对不上、SoC 也没有 → 必须老实返回 null，不能随便拿一台充数
        assertNull(EmuReadyStore.match(context, "NoSuchBrand", listOf("ZZ9999"), ""))
    }
}
