package com.starringbox.staragent.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.starringbox.staragent.core.designsystem.theme.LocalColor

/** 星环的视觉基色。取名自"星环"意象：深靛蓝作主色，青色作副色。 */
object StarPalette {
    val Indigo = LocalColor.Brand_blue
    val IndigoDeep = LocalColor.Text_link
    val Violet = LocalColor.Brand_pink
    val Cyan = LocalColor.Brand_blue

    val Bg = LocalColor.Bg2
    val Surface = LocalColor.Bg1
    val SurfaceAlt = LocalColor.Bg3
    val SurfaceSunken = LocalColor.Line_light
    val SurfaceFloat = LocalColor.Bg2_float
    val SurfaceFloatElevated = LocalColor.Bg1_float

    val Text = LocalColor.Text1
    val TextMuted = LocalColor.Text2
    val TextFaint = LocalColor.Text3

    val Divider = LocalColor.Line_regular

    val Success = LocalColor.Success_green
    val Warning = LocalColor.Pay_yellow
    val Danger = LocalColor.Stress_red
}

private val AvatarGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
    listOf(Color(0xFF0EA5E9), Color(0xFF3B82F6)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF10B981), Color(0xFF06B6D4)),
    listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)),
    listOf(Color(0xFF14B8A6), Color(0xFF6366F1)),
)

/** 按序号取头像渐变，保证同一智能体的颜色稳定。 */
fun avatarGradient(index: Int): List<Color> {
    val n = AvatarGradients.size
    return AvatarGradients[((index % n) + n) % n]
}

private val StarColorScheme = lightColorScheme(
    primary = StarPalette.Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E8FF),
    onPrimaryContainer = StarPalette.IndigoDeep,
    secondary = StarPalette.Cyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF6FB),
    onSecondaryContainer = Color(0xFF084B58),
    tertiary = StarPalette.Violet,
    onTertiary = Color.White,
    background = StarPalette.Bg,
    onBackground = StarPalette.Text,
    surface = StarPalette.Surface,
    onSurface = StarPalette.Text,
    surfaceVariant = StarPalette.SurfaceAlt,
    onSurfaceVariant = StarPalette.TextMuted,
    outline = StarPalette.Divider,
    outlineVariant = StarPalette.Divider,
    error = StarPalette.Danger,
    onError = Color.White,
)

// 尺寸对照设计稿的字体层级：大标题 28 / 标题 20 / 正文 16 / 次要文本 14 / 辅助说明 12
private val StarTypography = Typography(
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

@Composable
fun StarAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarColorScheme,
        typography = StarTypography,
        content = content,
    )
}
