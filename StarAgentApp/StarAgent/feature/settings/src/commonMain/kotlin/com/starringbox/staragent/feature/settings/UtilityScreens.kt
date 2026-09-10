package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette

/**
 * IM 渠道绑定。
 *
 * 关键约定：绑定后智能体以**联系人**形式出现在 IM 里，与 App 内**共用同一条会话**。
 * 绝不能做成两套独立会话——那会让用户困惑，记忆也会割裂。
 */
@Composable
fun ChannelsScreen(navigator: Navigator) {
    val state = LocalAppState.current

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "IM 渠道",
            subtitle = state.activeChannelLabel(),
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Explainer("绑定后，智能体会以联系人的形式出现在你的聊天工具里。你在 App 里说过的话，在 IM 里能接着聊；在 IM 里交代的任务，结果也会回流到 App。同一条会话、同一份记忆。") }

            items(state.channels, key = { it.id }) { channel ->
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(channel.glyph, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = StarPalette.Text,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (channel.connected) "已绑定，可接收任务结果" else "未绑定",
                                style = MaterialTheme.typography.bodySmall,
                                color = StarPalette.TextMuted,
                            )
                        }
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
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 技能管理：能力可以持续扩展，不必等 App 版本更新。 */
@Composable
fun SkillsScreen(navigator: Navigator) {
    val state = LocalAppState.current

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "技能",
            subtitle = "${state.skills.count { it.installed }} / ${state.skills.size} 个已安装",
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Explainer("技能是可插拔的能力单元。装上之后，智能体在处理相关任务时会自动用上它——这也是它随时间变得更强的途径之一。") }

            items(state.skills, key = { it.id }) { skill ->
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .clickable { state.toggleSkill(skill.id) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(
                            glyph = StarIconGlyph.Bolt,
                            tint = if (skill.installed) StarPalette.Indigo else StarPalette.TextFaint,
                            containerColor = if (skill.installed) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = skill.glyph + "  " + skill.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = StarPalette.Text,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = skill.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = StarPalette.TextMuted,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (skill.installed) {
                            StarTag("已安装", contentColor = StarPalette.Success, containerColor = Color(0xFFE8F7F1))
                        } else {
                            StarTag("安装", contentColor = StarPalette.Indigo, containerColor = Color(0xFFE7E8FF))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun Explainer(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StarPalette.SurfaceAlt)
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StarPalette.TextMuted,
        )
    }
}
