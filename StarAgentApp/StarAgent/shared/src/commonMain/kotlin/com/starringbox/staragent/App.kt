package com.starringbox.staragent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.NavAction
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.navigation.isTabRoot
import com.starringbox.staragent.core.designsystem.theme.StarAgentTheme
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.feature.agents.AgentEditorScreen
import com.starringbox.staragent.feature.agents.AgentListScreen
import com.starringbox.staragent.feature.agents.GroupEditorScreen
import com.starringbox.staragent.feature.chat.ChatScreen
import com.starringbox.staragent.feature.chat.GroupChatScreen
import com.starringbox.staragent.feature.settings.ChannelsScreen
import com.starringbox.staragent.feature.settings.MeScreen
import com.starringbox.staragent.feature.settings.MemoryScreen
import com.starringbox.staragent.feature.settings.ModelConfigScreen
import com.starringbox.staragent.feature.settings.ProviderDetailScreen
import com.starringbox.staragent.feature.settings.SettingsScreen
import com.starringbox.staragent.feature.settings.SkillsScreen
import com.starringbox.staragent.feature.settings.SpeechConfigScreen
import com.starringbox.staragent.feature.settings.StorageBrowserScreen
import com.starringbox.staragent.feature.tasks.TaskCenterScreen
import com.starringbox.staragent.feature.tasks.TaskDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 应用外壳：注入主题与状态，承载导航宿主与底部导航。
 * 三端（Android / iOS / Desktop）共用这一个入口。
 */
/** Android 通知点击后送进共享导航的单次事件。 */
data class AppNavigationEvent(val sequence: Long, val route: Route)

