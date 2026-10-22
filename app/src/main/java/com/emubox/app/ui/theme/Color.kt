package com.emubox.app.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** 融合风（方案 C）品牌色板：深色底 + 蓝紫渐变 + 卡片体系 */
object Fusion {
    val Background = Color(0xFF0E1016)      // 页面底色
    val Surface = Color(0xFF161924)         // 卡片/列表底
    val SurfaceAlt = Color(0xFF181B26)      // 次级卡/搜索条底
    val Border = Color(0xFF212536)          // 卡片描边
    val BorderAlt = Color(0xFF232738)       // 轻描边
    val Primary = Color(0xFF7EA2FF)         // 强调蓝（标题渐变/下载/激活）
    val Secondary = Color(0xFFA98BFF)       // 渐变辅助紫
    val GradientStart = Color(0xFF3B6EF5)   // 渐变起（分类选中/按钮）
    val GradientEnd = Color(0xFF7A5CF5)     // 渐变止
    val OnSurface = Color(0xFFECEEF4)       // 主正文
    val OnSurfaceDim = Color(0xFFA9ADC4)    // 次级正文
    val OnSurfaceFaint = Color(0xFF8A90AD)  // 弱正文
    val OnSurfaceFainter = Color(0xFF7B80A0)// 更弱正文
    val Amber = Color(0xFFFFB84D)           // 热度/排行高亮
    val AmberBg = Color(0xFF3A2F1E)         // 热度标签底
    val HeroStart = Color(0xFF22305C)       // hero 大卡渐变起
    val HeroEnd = Color(0xFF2C2050)         // hero 大卡渐变止
}