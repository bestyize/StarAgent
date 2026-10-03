package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.tools.ports.FilePort
import com.starringbox.staragent.core.tools.ports.NoopFilePort
import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.PermissionPort
import com.starringbox.staragent.core.tools.ports.NoopPermissionPort
import com.starringbox.staragent.core.tools.ports.NoopSkillPort
import com.starringbox.staragent.core.tools.ports.SkillPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.TaskSummary
import com.starringbox.staragent.core.tools.ports.UserInputPort
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

/**
 * 一次 create 调用的**完整**入参。
 *
 * 只记 title 的话，"参数到底有没有被正确映射"就测不出来——把 recurrence 和
 * schedule 写反、或者干脆硬编码成 Daily，断言照样过。字段要全记下来。
 */
data class CreateCall(
    val agentId: String,
    val title: String,
    val recurrence: Recurrence,
    val scheduleLabel: String,
    val note: String?,
)

/** 一次 update 调用的**完整**入参。理由同 [CreateCall]：只记 id 的话两个字段写反照样过。 */
data class UpdateCall(
    val agentId: String,
    val id: String,
    val title: String?,
    val scheduleLabel: String?,
)

/** 一次 cancel 调用的**完整**入参。 */
data class CancelCall(
    val agentId: String,
    val id: String,
)

class FakeTaskPort : TaskPort {
    val createCalls = mutableListOf<CreateCall>()
    val updateCalls = mutableListOf<UpdateCall>()
    val cancelCalls = mutableListOf<CancelCall>()
    val listedAgentIds = mutableListOf<String>()
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
        createCalls += CreateCall(agentId, title, recurrence, scheduleLabel, note)
        return nextId
    }

    override suspend fun list(agentId: String): List<TaskSummary> {
        listedAgentIds += agentId
        return listed
    }

    override suspend fun update(
        agentId: String,
        id: String,
        title: String?,
        scheduleLabel: String?,
    ): Boolean {
        updateCalls += UpdateCall(agentId, id, title, scheduleLabel)
        return updateResult
    }

    override suspend fun cancel(agentId: String, id: String): Boolean {
        cancelCalls += CancelCall(agentId, id)
        return cancelResult
    }
}

class FakeWebPort(var result: FetchResult = FetchResult(true, "正文")) : WebPort {
    val fetched = mutableListOf<String>()
    override suspend fun fetch(url: String): FetchResult {
        fetched += url
        return result
    }
}

class FakeUserInputPort(var answerToReturn: String = "好的") : UserInputPort {
    val requests = mutableListOf<RequestCall>()

    data class RequestCall(
        val agentId: String,
        val question: String,
        val options: List<String>,
        val placeholder: String?,
    )

    override suspend fun requestInput(
        agentId: String,
        question: String,
        options: List<String>,
        placeholder: String?,
    ): String {
        requests += RequestCall(agentId, question, options, placeholder)
        return answerToReturn
    }
}

class FakeDevicePort : com.starringbox.staragent.core.tools.ports.DevicePort {
    val launchedApps = mutableListOf<String>()
    var listResult = com.starringbox.staragent.core.tools.ports.DeviceAppsResult(
        true,
        listOf(
            com.starringbox.staragent.core.tools.ports.InstalledApp("微信", "com.tencent.mm"),
            com.starringbox.staragent.core.tools.ports.InstalledApp("网易云音乐", "com.netease.cloudmusic"),
        )
    )

    override suspend fun inspect(scope: com.starringbox.staragent.core.tools.ports.DeviceScope) =
        com.starringbox.staragent.core.tools.ports.DeviceScreenResult(true)
    override suspend fun click(screenId: String, elementId: String) =
        com.starringbox.staragent.core.tools.ports.DeviceActionResult(true, "clicked")
    override suspend fun type(screenId: String, elementId: String?, text: String) =
        com.starringbox.staragent.core.tools.ports.DeviceActionResult(true, "typed")
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long) =
        com.starringbox.staragent.core.tools.ports.DeviceActionResult(true, "swiped")
    override suspend fun pressBack() =
        com.starringbox.staragent.core.tools.ports.DeviceActionResult(true, "backed")
    override suspend fun launchApp(packageOrBundleId: String): com.starringbox.staragent.core.tools.ports.DeviceActionResult {
        launchedApps += packageOrBundleId
        return com.starringbox.staragent.core.tools.ports.DeviceActionResult(true, "已启动：$packageOrBundleId")
    }
    override suspend fun listApps(query: String?): com.starringbox.staragent.core.tools.ports.DeviceAppsResult = listResult
}

/** 造一个 host。默认端口都是空的假实现，测哪个换哪个。 */
fun testHost(
    memory: MemoryPort = FakeMemoryPort(),
    tasks: TaskPort = FakeTaskPort(),
    web: WebPort = FakeWebPort(),
    userInput: UserInputPort = FakeUserInputPort(),
    permissions: PermissionPort = NoopPermissionPort,
    device: com.starringbox.staragent.core.tools.ports.DevicePort = FakeDevicePort(),
    executionPolicy: ExecutionPolicy = ExecutionPolicy.Auto,
    agentId: String = "a-xing",
    files: FilePort = NoopFilePort,
    skills: SkillPort = NoopSkillPort,
) = ToolHost(
    memory = memory,
    tasks = tasks,
    web = web,
    userInput = userInput,
    files = files,
    skills = skills,
    permissions = permissions,
    device = device,
    executionPolicy = executionPolicy,
    agentId = agentId,
    conversationKey = "agent:a-xing",
)