@Composable
fun App(notificationEvent: AppNavigationEvent? = null) {
    StarAgentTheme {
        val state = remember { AppState() }
        val navigator = remember { Navigator(Route.Agents) }
        LaunchedEffect(notificationEvent?.sequence) {
            val route = notificationEvent?.route ?: return@LaunchedEffect
            when (route) {
                is Route.TaskDetail -> {
                    navigator.switchTab(Route.Tasks)
                    navigator.push(route)
                }
                else -> {
                    navigator.switchTab(Route.Agents)
                    navigator.push(route)
                }
            }
        }

        CompositionLocalProvider(LocalAppState provides state) {
            // 系统返回键接进导航栈：还有上一页就返回，到底了才交回系统（退出）。
            // 不接这一步的话，在二级页按返回会直接退出 App。
            PlatformBackHandler(enabled = navigator.canGoBack) { navigator.pop() }

            // 定时任务的心跳。只在 App 开着时跑——关着期间刚好错过的那次，
            // 下次打开时会被补上（runDueTasks 会把过期的排期一次跑掉）。
            LaunchedEffect(state) {
                while (true) {
                    try {
                        state.runDueTasks()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 一次执行出错不该让心跳停掉：停了就再也不会跑任何任务，而且没有任何提示
                    }
                    delay(TASK_HEARTBEAT_MILLIS)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // 只让开底部。顶部交给各页自己的顶栏去处理：
                    // 顶栏的底色要一直铺到状态栏后面，内容再用 statusBarsPadding() 躲开。
                    // 在这里统一内缩的话，状态栏那一条就只能是背景色，
                    // 顶栏看起来像"没顶到边"。
                    //
                    // 底部取**导航栏与软键盘的较大者**，不是两个相加：键盘弹起时它本来就盖住了
                    // 导航栏，加起来会多空一条。只写 navigationBarsPadding() 的话键盘完全不躲——
                    // edge-to-edge 下键盘是叠上来的，输入坞底下会被啃掉一截。
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
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

/** 任务的检查间隔。任务时间精度是分钟，半分钟看一眼足够，也不会白烧 CPU。 */
private const val TASK_HEARTBEAT_MILLIS = 30_000L

/**
 * 页面转场用 tween 而不是 spring：整屏位移下弹簧的过冲会让页面"弹"一下，时长也不可预期。
 * FastOutSlowIn 起步快、收尾稳，是页面级位移的常规选择。
 */
private val SlideSpec = tween<IntOffset>(durationMillis = 280, easing = FastOutSlowInEasing)

/**
 * 关掉 AnimatedContent 默认的尺寸补间。
 *
 * 它会为容器高度做一段补间，补间的起点是上一页记住的高度、终点是当前页的高度；
 * 而底栏在进出二级页时会立刻出现/消失，内容区高度本来就是突变的。
 * 两者叠在一起，正在滑动的页面会被按一个不断变化的高度反复重新测量——
 * 表现就是返回时整页文字从上往下重新排版一遍。
 *
 * 整屏页面不需要尺寸补间：宽高永远铺满，动画只应该是位移。
 */
private fun routeTransform(enter: EnterTransition, exit: ExitTransition) =
    ContentTransform(enter, exit, targetContentZIndex = 0f, sizeTransform = null)

@Composable
private fun RouteHost(navigator: Navigator) {
    AnimatedContent(
        targetState = navigator.target,
        transitionSpec = {
            when (targetState.action) {
                // Tab 之间是平级切换，没有"更深一层"的关系，加位移动画反而像又进了一层
                NavAction.Replace -> EnterTransition.None togetherWith ExitTransition.None

                // 前进：两页一起向左移动整屏宽度——新页从右边进来，旧页从左边出去。
                // 两者始终首尾相接，任意时刻都不重叠。
                NavAction.Push -> routeTransform(
                    enter = slideInHorizontally(SlideSpec) { it },
                    exit = slideOutHorizontally(SlideSpec) { -it },
                )

                // 返回：方向反过来，同样是整屏等速。
                // 这里不用视差（让旧页只让出三分之一屏宽那种）：那会让新页一开场就
                // 压在旧页还在的区域上，看起来像新页的内容提前"透"了出来。
                NavAction.Pop -> routeTransform(
                    enter = slideInHorizontally(SlideSpec) { -it },
                    exit = slideOutHorizontally(SlideSpec) { it },
                )
            }
        },
        // 一个页面的身份就是它的路由，动作不同不构成换页
        contentKey = { it.route },
        label = "route",
    ) { target ->
        when (val route = target.route) {
            Route.Agents -> AgentListScreen(navigator)
            is Route.Chat -> ChatScreen(navigator, route.agentId)
            is Route.GroupChat -> GroupChatScreen(navigator, route.groupId)
            is Route.GroupEditor -> GroupEditorScreen(navigator, route.groupId)
            Route.Tasks -> TaskCenterScreen(navigator)
            is Route.TaskDetail -> TaskDetailScreen(navigator, route.taskId)
            is Route.ArtifactPreview -> ArtifactPreviewScreen(navigator, route.artifactId)
            Route.Me -> MeScreen(navigator)
            is Route.AgentEditor -> AgentEditorScreen(navigator, route.agentId)
            Route.ModelConfig -> ModelConfigScreen(navigator)
            is Route.ProviderDetail -> ProviderDetailScreen(navigator, route.providerId)
            Route.SpeechConfig -> SpeechConfigScreen(navigator)
            Route.Memories -> MemoryScreen(navigator)
            Route.Channels -> ChannelsScreen(navigator)
            Route.Skills -> SkillsScreen(navigator)
            Route.Settings -> SettingsScreen(navigator)
            is Route.StorageBrowser -> StorageBrowserScreen(navigator, route.path)
        }
    }
}

private data class Tab(val route: Route, val glyph: StarIconGlyph, val label: String)

private val Tabs = listOf(
    Tab(Route.Agents, StarIconGlyph.Chat, "首页"),
    Tab(Route.Tasks, StarIconGlyph.Task, "任务"),
    Tab(Route.Memories, StarIconGlyph.Book, "记忆"),
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
                .padding(top = 10.dp, bottom = 14.dp),
        ) {
            Tabs.forEach { tab ->
                val selected = current == tab.route
                val tint = if (selected) StarPalette.Indigo else StarPalette.TextFaint
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 去掉点击水波纹：底栏是常驻导航，反馈靠颜色变化就够了
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (!selected) navigator.switchTab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StarIcon(
                        icon = tab.glyph,
                        iconSize = 24.dp,
                        tint = tint,
                        strokeWidth = if (selected) 2.15f else 1.8f,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
            }
        }
    }
}
