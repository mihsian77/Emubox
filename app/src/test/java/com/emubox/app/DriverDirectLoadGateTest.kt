package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 直载门禁分流判定。
 *
 * 锁住口径：adrenotools/Mesa 打包（Turnip/PanVK）与厂商系统提取驱动不进
 * App 进程内的裸 dlopen 链路；meta 解析失败或类型无法识别时保持旧行为，不误杀。
 * 分类函数本身在 DriverMetaParser.driverType()，这里只验门禁映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverDirectLoadGateTest {

    private fun meta(
        name: String = "",
        vendor: String = "",
        libraryName: String = "",
        description: String = "",
    ) = DriverMetaParser.DriverMeta(
        name = name,
        description = description,
        author = "",
        packageVersion = "",
        vendor = vendor,
        driverVersion = "",
        minApi = "",
        libraryName = libraryName,
        raw = "",
    )

    @Test
    fun `adrenotools 打包的 turnip 包被拦在直载链路外`() {
        val m = meta(vendor = "mesa", libraryName = "libvulkan_turnip.so")
        assertEquals(DriverDirectLoadGate.Verdict.BLOCKED_ADRENOTOOLS, DriverDirectLoadGate.verdict(m))
    }

    @Test
    fun `panvk 包被拦在直载链路外`() {
        val m = meta(name = "PanVK preview build")
        assertEquals(DriverDirectLoadGate.Verdict.BLOCKED_ADRENOTOOLS, DriverDirectLoadGate.verdict(m))
    }

    @Test
    fun `厂商系统提取驱动被拦下`() {
        val m = meta(vendor = "qualcomm")
        assertEquals(DriverDirectLoadGate.Verdict.BLOCKED_VENDOR_SYSTEM, DriverDirectLoadGate.verdict(m))
    }

    @Test
    fun `未识别包与 meta 解析失败保持可选`() {
        assertEquals(DriverDirectLoadGate.Verdict.LOADABLE, DriverDirectLoadGate.verdict(meta(libraryName = "libfoo.so")))
        assertEquals(DriverDirectLoadGate.Verdict.LOADABLE, DriverDirectLoadGate.verdict(null))
    }

    @Test
    fun `canDirectLoad 与 verdict 口径一致`() {
        assertEquals(false, DriverDirectLoadGate.canDirectLoad(meta(vendor = "mesa")))
        assertEquals(true, DriverDirectLoadGate.canDirectLoad(null))
    }
}
