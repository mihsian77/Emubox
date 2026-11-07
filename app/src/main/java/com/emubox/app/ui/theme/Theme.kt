package com.emubox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.emubox.app.ColorTheme
import com.emubox.app.ThemeMode

private val EmuBoxLight = lightColorScheme(
    primary = Color(0xFF006C4C),
    secondary = Color(0xFF4D6358),
    tertiary = Color(0xFF3E6374)
)
private val EmuBoxDark = darkColorScheme(
    primary = Color(0xFF4FD9C9),
    secondary = Color(0xFF9B7CFF),
    tertiary = Color(0xFFC9A9FF),
    background = Color(0xFF0B0A13),
    surface = Color(0xFF161423),
    surfaceVariant = Color(0xFF1C1A2C),
    onBackground = Color(0xFFF0EEF8),
    onSurface = Color(0xFFF0EEF8),
    onSurfaceVariant = Color(0xFFABA6C9),
    outline = Color(0xFF332F4E)
)

private val BlueLight = lightColorScheme(
    primary = Color(0xFF305DA8),
    secondary = Color(0xFF565F71),
    tertiary = Color(0xFF705574)
)
private val BlueDark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    secondary = Color(0xFFBEC7DC),
    tertiary = Color(0xFFDEBCDF)
)

private val PurpleLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)
private val PurpleDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

private val OrangeLight = lightColorScheme(
    primary = Color(0xFF8A5100),
    secondary = Color(0xFF735A42),
    tertiary = Color(0xFF596339)
)
private val OrangeDark = darkColorScheme(
    primary = Color(0xFFFFB870),
    secondary = Color(0xFFE3C2A5),
    tertiary = Color(0xFFC1CC99)
)

// 国内版专属配色：深蓝主色 + 朱红强调
private val CnLight = lightColorScheme(
    primary = Color(0xFF1A5FB4),
    secondary = Color(0xFF545F70),
    tertiary = Color(0xFFB3261E),
    primaryContainer = Color(0xFFD6E3FF),
    tertiaryContainer = Color(0xFFFFDAD6)
)
private val CnDark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    secondary = Color(0xFFBCC7DB),
    tertiary = Color(0xFFFFB4AB),
    primaryContainer = Color(0xFF004899),
    tertiaryContainer = Color(0xFF8C1D18)
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
private fun fusionScheme(dark: Boolean): ColorScheme {
    val p = if (dark) FusionPalette.Dark else FusionPalette.Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary,
        onPrimary = if (dark) Color(0xFF06231F) else Color.White,
        primaryContainer = p.surfaceAlt,
        onPrimaryContainer = p.onSurface,
        secondary = p.secondary,
        onSecondary = if (dark) Color(0xFF1A1030) else Color.White,
        secondaryContainer = p.surfaceAlt,
        onSecondaryContainer = p.onSurface,
        // tertiary 一族原来漏了映射 → 沿用 darkColorScheme() 的番茄紫，
        // 这就是「指南页图标盒是粉的、组件页卡片发紫」的来源。
        tertiary = p.secondary,
        onTertiary = if (dark) Color(0xFF1A1030) else Color.White,
        tertiaryContainer = p.surfaceAlt,
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
        colorTheme == ColorTheme.BLUE -> if (darkTheme) BlueDark else BlueLight
        colorTheme == ColorTheme.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
        colorTheme == ColorTheme.ORANGE -> if (darkTheme) OrangeDark else OrangeLight
        colorTheme == ColorTheme.CN -> if (darkTheme) CnDark else CnLight
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
