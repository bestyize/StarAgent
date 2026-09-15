package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarPageHeader
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem

/** 分类的图标与配色，界面层的事，不放进模型。 */
private fun iconOf(category: MemoryCategory): StarIconGlyph = when (category) {
    MemoryCategory.Preference -> StarIconGlyph.Sparkle
    MemoryCategory.Person -> StarIconGlyph.Person
    MemoryCategory.Habit -> StarIconGlyph.Repeat
    MemoryCategory.Experience -> StarIconGlyph.Book
    MemoryCategory.Decision -> StarIconGlyph.Bolt
}

private fun colorOf(category: MemoryCategory): Color = when (category) {
    MemoryCategory.Preference -> StarPalette.Indigo
    MemoryCategory.Person -> StarPalette.Violet
    MemoryCategory.Habit -> StarPalette.Success
    MemoryCategory.Experience -> StarPalette.Warning
    MemoryCategory.Decision -> StarPalette.Danger
}

/**
 * 记忆与成长页，按设计稿的三段结构组织：
 * 引导区 → 我的记忆（分类 + 智能体两层筛选）→ 成长概览与自我优化循环 → 记忆明细。
 *
 * **记忆只能从对话里来**。这里原本有个手动输入框，但它记下的东西和智能体在对话里
 * 记下的东西是两回事——前者模型根本不知道，后者才是"它认识你"。留着一个写进去也没人
 * 读得着的入口，只会让人以为记忆是靠手填的，所以去掉了。
 */
@Composable
fun MemoryScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val scroll = rememberScrollState()

    var categoryFilter by remember { mutableStateOf<MemoryCategory?>(null) }
    var agentFilter by remember { mutableStateOf<String?>(null) }

    // 两个筛选是叠的：先按智能体收窄，再按分类收窄。
    // 分类卡片上的数字走的是**收窄之后**的集合——那几张卡片本身就是筛选器，
    // 数字和点进去看到的东西对不上会很别扭。
    val ofAgent = state.memories.filter { agentFilter == null || it.agentId == agentFilter }
    val visible = ofAgent.filter { categoryFilter == null || it.category == categoryFilter }
    // 一条记忆都没有的智能体不出现：筛选器上列出一堆点了是空的东西没有意义
    val agentsWithMemories = state.agents.filter { agent -> state.memories.any { it.agentId == agent.id } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        StarPageHeader(
            title = "星环智能 Agent",
            subtitle = "S T A R   R I N G",
        )

        GreetingBlock()

        Spacer(Modifier.height(24.dp))
        MyMemories(
            memories = ofAgent,
            selected = categoryFilter,
            onSelect = { categoryFilter = if (categoryFilter == it) null else it },
            // 只管分类这一层。智能体那层有它自己的「全部」，各清各的才不会有意外。
            onShowAll = { categoryFilter = null },
        )

        if (agentsWithMemories.size > 1) {
            Spacer(Modifier.height(14.dp))
            AgentFilter(
                agents = agentsWithMemories.map { it.id to it.name },
                selected = agentFilter,
                onSelect = { agentFilter = if (agentFilter == it) null else it },
                onShowAll = { agentFilter = null },
            )
        }

        Spacer(Modifier.height(24.dp))
        GrowthOverview(memories = state.memories, agentCount = state.agents.size)

        Spacer(Modifier.height(24.dp))
        GrowthLoop()

        Spacer(Modifier.height(24.dp))
        MemoryList(
            memories = visible,
            agentName = { id -> state.agent(id)?.name },
            onDelete = { state.removeMemory(it) },
            emptyHint = if (state.memories.isEmpty()) {
                "这里还空着。和智能体聊聊，值得留下的事它会自己记下来。"
            } else {
                "这个筛选条件下还没有记忆。"
            },
        )

        Spacer(Modifier.height(28.dp))
    }
}

// ── 引导区 ──────────────────────────────────────

@Composable
private fun GreetingBlock() {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "你好，",
            style = MaterialTheme.typography.headlineSmall,
            color = StarPalette.Text,
        )
        Text(
            text = "很高兴见证你的成长 ✨",
            style = MaterialTheme.typography.headlineSmall,
            color = StarPalette.Text,
        )
        Spacer(Modifier.height(6.dp))
        // 手动记录的入口去掉之后，得说清楚记忆是从哪来的——
        // 否则这一页看起来就是个"只能看、不知道怎么往里加"的清单
        Text(
            text = "记忆来自你和智能体的对话——聊到值得留下的事，它会自己记下来。",
            style = MaterialTheme.typography.bodyMedium,
            color = StarPalette.TextMuted,
        )
    }
}

// ── 智能体筛选 ──────────────────────────────────

@Composable
private fun AgentFilter(
    agents: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("只看谁的记忆", style = MaterialTheme.typography.labelMedium, color = StarPalette.TextFaint)
        Spacer(Modifier.height(6.dp))
        // 有几个智能体说不准，横排会溢出，所以让它能横向滚
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceChip(label = "全部", selected = selected == null, onClick = onShowAll)
            agents.forEach { (id, name) ->
                ChoiceChip(label = name, selected = selected == id, onClick = { onSelect(id) })
            }
        }
    }
}

// ── 我的记忆 ────────────────────────────────────

