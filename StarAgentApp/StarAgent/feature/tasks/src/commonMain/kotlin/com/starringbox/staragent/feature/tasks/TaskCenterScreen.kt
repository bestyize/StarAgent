package com.starringbox.staragent.feature.tasks

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarIconTag
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarActionSheet
import com.starringbox.staragent.core.designsystem.component.StarSheetAction
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus

/** 任务中心：任务类型/状态切换和托管任务卡片。 */
@Composable
fun TaskCenterScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val managing = remember { mutableStateOf<AgentTask?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selectedType = remember { mutableStateOf("一次性任务") }
    val selectedStatus = remember { mutableStateOf("全部") }
    val byStatus = when (selectedStatus.value) {
        "进行中" -> state.tasks.filter { it.status == TaskStatus.Active }
        "已完成" -> state.tasks.filter { it.status == TaskStatus.Done }
        "已暂停" -> state.tasks.filter { it.status == TaskStatus.Paused }
        else -> state.tasks
    }
    val visible = when (selectedType.value) {
        "一次性任务" -> byStatus.filter { it.recurrence == Recurrence.Once }
        "定时任务" -> byStatus.filter { it.recurrence == Recurrence.Daily }
        "周期任务" -> byStatus.filter { it.recurrence == Recurrence.Weekly || it.recurrence == Recurrence.Monthly }
        "条件触发" -> byStatus.filter { it.recurrence == Recurrence.Monthly }
        else -> byStatus
    }.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val active = visible.filter { it.status == TaskStatus.Active }
    val inactive = visible.filter { it.status != TaskStatus.Active }

    Column(Modifier.fillMaxSize().background(StarPalette.Bg)) {
        // 顶部状态/标题区，使用轻盈渐变和光晕营造设计稿中的空间感。
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color(0xFFEAF2FF), StarPalette.Bg)),
            ),
        ) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 34.dp)
                    .size(150.dp).clip(CircleShape)
                    .background(Color(0xFFB8C9FF).copy(alpha = 0.26f)),
            )
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("任务中心", style = MaterialTheme.typography.headlineSmall, color = StarPalette.Text)
                        Text("T A S K   C E N T E R", style = MaterialTheme.typography.labelSmall, color = StarPalette.Indigo)
                    }
                    CircleIconButton(StarIconGlyph.Search, onClick = { searching = !searching }, tint = StarPalette.Text, size = 38.dp)
                    Spacer(Modifier.width(5.dp))
                    CircleIconButton(StarIconGlyph.Plus, onClick = { state.addQuickTask() }, tint = Color.White, containerColor = StarPalette.Indigo, size = 38.dp)
                }
                if (searching) {
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text), cursorBrush = SolidColor(StarPalette.Indigo), modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Spacer(Modifier.height(22.dp))
                Text("让重要的事，在对的时间自然发生", style = MaterialTheme.typography.titleLarge, color = StarPalette.Text)
                Text("用 AI 帮你规划、执行、追踪每一个任务，把精力留给更有价值的生活。", style = MaterialTheme.typography.bodyMedium, color = StarPalette.TextMuted, modifier = Modifier.padding(top = 5.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TaskTypeTabs(selectedType.value) { selectedType.value = it }
                Spacer(Modifier.height(8.dp))
                TaskStatusTabs(selectedStatus.value) { selectedStatus.value = it }
            }
            if (state.tasks.isEmpty()) {
                item { EmptyHint("在对话里说一句「每天9点帮我汇总 AI 新闻」，任务就建好了") }
            } else if (visible.isEmpty()) {
                item { EmptyHint("这个筛选条件下还没有任务") }
            } else {
                if (active.isNotEmpty()) {
                    item { Text("进行中的任务", style = MaterialTheme.typography.titleMedium, color = StarPalette.Text, modifier = Modifier.padding(top = 8.dp, start = 2.dp)) }
                    items(active, key = { it.id }) { task -> TaskCard(state, task, { navigator.push(Route.Chat(task.agentId)) }, { managing.value = task }) }
                }
                if (inactive.isNotEmpty()) {
                    item { Text("已完成或暂停", style = MaterialTheme.typography.titleMedium, color = StarPalette.TextMuted, modifier = Modifier.padding(top = 8.dp, start = 2.dp)) }
                    items(inactive, key = { it.id }) { task -> TaskCard(state, task, { navigator.push(Route.Chat(task.agentId)) }, { managing.value = task }) }
                }
            }
        }
    }
    managing.value?.let { task ->
        val paused = task.status == TaskStatus.Paused
        StarActionSheet(
            actions = listOfNotNull(
                if (task.status == TaskStatus.Done) null else StarSheetAction(if (paused) "恢复任务" else "暂停任务"),
                if (task.status == TaskStatus.Done) null else StarSheetAction("标记为已完成"),
                StarSheetAction("删除任务", destructive = true),
            ),
            onAction = { index ->
                when {
                    task.status != TaskStatus.Done && index == 0 -> state.toggleTaskPaused(task.id)
                    task.status != TaskStatus.Done && index == 1 -> state.completeTask(task.id)
                    else -> state.removeTask(task.id)
                }
                managing.value = null
            },
            onDismiss = { managing.value = null },
        )
    }
}

