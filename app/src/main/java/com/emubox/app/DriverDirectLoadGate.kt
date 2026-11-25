package com.emubox.app

/**
 * 直载（App 进程内 dlopen）可选性判定。
 *
 * 新渲染实测卡的 native 链路只收单个主 so 路径，在 App 进程内直接 dlopen；
 * 而通过 zip/adpkg 分发的 adrenotools/Mesa 包（Turnip/PanVK）依赖模拟器经
 * adrenotools 注入的钩子与运行环境，厂商系统提取驱动又依赖系统服务，
 * 这两类在 App 进程内直载必然失败，不应放进直载可选目标。
 *
 * 分类口径沿用 [DriverMetaParser.DriverMeta.driverType]，这里只做直载门禁分流，
 * 不改动解析与解压逻辑。
 */
object DriverDirectLoadGate {

    /** 直载判定结果 */
    enum class Verdict {
        /** 可以走本卡直载实测 */
        LOADABLE,

        /** adrenotools/Mesa 打包（Turnip/PanVK），需走注入加载，本卡不直载 */
        BLOCKED_ADRENOTOOLS,

        /** 厂商系统提取驱动，需 root/系统级环境，App 内无法实测 */
        BLOCKED_VENDOR_SYSTEM,
    }

    /**
     * 按包 meta 判定能否直载。meta 解析失败（null）或类型无法识别时，
     * 保持旧行为、允许选择，不在门禁里误杀。
     */
    fun verdict(meta: DriverMetaParser.DriverMeta?): Verdict = when (meta?.driverType()) {
        DriverMetaParser.DriverType.ADRENOTOOLS_PACKAGE -> Verdict.BLOCKED_ADRENOTOOLS
        DriverMetaParser.DriverType.VENDOR_SYSTEM -> Verdict.BLOCKED_VENDOR_SYSTEM
        DriverMetaParser.DriverType.UNKNOWN, null -> Verdict.LOADABLE
    }

    /** 是否允许进直载链路 */
    fun canDirectLoad(meta: DriverMetaParser.DriverMeta?): Boolean = verdict(meta) == Verdict.LOADABLE
}
