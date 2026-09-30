package com.starringbox.staragent.core.tools.ports

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus

/**
 * 工具取能力的三道窄门。
 *
 * 每个方法都带 agentId 而不是让实现去记——端口的实现因此可以是无状态单例，
 * 测试里造假的也容易。这三道门是 core:tools 唯一能碰到 App 能力的地方，
 * 也是将来接外部工具的接缝。
 */
interface MemoryPort {
    suspend fun recall(agentId: String, query: String, limit: Int): List<MemoryItem>
    suspend fun remember(agentId: String, category: MemoryCategory, text: String)
}

/** 给模型看的任务摘要，不是完整的 AgentTask——模型不需要 nextRunAt 这种调度字段。 */
data class TaskSummary(
    val id: String,
    val title: String,
    val recurrence: Recurrence,
    val scheduleLabel: String,
    val nextRunLabel: String,
    val status: TaskStatus,
)

interface TaskPort {
    suspend fun create(
        agentId: String,
        title: String,
        recurrence: Recurrence,
        scheduleLabel: String,
        note: String?,
    ): String
    suspend fun list(agentId: String): List<TaskSummary>
    suspend fun update(agentId: String, id: String, title: String?, scheduleLabel: String?): Boolean
    suspend fun cancel(agentId: String, id: String): Boolean
}

/**
 * 取网页。返回 [FetchResult] 而不是抛异常——抓取失败是**给模型看的信息**，
 * 不是给程序的异常：模型看到"这个地址取不到"会换个地址重试。
 */
data class FetchResult(
    val ok: Boolean,
    val text: String,
    val error: String? = null,
)

interface WebPort {
    suspend fun fetch(url: String): FetchResult
}
