package com.starringbox.staragent.feature.agents

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent

private data class AgentTemplate(
    val id: String,
    val label: String,
    val glyph: String,
    val persona: String,
    val modelId: String,
    val skills: List<String>,
)

private val Templates = listOf(
    AgentTemplate("general", "通用", "星", "通用助手，日常问答与信息汇总", "anthropic:0", listOf("web", "digest")),
    AgentTemplate("writer", "写作", "写", "擅长把零散想法整理成结构清晰的文字", "anthropic:0", listOf("doc")),
    AgentTemplate("study", "学习", "学", "陪你学习，讲解概念并出练习题", "openai:0", listOf("web")),
    AgentTemplate("work", "工作", "工", "面向工作场景：周报、邮件、会议纪要", "openai:0", listOf("doc", "mail")),
    AgentTemplate("blank", "空白", "＋", "一个全新的智能体，人设由你来定义", "anthropic:0", emptyList()),
)

/**
 * 创建 / 编辑智能体。
 *
 * 双路径：选模板（快），或者直接用一句话描述（自由）。
 * 后者才是"高自由度"的体现——用户不需要填 system prompt，
 * 那是开发者的交互，不是用户的。
 */
@Composable
fun AgentEditorScreen(navigator: Navigator, agentId: String?) {
    val state = LocalAppState.current
    val editing = agentId?.let { state.agent(it) }

    var name by remember { mutableStateOf(editing?.name ?: "") }
    var persona by remember { mutableStateOf(editing?.persona ?: "") }
    var modelId by remember { mutableStateOf(editing?.modelId ?: state.defaultModelId) }
    var glyph by remember { mutableStateOf(editing?.glyph ?: "星") }
    var description by remember { mutableStateOf("") }

    // 新智能体默认**不开**任务能力——默认保持纯对话，想要的人自己打开。
    var canScheduleTasks by remember { mutableStateOf(editing?.canScheduleTasks ?: false) }

    // 直接算，不用 remember 缓存——服务商配置随时可能变，缓存反而容易过期。
    val modelChoices = state.providers.flatMap { provider ->
        provider.models.mapIndexed { index, label -> state.modelKey(provider.id, index) to label }
    }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = if (editing == null) "创建智能体" else "编辑智能体",
            onBack = { navigator.pop() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 实时预览：改名字和人设，上面的头像立刻跟着变。
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(glyph = glyph, accentIndex = editing?.accentIndex ?: state.agents.size)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name.ifBlank { "未命名智能体" },
                        style = MaterialTheme.typography.titleMedium,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = persona.ifBlank { "还没有人设" },
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("从模板开始")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Templates, key = { it.id }) { template ->
                    val selected = template.persona == persona && persona.isNotBlank()
                    TemplateCard(
                        template = template,
                        selected = selected,
                        onClick = {
                            glyph = template.glyph
                            if (name.isBlank() || Templates.any { it.label == name }) {
                                name = if (template.id == "blank") "" else template.label + "助手"
                            }
                            persona = template.persona
                            modelId = template.modelId
                        },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("或直接描述你想要的智能体")
            PlainField(
                value = description,
                onValueChange = { description = it },
                placeholder = "一个说话直接、擅长帮我做技术选型对比的助手",
                minHeight = 78.dp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    persona = "你是一个" + description.trim() +
                        "。回答时先给结论再给依据，保持简洁直接。"
                    if (name.isBlank()) name = "自定义智能体"
                    glyph = "定"
                },
                enabled = description.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StarPalette.Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("生成人设", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("名称")
            PlainField(
                value = name,
                onValueChange = { name = it },
                placeholder = "给这个智能体起个名字",
                minHeight = 0.dp,
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("人设")
            PlainField(
                value = persona,
                onValueChange = { persona = it },
                placeholder = "它是什么样的、擅长什么、怎么说话",
                minHeight = 96.dp,
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("模型")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modelChoices, key = { it.first }) { choice ->
                    val selected = choice.first == modelId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
                            .clickable { modelId = choice.first }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = choice.second,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) androidx.compose.ui.graphics.Color.White else StarPalette.TextMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("能力")
            StarCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "把对话变成托管任务",
                            style = MaterialTheme.typography.titleSmall,
                            color = StarPalette.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "开启后，你说「每天…」或者「到时候提醒我…」时，" +
                                "它会生成一张任务草稿卡片让你确认；关掉就只是聊天。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = canScheduleTasks,
                        onCheckedChange = { canScheduleTasks = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StarPalette.Indigo,
                            checkedBorderColor = StarPalette.Indigo,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }

        Button(
            onClick = {
                val existing = editing
                if (existing == null) {
                    state.addAgent(
                        Agent(
                            id = state.nextId("agent"),
                            name = name.trim(),
                            glyph = glyph,
                            persona = persona.trim(),
                            modelId = modelId,
                            accentIndex = state.agents.size,
                            canScheduleTasks = canScheduleTasks,
                        )
                    )
                } else {
                    val index = state.agents.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        state.agents[index] = existing.copy(
                            name = name.trim(),
                            glyph = glyph,
                            persona = persona.trim(),
                            modelId = modelId,
                            canScheduleTasks = canScheduleTasks,
                        )
                    }
                }
                navigator.pop()
            },
            enabled = name.isNotBlank(),
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
private fun TemplateCard(template: AgentTemplate, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) StarPalette.Indigo.copy(alpha = 0.10f) else StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = template.glyph, style = MaterialTheme.typography.titleLarge, color = StarPalette.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            text = template.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) StarPalette.Indigo else StarPalette.TextMuted,
        )
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StarPalette.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarPalette.Text),
            cursorBrush = SolidColor(StarPalette.Indigo),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (minHeight > 0.dp) Modifier.height(minHeight) else Modifier),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
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
