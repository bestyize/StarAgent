package com.starringbox.staragent.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarImage
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.TaskDraft

private val BubbleShapeUser = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
private val BubbleShapeAgent = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)

@Composable
internal fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 272.dp)
                .clip(BubbleShapeUser)
                .background(StarPalette.Indigo)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 能选中才能复制——聊天里这是刚需。
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
        }
    }
}

/**
 * 用户发出的图片。图片在上、文字在下——和常见 IM 一致。
 * 可以只有图没有字，所以 caption 为空时不画气泡。
 *
 * 单张图片尺寸有上限，点一下开全屏查看器看原图。
 */
@Composable
internal fun UserImageBubble(
    assets: List<MediaAsset>,
    caption: String,
    onImageClick: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            assets.forEachIndexed { index, asset ->
                StarImage(
                    bytes = asset.bytes,
                    modifier = Modifier
                        .widthIn(max = 232.dp)
                        .heightIn(max = 300.dp)
                        // 间距放最外层，这样点击区域正好是图片本身，不含留白
                        .padding(bottom = if (index == assets.lastIndex && caption.isBlank()) 0.dp else 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onImageClick(index) },
                )
            }
            if (caption.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 272.dp)
                        .clip(BubbleShapeUser)
                        .background(StarPalette.Indigo)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        Text(caption, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentTextBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 296.dp)
                .clip(BubbleShapeAgent)
                .background(StarPalette.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = StarPalette.Text)
            }
        }
    }
}

/**
 * 执行过程可视化。没有"手"的阶段，用户无法靠结果本身判断可信度，
 * 所以必须让"它在为我工作"这件事被看见。
 *
 * 抬头是**跟着进度变的**，不是写死的"正在处理"：这张卡会留在对话流里，
 * 一轮结束之后还顶着"正在处理"就是在说假话。全部打勾就换成"已完成"。
 */
@Composable
internal fun ExecutionCard(steps: List<ExecutionStep>) {
    val allDone = steps.isNotEmpty() && steps.all { it.done }
    StarCard(modifier = Modifier.widthIn(max = 300.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (allDone) "已完成" else "正在处理",
                style = MaterialTheme.typography.labelMedium,
                color = StarPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            steps.forEach { step ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step.done) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(StarPalette.Success),
                            contentAlignment = Alignment.Center,
                        ) {
                            StarIcon(
                                icon = StarIconGlyph.Check,
                                iconSize = 11.dp,
                                tint = Color.White,
                                strokeWidth = 2.8f,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(StarPalette.SurfaceSunken),
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.done) StarPalette.TextMuted else StarPalette.TextFaint,
                    )
                }
            }
        }
    }
}

/**
 * 任务草稿卡片——本产品交互设计的枢纽。
 * 用户只是说了一句话，智能体替他完成了"任务"这个抽象，
 * 把参数填好后请他确认，而不是让他去填表单。
 */
@Composable
internal fun DraftCard(draft: TaskDraft, onConfirm: () -> Unit) {
    Column(Modifier.widthIn(max = 320.dp)) {
        Text(
            text = "好的，我把它做成一个任务：",
            style = MaterialTheme.typography.bodyLarge,
            color = StarPalette.Text,
        )
        Spacer(Modifier.height(8.dp))
        StarCard {
            Column(Modifier.padding(14.dp)) {
                DraftRow(StarIconGlyph.Task, draft.scheduleLabel)
                DraftRow(StarIconGlyph.Link, draft.channelLabel)
                if (draft.skillLabels.isNotEmpty()) {
                    DraftRow(StarIconGlyph.Bolt, "用到：" + draft.skillLabels.joinToString("、"))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(StarPalette.Indigo)
                            .clickable { onConfirm() }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    ) {
                        Text("创建", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(StarPalette.SurfaceAlt)
                            .clickable { }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    ) {
                        Text("修改", style = MaterialTheme.typography.labelLarge, color = StarPalette.TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftRow(glyph: StarIconGlyph, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(icon = glyph, iconSize = 15.dp, tint = StarPalette.Indigo, strokeWidth = 1.9f)
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
    }
}

/** 任务创建成功的回执。任务与创建它的对话绑定，结果也会回流到这条对话。 */
@Composable
internal fun CreatedCard(task: AgentTask) {
    Row(
        modifier = Modifier
            .widthIn(max = 312.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE8F7F1))
            .padding(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(StarPalette.Success),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(StarIconGlyph.Check, iconSize = 13.dp, tint = Color.White, strokeWidth = 2.8f)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = "任务已创建",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF0F6B4F),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = StarPalette.Text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${task.scheduleLabel} · 下次 ${task.nextRunLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
            )
        }
    }
}

/**
 * 产物卡片——一期最重要的交付形态。
 * 聊天流里的文字会"流走"，产物可以沉淀下来被反复使用。
 */
@Composable
internal fun ArtifactCard(artifact: Artifact) {
    var expanded by remember { mutableStateOf(false) }

    StarCard(modifier = Modifier.widthIn(max = 336.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    glyph = StarIconGlyph.Book,
                    tint = StarPalette.Indigo,
                    containerColor = Color(0xFFE7E8FF),
                    size = 30.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = artifact.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artifact.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                    )
                }
                StarTag(artifact.kind.label)
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                artifact.sections.forEach { section ->
                    Text(
                        text = section.heading,
                        style = MaterialTheme.typography.labelMedium,
                        color = StarPalette.Indigo,
                    )
                    Spacer(Modifier.height(4.dp))
                    section.lines.forEach { line ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("· ", style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = StarPalette.Text)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(StarPalette.Divider),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                CardAction(if (expanded) "收起" else "展开") { expanded = !expanded }
                CardAction("导出") { }
                CardAction("分享") { }
            }
        }
    }
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = StarPalette.Indigo,
        modifier = Modifier.clickable { onClick() },
    )
}

/** 记忆提示：让"记忆"这个最容易变成隐形功能的能力，在自然场景中露出。 */
@Composable
internal fun MemoryNoteCard(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = 312.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StarPalette.SurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = "📍 $text",
            style = MaterialTheme.typography.bodySmall,
            color = StarPalette.TextMuted,
        )
    }
}

