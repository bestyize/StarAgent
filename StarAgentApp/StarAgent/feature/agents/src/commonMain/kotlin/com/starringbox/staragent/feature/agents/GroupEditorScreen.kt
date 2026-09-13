package com.starringbox.staragent.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup

/**
 * 创建 / 编辑群组：起个名字、挑几位成员、定讨论几轮。
 *
 * 轮数直接决定成本（成员数 × 轮数 次模型调用），所以选完就立刻把账算给用户看。
 */
@Composable
fun GroupEditorScreen(navigator: Navigator, groupId: String?) {
    val state = LocalAppState.current
    val editing = groupId?.let { state.group(it) }

    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var rounds by remember { mutableStateOf(editing?.rounds ?: 1) }
    var memberIds by remember { mutableStateOf(editing?.memberIds.orEmpty()) }

    val selectedMembers = state.agents.filter { it.id in memberIds }
    val calls = selectedMembers.size * rounds

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = if (editing == null) "创建群组" else "编辑群组",
            onBack = { navigator.pop() },
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(
                    glyph = "群",
                    accentIndex = editing?.accentIndex ?: state.groups.size,
                    size = 52.dp,
                    cornerRadius = 17.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = name.ifBlank { "未命名群组" },
                        style = MaterialTheme.typography.titleMedium,
                        color = StarPalette.Text,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (selectedMembers.isEmpty()) {
                            "还没有选成员"
                        } else {
                            "${selectedMembers.size} 位成员 · 共 $rounds 轮"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                    )
                }
            }

            SectionLabel("群名称")
            StarTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "给这个群起个名字",
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("选成员")
            if (state.agents.isEmpty()) {
                EmptyHint("先去首页创建几个智能体，才能组群。")
            } else {
                // 至少两位才谈得上"互相讨论"
                Text(
                    text = "至少选 2 位。发言按下面的顺序依次进行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextMuted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                state.agents.forEach { agent ->
                    MemberRow(
                        agent = agent,
                        selected = agent.id in memberIds,
                        onToggle = {
                            memberIds = if (agent.id in memberIds) {
                                memberIds - agent.id
                            } else {
                                memberIds + agent.id
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("讨论轮数")
            Row {
                listOf(1, 2, 3).forEach { value ->
                    RoundChip(value = value, selected = value == rounds, onClick = { rounds = value })
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "每轮每位成员发言一次。你发一条消息，会触发 " +
                    "${selectedMembers.size} × $rounds = $calls 次模型调用。",
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
            )

            Spacer(Modifier.height(28.dp))
        }

        Button(
            onClick = {
                val existing = editing
                state.saveGroup(
                    if (existing == null) {
                        AgentGroup(
                            id = state.newGroupId(),
                            name = name.trim(),
                            memberIds = memberIds,
                            rounds = rounds,
                            accentIndex = state.groups.size,
                        )
                    } else {
                        // 改群只动这些字段，头像索引保持不变
                        existing.copy(name = name.trim(), memberIds = memberIds, rounds = rounds)
                    }
                )
                navigator.pop()
            },
            // 一个人不成群：讨论至少需要两方
            enabled = name.isNotBlank() && selectedMembers.size >= 2,
            colors = ButtonDefaults.buttonColors(containerColor = StarPalette.Indigo),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (editing == null) "创建" else "保存",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun MemberRow(agent: Agent, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(StarPalette.Surface)
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(
            glyph = agent.glyph,
            accentIndex = agent.accentIndex,
            size = 38.dp,
            cornerRadius = 12.dp,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = agent.name,
                style = MaterialTheme.typography.titleSmall,
                color = StarPalette.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = agent.persona.ifBlank { "还没有人设" },
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                StarIcon(StarIconGlyph.Check, iconSize = 14.dp, tint = Color.White)
            }
        }
    }
}

@Composable
private fun RoundChip(value: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$value 轮",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else StarPalette.TextMuted,
        )
    }
}
