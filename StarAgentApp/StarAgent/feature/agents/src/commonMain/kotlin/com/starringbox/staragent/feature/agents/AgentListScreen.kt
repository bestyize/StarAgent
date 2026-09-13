package com.starringbox.staragent.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.component.StarActionSheet
import com.starringbox.staragent.core.designsystem.component.StarSheetAction
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup

/**
 * 首页：智能体联系人列表。
 *
 * 每一行是一个智能体，同时承载三类动态——与它的对话、它托管的任务结果、
 * 它的主动消息。三者共用同一条消息流，这正是"智能体即联系人"的心智：
 * 你看到的是"这个联系人最近发生了什么"。
 */
@Composable
fun AgentListScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val query = state.searchQuery
    val agents = state.agents.filter { agent ->
        query.isBlank() || agent.name.contains(query, ignoreCase = true) || agent.persona.contains(query, ignoreCase = true) || state.lastMessage(agent.id)?.let { state.previewOf(it).contains(query, ignoreCase = true) } == true
    }
    val groups = state.groups.filter { group ->
        query.isBlank() || group.name.contains(query, ignoreCase = true) || state.lastGroupMessage(group.id)?.let { state.previewOf(it).contains(query, ignoreCase = true) } == true
    }
    val managing = remember { mutableStateOf<Agent?>(null) }
    val managingGroup = remember { mutableStateOf<AgentGroup?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFE7EFFF), StarPalette.Bg)))) {
            Box(Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 12.dp).width(110.dp).height(110.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFBFCBFF), Color.Transparent))))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("星环", style = MaterialTheme.typography.headlineSmall, color = StarPalette.Text)
                        Text("${state.agents.size} 个智能体 · ${state.groups.size} 个群组", style = MaterialTheme.typography.bodyLarge, color = StarPalette.TextMuted)
                    }
                    CircleIconButton(glyph = StarIconGlyph.Plus, onClick = { navigator.push(Route.AgentEditor(null)) }, size = 44.dp, tint = Color.White, containerColor = StarPalette.Indigo)
                }
                Spacer(Modifier.height(12.dp))
                Text("智能体，让一切更简单", style = MaterialTheme.typography.headlineSmall, color = StarPalette.Text)
                Text("创建 · 连接 · 扩展 · 与 AI 一起成长", style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
            }
        }

        SearchField(
            value = query,
            onValueChange = { state.searchQuery = it },
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (agents.isEmpty() && groups.isEmpty()) {
            EmptyHint(
                text = if (query.isBlank()) "还没有智能体，点右上角创建一个" else "没有匹配的智能体",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 10.dp, top = 0.dp, end = 10.dp, bottom = 16.dp)) {
                val pinned = agents.filter { it.pinned }
                val recent = agents.filterNot { it.pinned }
                if (pinned.isNotEmpty()) item { SectionTitle("置顶会话", "最常用的智能体") }
                items(pinned, key = { "pinned-${it.id}" }) { agent ->
                    AgentRow(
                        state = state,
                        agent = agent,
                        onClick = { navigator.push(Route.Chat(agent.id)) },
                        onLongClick = { managing.value = agent },
                    )
                }
                // 群组和智能体并列——产品里两者都是"联系人"，只是一个是一对一、一个是多人
                item { SectionTitle("群组", "多个智能体一起讨论") }
                items(groups, key = { "group-${it.id}" }) { group ->
                    GroupRow(
                        state = state,
                        group = group,
                        onClick = { navigator.push(Route.GroupChat(group.id)) },
                        onLongClick = { managingGroup.value = group },
                    )
                }
                item {
                    CreateGroupRow(onClick = { navigator.push(Route.GroupEditor(null)) })
                }
                if (recent.isNotEmpty()) item { SectionTitle("最近会话", "与你的智能体保持连接") }
                items(recent, key = { "recent-${it.id}" }) { agent ->
                    AgentRow(state, agent, { navigator.push(Route.Chat(agent.id)) }, { managing.value = agent })
                }
                item {
                    CreateAgentRow(onClick = { navigator.push(Route.AgentEditor(null)) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    managing.value?.let { agent ->
        StarActionSheet( actions = listOf(StarSheetAction(if (agent.pinned) "取消置顶" else "置顶会话"), StarSheetAction("删除会话", true)), onAction = { index -> if (index == 0) state.setAgentPinned(agent.id, !agent.pinned) else state.deleteAgent(agent.id); managing.value = null }, onDismiss = { managing.value = null })
    }
    managingGroup.value?.let { group ->
        StarActionSheet(
            actions = listOf(
                StarSheetAction(if (group.pinned) "取消置顶" else "置顶群组"),
                StarSheetAction("群设置", false),
                StarSheetAction("删除群组", true),
            ),
            onAction = { index ->
                when (index) {
                    0 -> state.setGroupPinned(group.id, !group.pinned)
                    1 -> navigator.push(Route.GroupEditor(group.id))
                    else -> state.deleteGroup(group.id)
                }
                managingGroup.value = null
            },
            onDismiss = { managingGroup.value = null },
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = StarPalette.Text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
    }
}

@Composable
private fun AgentRow(state: AppState, agent: Agent, onClick: () -> Unit, onLongClick: () -> Unit) {
    val last = state.lastMessage(agent.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(agent.id) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(glyph = agent.glyph, accentIndex = agent.accentIndex)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = agent.name,
                style = MaterialTheme.typography.titleMedium,
                color = StarPalette.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // 摘要取最有信息量的一条，而不是时间戳。
                text = last?.let { state.previewOf(it) } ?: agent.persona,
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (last != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = last.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = StarPalette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CreateAgentRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(StarPalette.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(StarIconGlyph.Plus, iconSize = 20.dp, tint = StarPalette.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "创建新的智能体",
            style = MaterialTheme.typography.bodyLarge,
            color = StarPalette.TextMuted,
        )
    }
}

/** 群组行。结构照着 AgentRow 来，只是摘要取群的讨论记录、时间取最后一条。 */
@Composable
private fun GroupRow(state: AppState, group: AgentGroup, onClick: () -> Unit, onLongClick: () -> Unit) {
    val last = state.lastGroupMessage(group.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(group.id) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(glyph = group.glyph, accentIndex = group.accentIndex)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                color = StarPalette.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // 还没有讨论过就交代这个群的规模，让用户先知道一次发言要花多少次调用
                text = last?.let { state.previewOf(it) }
                    ?: "${state.membersOf(group).size} 位成员 · ${group.rounds} 轮",
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (last != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = last.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = StarPalette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CreateGroupRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(StarPalette.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(StarIconGlyph.Group, iconSize = 22.dp, tint = StarPalette.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "创建群组",
            style = MaterialTheme.typography.bodyLarge,
            color = StarPalette.TextMuted,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StarPalette.SurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(StarIconGlyph.Search, iconSize = 16.dp, tint = StarPalette.TextFaint)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text),
            cursorBrush = SolidColor(StarPalette.Indigo),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "搜索会话内容、智能体或关键词",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarPalette.TextFaint,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
