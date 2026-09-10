package com.starringbox.staragent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.navigation.isTabRoot
import com.starringbox.staragent.core.designsystem.theme.StarAgentTheme
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.feature.agents.AgentEditorScreen
import com.starringbox.staragent.feature.agents.AgentListScreen
import com.starringbox.staragent.feature.chat.ChatScreen
import com.starringbox.staragent.feature.settings.ChannelsScreen
import com.starringbox.staragent.feature.settings.MeScreen
import com.starringbox.staragent.feature.settings.MemoryScreen
import com.starringbox.staragent.feature.settings.ModelConfigScreen
import com.starringbox.staragent.feature.settings.ProviderDetailScreen
import com.starringbox.staragent.feature.settings.SkillsScreen
import com.starringbox.staragent.feature.tasks.TaskCenterScreen

/**
 * 应用外壳：注入主题与状态，承载导航宿主与底部导航。
 * 三端（Android / iOS / Desktop）共用这一个入口。
 */
@Composable
fun App() {
    StarAgentTheme {
        val state = remember { AppState() }
        val navigator = remember { Navigator(Route.Agents) }

        CompositionLocalProvider(LocalAppState provides state) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        RouteHost(navigator)
                    }
                    // 只有在三个根页面才显示底部导航——进入对话或二级页时让位给内容。
                    if (navigator.current.isTabRoot) {
                        StarBottomBar(navigator)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteHost(navigator: Navigator) {
    when (val route = navigator.current) {
        Route.Agents -> AgentListScreen(navigator)
        is Route.Chat -> ChatScreen(navigator, route.agentId)
        Route.Tasks -> TaskCenterScreen(navigator)
        Route.Me -> MeScreen(navigator)
        is Route.AgentEditor -> AgentEditorScreen(navigator, route.agentId)
        Route.ModelConfig -> ModelConfigScreen(navigator)
        is Route.ProviderDetail -> ProviderDetailScreen(navigator, route.providerId)
        Route.Memories -> MemoryScreen(navigator)
        Route.Channels -> ChannelsScreen(navigator)
        Route.Skills -> SkillsScreen(navigator)
    }
}

private data class Tab(val route: Route, val glyph: StarIconGlyph, val label: String)

private val Tabs = listOf(
    Tab(Route.Agents, StarIconGlyph.Chat, "对话"),
    Tab(Route.Tasks, StarIconGlyph.Task, "任务"),
    Tab(Route.Me, StarIconGlyph.Person, "我的"),
)

@Composable
private fun StarBottomBar(navigator: Navigator) {
    val current = navigator.current

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StarPalette.Divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StarPalette.Surface)
                .padding(vertical = 9.dp),
        ) {
            Tabs.forEach { tab ->
                val selected = current == tab.route
                val tint = if (selected) StarPalette.Indigo else StarPalette.TextFaint
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { if (!selected) navigator.switchTab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StarIcon(
                        icon = tab.glyph,
                        iconSize = 22.dp,
                        tint = tint,
                        strokeWidth = if (selected) 2.15f else 1.8f,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}
