package com.starringbox.staragent.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 星环的视觉基色。取名自"星环"意象：深靛蓝作主色，青色作副色。 */
object StarPalette {
    val Indigo = Color(0xFF4F46E5)
    val IndigoDeep = Color(0xFF312E81)
    val Violet = Color(0xFF7C3AED)
    val Cyan = Color(0xFF06B6D4)

    val Bg = Color(0xFFF6F7FB)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEFF1F8)
    val SurfaceSunken = Color(0xFFE7E9F3)

    val Text = Color(0xFF15161F)
    val TextMuted = Color(0xFF6B6C82)
    val TextFaint = Color(0xFF9A9BB0)

    val Divider = Color(0xFFE6E8F2)

    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
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

private val StarTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StarAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarColorScheme,
        typography = StarTypography,
        content = content,
    )
}
