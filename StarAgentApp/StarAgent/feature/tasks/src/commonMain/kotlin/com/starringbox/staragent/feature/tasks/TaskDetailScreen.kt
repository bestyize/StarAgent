package com.starringbox.staragent.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.TaskPriority
import com.starringbox.staragent.core.model.TaskStatus

private val CategoryOptions = listOf("工作", "成长", "健康", "生活", "系统")
private val RemindOptions = listOf("不提醒", "提前 5 分钟", "提前 10 分钟", "提前 30 分钟", "提前 1 小时")

/**
 * 任务详情，对应设计稿右侧那张"任务详情示例"。
 *
 * 从对话里建出来的任务只有标题和时间，分类、优先级、提醒、备注都是空的——
 * 这一页就是让用户把这些补齐，所以整体做成可直接编辑，而不是只读再点"编辑"。
 */
@Composable
fun TaskDetailScreen(navigator: Navigator, taskId: String) {
    val state = LocalAppState.current
    val task = state.task(taskId)
    if (task == null) {
        StarTopBar(title = "任务", onBack = { navigator.pop() })
        return
    }

    var title by remember(taskId) { mutableStateOf(task.title) }
    var note by remember(taskId) { mutableStateOf(task.note.orEmpty()) }
    var priority by remember(taskId) { mutableStateOf(task.priority) }
    var category by remember(taskId) { mutableStateOf(task.categoryLabel) }
    var remind by remember(taskId) { mutableStateOf(task.remindLabel) }
    var active by remember(taskId) { mutableStateOf(task.status == TaskStatus.Active) }

    val agentName = state.agent(task.agentId)?.name

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarTopBar(
            title = "任务详情",
            subtitle = agentName,
            onBack = { navigator.pop() },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            StarCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(
                        glyph = StarIconGlyph.Task,
                        tint = StarPalette.Indigo,
                        containerColor = StarPalette.Indigo.copy(alpha = 0.12f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "任务名称",
                            style = MaterialTheme.typography.labelSmall,
                            color = StarPalette.TextFaint,
                        )
                        Spacer(Modifier.height(6.dp))
                        StarTextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "任务名称",
                            containerColor = StarPalette.SurfaceAlt,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = active,
                        onCheckedChange = { active = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StarPalette.Indigo,
                            checkedBorderColor = StarPalette.Indigo,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            StarCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow(StarIconGlyph.Task, "时间", task.scheduleLabel)
                    InfoRow(StarIconGlyph.Book, "类型", task.recurrence.label)
                    // 先落到局部变量：跨模块的属性没法智能转换
                    val lastRun = task.lastRunLabel
                    if (lastRun != null) {
                        InfoRow(StarIconGlyph.Check, "上次", lastRun)
                    }
                    InfoRow(StarIconGlyph.Repeat, "下次", task.nextRunLabel)
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("提醒")
            ChipRow(
                options = RemindOptions,
                selected = remind,
                onSelect = { remind = if (remind == it) null else it },
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("分类")
            ChipRow(
                options = CategoryOptions,
                selected = category,
                onSelect = { category = if (category == it) null else it },
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("优先级")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TaskPriority.entries.forEach { entry ->
                    val selected = priority == entry
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) priorityColor(entry) else StarPalette.SurfaceAlt)
                            .clickable { priority = if (selected) null else entry }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color.White else StarPalette.TextMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("备注")
            StarTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = "要提醒自己注意什么",
                singleLine = false,
                minHeight = 76.dp,
            )

            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(StarPalette.Indigo)
                    .clickable {
                        state.updateTask(
                            task.copy(
                                title = title.trim().ifBlank { task.title },
                                note = note.trim().ifBlank { null },
                                priority = priority,
                                categoryLabel = category,
                                remindLabel = remind,
                                status = if (active) TaskStatus.Active else TaskStatus.Paused,
                            )
                        )
                        navigator.pop()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(StarPalette.Surface)
                    .clickable {
                        state.removeTask(task.id)
                        navigator.pop()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "删除任务",
                    style = MaterialTheme.typography.labelLarge,
                    color = StarPalette.Danger,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoRow(glyph: StarIconGlyph, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(icon = glyph, iconSize = 15.dp, tint = StarPalette.TextFaint, strokeWidth = 2f)
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StarPalette.TextMuted,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = StarPalette.Text,
        )
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    // 横向可滚：提醒的选项比较多，窄屏上硬挤会把标签压成竖排
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { option ->
            val isSelected = selected == option
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) StarPalette.Indigo else StarPalette.SurfaceAlt)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) Color.White else StarPalette.TextMuted,
                )
            }
        }
    }
}

/** 优先级配色，任务列表卡片也要用，所以是 internal 而不是 private。 */
internal fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.High -> StarPalette.Danger
    TaskPriority.Medium -> StarPalette.Warning
    TaskPriority.Low -> StarPalette.Success
}
