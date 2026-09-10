package com.starringbox.staragent.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.designsystem.theme.avatarGradient

/** 智能体头像：渐变底 + 字形，颜色按序号稳定取用。 */
@Composable
fun AgentAvatar(
    glyph: String,
    accentIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    cornerRadius: Dp = 15.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(avatarGradient(accentIndex))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = (size.value * 0.44f).sp, color = Color.White)
    }
}

/** 小标签。 */
@Composable
fun StarTag(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = StarPalette.Indigo,
    containerColor: Color = Color(0xFFE7E8FF),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 用图标做的小标签（带前导图标）。 */
@Composable
fun StarIconTag(
    glyph: StarIconGlyph,
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = StarPalette.TextMuted,
    containerColor: Color = StarPalette.SurfaceAlt,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StarIcon(icon = glyph, iconSize = 12.dp, tint = contentColor, strokeWidth = 2f)
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
    }
}

@Composable
fun StarDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(StarPalette.Divider),
    )
}

/** 分组标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = StarPalette.TextFaint,
    )
}

/** 带底色圆角的图标块，用于设置项前导。 */
@Composable
fun IconTile(
    glyph: StarIconGlyph,
    tint: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        StarIcon(icon = glyph, iconSize = size * 0.55f, tint = tint)
    }
}

/** 卡片容器。 */
@Composable
fun StarCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    containerColor: Color = StarPalette.Surface,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor),
    ) {
        content()
    }
}

/** 设置项：图标 + 标题/副标题 + 右侧自定义尾随内容。 */
@Composable
fun SettingsRow(
    glyph: StarIconGlyph,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = StarPalette.Indigo,
    containerColor: Color = Color(0xFFE7E8FF),
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(glyph = glyph, tint = tint, containerColor = containerColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = StarPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** 圆形图标按钮。 */
@Composable
fun CircleIconButton(
    glyph: StarIconGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    tint: Color = StarPalette.Text,
    containerColor: Color = Color.Transparent,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        StarIcon(icon = glyph, iconSize = size * 0.62f, tint = tint)
    }
}

/** 自定义顶栏。不用 Material 的 TopAppBar，避免实验性 API 并保持视觉一致。 */
@Composable
fun StarTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconButton(glyph = StarIconGlyph.Back, onClick = onBack)
            Spacer(Modifier.width(2.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = StarPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** 空状态提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = StarPalette.TextFaint,
        )
    }
}
