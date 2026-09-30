package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.TaskSummary
import com.starringbox.staragent.core.tools.ports.WebPort

/** 记住被写进来的东西，供断言。 */
class FakeMemoryPort(private val existing: List<MemoryItem> = emptyList()) : MemoryPort {
    val written = mutableListOf<Pair<MemoryCategory, String>>()
    val recalledQueries = mutableListOf<String>()

    override suspend fun recall(agentId: String, query: String, limit: Int): List<MemoryItem> {
        recalledQueries += query
        return existing.filter { it.agentId == agentId }.take(limit)
    }

    override suspend fun remember(agentId: String, category: MemoryCategory, text: String) {
        written += category to text
    }
}

class FakeTaskPort : TaskPort {
    val created = mutableListOf<String>()
    var nextId = "task-1"
    var updateResult = true
    var cancelResult = true
    var listed: List<TaskSummary> = emptyList()

    override suspend fun create(
        agentId: String,
        title: String,
        recurrence: Recurrence,
        scheduleLabel: String,
        note: String?,
    ): String {
        created += title
        return nextId
    }

    override suspend fun list(agentId: String): List<TaskSummary> = listed

    override suspend fun update(agentId: String, id: String, title: String?, scheduleLabel: String?) =
        updateResult

    override suspend fun cancel(agentId: String, id: String) = cancelResult
}

class FakeWebPort(var result: FetchResult = FetchResult(true, "正文")) : WebPort {
    val fetched = mutableListOf<String>()
    override suspend fun fetch(url: String): FetchResult {
        fetched += url
        return result
    }
}

/** 造一个 host。默认端口都是空的假实现，测哪个换哪个。 */
fun testHost(
    memory: MemoryPort = FakeMemoryPort(),
    tasks: TaskPort = FakeTaskPort(),
    web: WebPort = FakeWebPort(),
    agentId: String = "a-xing",
) = ToolHost(memory, tasks, web, agentId, conversationKey = "agent:a-xing")