@Composable
private fun TaskTypeTabs(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("一次性任务", "定时任务", "周期任务", "条件触发").forEach { label ->
            StarTag(
                label,
                modifier = Modifier.weight(1f).clickable { onSelect(label) },
                contentColor = if (selected == label) Color.White else StarPalette.TextMuted,
                containerColor = if (selected == label) StarPalette.Indigo else Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun TaskStatusTabs(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("全部", "进行中", "已完成", "已暂停").forEach { label ->
            StarTag(
                label,
                modifier = Modifier.clickable { onSelect(label) },
                contentColor = if (selected == label) StarPalette.Indigo else StarPalette.TextMuted,
                containerColor = if (selected == label) Color(0xFFDDE8FF) else Color.Transparent,
            )
        }
    }
}

@Composable
private fun TaskCard(state: AppState, task: AgentTask, onOpen: () -> Unit, onMore: () -> Unit) {
    val enabled = task.status == TaskStatus.Active
    val agentName = state.agent(task.agentId)?.name
    StarCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, containerColor = Color.White.copy(alpha = 0.92f)) {
        Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (task.status == TaskStatus.Done) Color(0xFF8EB6FF) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (task.status == TaskStatus.Done) StarIcon(StarIconGlyph.Check, iconSize = 17.dp, tint = Color.White)
                else Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Transparent))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, color = if (enabled) StarPalette.Text else StarPalette.TextMuted)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StarIcon(StarIconGlyph.Task, iconSize = 14.dp, tint = StarPalette.TextMuted)
                    Text(task.scheduleLabel, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
                    Text("·", color = StarPalette.TextFaint)
                    Text(task.recurrence.label, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StarTag(if (task.status == TaskStatus.Done) "已完成" else "工作", contentColor = if (task.status == TaskStatus.Done) StarPalette.Success else StarPalette.Indigo, containerColor = if (task.status == TaskStatus.Done) Color(0xFFE2F6EB) else Color(0xFFE6EEFF))
                    if (agentName != null) StarTag(agentName, contentColor = StarPalette.TextMuted, containerColor = Color(0xFFF0F3FA))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                SmallToggle(enabled) { state.toggleTaskPaused(task.id) }
                Spacer(Modifier.height(8.dp))
                Text("…", style = MaterialTheme.typography.titleMedium, color = StarPalette.TextFaint, modifier = Modifier.clickable { onMore() })
            }
        }
    }
}

@Composable
private fun SmallToggle(checked: Boolean, onCheckedChange: () -> Unit) {
    Box(Modifier.size(width = 42.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).background(if (checked) StarPalette.Indigo else Color(0xFFD0D7E6)).clickable { onCheckedChange() }.padding(3.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart))
    }
}
