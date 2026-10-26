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
 * BannerHub wrappers.json 清单汉化（2026-10-26 v2）：
 * 翻译原则：专名与国内社区通用英文术语不译（Wrapper/ICD/DX12/compat/BCn/
 * GameNative/leegao/Valhall/Mali/Shader 等），只译解释性句子为自然中文。
 * 每个条目在 UI 提供「译文/原文」切换。
 */
private val wrapperNameZhMap = mapOf(
    "Bannerlator — Wrapper (default)" to "Bannerlator — Wrapper（默认版）",
    "Bannerlator — Wrapper (original)" to "Bannerlator — Wrapper（原版）",
    "Bannerlator — Wrapper (legacy)" to "Bannerlator — Wrapper（旧版）",
    "Bannerlator — leegao" to "Bannerlator — leegao ICD",
    "Bannerlator — GameNative" to "Bannerlator — GameNative",
    "Bannerlator — BCn layer (leegao)" to "Bannerlator — BCn 图层（leegao）",
    "Bannerlator — Wrapper + compat + bcn (Mali DX12)" to "Bannerlator — Wrapper + compat + BCn（Mali DX12）",
    "WinlatorMali — Wrapper" to "WinlatorMali — Wrapper",
    "WinlatorMali — Wrapper v2" to "WinlatorMali — Wrapper v2",
    "WinlatorMali — leegao" to "WinlatorMali — leegao ICD",
    "WinlatorMali — GameNative" to "WinlatorMali — GameNative",
    "WinlatorMali — BCn layer (Fcharan fork)" to "WinlatorMali — BCn 图层（Fcharan 分支）",
    "WinNative — Wrapper" to "WinNative — Wrapper",
    "WinNative — GameNative" to "WinNative — GameNative",
    "WinNative — leegao" to "WinNative — leegao ICD",
    "GameNative — GameNative Wrapper" to "GameNative — GameNative Wrapper",
    "Ludashi — Wrapper (Steven)" to "Ludashi（路大师）— Wrapper（Steven）",
    "Pipetto — Winlator Bionic Wrapper" to "Pipetto — Winlator Bionic Wrapper"
)

