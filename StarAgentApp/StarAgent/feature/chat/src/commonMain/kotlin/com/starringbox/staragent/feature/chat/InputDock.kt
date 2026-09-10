package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette

/**
 * 输入坞：文字 / 语音 / 拍照三合一。
 * 语音和拍照是**输入方式的补充**，不另开独立模式——避免用户在多个入口之间迷路。
 *
 * 正在生成时右侧按钮变成「停止」：模型可能会说很久，用户必须能打断。
 */
@Composable
internal fun InputDock(
    enabled: Boolean,
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && !busy && text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StarPalette.Surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CircleIconButton(
            glyph = StarIconGlyph.Mic,
            onClick = { },
            size = 38.dp,
            tint = StarPalette.TextMuted,
        )
        Spacer(Modifier.width(6.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(StarPalette.SurfaceAlt)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text),
                cursorBrush = SolidColor(StarPalette.Indigo),
                maxLines = 4,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = if (busy) "正在回复…" else "说点什么…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StarPalette.TextFaint,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            StarIcon(
                icon = StarIconGlyph.Camera,
                iconSize = 18.dp,
                tint = StarPalette.TextFaint,
                modifier = Modifier.clickable { },
            )
        }

        Spacer(Modifier.width(6.dp))

        if (busy) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(StarPalette.SurfaceSunken)
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                StarIcon(StarIconGlyph.Pause, iconSize = 16.dp, tint = StarPalette.Text)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (canSend) StarPalette.Indigo else StarPalette.SurfaceAlt)
                    .clickable(enabled = canSend) {
                        val payload = text
                        text = ""
                        onSend(payload)
                    },
                contentAlignment = Alignment.Center,
            ) {
                StarIcon(
                    icon = if (canSend) StarIconGlyph.Send else StarIconGlyph.Plus,
                    iconSize = 18.dp,
                    tint = if (canSend) Color.White else StarPalette.TextMuted,
                )
            }
        }
    }
}
