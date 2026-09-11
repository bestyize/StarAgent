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
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem

/**
 * 记忆管理。
 *
 * 记忆最容易做成"隐形功能"——用户不知道它存在，就不会感知到价值。
 * 所以这里既要能看见，也要能改、能删：可查看、可编辑、可删除是隐私底线，
 * 也是"高自由度"的应有之义。
 */
@Composable
fun MemoryScreen(navigator: Navigator) {
    val state = LocalAppState.current
    var draft by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.Preference) }
    var agentId by remember { mutableStateOf(state.agents.firstOrNull()?.id.orEmpty()) }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "记忆与成长",
            subtitle = "${state.memories.size} 条 · 每个智能体各记各的",
            onBack = { navigator.pop() },
        )
        Text("记住重要的你，看见持续成长的自己", style = MaterialTheme.typography.headlineSmall, color = StarPalette.Text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemoryCategory.entries.take(4).forEach { entry -> StarTag(entry.label, containerColor = StarPalette.SurfaceAlt) }
        }
        Spacer(Modifier.height(14.dp))
        StarCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                MemoryStat("偏好", state.memories.count { it.category == MemoryCategory.Preference })
                MemoryStat("习惯", state.memories.count { it.category == MemoryCategory.Habit })
                MemoryStat("事实", state.memories.count { it.category == MemoryCategory.Fact })
                MemoryStat("项目", state.memories.count { it.category == MemoryCategory.Project })
            }
        }
        Spacer(Modifier.height(14.dp))
        StarCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自我优化循环", style = MaterialTheme.typography.titleMedium, color = StarPalette.Text, modifier = Modifier.weight(1f))
                    Text("了解更多 〉", style = MaterialTheme.typography.labelMedium, color = StarPalette.Indigo)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GrowthMetric("记录生活", "沉淀记忆")
                    Text("→", color = StarPalette.Indigo, modifier = Modifier.padding(top = 8.dp))
                    GrowthMetric("反思洞察", "发现机会")
                    Text("→", color = StarPalette.Indigo, modifier = Modifier.padding(top = 8.dp))
                    GrowthMetric("获得成长", "遇见更好的自己")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        StarCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的成长概览", style = MaterialTheme.typography.titleMedium, color = StarPalette.Text, modifier = Modifier.weight(1f))
                    StarTag("持续成长", contentColor = StarPalette.Indigo)
                }
                Spacer(Modifier.height(10.dp))
                Text("记忆让每一次对话都更懂你", style = MaterialTheme.typography.bodyMedium, color = StarPalette.TextMuted)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GrowthMetric("记录数", state.memories.size.toString())
                    GrowthMetric("智能体", state.agents.size.toString())
                    GrowthMetric("连续使用", "7 天")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "手动记一条",
                            style = MaterialTheme.typography.titleSmall,
                            color = StarPalette.Text,
                        )
                        Spacer(Modifier.height(10.dp))

                        SectionLabel("属于哪个智能体")
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            state.agents.forEach { agent ->
                                ChoiceChip(
                                    label = agent.name,
                                    selected = agentId == agent.id,
                                    onClick = { agentId = agent.id },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionLabel("类型")
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            MemoryCategory.entries.forEach { entry ->
                                ChoiceChip(
                                    label = entry.label,
                                    selected = category == entry,
                                    onClick = { category = entry },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(StarPalette.SurfaceAlt)
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                        ) {
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text),
                                cursorBrush = SolidColor(StarPalette.Indigo),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty()) {
                                            Text(
                                                text = "例如：回答尽量简短，先说结论",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = StarPalette.TextFaint,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (draft.isNotBlank()) StarPalette.Indigo else StarPalette.SurfaceSunken
                                )
                                .clickable(enabled = draft.isNotBlank()) {
                                    state.addMemory(agentId, category, draft)
                                    draft = ""
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "添加",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (draft.isNotBlank()) Color.White else StarPalette.TextFaint,
                            )
                        }
                    }
                }
            }

            MemoryCategory.entries.forEach { entry ->
                val group = state.memories.filter { it.category == entry }
                if (group.isNotEmpty()) {
                    item(key = "label-${entry.name}") {
                        Spacer(Modifier.height(10.dp))
                        SectionLabel(entry.label)
                    }
                    items(group, key = { it.id }) { memory ->
                        MemoryRow(
                            memory = memory,
                            agentName = state.agent(memory.agentId)?.name,
                            onDelete = { state.removeMemory(memory.id) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MemoryStat(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = StarPalette.Indigo)
        Text(label, style = MaterialTheme.typography.labelSmall, color = StarPalette.TextMuted)
    }
}

@Composable
private fun GrowthMetric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = StarPalette.Text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = StarPalette.TextMuted)
    }
}

@Composable
private fun MemoryRow(memory: MemoryItem, agentName: String?, onDelete: () -> Unit) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarPalette.Text,
                )
                if (agentName != null) {
                    Spacer(Modifier.height(5.dp))
                    StarTag(agentName, contentColor = StarPalette.TextMuted, containerColor = StarPalette.SurfaceAlt)
                }
            }
            Spacer(Modifier.width(10.dp))
            StarIcon(
                icon = StarIconGlyph.Trash,
                iconSize = 17.dp,
                tint = StarPalette.TextFaint,
                modifier = Modifier.clickable { onDelete() },
            )
        }
    }
}

@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else StarPalette.TextMuted,
        )
    }
}
