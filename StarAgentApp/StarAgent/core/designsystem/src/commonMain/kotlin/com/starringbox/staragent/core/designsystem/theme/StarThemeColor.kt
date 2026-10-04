package com.starringbox.staragent.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** StarAgent 的语义化颜色契约，命名与设计稿保持稳定，业务代码不直接依赖色值。 */
@Immutable
data class StarThemeColor(
    val Bg1: Color,
    val Bg2: Color,
    val Bg3: Color,
    val Bg1_float: Color,
    val Bg2_float: Color,
    val Bg3_float: Color,
    val Text_white: Color,
    val Text1: Color,
    val Text2: Color,
    val Text3: Color,
    val Text4: Color,
    val Text_link: Color,
    val Text_notice: Color,
    val Line_light: Color,
    val Line_regular: Color,
    val Line_bold: Color,
    val Brand_blue: Color,
    val Brand_blue_thin: Color,
    val Brand_pink: Color,
    val Stress_red: Color,
    val Success_green: Color,
    val Operate_orange: Color,
    val Pay_yellow: Color,
)

/**
 * 当前应用颜色。支持以 LocalColor.Bg1 的方式访问。
 *
 * 色值取自设计稿的四个锚点：`#3B82F6` 主品牌色、`#8B5CF6` 辅助色、
 * `#EAF2FF` 背景色、`#F8FAFF` 卡片色；其余令牌是在这四个锚点上推导出来的。
 */
object LocalColor {
    // 背景层级：Bg2 是页面底色，Bg1 是卡片，层层提亮
    val Bg1 = Color(0xFFF8FAFF)
    val Bg2 = Color(0xFFEAF2FF)
    val Bg3 = Color(0xFFDCE8FF)
    val Bg1_float = Color(0xFFFFFFFF)
    val Bg2_float = Color(0xFFF8FAFF)
    val Bg3_float = Color(0xFFEAF2FF)

    val Text_white = Color(0xFFFFFFFF)
    val Text1 = Color(0xFF1A1D26)
    val Text2 = Color(0xFF5A6072)
    val Text3 = Color(0xFF939AAB)
    val Text4 = Color(0xFFC3C9D6)
    val Text_link = Color(0xFF3B82F6)
    val Text_notice = Color(0xFFF59E0B)

    val Line_light = Color(0xFFE8EEF9)
    val Line_regular = Color(0xFFDAE3F3)
    val Line_bold = Color(0xFFC3C9D6)

    val Brand_blue = Color(0xFF3B82F6)
    val Brand_blue_thin = Color(0xFFE3EDFE)
    val Brand_pink = Color(0xFF8B5CF6)
    val Stress_red = Color(0xFFEF4444)
    val Success_green = Color(0xFF22C55E)
    val Operate_orange = Color(0xFFF97316)
    val Pay_yellow = Color(0xFFF59E0B)

    val Day = StarThemeColor(Bg1, Bg2, Bg3, Bg1_float, Bg2_float, Bg3_float, Text_white, Text1, Text2, Text3, Text4, Text_link, Text_notice, Line_light, Line_regular, Line_bold, Brand_blue, Brand_blue_thin, Brand_pink, Stress_red, Success_green, Operate_orange, Pay_yellow)
}
