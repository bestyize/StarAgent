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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.SettingsRow
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarPageHeader
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.voice.Voice

/**
 * 「我的」页，按设计稿做成模块化工作台：
 * 我的智能体 / Skill 商店 / 模型接入 / IM 渠道，每个模块都能直接操作，而不是一堆开关列表。
 */
@Composable
fun MeScreen(navigator: Navigator) {
    val state = LocalAppState.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarPageHeader(
            title = "星环智能 Agent",
            subtitle = "S T A R   R I N G",
            onSettings = { navigator.push(Route.Settings) },
        )

        Spacer(Modifier.height(26.dp))
        MyAgentsModule(state, navigator)

        Spacer(Modifier.height(26.dp))
        SkillStoreModule(state, navigator)

        Spacer(Modifier.height(26.dp))
        ModelModule(state, navigator)

        Spacer(Modifier.height(26.dp))
        SpeechModule(state, navigator)

        Spacer(Modifier.height(26.dp))
        ChannelModule(state, navigator)

        Spacer(Modifier.height(32.dp))
    }
}

// ── 模块头 ──────────────────────────────────────

@Composable
private fun ModuleHeader(
    glyph: StarIconGlyph,
    title: String,
    badge: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(StarPalette.Indigo.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(icon = glyph, iconSize = 15.dp, tint = StarPalette.Indigo, strokeWidth = 2f)
        }
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = StarPalette.Text)
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text(badge, style = MaterialTheme.typography.labelMedium, color = StarPalette.TextFaint)
        }
        Spacer(Modifier.weight(1f))
        if (onMore != null) {
            Text(
                text = "全部 〉",
                style = MaterialTheme.typography.labelMedium,
                color = StarPalette.Indigo,
                modifier = Modifier.clickable { onMore() },
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(StarPalette.Indigo.copy(alpha = 0.12f))
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall, color = StarPalette.Indigo)
            }
        }
    }
}

// ── 我的智能体 ──────────────────────────────────

@Composable
private fun MyAgentsModule(state: AppState, navigator: Navigator) {
    ModuleHeader(
        glyph = StarIconGlyph.Chat,
        title = "我的智能体",
        badge = state.agents.size.toString(),
        actionLabel = "＋ 创建智能体",
        onAction = { navigator.push(Route.AgentEditor(null)) },
        onMore = { navigator.switchTab(Route.Agents) },
    )
    Spacer(Modifier.height(12.dp))
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.agents, key = { it.id }) { agent ->
            AgentMiniCard(
                agent = agent,
                available = state.endpointFor(agent) != null,
                onClick = { navigator.push(Route.Chat(agent.id)) },
            )
        }
        item {
            CreateMiniCard(onClick = { navigator.push(Route.AgentEditor(null)) })
        }
    }
}

@Composable
private fun AgentMiniCard(agent: Agent, available: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AgentAvatar(glyph = agent.glyph, accentIndex = agent.accentIndex, size = 46.dp, cornerRadius = 15.dp)
        Spacer(Modifier.height(9.dp))
        Text(
            text = agent.name,
            style = MaterialTheme.typography.titleSmall,
            color = StarPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        // 「可用 / 未配置」是真实状态——取决于这个智能体有没有配好模型，
        // 而不是编一个假的"在线"。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (available) StarPalette.Success else StarPalette.Divider),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (available) "可用" else "未配置",
                style = MaterialTheme.typography.labelSmall,
                color = if (available) StarPalette.Success else StarPalette.TextFaint,
            )
        }
    }
}

@Composable
private fun CreateMiniCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(StarPalette.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(StarIconGlyph.Plus, iconSize = 20.dp, tint = StarPalette.Indigo)
        }
        Spacer(Modifier.height(9.dp))
        Text("创建智能体", style = MaterialTheme.typography.titleSmall, color = StarPalette.Text)
        Spacer(Modifier.height(3.dp))
        Text(
            text = "定制你的 AI",
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
        )
    }
}

// ── Skill 商店 ──────────────────────────────────

@Composable
private fun SkillStoreModule(state: AppState, navigator: Navigator) {
    ModuleHeader(
        glyph = StarIconGlyph.Bolt,
        title = "Skill 商店",
        actionLabel = "浏览更多",
        onAction = { navigator.push(Route.Skills) },
        onMore = { navigator.push(Route.Skills) },
    )
    Spacer(Modifier.height(12.dp))
    val featured = state.skills.take(4)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        featured.forEach { skill ->
            GridTile(
                glyph = skill.glyph,
                title = skill.name,
                subtitle = if (skill.installed) "已安装" else "可安装",
                modifier = Modifier.weight(1f),
                onClick = { navigator.push(Route.Skills) },
            )
        }
    }
}

// ── 模型接入 ────────────────────────────────────

