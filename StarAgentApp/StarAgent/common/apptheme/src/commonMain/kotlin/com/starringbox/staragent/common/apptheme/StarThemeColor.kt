package com.starringbox.staragent.common.apptheme

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

/** 当前应用颜色。支持以 LocalColor.Bg1 的方式访问。 */
object LocalColor {
    val Bg1 = Color(0xFFFFFFFF)
    val Bg2 = Color(0xFFF6F7F8)
    val Bg3 = Color(0xFFF1F2F3)
    val Bg1_float = Color(0xFFFFFFFF)
    val Bg2_float = Color(0xFFF6F7F8)
    val Bg3_float = Color(0xFFF1F2F3)
    val Text_white = Color(0xFFFFFFFF)
    val Text1 = Color(0xFF18191C)
    val Text2 = Color(0xFF61666D)
    val Text3 = Color(0xFF9499A0)
    val Text4 = Color(0xFFC9CCD0)
    val Text_link = Color(0xFF00699D)
    val Text_notice = Color(0xFFFA9600)
    val Line_light = Color(0xFFF1F2F3)
    val Line_regular = Color(0xFFE3E5E7)
    val Line_bold = Color(0xFFC9CCD0)
    val Brand_blue = Color(0xFF00AEEC)
    val Brand_blue_thin = Color(0xFFDFF6FD)
    val Brand_pink = Color(0xFFFF6699)
    val Stress_red = Color(0xFFF85A54)
    val Success_green = Color(0xFF2AC864)
    val Operate_orange = Color(0xFFFF661A)
    val Pay_yellow = Color(0xFFFFB027)

    val Day = StarThemeColor(Bg1, Bg2, Bg3, Bg1_float, Bg2_float, Bg3_float, Text_white, Text1, Text2, Text3, Text4, Text_link, Text_notice, Line_light, Line_regular, Line_bold, Brand_blue, Brand_blue_thin, Brand_pink, Stress_red, Success_green, Operate_orange, Pay_yellow)
}
