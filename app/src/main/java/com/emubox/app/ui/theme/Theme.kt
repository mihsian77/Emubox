package com.emubox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.emubox.app.ColorTheme
import com.emubox.app.ThemeMode

/**
 * 主题强调色：只描述「主色/次色/三级色」，结构色（背景、表面、描边）一律由 FusionPalette 提供。
 *
 * 以前 BLUE/PURPLE/ORANGE/CN 各自是一整套独立 ColorScheme，只写了 3~5 个槽位，
 * 剩下几十个槽位全部落回 Material3 默认的番茄紫——和 fusionScheme 那次漏映射是同一个病。
 * 现在它们只是同一套骨架上的不同强调色。
 */
private class Accent(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color? = null,
    val tertiaryContainer: Color? = null
)

private val BlueAccentLight = Accent(Color(0xFF305DA8), Color(0xFF565F71), Color(0xFF705574))
private val BlueAccentDark = Accent(Color(0xFFA9C7FF), Color(0xFFBEC7DC), Color(0xFFDEBCDF))

private val PurpleAccentLight = Accent(Color(0xFF6750A4), Color(0xFF625B71), Color(0xFF7D5260))
private val PurpleAccentDark = Accent(Color(0xFFD0BCFF), Color(0xFFCCC2DC), Color(0xFFEFB8C8))

private val OrangeAccentLight = Accent(Color(0xFF8A5100), Color(0xFF735A42), Color(0xFF596339))
private val OrangeAccentDark = Accent(Color(0xFFFFB870), Color(0xFFE3C2A5), Color(0xFFC1CC99))

// 国内版：深蓝主色 + 朱红强调
private val CnAccentLight = Accent(
    Color(0xFF1A5FB4), Color(0xFF545F70), Color(0xFFB3261E),
    primaryContainer = Color(0xFFD6E3FF), tertiaryContainer = Color(0xFFFFDAD6)
)
private val CnAccentDark = Accent(
    Color(0xFFA9C7FF), Color(0xFFBCC7DB), Color(0xFFFFB4AB),
    primaryContainer = Color(0xFF004899), tertiaryContainer = Color(0xFF8C1D18)
)

private val EmuBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * 由 Fusion 色板构造 Material3 ColorScheme。
 * 单一真值源：改这里，全 App 走 colorScheme 的取色一起变，不再和 Fusion.* 打架。
 */
private fun fusionScheme(dark: Boolean, accent: Accent? = null): ColorScheme {
    val p = if (dark) FusionPalette.Dark else FusionPalette.Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent?.primary ?: p.primary,
        onPrimary = if (dark) Color(0xFF06231F) else Color.White,
        primaryContainer = accent?.primaryContainer ?: p.surfaceAlt,
        onPrimaryContainer = p.onSurface,
        secondary = accent?.secondary ?: p.secondary,
        onSecondary = if (dark) Color(0xFF1A1030) else Color.White,
        secondaryContainer = p.surfaceAlt,
        onSecondaryContainer = p.onSurface,
        // tertiary 一族原来漏了映射 → 沿用 darkColorScheme() 的番茄紫，
        // 这就是「指南页图标盒是粉的、组件页卡片发紫」的来源。
        tertiary = accent?.tertiary ?: p.secondary,
        onTertiary = if (dark) Color(0xFF1A1030) else Color.White,
        tertiaryContainer = accent?.tertiaryContainer ?: p.surfaceAlt,
        onTertiaryContainer = p.onSurface,
        background = p.background,
        onBackground = p.onSurface,
        surface = p.surface,
        onSurface = p.onSurface,
        surfaceVariant = p.surfaceAlt,
        onSurfaceVariant = p.onSurfaceDim,
        // surfaceContainer 系列同样漏映射，卡片折叠态会突然变成 Material 的紫灰底
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceAlt,
        surfaceContainerHighest = p.surfaceAlt,
        surfaceDim = p.background,
        surfaceBright = p.surface,
        inverseSurface = p.onSurface,
        inverseOnSurface = p.background,
        outline = p.border,
        outlineVariant = p.borderAlt
    )
}

@Composable
fun EmuBoxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorTheme: ColorTheme = ColorTheme.DYNAMIC,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    // Fusion 品牌色板跟随主题（亮/暗双色板），驱动全 App 的 Fusion.* 引用
    Fusion.applyTheme(darkTheme)

    val baseScheme = when {
        colorTheme == ColorTheme.BLUE -> fusionScheme(darkTheme, if (darkTheme) BlueAccentDark else BlueAccentLight)
        colorTheme == ColorTheme.PURPLE -> fusionScheme(darkTheme, if (darkTheme) PurpleAccentDark else PurpleAccentLight)
        colorTheme == ColorTheme.ORANGE -> fusionScheme(darkTheme, if (darkTheme) OrangeAccentDark else OrangeAccentLight)
        colorTheme == ColorTheme.CN -> fusionScheme(darkTheme, if (darkTheme) CnAccentDark else CnAccentLight)
        // 模板 A「璃光暗夜」/ 浅色对应：默认主题直接由 Fusion 色板构造，
        // 让全 App 走 MaterialTheme.colorScheme 的 555 处取色自动收敛到同一套值，
        // 不再与 Fusion.* 手写引用形成两套色系。
        else -> fusionScheme(darkTheme)
    }

    val colorScheme = if (themeMode == ThemeMode.AMOLED) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF151515)
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = EmuBoxShapes,
        content = content
    )
}
