package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette

/** 我的页：按设计稿展示智能体、Skill、模型和 IM 渠道四个能力模块。 */
@Composable
fun MeScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val agents = state.agents.take(3)
    val skills = state.skills.take(4)
    val providers = state.providers.take(4)
    val channels = state.channels.take(4)

    Column(Modifier.fillMaxSize().background(StarPalette.Bg)) {
        HeroHeader(
            agentCount = state.agents.size,
            skillCount = state.skills.count { it.installed },
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            ModuleSection("我的智能体", StarIconGlyph.Person, "创建智能体", { navigator.push(Route.AgentEditor(null)) }) {
                agents.forEachIndexed { index, agent ->
                    AgentModuleTile(agent.name, agent.persona, agent.glyph, agent.accentIndex, state.endpointFor(agent) != null, Modifier.weight(1f)) { navigator.push(Route.Chat(agent.id)) }
                    if (index != agents.lastIndex) Spacer(Modifier.width(8.dp))
                }
                CreateTile(Modifier.weight(1f)) { navigator.push(Route.AgentEditor(null)) }
            }
            ModuleSection("Skill 商店", StarIconGlyph.Bolt, "浏览更多", { navigator.push(Route.Skills) }) {
                skills.forEachIndexed { index, skill ->
                    SkillModuleTile(skill.name, skill.summary, skill.glyph, Modifier.weight(1f)) { navigator.push(Route.Skills) }
                    if (index != skills.lastIndex) Spacer(Modifier.width(8.dp))
                }
            }
            ModuleSection("模型接入", StarIconGlyph.Chip, "添加模型", { navigator.push(Route.ModelConfig) }) {
                providers.forEachIndexed { index, provider ->
                    ProviderModuleTile(provider.name, provider.configured, providerGlyph(provider.name), Modifier.weight(1f)) { navigator.push(Route.ProviderDetail(provider.id)) }
                    if (index != providers.lastIndex) Spacer(Modifier.width(8.dp))
                }
            }
            ModuleSection("IM 渠道", StarIconGlyph.Link, "连接渠道", { navigator.push(Route.Channels) }) {
                channels.forEachIndexed { index, channel ->
                    ChannelModuleTile(channel.name, channel.glyph, channel.connected, Modifier.weight(1f)) { navigator.push(Route.Channels) }
                    if (index != channels.lastIndex) Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroHeader(agentCount: Int, skillCount: Int) {
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFEAF2FF), Color(0xFFF5F8FF), StarPalette.Bg))).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(Modifier.size(150.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFBFCBFF).copy(alpha = 0.20f)))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF6C7DFF), Color(0xFF9A74FF)))), contentAlignment = Alignment.Center) {
                    StarIcon(StarIconGlyph.Sparkle, iconSize = 20.dp, tint = Color.White, strokeWidth = 1.4f)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("星环智能 Agent", style = MaterialTheme.typography.titleLarge, color = StarPalette.Text)
                    Text("S T A R   R I N G", style = MaterialTheme.typography.labelSmall, color = StarPalette.Indigo, letterSpacing = 3.sp)
                }
                Spacer(Modifier.weight(1f))
                CircleIconButton(StarIconGlyph.Search, onClick = {}, tint = StarPalette.Text, size = 32.dp)
            }
            Spacer(Modifier.height(22.dp))
            Text("智能体，让一切更简单", style = MaterialTheme.typography.headlineSmall, color = StarPalette.Text)
            Spacer(Modifier.height(4.dp))
            Text("创建 · 连接 · 扩展 · 与 AI 一起成长", style = MaterialTheme.typography.bodyMedium, color = StarPalette.TextMuted)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.86f)).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                StarIcon(StarIconGlyph.Search, iconSize = 18.dp, tint = StarPalette.TextFaint)
                Spacer(Modifier.width(8.dp))
                Text("搜索智能体、技能或解决方案...", style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
                Spacer(Modifier.weight(1f))
                Text("$agentCount 个智能体 · $skillCount 个技能", style = MaterialTheme.typography.labelSmall, color = StarPalette.Indigo)
            }
        }
    }
}

@Composable
private fun ModuleSection(title: String, glyph: StarIconGlyph, action: String, onAction: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(glyph, StarPalette.Indigo, Color(0xFFE6EDFF), size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = StarPalette.Text)
            Spacer(Modifier.weight(1f))
            Text(action, style = MaterialTheme.typography.labelSmall, color = StarPalette.Indigo, modifier = Modifier.clickable { onAction() })
            Spacer(Modifier.width(3.dp))
            StarIcon(StarIconGlyph.ChevronRight, iconSize = 14.dp, tint = StarPalette.Indigo, strokeWidth = 1.8f)
        }
        Spacer(Modifier.height(7.dp))
        StarCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, content = content)
        }
    }
}

@Composable
private fun AgentModuleTile(name: String, persona: String, glyph: String, accentIndex: Int, online: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFF)).clickable { onClick() }.padding(vertical = 10.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AgentAvatar(glyph, accentIndex, size = 38.dp, cornerRadius = 12.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = StarPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(persona, style = MaterialTheme.typography.labelSmall, color = StarPalette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        StarTag(if (online) "在线" else "离线", contentColor = if (online) StarPalette.Success else StarPalette.TextFaint, containerColor = if (online) Color(0xFFE8F8F1) else StarPalette.SurfaceAlt)
    }
}

@Composable
private fun CreateTile(modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4F7FF)).clickable { onClick() }.padding(vertical = 10.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE4ECFF)), contentAlignment = Alignment.Center) { StarIcon(StarIconGlyph.Plus, iconSize = 21.dp, tint = StarPalette.Indigo) }
        Spacer(Modifier.height(6.dp))
        Text("创建智能体", style = MaterialTheme.typography.labelMedium, color = StarPalette.Indigo, maxLines = 1)
        Text("定制你的 AI", style = MaterialTheme.typography.labelSmall, color = StarPalette.TextMuted, maxLines = 1)
    }
}

@Composable
private fun SkillModuleTile(name: String, subtitle: String, glyph: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFF)).clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFE9E7FF)), contentAlignment = Alignment.Center) { Text(glyph.ifBlank { "✦" }, fontSize = 20.sp, color = StarPalette.Indigo) }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = StarPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = StarPalette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProviderModuleTile(name: String, configured: Boolean, glyph: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFF)).clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFFE8F0FF)), contentAlignment = Alignment.Center) { Text(glyph, fontSize = 17.sp, color = StarPalette.Indigo, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = StarPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (configured) "可用" else "待配置", style = MaterialTheme.typography.labelSmall, color = if (configured) StarPalette.Success else StarPalette.Warning, maxLines = 1)
    }
}

@Composable
private fun ChannelModuleTile(name: String, glyph: String, connected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFF)).clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (connected) Color(0xFFE7F8F2) else Color(0xFFF0F2F8)), contentAlignment = Alignment.Center) { Text(glyph, fontSize = 20.sp) }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = StarPalette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelSmall, color = if (connected) StarPalette.Success else StarPalette.TextMuted)
    }
}

private fun providerGlyph(name: String): String = when {
    name.contains("Open", ignoreCase = true) -> "◎"
    name.contains("Claude", ignoreCase = true) -> "✳"
    name.contains("Gemini", ignoreCase = true) -> "✦"
    name.contains("Deep", ignoreCase = true) -> "◈"
    else -> name.firstOrNull()?.uppercase() ?: "AI"
}
