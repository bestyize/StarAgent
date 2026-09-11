package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.starringbox.staragent.core.designsystem.component.StarImage
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.media.MediaAsset

/**
 * 输入坞：文字 / 语音 / 拍照三合一。
 * 语音和拍照是**输入方式的补充**，不另开独立模式——避免用户在多个入口之间迷路。
 *
 * 待发送的图片显示在文字行上方，可以逐张移除。
 */
@Composable
internal fun InputDock(
    enabled: Boolean,
    busy: Boolean,
    attachments: List<MediaAsset>,
    canPickImage: Boolean,
    onPickImage: () -> Unit,
    onRemoveAttachment: (MediaAsset) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // 只有图片、没有文字也要能发
    val canSend = enabled && !busy && (text.isNotBlank() || attachments.isNotEmpty())

    Column(
        Modifier
            .fillMaxWidth()
            .background(StarPalette.Surface),
    ) {
        if (attachments.isNotEmpty()) {
            PendingAttachments(
                attachments = attachments,
                onRemove = onRemoveAttachment,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                if (canPickImage) {
                    Spacer(Modifier.width(8.dp))
                    StarIcon(
                        icon = StarIconGlyph.Camera,
                        iconSize = 18.dp,
                        tint = if (enabled && !busy) StarPalette.TextMuted else StarPalette.TextFaint,
                        modifier = Modifier.clickable(enabled = enabled && !busy) { onPickImage() },
                    )
                }
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
}

/** 待发送图片的缩略图条。 */
@Composable
private fun PendingAttachments(attachments: List<MediaAsset>, onRemove: (MediaAsset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { asset ->
            Box(Modifier.size(56.dp)) {
                StarImage(
                    bytes = asset.bytes,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onRemove(asset) },
                    contentAlignment = Alignment.Center,
                ) {
                    StarIcon(
                        icon = StarIconGlyph.Close,
                        iconSize = 10.dp,
                        tint = Color.White,
                        strokeWidth = 2.6f,
                    )
                }
            }
        }
    }
}
