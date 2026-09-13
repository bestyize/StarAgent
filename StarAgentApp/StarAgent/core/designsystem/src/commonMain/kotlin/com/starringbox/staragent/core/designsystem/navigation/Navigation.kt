package com.starringbox.staragent.core.designsystem.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 应用的全部目的地。导航契约放在设计系统层，
 * 功能模块只依赖这里，不互相依赖，避免环路。
 */
sealed interface Route {
    /** 首页：智能体联系人列表。 */
    data object Agents : Route

    /** 与某个智能体的对话。 */
    data class Chat(val agentId: String) : Route

    /** 与某个群组的讨论。 */
    data class GroupChat(val groupId: String) : Route

    /** 创建 / 编辑群组；传 null 表示新建。 */
    data class GroupEditor(val groupId: String?) : Route

    /** 任务中心。 */
    data object Tasks : Route

    /** 某个任务的详情与编辑。 */
    data class TaskDetail(val taskId: String) : Route

    /** 我的（设置）。 */
    data object Me : Route

    /** 创建 / 编辑智能体；传 null 表示新建。 */
    data class AgentEditor(val agentId: String?) : Route

    /** 模型服务配置。 */
    data object ModelConfig : Route

    /** 某个服务商的详情：API Key、模型列表、默认模型；providerId 为 null 表示新增。 */
    data class ProviderDetail(val providerId: String?) : Route

    /** 记忆管理。 */
    data object Memories : Route

    /** IM 渠道绑定。 */
    data object Channels : Route

    /** 技能管理。 */
    data object Skills : Route
}

/**
 * 是否为底部导航的根页面。
 *
 * 技能页不在其中——它是二级页，从「我的 → Skill 商店」推进来。
 * 所以这里必须跟着一起改，否则进技能页时底栏会错误地留着。
 */
val Route.isTabRoot: Boolean
    get() = this is Route.Agents || this is Route.Tasks ||
        this is Route.Memories || this is Route.Me

/** 上一次导航动作。界面据此决定转场方向：前进从右侧滑入，返回则相反。 */
enum class NavAction { Push, Pop, Replace }

/**
 * 一次导航的结果：目的地 + 造成这次跳转的动作。
 *
 * 两者捆成一个值交给界面，是因为转场方向由动作决定。如果界面分别去读
 * 「当前路由」和「上一次动作」，两次读取之间状态可能已经变了，动画就会选错方向。
 */
data class NavTarget(val route: Route, val action: NavAction)

/**
 * 极简导航器：一条返回栈。
 * 切换 Tab 时重置到该 Tab 的根页面，符合底部导航的常规预期。
 */
class Navigator(initial: Route = Route.Agents) {
    private val stack = mutableStateListOf(initial)

    var target by mutableStateOf(NavTarget(initial, NavAction.Replace))
        private set

    val current: Route get() = target.route

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: Route) {
        stack.add(route)
        target = NavTarget(route, NavAction.Push)
    }

    fun pop() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            target = NavTarget(stack.last(), NavAction.Pop)
        }
    }

    fun switchTab(route: Route) {
        stack.clear()
        stack.add(route)
        target = NavTarget(route, NavAction.Replace)
    }
}
