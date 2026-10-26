package com.emubox.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage(val languageTag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CHINESE("zh-CN")
}

private val LocalAppResources = staticCompositionLocalOf<Resources> {
    error("App resources were not provided")
}

@Composable
fun ProvideAppLanguage(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resources = androidx.compose.runtime.remember(context, language) {
        resourcesForLanguage(context, language)
    }

    CompositionLocalProvider(LocalAppResources provides resources) {
        content()
    }
}

private fun resourcesForLanguage(context: Context, language: AppLanguage): Resources {
    if (language == AppLanguage.SYSTEM || language.languageTag == null) {
        return context.resources
    }

    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language.languageTag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
fun appString(@StringRes id: Int, vararg args: Any): String =
    LocalAppResources.current.getString(id, *args)

fun appStringFor(
    context: Context,
    language: AppLanguage,
    @StringRes id: Int,
    vararg args: Any
): String = resourcesForLanguage(context, language).getString(id, *args)

/**
 * BannerHub wrappers.json 清单汉化（2026-10-26）：
 * 上游清单为英文原文，App 内整句映射翻译，未命中保持原文。
 * 专名（WinlatorMali/WinNative/Ludashi/leegao/BCn/GameNative 等）保留原文。
 */
private val wrapperNameZhMap = mapOf(
    "Bannerlator — Wrapper (default)" to "Bannerlator — 包装层（默认版）",
    "Bannerlator — Wrapper (original)" to "Bannerlator — 包装层（原版）",
    "Bannerlator — Wrapper (legacy)" to "Bannerlator — 包装层（旧版）",
    "Bannerlator — leegao" to "Bannerlator — leegao 驱动",
    "Bannerlator — GameNative" to "Bannerlator — GameNative 包装层",
    "Bannerlator — BCn layer (leegao)" to "Bannerlator — BCn 图层（leegao）",
    "Bannerlator — Wrapper + compat + bcn (Mali DX12)" to "Bannerlator — 包装层 + compat + BCn（Mali DX12）",
    "WinlatorMali — Wrapper" to "WinlatorMali — 包装层",
    "WinlatorMali — Wrapper v2" to "WinlatorMali — 包装层 v2",
    "WinlatorMali — leegao" to "WinlatorMali — leegao 驱动",
    "WinlatorMali — GameNative" to "WinlatorMali — GameNative 包装层",
    "WinlatorMali — BCn layer (Fcharan fork)" to "WinlatorMali — BCn 图层（Fcharan 分支）",
    "WinNative — Wrapper" to "WinNative — 包装层",
    "WinNative — GameNative" to "WinNative — GameNative 包装层",
    "WinNative — leegao" to "WinNative — leegao 驱动",
    "GameNative — GameNative Wrapper" to "GameNative — GameNative 包装层",
    "Ludashi — Wrapper (Steven)" to "路大师 — 包装层（Steven）",
    "Pipetto — Winlator Bionic Wrapper" to "Pipetto — Winlator Bionic 包装层"
)

private val wrapperDescZhMap = mapOf(
    "Bannerlator's default graphics wrapper. Byte-identical to WinlatorMali's Wrapper (same file); also identical to Ludashi's Wrapper (Steven)."
        to "Bannerlator 默认图形包装层。与 WinlatorMali 的 Wrapper 字节一致（同一文件）；也与路大师（Steven）的 Wrapper 相同。",
    "The original Winlator wrapper (Bruno's base). Byte-identical to WinNative's Wrapper and WinlatorMali's Wrapper v2 (verified same file)."
        to "Winlator 原始包装层（Bruno 基底）。与 WinNative 的 Wrapper 及 WinlatorMali 的 Wrapper v2 字节一致（已核实为同一文件）。",
    "Bannerlator's 'legacy' wrapper — this is Pipetto-crypto's Winlator Bionic wrapper (same file). Ludashi descends from this upstream."
        to "Bannerlator 的旧版包装层——即 Pipetto-crypto 的 Winlator Bionic 包装层（同一文件）。路大师（Ludashi）由该上游衍生。",
    "Bannerlator's leegao ICD build. Each project ships its own leegao build (this one differs from WinlatorMali's and WinNative's)."
        to "Bannerlator 的 leegao ICD 构建。各项目自带各自的 leegao 构建（此版本与 WinlatorMali 和 WinNative 的不同）。",
    "The GameNative wrapper (DX12 + integrated BCn). Byte-identical to WinlatorMali's GameNative (same file); WinNative ships a different build."
        to "GameNative 包装层（DX12 + 集成 BCn）。与 WinlatorMali 的 GameNative 字节一致（同一文件）；WinNative 为不同构建。",
    "leegao BCn texture-transcode layer (upstream shader-v3). Import into the BCn layer slot. For non-Adreno GPUs."
        to "leegao BCn 纹理转码图层（上游 shader-v3）。导入到 BCn 图层槽位。适用于非 Adreno GPU。",
    "Full Mali DX12 stack: leegao ICD + BCn layer + DXVK-Mali compat layer. Valhall Mali r32p1+."
        to "完整 Mali DX12 组合：leegao ICD + BCn 图层 + DXVK-Mali 兼容层。适用于 Valhall Mali r32p1 及以上。",
    "WinlatorMali's default wrapper. Byte-identical to Bannerlator's Wrapper (default) (same file); also identical to Ludashi's Wrapper (Steven)."
        to "WinlatorMali 默认包装层。与 Bannerlator 的默认包装层字节一致（同一文件）；也与路大师（Steven）的 Wrapper 相同。",
    "WinlatorMali's 'v2' wrapper — byte-identical to the Bruno original (Bannerlator Wrapper original / WinNative Wrapper)."
        to "WinlatorMali 的 v2 包装层——与 Bruno 原版（Bannerlator 原版 / WinNative）字节一致。",
    "WinlatorMali's leegao ICD build (differs from Bannerlator's and WinNative's leegao)."
        to "WinlatorMali 的 leegao ICD 构建（与 Bannerlator 和 WinNative 的 leegao 不同）。",
    "WinlatorMali's GameNative wrapper. Byte-identical to Bannerlator's GameNative (same file)."
        to "WinlatorMali 的 GameNative 包装层。与 Bannerlator 的 GameNative 字节一致（同一文件）。",
    "Performance/stability-tuned BCn layer, a fork of leegao's with extra ASTC-quality, staging-cache and queue-throttle controls. Import into the BCn layer slot."
        to "性能/稳定性调优版 BCn 图层，为 leegao 的分支，额外提供 ASTC 质量、暂存缓存与队列限速控制。导入到 BCn 图层槽位。",
    "WinNative's default wrapper — byte-identical to the Bruno original (same file)."
        to "WinNative 默认包装层——与 Bruno 原版字节一致（同一文件）。",
    "WinNative's own GameNative wrapper build (differs from Bannerlator's/WinlatorMali's GameNative)."
        to "WinNative 自有的 GameNative 包装层构建（与 Bannerlator / WinlatorMali 的 GameNative 不同）。",
    "WinNative's own leegao ICD build (differs from Bannerlator's and WinlatorMali's)."
        to "WinNative 自有的 leegao ICD 构建（与 Bannerlator 和 WinlatorMali 的不同）。",
    "The canonical GameNative wrapper — latest upstream build (20260725, GameNative #1771): leegao's latest changes on top of the #1762 older-device workaround base."
        to "标准 GameNative 包装层——最新上游构建（20260725，GameNative #1771）：在 #1762 老设备兼容基底之上叠加 leegao 最新改动。",
    "Winlator‑Ludashi's default wrapper (StevenMXZ, ludashi-3.1). Byte‑identical to the Bannerlator/WinlatorMali default Wrapper (same file)."
        to "Winlator-Ludashi 默认包装层（StevenMXZ，ludashi-3.1）。与 Bannerlator / WinlatorMali 默认包装层字节一致（同一文件）。",
    "Pipetto-crypto's Winlator Bionic wrapper (dev branch) — the bionic upstream that Ludashi and other bionic forks descend from. Byte-identical to Bannerlator's legacy Wrapper."
        to "Pipetto-crypto 的 Winlator Bionic 包装层（dev 分支）——路大师及众多 Bionic 分支的上游。与 Bannerlator 旧版包装层字节一致。"
)

/** wrappers.json 清单条目名称汉化；未命中保持原文 */
fun wrapperNameZh(name: String): String = wrapperNameZhMap[name] ?: name

/** wrappers.json 清单条目描述汉化；未命中保持原文 */
fun wrapperDescZh(description: String): String = wrapperDescZhMap[description] ?: description