/**
 * 系统提示卡片。和普通回复刻意做得不一样——
 * 它说的不是"模型想说的话"，而是"你需要去处理的事"。
 */
@Composable
internal fun NoticeCard(text: String, isError: Boolean) {
    Row(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isError) Color(0xFFFDECEC) else StarPalette.SurfaceAlt)
            .padding(13.dp),
    ) {
        StarIcon(
            icon = if (isError) StarIconGlyph.Info else StarIconGlyph.Check,
            iconSize = 16.dp,
            tint = if (isError) StarPalette.Danger else StarPalette.TextMuted,
            strokeWidth = 2f,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) Color(0xFF9B1C1C) else StarPalette.TextMuted,
        )
    }
}

/**
 * 等待首个字符出现时的占位。
 *
 * 三个点错峰呼吸，后面跟一句说明。**只有动画是不够的**：一串跳动的点能表达"在动"，
 * 但说不清在动什么——用户没法区分"它在想"和"界面卡住了"。补上文字才读得懂。
 *
 * [speakerName] 只有群聊会传——群里的占位要说清楚"现在轮到谁了"。
 */
@Composable
internal fun ThinkingRow(speakerName: String? = null) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val label = if (speakerName.isNullOrBlank()) "正在思考" else "$speakerName 正在思考"

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .clip(BubbleShapeAgent)
                .background(StarPalette.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = StarPalette.TextFaint,
            )
            Spacer(Modifier.width(8.dp))
            repeat(3) { index ->
                // 错峰：每个点比前一个晚 170ms。三个点同相位地一起明暗，看起来像整块在闪，
                // 错开才像"一个接一个"的推进感。
                val alpha by transition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 520,
                            delayMillis = index * 170,
                            easing = LinearEasing,
                        ),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "thinking-dot-$index",
                )
                if (index > 0) Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StarPalette.TextFaint.copy(alpha = alpha)),
                )
            }
        }
    }
}

/**
 * 智能体提问卡片。
 *
 * 智能体在多步执行中需要用户确认或选择时弹出。
 * 支持快捷选项 Chip 单选与自由文本输入框二合一。
 * [selectedAnswer] 非 null 时表示已作答，转为只读态。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(
    question: MessageBody.Question,
    onAnswer: (String) -> Unit,
) {
    var customInput by remember { mutableStateOf("") }
    val isAnswered = question.selectedAnswer != null

    Column(Modifier.widthIn(max = 340.dp)) {
        StarCard {
            Column(Modifier.padding(14.dp)) {
                // 顶栏标识
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        glyph = StarIconGlyph.Sparkle,
                        containerColor = Color(0xFFE7E8FF),
                        tint = StarPalette.Indigo,
                        size = 26.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "智能体提问",
                        style = MaterialTheme.typography.labelMedium,
                        color = StarPalette.TextMuted,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 问题正文
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = StarPalette.Text,
                )

                Spacer(Modifier.height(12.dp))

                if (isAnswered) {
                    // 已作答只读态
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StarPalette.SurfaceAlt)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StarIcon(
                            icon = StarIconGlyph.Check,
                            iconSize = 14.dp,
                            tint = StarPalette.Success,
                            strokeWidth = 2.2f,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "已选择：${question.selectedAnswer}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarPalette.TextMuted,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    // 待作答态
                    // 1. 快捷选项列表
                    if (question.options.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            question.options.forEach { option ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(StarPalette.SurfaceAlt)
                                        .clickable { onAnswer(option) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = option,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StarPalette.Indigo,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2. 自定义输入框 + 提交按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StarTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            placeholder = question.placeholder ?: "输入其他回答…",
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (customInput.isNotBlank()) StarPalette.Indigo else StarPalette.SurfaceAlt)
                                .clickable(enabled = customInput.isNotBlank()) {
                                    if (customInput.isNotBlank()) onAnswer(customInput.trim())
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "提交",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (customInput.isNotBlank()) Color.White else StarPalette.TextFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

