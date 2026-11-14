package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GPU 识别与制程文案的回归测试。
 *
 * 这两处都曾把「解析不到」当成「默认值」，错得很安静：
 * 展锐 T606 的 Mali-G57 显示成「Adreno 57」，台积电 N12 显示成「1nm2」。
 */
class HardwareUtilsTest {

    private fun device(
        renderer: String,
        model: String,
        vendor: GpuVendor,
        series: String = "unknown"
    ) = DeviceInfo(
        androidVersion = "15 (SDK 35)",
        ram = "8 GB",
        gpuRenderer = renderer,
        adrenoSeries = series,
        gpuModel = model,
        gpuVendor = vendor
    )

    @Test
    fun `Mali 两位型号也能识别`() {
        assertEquals("57", detectGpuModel("Mali-G57 MC2"))
        assertEquals("52", detectGpuModel("Mali-G52"))
        assertEquals("76", detectGpuModel("Mali-G76 MC4"))
        // 三位、四位数号保持原样
        assertEquals("720", detectGpuModel("Mali-G720"))
        assertEquals("610", detectGpuModel("Mali G610"))
    }

    @Test
    fun `Adreno 型号仍按三位四位识别`() {
        assertEquals("750", detectGpuModel("Adreno (TM) 750"))
        assertEquals("830", detectGpuModel("Adreno 830"))
        assertEquals("unknown", detectGpuModel("unknown renderer"))
    }

    @Test
    fun `Mali 设备不能显示成 Adreno`() {
        // 多核后缀（MC2）不展示，型号本身已经够识别
        val withRenderer = device("Mali-G57 MC2", "57", GpuVendor.MALI)
        assertEquals("Mali-G57", gpuDisplayName(withRenderer))
        // renderer 拿不到时按厂商拼，不能默认 Adreno
        val noRenderer = device("", "57", GpuVendor.MALI)
        assertEquals("Mali-G57", gpuDisplayName(noRenderer))
    }

    @Test
    fun `Xclipse 与 Adreno 的显示名`() {
        assertEquals("Xclipse 940", gpuDisplayName(device("Xclipse 940", "940", GpuVendor.XCLIPSE)))
        assertEquals("Adreno 750", gpuDisplayName(device("", "750", GpuVendor.ADRENO)))
        // gpuModel 已是全名时不重复拼前缀
        assertEquals("Adreno 750", gpuDisplayName(device("", "Adreno 750", GpuVendor.ADRENO)))
    }

    @Test
    fun `制程节点两位数不被拆开`() {
        assertEquals("台积电 12nm", formatProcess("TSMC N12"))
        assertEquals("台积电 6nm", formatProcess("TSMC N6"))
        assertEquals("台积电 3nm", formatProcess("TSMC N3E"))
        assertEquals("三星 4nm", formatProcess("Samsung 4LPP+"))
        assertEquals("中芯国际 7nm", formatProcess("SMIC N7"))
    }
}
