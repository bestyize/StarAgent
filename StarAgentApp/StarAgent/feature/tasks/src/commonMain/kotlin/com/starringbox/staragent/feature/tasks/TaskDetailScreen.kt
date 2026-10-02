package com.starringbox.staragent.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskPriority
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.model.defaultScheduleFor

private val CategoryOptions = listOf("工作", "成长", "健康", "生活", "系统")
private val RemindOptions = listOf("不提醒", "提前 5 分钟", "提前 10 分钟", "提前 30 分钟", "提前 1 小时")

/**
 * 任务详情 / 新建任务。[taskId] 为 null 表示新建。
 *
 * 新建**不预先往列表里塞一条再让人改**——那样用户中途退回，列表里就留下一条叫
 * "新的托管任务"的垃圾。这里先在本地把参数填好，点保存才落盘。
 *
 * 类型和时间都可改，且"下次执行"就地实时重算：改完立刻知道它什么时候会跑，
 * 不用保存后回列表里对。
 */
@Composable
fun TaskDetailScreen(navigator: Navigator, taskId: String?) {
    val state = LocalAppState.current
    val existing = taskId?.let { state.task(it) }
    val isNew = existing == null

    var title by remember(taskId) { mutableStateOf(existing?.title.orEmpty()) }
    var recurrence by remember(taskId) { mutableStateOf(existing?.recurrence ?: Recurrence.Daily) }
    var schedule by remember(taskId) {
        mutableStateOf(existing?.scheduleLabel ?: defaultScheduleFor(Recurrence.Daily))
    }
    var agentId by remember(taskId) {
        mutableStateOf(existing?.agentId ?: state.agents.firstOrNull()?.id.orEmpty())
    }
    var note by remember(taskId) { mutableStateOf(existing?.note.orEmpty()) }
    var priority by remember(taskId) { mutableStateOf(existing?.priority) }
    var category by remember(taskId) { mutableStateOf(existing?.categoryLabel) }
    var remind by remember(taskId) { mutableStateOf(existing?.remindLabel) }
    var active by remember(taskId) { mutableStateOf(existing?.status != TaskStatus.Paused) }

    val canSave = title.isNotBlank() && agentId.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarTopBar(
            title = if (isNew) "新建任务" else "任务详情",
            subtitle = state.agent(agentId)?.name,
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
                            placeholder = "要托管什么事",
                            containerColor = StarPalette.SurfaceAlt,
                        )
                    }
                    // 新建的任务没有"启用/暂停"可言，保存时默认就是启用
                    if (!isNew) {
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
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("类型")
            ChipRow(Recurrence.entries.map { it.label }, recurrence.label) { label ->
                val picked = Recurrence.entries.first { it.label == label }
                // 时间描述还没被手动改过，就跟着类型换成对应的示例——
                // 否则切到"每周"还留着"今天 10:00"，用户得自己拼格式
                if (schedule.isBlank() || schedule == defaultScheduleFor(recurrence)) {
                    schedule = defaultScheduleFor(picked)
                }
                recurrence = picked
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(if (recurrence == Recurrence.Conditional) "触发条件" else "时间")
            StarTextField(
                value = schedule,
                onValueChange = { schedule = it },
                placeholder = defaultScheduleFor(recurrence),
            )

            Spacer(Modifier.height(16.dp))
            StarCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    // 有上次才显示上次——新建的任务没有历史
                    existing?.lastRunLabel?.let { InfoRow(StarIconGlyph.Check, "上次", it) }
                    InfoRow(StarIconGlyph.Repeat, "下次", state.nextRunPreview(recurrence, schedule))
                }
            }

            existing?.let { savedTask ->
                savedTask.lastRunResult?.let { result ->
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("最近一次结果")
                    StarCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StarPalette.Text,
                            )
                            savedTask.lastArtifactId?.let { artifactId ->
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "打开生成文件预览",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = StarPalette.Indigo,
                                    modifier = Modifier.clickable {
                                        navigator.push(Route.ArtifactPreview(artifactId))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("由谁执行")
            if (state.agents.isEmpty()) {
                EmptyHint("还没有智能体，先去首页创建一个")
            } else {
                AgentChipRow(
                    names = state.agents.map { it.id to it.name },
                    selectedId = agentId,
                    onSelect = { agentId = it },
                )
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
                    .background(if (canSave) StarPalette.Indigo else StarPalette.SurfaceAlt)
                    .clickable(enabled = canSave) {
                        state.saveTask(
                            (existing ?: state.newTask(agentId)).copy(
                                title = title.trim(),
                                recurrence = recurrence,
                                scheduleLabel = schedule.trim().ifBlank { defaultScheduleFor(recurrence) },
                                agentId = agentId,
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
                Text(
                    text = if (isNew) "创建" else "保存",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (canSave) Color.White else StarPalette.TextFaint,
                )
            }

            if (!isNew) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StarPalette.Surface)
                        .clickable {
                            state.removeTask(existing.id)
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
            }

            Spacer(Modifier.height(28.dp))
        }