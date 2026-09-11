package com.starringbox.staragent.core.designsystem.navigation

import androidx.compose.runtime.mutableStateListOf

/**
 * 应用的全部目的地。导航契约放在设计系统层，
 * 功能模块只依赖这里，不互相依赖，避免环路。
 */
sealed interface Route {
    /** 首页：智能体联系人列表。 */
    data object Agents : Route

    /** 与某个智能体的对话。 */
    data class Chat(val agentId: String) : Route

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

/** 是否为底部导航的三个根页面。 */
val Route.isTabRoot: Boolean
    get() = this is Route.Agents || this is Route.Tasks || this is Route.Memories || this is Route.Skills || this is Route.Me

/**
 * 极简导航器：一条返回栈。
 * 切换 Tab 时重置到该 Tab 的根页面，符合底部导航的常规预期。
 */
class Navigator(initial: Route = Route.Agents) {
    private val stack = mutableStateListOf(initial)

    val current: Route get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun switchTab(route: Route) {
        stack.clear()
        stack.add(route)
    }
}
