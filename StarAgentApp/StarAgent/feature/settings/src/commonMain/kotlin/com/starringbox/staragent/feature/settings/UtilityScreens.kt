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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarConfirmDialog
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.SkillSource

private fun skillSourceTagColors(source: SkillSource): Pair<Color, Color> = when (source) {
    SkillSource.BuiltIn -> StarPalette.TextMuted to StarPalette.SurfaceAlt
    SkillSource.Imported -> StarPalette.Indigo to Color(0xFFE7E8FF)
    SkillSource.Agent -> StarPalette.Violet to Color(0xFFF6E9FB)
}

/** 技能管理：能力可以持续扩展，不必等 App 版本更新。 */
@Composable
fun SkillsScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val expandedIds = remember { mutableStateOf(setOf<String>()) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "技能",
            subtitle = "${state.skills.count { it.installed }} / ${state.skills.size} 个已启用",
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Explainer("技能遵循开放的 SKILL.md 标准，和 Claude、Codex 用的是同一套。智能体只在用得上时才会读取某个技能，你也可以直接在对话开头输入 /技能名 来指定调用。智能体发现值得沉淀的新技能时会主动提议保存，但一定会先问过你。") }

            items(state.skills, key = { it.id }) { skill ->
                val expanded = skill.id in expandedIds.value
                SkillCard(
                    skill = skill,
                    expanded = expanded,
                    onToggleExpanded = {
                        expandedIds.value = if (expanded) {
                            expandedIds.value - skill.id
                        } else {
                            expandedIds.value + skill.id
                        }
                    },
                    onToggleInstalled = { state.toggleSkill(skill.id) },
                    onRequestDelete = { pendingDeleteId = skill.id },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val deleteTarget = pendingDeleteId
    if (deleteTarget != null) {
        val skillName = state.skills.firstOrNull { it.id == deleteTarget }?.name ?: deleteTarget
        StarConfirmDialog(
            title = "删除技能",
            message = "删除「$skillName」后无法恢复，智能体和你都不能再用它。",
            confirmLabel = "删除",
            onConfirm = {
                state.removeSkill(deleteTarget)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@Composable
private fun SkillCard(
    skill: com.starringbox.staragent.core.model.Skill,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleInstalled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val state = LocalAppState.current
    var instructionsExpanded by remember(skill.id) { mutableStateOf(false) }
    val (sourceTint, sourceContainer) = skillSourceTagColors(skill.source)

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { onToggleExpanded() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    glyph = StarIconGlyph.Bolt,
                    tint = if (skill.installed) StarPalette.Indigo else StarPalette.TextFaint,
                    containerColor = if (skill.installed) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = skill.glyph + "  " + skill.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = skill.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StarTag(skill.source.label, contentColor = sourceTint, containerColor = sourceContainer)
                        if (!skill.modelInvocable) {
                            StarTag("仅手动调用", contentColor = StarPalette.Warning, containerColor = Color(0xFFFCF3DD))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                StarIcon(
                    icon = if (expanded) StarIconGlyph.ChevronDown else StarIconGlyph.ChevronRight,
                    iconSize = 16.dp,
                    tint = StarPalette.TextFaint,
                )
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = skill.installed,
                    onCheckedChange = { onToggleInstalled() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StarPalette.Indigo, checkedBorderColor = StarPalette.Indigo),
                )
            }

            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    if (skill.userInvocable) {
                        Text(
                            text = "调用方式：在对话开头输入 /${skill.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StarPalette.TextMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (skill.instructions.isNotBlank()) {
                        val lines = skill.instructions.lines()
                        val isLong = lines.size > 12
                        val shown = if (instructionsExpanded || !isLong) skill.instructions else lines.take(12).joinToString("\n")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(StarPalette.SurfaceAlt)
                                .padding(10.dp),
                        ) {
                            Text(shown, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
                        }
                        if (isLong) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (instructionsExpanded) "收起" else "展开全部",
                                style = MaterialTheme.typography.labelMedium,
                                color = StarPalette.Indigo,
                                modifier = Modifier.clickable { instructionsExpanded = !instructionsExpanded },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    val resources = state.skillResources(skill.id)
                    if (resources.isNotEmpty()) {
                        Text("附带文件", style = MaterialTheme.typography.labelMedium, color = StarPalette.TextFaint)
                        Spacer(Modifier.height(4.dp))
                        resources.forEach { path ->
                            Text(path, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    if (skill.source != SkillSource.BuiltIn) {
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelLarge,
                            color = StarPalette.Danger,
                            modifier = Modifier.clickable { onRequestDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Explainer(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StarPalette.SurfaceAlt)
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StarPalette.TextMuted,
        )
    }
}