@Composable
private fun ModelModule(state: AppState, navigator: Navigator) {
    ModuleHeader(
        glyph = StarIconGlyph.Chip,
        title = "模型接入",
        badge = "${state.configuredProviders().size}/${state.providers.size}",
        actionLabel = "＋ 添加模型",
        onAction = { navigator.push(Route.ModelConfig) },
        onMore = { navigator.push(Route.ModelConfig) },
    )
    Spacer(Modifier.height(12.dp))
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.providers, key = { it.id }) { provider ->
            ProviderTile(provider) { navigator.push(Route.ProviderDetail(provider.id)) }
        }
        item {
            AddTile(label = "自建端点", onClick = { navigator.push(Route.ProviderDetail(null)) })
        }
    }
}

@Composable
private fun ProviderTile(provider: ModelProvider, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(94.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    when (provider.protocol) {
                        LlmProtocol.Anthropic ->
                            StarPalette.Violet.copy(alpha = 0.14f)

                        LlmProtocol.OpenAi ->
                            StarPalette.Indigo.copy(alpha = 0.12f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(
                icon = StarIconGlyph.Chip,
                iconSize = 18.dp,
                tint = when (provider.protocol) {
                    LlmProtocol.Anthropic -> StarPalette.Violet
                    LlmProtocol.OpenAi -> StarPalette.Indigo
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = provider.name,
            style = MaterialTheme.typography.labelLarge,
            color = StarPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (provider.configured) "已接入" else "待配置",
            style = MaterialTheme.typography.labelSmall,
            color = if (provider.configured) StarPalette.Success else StarPalette.TextFaint,
        )
    }
}

// ── 语音识别 ────────────────────────────────────

/**
 * 和「模型接入」平级的一节：语音识别走哪条路。
 *
 * 这里只摆一行摘要 + 一个入口，不把三选塞进来——选服务商还要能看到"哪家待配置"、
 * 能选识别模型，那是一页的信息量。副标题直接说**现在实际会走哪条**，
 * 而不是"去配置"这种没信息的字。
 */
@Composable
private fun SpeechModule(state: AppState, navigator: Navigator) {
    val active = state.activeSpeechProvider()
    val configured = active != null && state.speechConfigured(active.id)

    ModuleHeader(
        glyph = StarIconGlyph.Mic,
        title = "语音",
        // 只有一条配置了，所以 badge 不再是"几家配好了"的进度，而是"现在用的是谁"
        badge = if (configured) active?.name else null,
        actionLabel = "配置",
        onAction = { navigator.push(Route.SpeechConfig) },
        onMore = { navigator.push(Route.SpeechConfig) },
    )
    Spacer(Modifier.height(12.dp))
    Box(Modifier.padding(horizontal = 20.dp)) {
        StarCard(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                glyph = StarIconGlyph.Mic,
                title = state.asrSummary,
                subtitle = speechSubtitle(state),
                tint = if (state.canListen) StarPalette.Indigo else StarPalette.TextFaint,
                onClick = { navigator.push(Route.SpeechConfig) },
            )
        }
    }
}

/**
 * 这一行副标题要回答的是"现在能不能按麦克风，不能的话卡在哪"。
 *
 * 三种不能用的原因分开说，因为用户要做的事完全不同：**没配服务商**去这一页选一家；
 * **没有麦克风**（桌面常态）怎么配都不会好；**系统识别起不来**是设备那一路的事。
 * 一句"还用不了"会让一个配好了 Key 的人反复回去检查 Key。
 */
private fun speechSubtitle(state: AppState): String = when {
    state.canListen -> "麦克风按钮已经可用"
    state.asrEndpoint() == null && !Voice.deviceListenInstalled ->
        // 刻意不列有哪些家：加一家就要回来改一句文案，而漏改的后果是"明明有却不告诉用户"
        "还没配语音服务商：点进去选一家并填上 Key"
    !Voice.canRecord && !Voice.deviceListenInstalled -> "这台机器没有可用的麦克风，录不了音"
    else -> "还用不了：点进去看识别方式"
}

// ── IM 渠道 ─────────────────────────────────────

@Composable
private fun ChannelModule(state: AppState, navigator: Navigator) {
    ModuleHeader(
        glyph = StarIconGlyph.Link,
        title = "IM 渠道",
        badge = "${state.activeChannels().size}",
        actionLabel = "连接渠道",
        onAction = { navigator.push(Route.Channels) },
        onMore = { navigator.push(Route.Channels) },
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.channels.forEach { channel ->
            ChannelTile(channel, Modifier.weight(1f)) { navigator.push(Route.Channels) }
        }
    }
}

@Composable
private fun ChannelTile(channel: Channel, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(channel.glyph, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelLarge,
            color = StarPalette.Text,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (channel.connected) "已连接" else "未连接",
            style = MaterialTheme.typography.labelSmall,
            color = if (channel.connected) StarPalette.Success else StarPalette.TextFaint,
        )
    }
}

// ── 通用小格 ────────────────────────────────────

@Composable
private fun GridTile(
    glyph: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = StarPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddTile(label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(94.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StarPalette.Surface.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(StarPalette.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(StarIconGlyph.Plus, iconSize = 18.dp, tint = StarPalette.Indigo)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = StarPalette.Text, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text("自定义", style = MaterialTheme.typography.labelSmall, color = StarPalette.TextFaint)
    }
}
