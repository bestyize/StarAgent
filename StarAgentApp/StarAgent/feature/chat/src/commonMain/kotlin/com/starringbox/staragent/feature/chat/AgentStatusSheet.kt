package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.voice.VoiceSource

/**
 * 对话页顶部状态位的展开面板。
 *
 * 这里做了一个刻意的取舍：**响应模式在前，具体模型在后**。
 * 用户真正关心的是"这次要快还是要深"，而不是模型的名字；
 * 但"模型自由"又是产品的立身之本，所以两者都要给，只是排序不同。
 */
@Composable
internal fun AgentStatusSheet(
    agent: Agent,
    onDismiss: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    val state = LocalAppState.current
    val swallow = remember { MutableInteractionSource() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(interactionSource = swallow, indication = null) { onDismiss() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(StarPalette.Surface)
                .clickable(interactionSource = swallow, indication = null) { }
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 22.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(StarPalette.SurfaceSunken),
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel("响应模式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResponseMode.entries.forEach { mode ->
                    val selected = state.responseMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
                            .clickable { state.selectResponseMode(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color.White else StarPalette.TextMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.responseMode.hint,
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("模型")
            val usable = state.providers.filter { it.configured }
            if (usable.isEmpty()) {
                Text(
                    text = "还没有配置模型服务。去「我的 → 模型服务」填一个 API Key，这里就能切换模型了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextFaint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                usable.forEach { provider ->
                    provider.models.forEachIndexed { index, label ->
                        val key = state.modelKey(provider.id, index)
                        val selected = key == agent.modelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPickModel(key) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(
                                glyph = StarIconGlyph.Chip,
                                tint = if (selected) StarPalette.Indigo else StarPalette.TextFaint,
                                containerColor = if (selected) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                                size = 30.dp,
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StarPalette.Text,
                                )
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StarPalette.TextFaint,
                                )
                            }
                            if (selected) {
                                StarIcon(
                                    icon = StarIconGlyph.Check,
                                    iconSize = 17.dp,
                                    tint = StarPalette.Indigo,
                                    strokeWidth = 2.2f,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("渠道")
            state.channels.forEach { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(channel.glyph, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarPalette.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = channel.connected,
                        onCheckedChange = { state.toggleChannel(channel.id) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StarPalette.Indigo,
                            checkedBorderColor = StarPalette.Indigo,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("语音")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "朗读回复",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarPalette.Text,
                    )
                    Text(
                        text = "念出来会调用语音合成，按量计费",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextFaint,
                    )
                }
                Switch(
                    checked = state.speakReplies,
                    onCheckedChange = { state.setReadingReplies(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StarPalette.Indigo,
                        checkedBorderColor = StarPalette.Indigo,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            // 引擎选择。默认「自动」：优先系统能力，它不要 Key、不花钱。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val choices = listOf(null, VoiceSource.Device, VoiceSource.Model)
                choices.forEach { source ->
                    val selected = state.voiceSource == source
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
                            .clickable { state.selectVoiceSource(source) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = source?.label ?: "自动",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color.White else StarPalette.TextMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !state.canListen && !state.canSpeak ->
                        "现在还用不了语音：这台设备没有可用的识别与合成，也没配支持语音的服务商（智谱或 OpenAI）。"

                    state.voiceSource == null ->
                        "自动：优先用系统能力，它不要 Key 也不花钱；用不了时才走大模型。"

                    else -> state.voiceSource?.hint.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
            )

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(StarPalette.Indigo)
                    .clickable { onDismiss() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("完成", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}