@Composable
private fun MyMemories(
    memories: List<MemoryItem>,
    selected: MemoryCategory?,
    onSelect: (MemoryCategory) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "我的记忆",
                style = MaterialTheme.typography.titleMedium,
                color = StarPalette.Text,
                modifier = Modifier.weight(1f),
            )
            if (selected != null) {
                Text(
                    text = "显示全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarPalette.Indigo,
                    modifier = Modifier.clickable { onShowAll() },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemoryCategory.entries.forEach { entry ->
                val count = memories.count { it.category == entry }
                CategoryCard(
                    category = entry,
                    count = count,
                    selected = selected == entry,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: MemoryCategory,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = colorOf(category)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StarIcon(icon = iconOf(category), iconSize = 20.dp, tint = accent, strokeWidth = 2f)
        Spacer(Modifier.height(8.dp))
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelMedium,
            color = StarPalette.Text,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$count ${category.unit}",
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
            maxLines = 1,
        )
    }
}

// ── 成长概览 ────────────────────────────────────

/**
 * 成长概览的图表用的是**真实数据**：每根柱子是一个分类下的记忆条数。
 *
 * 设计稿画的是"近 6 个月的记录趋势"，但记忆目前没有时间戳，画不出趋势——
 * 与其编一条假曲线，不如把手上真有的数据画出来。
 */
@Composable
private fun GrowthOverview(memories: List<MemoryItem>, agentCount: Int) {
    val counts = MemoryCategory.entries.map { entry -> memories.count { it.category == entry } }
    val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val habit = memories.count { it.category == MemoryCategory.Habit }
    val decision = memories.count { it.category == MemoryCategory.Decision }

    StarCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的成长概览",
                    style = MaterialTheme.typography.titleMedium,
                    color = StarPalette.Text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(StarPalette.Indigo.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "持续成长中",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarPalette.Indigo,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "比昨天的自己更好一点",
                style = MaterialTheme.typography.titleLarge,
                color = StarPalette.Text,
            )
            Text(
                text = "在持续的积累中，你正朝着更理想的自己靠近。",
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextMuted,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                MemoryCategory.entries.forEachIndexed { index, entry ->
                    val value = counts[index]
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = StarPalette.TextMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((10 + 58 * value / peak).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (value == 0) StarPalette.SurfaceSunken
                                    else colorOf(entry).copy(alpha = 0.85f)
                                ),
                        )
                        Spacer(Modifier.height(6.dp))
                        // 用完整分类名，不要截断——"重要的人"和"重要决策"两字是一样的
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = StarPalette.TextFaint,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("记录数", memories.size.toString())
                Metric("生活习惯", habit.toString())
                Metric("重要决策", decision.toString())
                Metric("覆盖智能体", agentCount.toString())
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = StarPalette.Text,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = StarPalette.TextFaint)
    }
}

// ── 自我优化循环 ────────────────────────────────

@Composable
private fun GrowthLoop() {
    val steps = listOf(
        "记录生活" to "沉淀有价值的记忆",
        "反思洞察" to "发现模式与机会",
        "获得成长" to "遇见更好的自己",
        "调整行动" to "设定更好的计划",
    )

    StarCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自我优化循环",
                    style = MaterialTheme.typography.titleMedium,
                    color = StarPalette.Text,
                    modifier = Modifier.weight(1f),
                )
                Text("持续进化", style = MaterialTheme.typography.labelMedium, color = StarPalette.Indigo)
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            ) {
                // 循环环本身只做视觉暗示，四步文字摆在四个角
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension * 0.28f
                    drawCircle(
                        color = StarPalette.Indigo.copy(alpha = 0.18f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawArc(
                        color = StarPalette.Indigo,
                        startAngle = -60f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                    listOf(
                        Offset(center.x, center.y - radius),
                        Offset(center.x + radius, center.y),
                        Offset(center.x, center.y + radius),
                        Offset(center.x - radius, center.y),
                    ).forEach { point ->
                        drawCircle(color = StarPalette.Indigo, radius = 5.dp.toPx(), center = point)
                    }
                }

                LoopStep(steps[0], Modifier.align(Alignment.TopStart))
                LoopStep(steps[1], Modifier.align(Alignment.TopEnd), TextAlign.End)
                LoopStep(steps[2], Modifier.align(Alignment.BottomEnd), TextAlign.End)
                LoopStep(steps[3], Modifier.align(Alignment.BottomStart))

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "持续进化",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarPalette.Indigo,
                    )
                    Text(
                        text = "成为更好的自己",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarPalette.TextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoopStep(text: Pair<String, String>, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Column(modifier.width(112.dp)) {
        Text(
            text = text.first,
            style = MaterialTheme.typography.titleSmall,
            color = StarPalette.Text,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = text.second,
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── 记忆明细 ────────────────────────────────────

@Composable
private fun MemoryList(
    memories: List<MemoryItem>,
    agentName: (String) -> String?,
    onDelete: (String) -> Unit,
    emptyHint: String,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "记忆明细",
            style = MaterialTheme.typography.titleMedium,
            color = StarPalette.Text,
        )
        Spacer(Modifier.height(12.dp))

        if (memories.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }

        memories.forEach { memory ->
            StarCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorOf(memory.category).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StarIcon(
                            icon = iconOf(memory.category),
                            iconSize = 15.dp,
                            tint = colorOf(memory.category),
                            strokeWidth = 2f,
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = memory.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarPalette.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = listOfNotNull(memory.category.label, agentName(memory.agentId))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = StarPalette.TextFaint,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    StarIcon(
                        icon = StarIconGlyph.Trash,
                        iconSize = 16.dp,
                        tint = StarPalette.TextFaint,
                        modifier = Modifier.clickable { onDelete(memory.id) },
                    )
                }
            }
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
