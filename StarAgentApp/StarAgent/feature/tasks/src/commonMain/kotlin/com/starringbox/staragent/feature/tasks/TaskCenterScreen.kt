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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus

/**
 * 任务中心。
 *
 * 它是**管理视图**，不是主消费场景——用户平时看结果的入口是对话。
 * 所以这里每条任务只回答三个问题：频率是什么、下次什么时候跑、上次跑了没有。
 * 这三条信息决定了用户对"它到底有没有在干活"的信任。
 */
@Composable
fun TaskCenterScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val active = state.tasks.filter { it.status == TaskStatus.Active }
    val resting = state.tasks.filter { it.status != TaskStatus.Active }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "任务",
            subtitle = if (state.tasks.isEmpty()) "还没有托管任务" else "共 ${state.tasks.size} 个托管任务",
        )

        if (state.tasks.isEmpty()) {
            EmptyHint("在对话里说一句「每天9点帮我汇总AI新闻」，任务就建好了")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (active.isNotEmpty()) {
                    item { SectionLabel("进行中") }
                    items(active, key = { it.id }) { task ->
                        TaskCard(
                            state = state,
                            task = task,
                            onOpen = { navigator.push(Route.Chat(task.agentId)) },
                        )
                    }
                }
                if (resting.isNotEmpty()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionLabel("已暂停") }
                    items(resting, key = { it.id }) { task ->
                        TaskCard(
                            state = state,
                            task = task,
                            onOpen = { navigator.push(Route.Chat(task.agentId)) },
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun TaskCard(state: AppState, task: AgentTask, onOpen: () -> Unit) {
    val paused = task.status != TaskStatus.Active
    val agentName = state.agent(task.agentId)?.name

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable { onOpen() }
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    glyph = if (task.recurrence == Recurrence.Once) StarIconGlyph.Task else StarIconGlyph.Repeat,
                    tint = if (paused) StarPalette.TextFaint else StarPalette.Indigo,
                    containerColor = if (paused) StarPalette.SurfaceAlt else Color(0xFFE7E8FF),
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (paused) StarPalette.TextMuted else StarPalette.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = listOfNotNull(task.scheduleLabel, agentName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                    )
                }
                StarTag(task.recurrence.label)
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(StarPalette.Divider),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta("下次", task.nextRunLabel)
                Spacer(Modifier.width(20.dp))
                Meta("上次", task.lastRunLabel ?: "尚未执行")
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (paused) "恢复" else "暂停",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarPalette.Indigo,
                    modifier = Modifier
                        .clickable { state.toggleTaskPaused(task.id) }
                        .padding(4.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarPalette.TextFaint,
                    modifier = Modifier
                        .clickable { state.removeTask(task.id) }
                        .padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = StarPalette.Text,
        )
    }
}
