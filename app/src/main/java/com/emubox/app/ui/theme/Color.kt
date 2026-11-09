package com.emubox.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** 融合风（方案 C）品牌色板：深色底 + 蓝紫渐变 + 卡片体系。亮/暗双色板，跟随主题切换。 */
data class FusionPalette(
    val background: Color,        // 页面底色
    val surface: Color,           // 卡片/列表底
    val surfaceAlt: Color,        // 次级卡/搜索条底
    val border: Color,            // 卡片描边
    val borderAlt: Color,         // 轻描边
    val primary: Color,           // 强调蓝（标题渐变/下载/激活）
    val secondary: Color,         // 渐变辅助紫
    val gradientStart: Color,     // 渐变起（分类选中/按钮）
    val gradientEnd: Color,       // 渐变止
    val onSurface: Color,         // 主正文
    val onSurfaceDim: Color,      // 次级正文
    val onSurfaceFaint: Color,    // 弱正文
    val onSurfaceFainter: Color,  // 更弱正文
    val amber: Color,             // 热度/排行高亮
    val amberBg: Color,           // 热度标签底
    val heroStart: Color,         // hero 大卡渐变起
    val heroEnd: Color,           // hero 大卡渐变止
) {
    companion object {
        // 模板 A「璃光暗夜」：近黑紫底 + 玻璃卡片高光描边 + 青→紫渐变强调
        val Dark = FusionPalette(
            background = Color(0xFF0B0A13),
            surface = Color(0xFF1A1828),
            surfaceAlt = Color(0xFF232036),
            border = Color(0xFF373252),
            borderAlt = Color(0xFF413B60),
            primary = Color(0xFF4FD9C9),
            secondary = Color(0xFF9B7CFF),
            gradientStart = Color(0xFF3FD9C4),
            gradientEnd = Color(0xFF8B5CF6),
            onSurface = Color(0xFFF4F2FB),
            // 次级/弱文字三级台阶：都按「深色底上 ≥4.5:1」反算，
            // 原来的 #756F95 只有 3.6:1，深色模式下读起来发虚
            onSurfaceDim = Color(0xFFC2BEDC),
            onSurfaceFaint = Color(0xFFA9A4C6),
            onSurfaceFainter = Color(0xFF918BB2),
            amber = Color(0xFFFFC24D),
            amberBg = Color(0xFF3A2E1E),
            heroStart = Color(0xFF12363F),
            heroEnd = Color(0xFF2A1B4D),
        )
        val Light = FusionPalette(
            // 背景压到 #E9ECF3：纯白卡片与背景拉开层次，不再整屏发白
            background = Color(0xFFE9ECF3),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF3F5FA),
            border = Color(0xFFD3D9E6),
            borderAlt = Color(0xFFDFE4EE),
            primary = Color(0xFF3B6EF5),
            secondary = Color(0xFF8A63F0),
            gradientStart = Color(0xFF3B6EF5),
            gradientEnd = Color(0xFF7A5CF5),
            onSurface = Color(0xFF15181F),
            onSurfaceDim = Color(0xFF474D5E),
            onSurfaceFaint = Color(0xFF5B6173),
            onSurfaceFainter = Color(0xFF6D7385),
            amber = Color(0xFFB25F00),
            amberBg = Color(0xFFFFE8CC),
            heroStart = Color(0xFFDDE6FF),
            heroEnd = Color(0xFFE4DBFF),
        )
    }
}

/** 融合风品牌色板入口：读取当前主题色板（亮/暗由 EmuBoxTheme 切换）。 */
object Fusion {
    var palette: FusionPalette by mutableStateOf(FusionPalette.Dark)
        private set

    fun applyTheme(dark: Boolean) {
        palette = if (dark) FusionPalette.Dark else FusionPalette.Light
    }

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceAlt get() = palette.surfaceAlt
    val Border get() = palette.border
    val BorderAlt get() = palette.borderAlt
    val Primary get() = palette.primary
    val Secondary get() = palette.secondary
    val GradientStart get() = palette.gradientStart
    val GradientEnd get() = palette.gradientEnd
    val OnSurface get() = palette.onSurface
    val OnSurfaceDim get() = palette.onSurfaceDim
    val OnSurfaceFaint get() = palette.onSurfaceFaint
    val OnSurfaceFainter get() = palette.onSurfaceFainter
    val Amber get() = palette.amber
    val AmberBg get() = palette.amberBg
    val HeroStart get() = palette.heroStart
    val HeroEnd get() = palette.heroEnd
}
