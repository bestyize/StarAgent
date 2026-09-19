package com.starringbox.staragent.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette

/**
 * 通用文本输入。样式统一放在设计系统里，
 * 免得各功能模块各写一套 BasicTextField。
 *
 * [masked] 打开后是密码框的写法：默认只显示圆点，右侧有个眼睛图标，
 * 点一下才明文。**明文状态是这一处的局部状态，不往外传**——
 * "用户刚才点过眼睛"没有任何持久化的意义，下一个页面进来就该重新藏好。
 */
@Composable
fun StarTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    containerColor: Color = StarPalette.SurfaceAlt,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    masked: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val hidden = masked && !revealed

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                // 键盘别把 Key 记进输入法的词库和学习记录里
                keyboardOptions = if (masked) {
                    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                } else {
                    KeyboardOptions.Default
                },
                // 明文时是 Identity，藏起来时换成圆点。**不用 PasswordVisualTransformation**：
                // 它固定显示 '•'，而这里想跟 placeholder 的样式保持一致，
                // 也想让"藏起来"这件事只由一个布尔值决定，好读也好测。
                visualTransformation = if (hidden) {
                    VisualTransformation { annotated ->
                        TransformedText(
                            text = AnnotatedString("•".repeat(annotated.text.length)),
                            offsetMapping = OffsetMapping.Identity,
                        )
                    }
                } else {
                    VisualTransformation.None
                },
                textStyle = textStyle.copy(color = StarPalette.Text),
                cursorBrush = SolidColor(StarPalette.Indigo),
                modifier = Modifier
                    .weight(1f)
                    .then(if (minHeight > 0.dp) Modifier.height(minHeight) else Modifier),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = StarPalette.TextFaint,
                            )
                        }
                        inner()
                    }
                },
            )

            if (masked) {
                Spacer(Modifier.width(8.dp))
                StarIcon(
                    icon = if (revealed) StarIconGlyph.EyeOff else StarIconGlyph.Eye,
                    iconSize = 19.dp,
                    tint = StarPalette.TextFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { revealed = !revealed }
                        .padding(2.dp),
                )
            }
        }
    }
}
