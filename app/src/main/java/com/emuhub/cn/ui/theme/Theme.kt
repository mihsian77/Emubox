package com.emuhub.cn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.emuhub.cn.ColorTheme
import com.emuhub.cn.ThemeMode

private val EmuHubLight = lightColorScheme(
    primary = Color(0xFF006C4C),
    secondary = Color(0xFF4D6358),
    tertiary = Color(0xFF3E6374)
)
private val EmuHubDark = darkColorScheme(
    primary = Color(0xFF56DBA5),
    secondary = Color(0xFFB4CCBF),
    tertiary = Color(0xFFA5CDDF)
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

private val EmuHubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun EmuHubTheme(
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

    val context = LocalContext.current
    val baseScheme = when {
        colorTheme == ColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorTheme == ColorTheme.BLUE -> if (darkTheme) BlueDark else BlueLight
        colorTheme == ColorTheme.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
        colorTheme == ColorTheme.ORANGE -> if (darkTheme) OrangeDark else OrangeLight
        colorTheme == ColorTheme.CN -> if (darkTheme) CnDark else CnLight
        else -> if (darkTheme) EmuHubDark else EmuHubLight
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
        shapes = EmuHubShapes,
        content = content
    )
}
