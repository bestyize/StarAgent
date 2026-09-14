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
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarImage
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.media.MediaAsset
import com.starringbox.staragent.core.model.Agent

/**
 * 输入坞：文字 / 语音 / 拍照三合一。
 * 语音和拍照是**输入方式的补充**，不另开独立模式——避免用户在多个入口之间迷路。
 *
 * 待发送的图片显示在文字行上方，可以逐张移除。
 *
 * [mentions] 只有群聊会传：打「@」时在输入框上方列出成员供点选。单聊传空列表，行为不变。
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
    mentions: List<Agent> = emptyList(),
    /** 能不能语音输入。不能时麦克风入口整个不显示，而不是留个点了没反应的按钮。 */
    canVoice: Boolean = false,
    /** 正在听。界面据此把按钮换成停止、输入框换成提示。 */
    listening: Boolean = false,
    onVoiceToggle: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    // 只有图片、没有文字也要能发
    val canSend = enabled && !busy && (text.isNotBlank() || attachments.isNotEmpty())
    val openMention = remember(text, mentions) { openMention(text, mentions) }

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

        // 打「@」时把成员列在输入框上方。点一位就把 "@名字 " 补进文本。
        openMention?.let { mention ->
            MentionPicker(
                candidates = mention.candidates,
                onPick = { agent ->
                    text = text.substring(0, mention.start) + "@" + agent.name + " "
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (canVoice) {
                CircleIconButton(
                    glyph = if (listening) StarIconGlyph.Pause else StarIconGlyph.Mic,
                    onClick = onVoiceToggle,
                    size = 38.dp,
                    tint = if (listening) StarPalette.Danger else StarPalette.TextMuted,
                )
                Spacer(Modifier.width(6.dp))
            }

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
                    enabled = !busy && !listening,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text),
                    cursorBrush = SolidColor(StarPalette.Indigo),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = when {
                                        listening -> "正在听…再点一下结束"
                                        busy -> "正在回复…"
                                        else -> "说点什么…"
                                    },
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

/** 正在输入的 @ 查询：从哪个位置开始、以及匹配到的候选。 */
private data class OpenMention(val start: Int, val candidates: List<Agent>)

/**
 * 判断当前是不是正在打一个 @。
 *
 * 取**最后一个**还没跟空格的 @ 当查询词，不做光标跟踪——
 * 那需要把光标位置一路传进输入框，代价大，而实际输入里这个近似已经够准。
 */
private fun openMention(text: String, mentions: List<Agent>): OpenMention? {
    if (mentions.isEmpty()) return null
    val at = text.lastIndexOf('@')
    if (at < 0) return null

    val query = text.substring(at + 1)
    // 已经打完一个名字、后面跟了空格，就不再弹了
    if (query.any { it == ' ' || it == '\n' || it == '@' }) return null

    val candidates = mentions.filter { it.name.contains(query, ignoreCase = true) }
    return if (candidates.isEmpty()) null else OpenMention(at, candidates)
}

/** @ 的候选列表。**用点选代替手打**，名字重名或者被人截断时都不会认错。 */
@Composable
private fun MentionPicker(candidates: List<Agent>, onPick: (Agent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StarPalette.SurfaceAlt)
            .padding(vertical = 4.dp),
    ) {
        candidates.take(6).forEach { agent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(agent) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(
                    glyph = agent.glyph,
                    accentIndex = agent.accentIndex,
                    size = 24.dp,
                    cornerRadius = 8.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarPalette.Text,
                )
            }
        }
    }
}