private val wrapperDescZhMap = mapOf(
    "Bannerlator's default graphics wrapper. Byte-identical to WinlatorMali's Wrapper (same file); also identical to Ludashi's Wrapper (Steven)."
        to "Bannerlator 默认图形 Wrapper，与 WinlatorMali 的 Wrapper 为同一文件（字节一致），也与 Ludashi（Steven）的 Wrapper 相同。",
    "The original Winlator wrapper (Bruno's base). Byte-identical to WinNative's Wrapper and WinlatorMali's Wrapper v2 (verified same file)."
        to "Winlator 原始 Wrapper（Bruno 基底），与 WinNative 的 Wrapper、WinlatorMali 的 Wrapper v2 为同一文件（已验证字节一致）。",
    "Bannerlator's 'legacy' wrapper — this is Pipetto-crypto's Winlator Bionic wrapper (same file). Ludashi descends from this upstream."
        to "Bannerlator 旧版 Wrapper，即 Pipetto-crypto 的 Winlator Bionic Wrapper（同一文件），Ludashi 由此上游衍生。",
    "Bannerlator's leegao ICD build. Each project ships its own leegao build (this one differs from WinlatorMali's and WinNative's)."
        to "Bannerlator 的 leegao ICD 构建。各项目自带各自的 leegao 构建（此版本与 WinlatorMali、WinNative 的不同）。",
    "The GameNative wrapper (DX12 + integrated BCn). Byte-identical to WinlatorMali's GameNative (same file); WinNative ships a different build."
        to "GameNative Wrapper（DX12 + 内置 BCn），与 WinlatorMali 的 GameNative 为同一文件（字节一致），WinNative 为不同构建。",
    "leegao BCn texture-transcode layer (upstream shader-v3). Import into the BCn layer slot. For non-Adreno GPUs."
        to "leegao 的 BCn 纹理转码图层（上游 shader-v3），导入 BCn 图层槽位，仅适用于非 Adreno GPU。",
    "Full Mali DX12 stack: leegao ICD + BCn layer + DXVK-Mali compat layer. Valhall Mali r32p1+."
        to "完整 Mali DX12 组合：leegao ICD + BCn 图层 + DXVK-Mali 兼容层，要求 Valhall Mali r32p1 及以上。",
    "WinlatorMali's default wrapper. Byte-identical to Bannerlator's Wrapper (default) (same file); also identical to Ludashi's Wrapper (Steven)."
        to "WinlatorMali 默认 Wrapper，与 Bannerlator 的默认 Wrapper 为同一文件（字节一致），也与 Ludashi（Steven）的 Wrapper 相同。",
    "WinlatorMali's 'v2' wrapper — byte-identical to the Bruno original (Bannerlator Wrapper original / WinNative Wrapper)."
        to "WinlatorMali 的 v2 Wrapper，与 Bruno 原版（Bannerlator 原版 / WinNative）字节一致。",
    "WinlatorMali's leegao ICD build (differs from Bannerlator's and WinNative's leegao)."
        to "WinlatorMali 的 leegao ICD 构建（与 Bannerlator、WinNative 的 leegao 不同）。",
    "WinlatorMali's GameNative wrapper. Byte-identical to Bannerlator's GameNative (same file)."
        to "WinlatorMali 的 GameNative Wrapper，与 Bannerlator 的 GameNative 为同一文件（字节一致）。",
    "Performance/stability-tuned BCn layer, a fork of leegao's with extra ASTC-quality, staging-cache and queue-throttle controls. Import into the BCn layer slot."
        to "性能/稳定性调优的 BCn 图层，为 leegao 的分支，额外带 ASTC 质量、暂存缓存与队列限速控制，导入 BCn 图层槽位。",
    "WinNative's default wrapper — byte-identical to the Bruno original (same file)."
        to "WinNative 默认 Wrapper，与 Bruno 原版为同一文件（字节一致）。",
    "WinNative's own GameNative wrapper build (differs from Bannerlator's/WinlatorMali's GameNative)."
        to "WinNative 自有的 GameNative Wrapper 构建（与 Bannerlator / WinlatorMali 的不同）。",
    "WinNative's own leegao ICD build (differs from Bannerlator's and WinlatorMali's)."
        to "WinNative 自有的 leegao ICD 构建（与 Bannerlator、WinlatorMali 的不同）。",
    "The canonical GameNative wrapper — latest upstream build (20260725, GameNative #1771): leegao's latest changes on top of the #1762 older-device workaround base."
        to "标准 GameNative Wrapper——最新上游构建（20260725，GameNative #1771）：在 #1762 老设备兼容基底上叠加 leegao 最新改动。",
    "Winlator‑Ludashi's default wrapper (StevenMXZ, ludashi-3.1). Byte‑identical to the Bannerlator/WinlatorMali default Wrapper (same file)."
        to "Winlator-Ludashi 默认 Wrapper（StevenMXZ，ludashi-3.1），与 Bannerlator / WinlatorMali 默认 Wrapper 为同一文件（字节一致）。",
    "Pipetto-crypto's Winlator Bionic wrapper (dev branch) — the bionic upstream that Ludashi and other bionic forks descend from. Byte-identical to Bannerlator's legacy Wrapper."
        to "Pipetto-crypto 的 Winlator Bionic Wrapper（dev 分支）——Ludashi 及各 Bionic 分支的上游，与 Bannerlator 旧版 Wrapper 字节一致。"
)

/** wrappers.json 清单条目名称汉化；未命中保持原文 */
fun wrapperNameZh(name: String): String = wrapperNameZhMap[name] ?: name

/** wrappers.json 清单条目描述汉化；未命中保持原文 */
fun wrapperDescZh(description: String): String = wrapperDescZhMap[description] ?: description
