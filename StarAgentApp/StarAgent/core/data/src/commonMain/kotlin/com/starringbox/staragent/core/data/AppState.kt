package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.data.speech.ReplySpeechSession
import com.starringbox.staragent.core.data.speech.SpeechController
import com.starringbox.staragent.core.data.extensions.*
import com.starringbox.staragent.core.runtime.*
import com.starringbox.staragent.core.runtime.extensions.*

import com.starringbox.staragent.core.model.describeMoment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import com.starringbox.staragent.core.data.ports.AppMemoryPort
import com.starringbox.staragent.core.data.ports.AppTaskPort
import com.starringbox.staragent.core.data.ports.AppUserInputPort
import com.starringbox.staragent.core.data.ports.AppWebPort
import com.starringbox.staragent.core.llm.ChatCompletion
import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.platform.notify.Notifiers
import com.starringbox.staragent.core.platform.notify.NotificationTarget
import com.starringbox.staragent.core.platform.platformCommandPort
import com.starringbox.staragent.core.platform.platformMcpManager
import com.starringbox.staragent.core.platform.platformDevicePort
import com.starringbox.staragent.core.platform.permissions.PermissionRequests
import com.starringbox.staragent.core.platform.schedule.TaskSchedulers
import com.starringbox.staragent.core.platform.schedule.TaskSchedulerStatus
import com.starringbox.staragent.core.platform.schedule.WakeupTrigger
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.BuiltInCompanion
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.model.SkillSource
import com.starringbox.staragent.core.model.TaskDraft
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import com.starringbox.staragent.core.tools.ports.TaskSummary
import com.starringbox.staragent.core.tools.ports.WebPort
import com.starringbox.staragent.core.data.ports.AppFilePort
import com.starringbox.staragent.core.data.ports.AppSearchPort
import com.starringbox.staragent.core.data.ports.AppSkillPort
import com.starringbox.staragent.core.data.ports.AppBrowserPort
import com.starringbox.staragent.core.model.describeNextRun
import com.starringbox.staragent.core.model.nextRunAtFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 应用的单一状态源。
 *
 * 对话走 [ChatCompletion] 直连用户自己配置的大模型服务；
 * 模型服务配置、API Key、默认模型都通过 [SettingsStore] 持久化到本机。
 *
 * [stream] 是给测试留的缝：单测可以塞一个假的流实现，把整条编排链路变成可回归的，
 * 默认值就是真实实现，生产路径不受影响。
 * [extensionFactory] 在恢复应用数据前组装扩展；访问会话或记忆的扩展应通过闭包延迟读取。
 *
 * [webPort] 同样是缝，而且**声明在 [stream] 之前**：Kotlin 的尾随 lambda 只绑最后一个参数，
 * `stream` 必须留在末位，`AppState { ... }` 这种现有写法才不会被迫改写。它留成参数是为了
 * 让"这一轮取过外部内容就不写记忆"这条闸门能被单测钉住——`WebPort` 是 core:data 侧
 * 唯一要发真实 HTTP 的能力，不换掉它就没法在不联网的前提下测那条路。
 */
class AppState(
    private val webPort: WebPort = AppWebPort(),
    extensionFactory: (AppState) -> List<AgentExtension> = ::builtinExtensions,
    private val stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = ChatCompletion::stream,
) {

    val agents = mutableStateListOf<Agent>()
    val tasks = mutableStateListOf<AgentTask>()
    val memories = mutableStateListOf<MemoryItem>()
    val skills = mutableStateListOf<Skill>()
    val channels = mutableStateListOf<Channel>()
    val providers = mutableStateListOf<ModelProvider>()
    val groups = mutableStateListOf<AgentGroup>()
    val userInputPort = AppUserInputPort(this)
    val companionVoice = CompanionVoiceSession(this)
    val companion: Agent get() = requireNotNull(agent(BuiltInCompanion.ID))

    /** 平台与业务扩展集合，各扩展独立声明工具与 Prompt 贡献。 */
    private val extensionSet = AgentExtensions(extensionFactory(this))
    val extensions: List<AgentExtension> get() = extensionSet.installed

    var companionDarkMode by mutableStateOf<Boolean?>(null)
        private set

    fun setCompanionAppearance(dark: Boolean?) {
        companionDarkMode = dark
        SettingsStore.put("companionDarkMode", dark?.toString().orEmpty())
    }

    private val conversations = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    private val busy = mutableStateMapOf<String, Boolean>()

    var responseMode by mutableStateOf(ResponseMode.Standard)
        private set

    /** 工具执行权限策略：Auto（自动）或 OnDemand（按需二次确认）。 */
    var executionPolicy by mutableStateOf(ExecutionPolicy.Auto)
        private set

    /** 语音：引擎偏好、服务商与 Key、离线模型、听写与回复朗读。 */
    val speech = SpeechController()

    var defaultModelId by mutableStateOf("")
        private set
    var searchQuery by mutableStateOf("")

    private var idSeq = 0

    /**
     * 整个 AppState 共用**一个** persister。
     *
     * 它记着"上次写出去的是什么"，才能只写变了的文件——每次新建一个的话这份记忆就没了，
     * 25 个实体文件会每次全量重写，比原来那一份 JSON 还慢。
     */
    private val persister: StatePersister? =
        if (LocalStores.available) StatePersister(LocalStores.current) else null

    private val turnRunner = TurnRunner(
        AgentLoop(
            stream = stream,
            toolExecutor = DefaultToolCallExecutor(confirmation = UserInputToolConfirmation(::describeToolOperation)),
        ),
        describeTool = ::readableLabelFor,
    )

    private fun createToolHost(
        agentId: String,
        conversationKey: String,
        isSuppressed: () -> Boolean,
    ): ToolHost {
        val device = platformDevicePort()
        return ToolHost(
            memory = AppMemoryPort(this, isSuppressed),
            tasks = AppTaskPort(this),
            web = webPort,
            userInput = userInputPort,
            files = AppFilePort(),
            commands = platformCommandPort(),
            search = AppSearchPort(),
            skills = AppSkillPort(this, isSuppressed),
            browser = AppBrowserPort(webPort),
            mcp = platformMcpManager(),
            device = device,
            permissions = PermissionRequests,
            executionPolicy = executionPolicy,
            agentId = agentId,
            conversationKey = conversationKey,
            onTurnFinished = { device.finishOperationSession() },
        )
    }

    /**
     * 上次启动有几个文件没能用（坏掉的已经挪到 `backups/broken/`）。
     *
     * 以前这类事是静默的；现在至少能显示出来，让"内容不见了"变成"内容坏了，在这"。
     */
    var loadIssues by mutableStateOf(0)
        private set

    init {
        // 内置服务商是结构不是内容：用户配的 Key 和模型清单存在 SettingsStore 里，
        // 这一层每次启动都要重建，否则走存档还原时服务商列表是空的。
        Seed.installProviders(this)

        // 演示内容（智能体、任务、记忆…）是另一回事：有存档就还原，没有才灌种子。
        // 这一步是"删掉的会话冷启又回来"的根因所在——原先每次启动都无条件灌种子，
        // 用户的删除自然就白删了。
        //
        // 存档现在是目录树；`fromLegacyFile` 表示这次读到的还是旧的那份 `state.json`，
        // 树还没建——下面补的那次落盘就是迁移本身。
        val loaded = persister?.load()

        if (loaded != null) {
            // id 计数器（idSeq）不落盘，重启后从 0 开始，新消息就会拿到跟存档里
            // 已有消息相同的 id——列表里出现重复 key，LazyColumn 会直接崩。
            // 所以还原前先修一遍：计数器顶到已有 id 之上，已经撞上的重新编号。
            val (saved, counter) = normalizeIds(loaded.state)
            idSeq = counter
            loadIssues = loaded.issues

            agents.addAll(saved.agents)
            tasks.addAll(saved.tasks)
            memories.addAll(saved.memories)
            // 加技能正文之前的存档里，内置技能的 SKILL.md 只有 frontmatter：补上正文，否则老用户的
            // 技能永远是空壳。只补空的——用户自己写过正文的不覆盖。
            //
            // 来源也在这里补：单文件 state.json 迁移过来的技能没有 source 字段，反序列化成了默认的
            // Imported。真导入的技能一定带着原文（document），所以"Imported、没有原文、id 是内置的"
            // 只可能是迁移来的内置技能。不改的话它会被当成导入技能按原文冻住，内置说明再也更新不到。
            val backfilledSkills = saved.skills.map { skill ->
                val builtIn = Seed.builtInSkillInstructions[skill.id]
                var fixed = skill
                if (builtIn != null && fixed.source == SkillSource.Imported && fixed.document.isBlank()) {
                    fixed = fixed.copy(source = SkillSource.BuiltIn)
                }
                if (builtIn != null && fixed.instructions.isBlank()) fixed = fixed.copy(instructions = builtIn)
                fixed
            }
            skills.addAll(backfilledSkills)
            channels.addAll(saved.channels)
            groups.addAll(saved.groups)
            var removedSeedMessages = false
            saved.conversations.forEach { (agentId, messages) ->
                // 旧版本曾给内置智能体写入 seed-m* 演示消息；升级后也清掉，保持首次会话干净。
                val cleaned = messages.filterNot { it.id.startsWith("seed-") }
                removedSeedMessages = removedSeedMessages || cleaned.size != messages.size
                messagesOf(agentId).addAll(cleaned)
            }

            // 修过 id 或清掉旧种子消息就顺手落盘，否则这份存档每次启动都要再处理一遍。
            // 必须等上面填完再写——persistState 是从这些列表里取的，提前调会写出空存档。
            if (saved != loaded.state || loaded.fromLegacyFile || removedSeedMessages || backfilledSkills != saved.skills) {
                persistState()
            }
        } else {
            Seed.populateDemo(this)
            // 首次运行立刻落盘：否则用户这次删的东西，下次启动又会被种子灌回来
            persistState()
        }

        // 兜底默认模型取第一家的第一个；没有服务商时留空，交给 SettingsStore 里用户自己的选择
        providers.firstOrNull()?.let { defaultModelId = modelKey(it.id, 0) }
        restoreSettings()
        companionDarkMode = SettingsStore.get("companionDarkMode")?.toBooleanStrictOrNull()
        // Upgrade old saves additively; never replace any existing agents or companion edits.
        if (agent(BuiltInCompanion.ID) == null) {
            agents.add(BuiltInCompanion.create(defaultModelId))
            persistState()
        }

        // 注册系统级后台唤醒处理器：操作系统在后台或锁屏被 Alarm/WorkManager 触发时调用
        WakeupTrigger.registerHandler {
            runDueTasksAndNotify()
        }
        syncNextSystemWakeup()
    }

    /** 把当前状态落盘。没装存储实现（iOS）时整段跳过。 */
    private fun persistState() {
        persister?.save(
            PersistedState(
                agents = agents.toList(),
                tasks = tasks.toList(),
                memories = memories.toList(),
                skills = skills.toList(),
                channels = channels.toList(),
                groups = groups.toList(),
                conversations = conversations.mapValues { (_, list) -> list.toList() },
            )
        )
    }

    fun nextId(prefix: String): String = "$prefix-${++idSeq}"

    /**
     * 修正存档里的 id。
     *
     * [idSeq] 只是个内存里的自增数，不落盘，所以每次冷启都从 0 开始——新消息于是会拿到
     * 跟存档里已有消息**相同**的 id。界面上消息列表用 id 当 key，重复 key 会让
     * LazyColumn 直接抛异常。单聊同样会中，只是群聊一次生成好几条，更早撞上。
     *
     * 这里做两件事：把计数器顶到已有 id 的最大值之上（以后不再撞）；
     * 把**已经撞上的**重新编号（已经坏掉的存档也得能打开）。重编号只改身份不改内容。
     *
     * 返回修正后的存档和修正后的计数器。
     */
    private fun normalizeIds(state: PersistedState): Pair<PersistedState, Int> {
        var counter = 0

        fun bump(id: String) {
            id.substringAfterLast('-').toIntOrNull()?.let { if (it > counter) counter = it }
        }

        val agents = state.agents.onEach { bump(it.id) }
        val tasks = state.tasks.onEach { bump(it.id) }
        val memories = state.memories.onEach { bump(it.id) }
        val skills = state.skills.onEach { bump(it.id) }
        val channels = state.channels.onEach { bump(it.id) }
        val groups = state.groups.onEach { bump(it.id) }

        val conversations = state.conversations.mapValues { (_, messages) ->
            val seen = mutableSetOf<String>()
            messages.map { message ->
                if (seen.add(message.id)) {
                    bump(message.id)
                    message
                } else {
                    val fresh = "m-${++counter}"
                    seen.add(fresh)
                    message.copy(id = fresh)
                }
            }
        }

        return PersistedState(
            agents = agents,
            tasks = tasks,
            memories = memories,
            skills = skills,
            channels = channels,
            groups = groups,
            conversations = conversations,
        ) to counter
    }

    // ── 会话查询 ────────────────────────────────

    /**
     * 有几段**有内容的**会话（单聊 + 群聊）。设置页的统计用。
     *
     * 只数非空的：清空过的对话在存档里还会留一个空文件（那是"这条对话存在过"的记录），
     * 但对着用户说"你有 12 段会话"时，空的不该算进去。
     */
    val conversationCount: Int get() = conversations.count { it.value.isNotEmpty() }

    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id }

    fun deleteAgent(id: String) {
        if (id == BuiltInCompanion.ID) return
        agents.removeAll { it.id == id }
        conversations.remove(id)
        // 人删了，群成员里也要摘掉——否则群的成员计数对不上，
        // 编排时还得靠 mapNotNull 兜底，等于把一个数据问题藏在运行期。
        groups.indices.forEach { index ->
            val group = groups[index]
            if (id in group.memberIds) {
                groups[index] = group.copy(memberIds = group.memberIds - id)
            }
        }
        persistState()
    }

    fun setAgentPinned(id: String, pinned: Boolean) {
        val i = agents.indexOfFirst { it.id == id }
        if (i >= 0) agents[i] = agents[i].copy(pinned = pinned)
        persistState()
    }

    // ── 群组 ────────────────────────────────────

    fun group(id: String): AgentGroup? = groups.firstOrNull { it.id == id }

    fun newGroupId(): String = nextId("g")

    /** 群的讨论记录。群复用了智能体的那套会话容器，key 加前缀区分。 */
    fun messagesOfGroup(groupId: String): SnapshotStateList<ChatMessage> =
        messagesOf(GroupTalk.key(groupId))

    fun lastGroupMessage(groupId: String): ChatMessage? = lastMessage(GroupTalk.key(groupId))

    fun isGroupBusy(groupId: String): Boolean = isBusy(GroupTalk.key(groupId))

    /** 删掉群里的一条消息。群会话的 key 只有本模块知道，所以从界面来的删除走这里。 */
    fun removeGroupMessage(groupId: String, messageId: String) {
        removeMessage(GroupTalk.key(groupId), messageId)
    }

    /** 群成员，按群设置里的顺序；已经被删除的智能体自动略过。 */
    fun membersOf(group: AgentGroup): List<Agent> = group.memberIds.mapNotNull { agent(it) }

    /** 一次提问要花掉多少次模型调用：成员数 × 轮数。界面上要提前告诉用户。 */
    fun callsPerMessage(group: AgentGroup): Int = membersOf(group).size * group.rounds

    /** 群里有没有至少一位成员配好了模型——没有的话界面要给出配置入口。 */
    fun groupReady(group: AgentGroup): Boolean = membersOf(group).any { endpointFor(it) != null }

    /** 新建或更新一个群。界面只调这一个方法，落盘由这里负责。 */
    fun saveGroup(group: AgentGroup) {
        val index = groups.indexOfFirst { it.id == group.id }
        if (index >= 0) groups[index] = group else groups.add(group)
        persistState()
    }

    fun setGroupPinned(id: String, pinned: Boolean) {
        val index = groups.indexOfFirst { it.id == id }
        if (index >= 0) groups[index] = groups[index].copy(pinned = pinned)
        persistState()
    }

    fun deleteGroup(id: String) {
        groups.removeAll { it.id == id }
        conversations.remove(GroupTalk.key(id))
        persistState()
    }

    fun messagesOf(agentId: String): SnapshotStateList<ChatMessage> =
        conversations.getOrPut(agentId) { mutableStateListOf() }

    fun isBusy(agentId: String): Boolean = busy[agentId] == true

    fun lastMessage(agentId: String): ChatMessage? = conversations[agentId]?.lastOrNull()

    /** 产物元数据随聊天记录保存；按 id 查找供聊天链接与通知落地页使用。 */
    fun artifactById(id: String): Artifact? =
        conversations.values.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { (it.body as? MessageBody.Delivered)?.artifact }
            .firstOrNull { it.id == id }

    fun readArtifactText(artifact: Artifact): String? {
        val agentId = artifact.agentId ?: return null
        val filePath = artifact.filePath ?: return null
        return AppFilePort().read(agentId, filePath)
    }

    fun readArtifactBytes(artifact: Artifact): ByteArray? {
        val agentId = artifact.agentId ?: return null
        val filePath = artifact.filePath ?: return null
        return AppFilePort().readBytes(agentId, filePath)
    }

    /** 删掉对话里的某一条消息。 */
    fun removeMessage(agentId: String, messageId: String) {
        conversations[agentId]?.removeAll { it.id == messageId }
        persistState()
    }

    /**
     * 清空这条会话的全部聊天记录。
     *
     * 只清对话，**不动记忆**：记忆是智能体"认识你"的部分，是另一回事，
     * 清聊天记录不该顺手把它一起抹掉。
     */
    fun clearMessages(agentId: String) {
        conversations[agentId]?.clear()
        persistState()
    }

    fun tasksOf(agentId: String): List<AgentTask> = tasks.filter { it.agentId == agentId }

    fun memoriesOf(agentId: String): List<MemoryItem> = memories.filter { it.agentId == agentId }

    /**
     * 按关键词检索记忆。给 recall_memory 工具用。
     *
     * 一期没有向量检索——记忆条数本来就少，关键词匹配够用。
     * 参数是 query 而不是 id，是为了将来换成向量检索时接口不用改。
     */
    fun recallMemories(agentId: String, query: String, limit: Int): List<MemoryItem> {
        val pool = memoriesOf(agentId)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return pool.take(limit)

        return pool
            .map { it to scoreMemory(it, needle) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** 命中字数。粗糙但够用，而且结果稳定可测。 */
    private fun scoreMemory(item: MemoryItem, needle: String): Int {
        val haystack = item.text.lowercase()
        // 整串命中给高分，否则按逐字命中计数
        return if (haystack.contains(needle)) 100 + needle.length
        else needle.count { it in haystack }
    }

    fun activeChannels(): List<Channel> = channels.filter { it.connected }

    fun activeChannelLabel(): String {
        val active = activeChannels()
        return if (active.isEmpty()) "未连接渠道" else "已连接${active.joinToString("、") { it.name }}"
    }

    /** 列表页显示的消息摘要——取最有信息量的一条，而不是时间戳。 */
    fun previewOf(message: ChatMessage): String = when (val body = message.body) {
        is MessageBody.Text -> body.text.ifBlank { "正在回复…" }
        is MessageBody.Image -> buildString {
            append("[图片")
            if (body.assets.size > 1) append(" ×").append(body.assets.size)
            append(']')
            if (body.caption.isNotBlank()) append(' ').append(body.caption)
        }

        is MessageBody.Notice -> body.text
        is MessageBody.Execution -> "正在处理…"
        is MessageBody.Draft -> "任务草稿：${body.draft.title}"
        is MessageBody.Created -> "已创建任务 · ${body.task.title}"
        is MessageBody.Delivered -> "产物 · ${body.artifact.title}"
        is MessageBody.MemoryNote -> body.text
        is MessageBody.Skipped -> body.reason.ifBlank { "跳过了本轮" }
        is MessageBody.Question -> "提问 · ${body.question}"
    }

    // ── 模型服务 ────────────────────────────────

    fun providerById(id: String): ModelProvider? = providers.firstOrNull { it.id == id }

    fun modelKey(providerId: String, index: Int): String = "$providerId:$index"

    /** 把 "provider:index" 解析回服务商与该服务商下的模型名。 */
    fun resolveModel(modelId: String): Pair<ModelProvider, String>? {
        val separator = modelId.lastIndexOf(':')
        if (separator <= 0) return null
        val provider = providerById(modelId.substring(0, separator)) ?: return null
        val index = modelId.substring(separator + 1).toIntOrNull() ?: return null
        val model = provider.models.getOrNull(index) ?: return null
        return provider to model
    }

    fun modelLabel(modelId: String): String = resolveModel(modelId)?.second ?: modelId

    fun configuredProviders(): List<ModelProvider> = providers.filter { it.configured }

    /** 这个智能体当前能不能真正对话——不能的话界面要给出明确的去配置入口。 */
    fun endpointFor(agent: Agent): LlmEndpoint? =
        endpointFor(agent, systemPromptFor(agent, toolsAvailable = true), toolsAvailable = true)

    /**
     * 同上，但由调用方给系统提示。群聊走这条：模型与 Key 的解析完全一样，
     * 只是提示里要写群上下文而不是单聊人设。
     *
     * [toolsAvailable] 决定这次请求带不带工具定义。**现在是三个调用点全传 true**
     * ——单聊、群聊、定时执行都走工具循环了。这个开关留着，是给降级路径
     * （服务商拒收 `tools` 字段时退回文本块协议）用的，那个还没做；
     * 传 false 时提示与请求**两头**都不带工具，两套指令才不会同时给出去。
     */
    fun endpointFor(
        agent: Agent,
        systemPrompt: String,
        toolsAvailable: Boolean = true,
    ): LlmEndpoint? {
        val (provider, model) = resolveModel(agent.modelId) ?: return null
        if (!provider.configured) return null
        // 地址按当前协议取：同一家两种协议的路径往往不同
        val baseUrl = provider.baseUrlFor(provider.protocol) ?: return null
        return LlmEndpoint(
            protocol = provider.protocol,
            baseUrl = baseUrl,
            apiKey = provider.apiKey,
            model = model,
            systemPrompt = systemPrompt,
            tools = if (toolsAvailable) registryFor(agent).definitions() else emptyList(),
        )
    }

    /**
     * 这个智能体能用哪些工具。由已注册的 [extensions] 按需提供，
     * 动态连接的 MCP 工具也由 McpExtension 贡献，未装配该扩展时不暴露其工具。
     */
    internal fun registryFor(agent: Agent): ToolRegistry {
        return extensionSet.registryFor(agent)
    }

    fun updateProvider(updated: ModelProvider) {
        val index = providers.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        providers[index] = updated
        persistProvider(updated)

        // 换掉模型列表后，原先选中的模型可能已经不存在了，回退到第一个可用的。
        if (resolveModel(defaultModelId) == null) {
            setDefaultModel(modelKey(updated.id, 0))
        }
    }

    /** 新增一个自定义服务商。两种协议的地址分开填，用不到的那个可以留空。 */
    fun addCustomProvider(
        name: String,
        protocol: LlmProtocol,
        openAiBaseUrl: String,
        anthropicBaseUrl: String,
        models: List<String>,
        apiKey: String,
    ): ModelProvider {
        val provider = ModelProvider(
            id = nextId("custom"),
            name = name.trim(),
            protocol = protocol,
            openAiBaseUrl = openAiBaseUrl.trim(),
            anthropicBaseUrl = anthropicBaseUrl.trim(),
            models = models,
            apiKey = apiKey.trim(),
            builtIn = false,
        )
        providers.add(provider)
        persistProvider(provider)

        val ids = readCustomIds()
        writeCustomIds(ids + provider.id)
        return provider
    }

    fun removeProvider(id: String) {
        val provider = providerById(id) ?: return
        if (provider.builtIn) return

        providers.removeAll { it.id == id }
        writeCustomIds(readCustomIds().filterNot { it == id })
        listOf(
            keyOf(id), modelsOf(id), "custom:$id:name", "custom:$id:protocol",
            "custom:$id:baseUrl", "custom:$id:models", "custom:$id:key",
        ).forEach { SettingsStore.remove(it) }

        if (resolveModel(defaultModelId) == null) {
            // 内置服务商删不掉，这里本该不会为空；但"删一个服务商"不该变成崩溃，
            // 没有可选的就把默认模型留空。
            providers.firstOrNull()?.let { setDefaultModel(modelKey(it.id, 0)) }
        }
    }

    fun setDefaultModel(modelId: String) {
        defaultModelId = modelId
        SettingsStore.put(KEY_DEFAULT_MODEL, modelId)
    }

    /** 注意：不能叫 setResponseMode——那会跟属性自带的 setter 撞 JVM 签名。 */
    fun selectResponseMode(mode: ResponseMode) {
        responseMode = mode
        SettingsStore.put(KEY_RESPONSE_MODE, mode.name)
    }

    /** 切换执行权限策略。自动 vs 按需。 */
    fun selectExecutionPolicy(policy: ExecutionPolicy) {
        executionPolicy = policy
        SettingsStore.put(KEY_EXECUTION_POLICY, policy.name)
    }

    fun setAgentModel(agentId: String, modelId: String) {
        val index = agents.indexOfFirst { it.id == agentId }
        if (index >= 0) agents[index] = agents[index].copy(modelId = modelId)
        persistState()
    }

    // ── 对话 ────────────────────────────────────

    suspend fun send(agentId: String, text: String, images: List<MediaAsset> = emptyList(), readAloud: Boolean = false) {
        if (isBusy(agentId)) return
        speech.withReplySpeech(agentId, readAloud, messages = { messagesOf(agentId) }) { replySpeech ->
            sendImpl(agentId, text, images, replySpeech)
        }
    }

    /** Entering a voice conversation explicitly opts into speaking for this turn only. */
    suspend fun sendCompanionVoice(text: String) {
        if (isBusy(BuiltInCompanion.ID)) return
        speech.withReplySpeech(BuiltInCompanion.ID, readAloud = true, forceSpeech = true, messages = { messagesOf(BuiltInCompanion.ID) }) { replySpeech ->
            sendImpl(BuiltInCompanion.ID, text, emptyList(), replySpeech)
        }
    }

    private suspend fun sendImpl(agentId: String, text: String, images: List<MediaAsset>, replySpeech: ReplySpeechSession?) {
        val trimmed = text.trim()
        // 只发图片、不写字，是常见用法，不能拦掉
        if ((trimmed.isEmpty() && images.isEmpty()) || isBusy(agentId)) return

        val agent = agent(agentId) ?: return
        val conversation = messagesOf(agentId)

        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.User,
                body = if (images.isEmpty()) {
                    MessageBody.Text(trimmed)
                } else {
                    MessageBody.Image(assets = images, caption = trimmed)
                },
                timeLabel = "刚刚",
            )
        )

        val endpoint = endpointFor(agent)
        if (endpoint == null) {
            conversation.add(
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Notice(
                        "还没有配置模型服务。去「我的 → 模型服务」选一家，填入 API Key 就能真正对话了。",
                        isError = true,
                    ),
                    timeLabel = "刚刚",
                )
            )
            return
        }

        val placeholder = ChatMessage(
            id = nextId("m"),
            author = Author.Agent,
            body = MessageBody.Text(""),
            timeLabel = "刚刚",
        )
        val slot = TurnConversationSlot(conversation, placeholder, { nextId(it) })
        slot.attach()

        busy[agentId] = true
        val turnResult = try {
            turnRunner.run(
                endpoint = endpoint,
                history = withSkillInvocation(historyFor(agentId)),
                registry = registryFor(agent),
                createHost = { isSuppressed -> createToolHost(agentId, agentId, isSuppressed) },
                onEvent = { event ->
                    when (event) {
                        is TurnEvent.RoundStarted -> replySpeech?.boundary()
                        is TurnEvent.TextChunk -> {
                            slot.onText(event.chunk)
                            replySpeech?.append(event.chunk)
                        }
                        is TurnEvent.StepsChanged -> slot.onSteps(event.steps)
                        is TurnEvent.ArtifactProduced -> Unit
                    }
                },
            )
        } catch (cancelled: TurnCancelledException) {
            slot.onCancelled(cancelled) { c, pos, arts -> appendArtifacts(c, pos, arts) }
            persistState()
            throw cancelled.cause
        } finally {
            busy[agentId] = false
        }

        when (val outcome = turnResult.outcome) {
            is LoopOutcome.Failed -> {
                slot.setNotice(outcome.message, isError = true)
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)
                persistState()
                return
            }
            is LoopOutcome.Stopped -> {
                if (turnResult.text.isEmpty()) {
                    slot.setNotice(outcome.reason)
                } else {
                    slot.addNoticeAfter(outcome.reason)
                }
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)
                persistState()
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = turnResult.text
        if (raw.isBlank() && turnResult.artifacts.isEmpty()) {
            slot.setNotice("模型没有返回内容", isError = true)
            return
        }

        // 模型如果判断出这是可托管的任务，会附一个任务块——把它变成草稿卡片。
        // 但这个智能体没开任务能力时，即便模型多吐了块，也只把块抹掉，不给用户看原始 JSON。
        val reply = resolveReply(raw, agent.canScheduleTasks)
        val spoken = when (reply) {
            is ReplyOutcome.Plain -> reply.text
            is ReplyOutcome.AsTask -> reply.text
        }

        // 记忆块从剥完任务块的正文里再剥一层：两件事可以同时发生
        //（「以后每周一提醒我复盘」既可以建任务，也值得记住这个习惯）。
        val memory = MemoryBlock.extract(spoken)
        val answer = when {
            memory == null && spoken.isBlank() && turnResult.artifacts.isNotEmpty() ->
                "已生成文件，见下方预览卡片。"
            memory == null -> spoken
            // 只回了一个记忆块、没有正文时补一句，否则气泡会是空的
            memory.remainingText.isBlank() -> "好的，我记下了。"
            else -> memory.remainingText
        }
        slot.setReply(MessageBody.Text(answer))

        if (reply is ReplyOutcome.AsTask) {
            conversation.add(
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Draft(reply.draft),
                    timeLabel = "刚刚",
                )
            )
        }

        // 落库之后在对话里留一张卡片：记忆是最容易变成隐形功能的能力，
        // 不说一声的话，用户根本不知道它记住了什么。
        //
        // **但这一轮取过网页就整轮不写**：网页正文是别人写的内容，里面可以是
        // "用户喜欢简洁回答"这种看起来像事实的指令。它随历史进了模型视野之后，
        // 模型可能把同一轮里被诱导出的话讲成"用户说的"，于是被当成事实记下来，
        // 而用户永远不会知道。同轮有一次不可信的外部抓取，本轮记忆就整轮不作数——
        // 宁可少记一次，也不要把别人写的文本沉淀成关于用户的"事实"。
        //
        //（这里堵的是**文本块**那条写记忆的路；模型改用 `remember` 工具写的那条，
        //  由 AppMemoryPort 读同一个标志来堵。两条读同一个来源，才是一道闸门。）
        if (!turnResult.externalContextObserved) {
            memory?.items?.forEach { item ->
                addMemory(agent.id, item.category, item.text)
                conversation.add(
                    ChatMessage(
                        id = nextId("m"),
                        author = Author.Agent,
                        body = MessageBody.MemoryNote(item.text),
                        timeLabel = "刚刚",
                    )
                )
            }
        }

        appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)

        // 一轮回复结束才落盘：流式过程中每个字都写一次磁盘没有必要
        persistState()
    }

    /** 用户点「创建」才真正落一条任务——草稿只是建议。 */
    fun confirmDraft(agentId: String, draft: TaskDraft): Boolean {
        schedulerBlockMessage()?.let { message ->
            messagesOf(agentId).add(
                ChatMessage(nextId("m"), Author.Agent, MessageBody.Notice(message, isError = true), "刚刚")
            )
            persistState()
            return false
        }
        // 排期统一走 scheduled：它同时算出"给人看的文案"和"调度用的时刻"
        val task = scheduled(
            draft.toTask(id = nextId("task"), agentId = agentId, nextRunLabel = "")
        )
        tasks.add(0, task)
        messagesOf(agentId).add(
            ChatMessage(
                id = nextId("m"),
                author = Author.Agent,
                body = MessageBody.Created(task),
                timeLabel = "刚刚",
            )
        )
        persistState()
        syncNextSystemWakeup()
        return true
    }

    /** 向指定智能体会话追加一张待作答的提问卡片。 */
    fun postQuestion(
        agentId: String,
        questionId: String,
        question: String,
        options: List<String>,
        placeholder: String?,
    ) {
        val conversation = messagesOf(agentId)
        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.Agent,
                body = MessageBody.Question(
                    id = questionId,
                    question = question,
                    options = options,
                    placeholder = placeholder,
                    selectedAnswer = null,
                ),
                timeLabel = "刚刚",
            )
        )
    }

    /**
     * 用户在提问卡片上完成了单选或输入提交。
     *
     * 1. 唤醒挂起的工具执行协程；
     * 2. 将消息卡片更新为已作答只读态；
     * 3. 触发持久化。
     */
    fun answerQuestion(agentId: String, questionId: String, answer: String): Boolean {
        val completed = userInputPort.submitAnswer(questionId, answer)
        if (!completed) return false

        val conversation = messagesOf(agentId)
        val index = conversation.indexOfFirst {
            (it.body as? MessageBody.Question)?.id == questionId
        }
        if (index >= 0) {
            val old = conversation[index].body as MessageBody.Question
            conversation[index] = conversation[index].copy(
                body = old.copy(selectedAnswer = answer)
            )
        }
        persistState()
        return true
    }

    fun cancelQuestion(questionId: String) {
        userInputPort.cancelQuestion(questionId)
    }

    // ── 群组讨论 ────────────────────────────────

    /**
     * 在群里发一条消息，随后成员依次发言。
     *
     * 讨论是**串行**的：一个成员收完流才轮到下一个，所以后面发言的人天然看得到前面刚说的话，
     * 讨论就是这样串起来的。一条消息的真实成本 = 成员数 × 轮数 次模型调用。
     */
    suspend fun sendToGroup(groupId: String, text: String, images: List<MediaAsset> = emptyList(), readAloud: Boolean = false) {
        val key = GroupTalk.key(groupId)
        if (isBusy(key)) return
        speech.withReplySpeech(key, readAloud, messages = { messagesOf(key) }) { replySpeech ->
            sendToGroupImpl(groupId, text, images, replySpeech)
        }
    }

    private suspend fun sendToGroupImpl(groupId: String, text: String, images: List<MediaAsset>, replySpeech: ReplySpeechSession?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return

        val group = group(groupId) ?: return
        val key = GroupTalk.key(groupId)
        if (isBusy(key)) return

        val conversation = messagesOf(key)
        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.User,
                body = if (images.isEmpty()) {
                    MessageBody.Text(trimmed)
                } else {
                    MessageBody.Image(assets = images, caption = trimmed)
                },
                timeLabel = "刚刚",
            )
        )

        val members = membersOf(group)
        if (members.isEmpty()) {
            conversation.add(notice("这个群还没有成员。去群设置里加几位智能体再试。"))
            persistState()
            return
        }
        if (members.none { endpointFor(it) != null }) {
            conversation.add(
                notice("群里还没有可用的模型服务。去「我的 → 模型服务」配一家，填入 API Key 就能讨论了。")
            )
            persistState()
            return
        }

        // @ 谁就只叫谁说；没 @ 就按顺序轮流。被 @ 的人还需要在提示里被点明这次是找他的。
        val addressed = GroupTalk.mentionedIds(trimmed, members)
        val speakers = if (addressed.isEmpty()) members else members.filter { it.id in addressed }

        busy[key] = true
        try {
            for (round in 1..group.rounds) {
                // 用户点了停止：不再进入下一轮
                currentCoroutineContext().ensureActive()
                for (member in speakers) {
                    currentCoroutineContext().ensureActive()
                    speakOnce(key, group, member, round, members, addressed = member.id in addressed, replySpeech = replySpeech)
                }
            }
        } finally {
            busy[key] = false
            // 整场讨论结束才落盘一次，与单聊一致
            persistState()
        }
    }

    /** 群里一位成员发言一次。跳过、失败、被停止都在这里落地。 */
    private suspend fun speakOnce(
        key: String,
        group: AgentGroup,
        member: Agent,
        round: Int,
        roster: List<Agent>,
        addressed: Boolean = false,
        replySpeech: ReplySpeechSession? = null,
    ) {
        val conversation = messagesOf(key)
        val endpoint = endpointFor(
            member,
            GroupTalk.promptFor(group, member, round, roster, responseMode, addressed = addressed),
        )
        if (endpoint == null) {
            // 个别成员没配模型：记一条说明，讨论继续，不因此中断整场
            conversation.add(
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Notice("${member.name} 没有可用的模型，这一轮先跳过。", isError = true),
                    timeLabel = "刚刚",
                    speakerAgentId = member.id,
                    roundIndex = round,
                )
            )
            return
        }

        // 历史要在占位消息之前取，否则这句空发言会被带进上下文
        val history = withSkillInvocation(GroupTalk.historyFor(conversation, self = member, roster = roster))

        val placeholder = ChatMessage(
            id = nextId("m"),
            author = Author.Agent,
            body = MessageBody.Text(""),
            timeLabel = "刚刚",
            speakerAgentId = member.id,
            roundIndex = round,
        )
        val slot = TurnConversationSlot(
            conversation = conversation,
            placeholder = placeholder,
            nextId = { nextId(it) },
            speakerAgentId = member.id,
            roundIndex = round,
        )
        slot.attach()

        val turnResult = try {
            turnRunner.run(
                endpoint = endpoint,
                history = history.toMutableList(),
                registry = registryFor(member),
                createHost = { isSuppressed -> createToolHost(member.id, key, isSuppressed) },
                onEvent = { event ->
                    when (event) {
                        is TurnEvent.RoundStarted -> replySpeech?.boundary()
                        is TurnEvent.TextChunk -> {
                            slot.onText(event.chunk)
                            replySpeech?.append(event.chunk)
                        }
                        is TurnEvent.StepsChanged -> slot.onSteps(event.steps)
                        is TurnEvent.ArtifactProduced -> Unit
                    }
                },
            )
        } catch (cancelled: TurnCancelledException) {
            slot.onCancelled(cancelled) { c, pos, arts -> appendArtifacts(c, pos, arts, member.id, round) }
            throw cancelled.cause
        }

        when (val outcome = turnResult.outcome) {
            is LoopOutcome.Failed -> {
                slot.setNotice(outcome.message, isError = true)
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts, member.id, round)
                return
            }
            is LoopOutcome.Stopped -> {
                if (turnResult.text.isEmpty()) {
                    slot.setNotice(outcome.reason)
                } else {
                    slot.addNoticeAfter(outcome.reason)
                }
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts, member.id, round)
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = turnResult.text
        if (raw.isBlank() && turnResult.artifacts.isEmpty()) {
            slot.setNotice("模型没有返回内容", isError = true)
            return
        }

        if (raw.isBlank()) {
            slot.setReply(MessageBody.Text("已生成文件，见下方预览卡片。"))
        } else {
            when (val resolved = resolveGroupReply(raw)) {
                is GroupReplyOutcome.Speak -> slot.setReply(MessageBody.Text(resolved.text))
                is GroupReplyOutcome.Skip -> slot.setReply(MessageBody.Skipped(resolved.reason))
            }
        }
        appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts, member.id, round)
    }

    private fun appendArtifacts(
        conversation: SnapshotStateList<ChatMessage>,
        insertionIndex: Int,
        artifacts: List<Artifact>,
        speakerAgentId: String? = null,
        roundIndex: Int? = null,
    ) {
        artifacts.forEachIndexed { offset, artifact ->
            conversation.add(
                insertionIndex + offset,
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Delivered(artifact),
                    timeLabel = "刚刚",
                    speakerAgentId = speakerAgentId,
                    roundIndex = roundIndex,
                ),
            )
        }
    }

    private fun notice(text: String) = ChatMessage(
        id = nextId("m"),
        author = Author.Agent,
        body = MessageBody.Notice(text, isError = true),
        timeLabel = "刚刚",
    )

    // ── 任务 / 记忆 / 技能 / 渠道 ────────────────

    fun toggleTaskPaused(taskId: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val task = tasks[index]
        tasks[index] = task.copy(
            status = if (task.status == TaskStatus.Active) TaskStatus.Paused else TaskStatus.Active,
        )
        persistState()
    }

    fun removeTask(taskId: String) {
        tasks.removeAll { it.id == taskId }
        persistState()
    }

    fun task(id: String): AgentTask? = tasks.firstOrNull { it.id == id }

    /**
     * 任务详情页保存用。新建和更新走同一条路：列表里没有这条就插到最前面。
     *
     * **下一次执行的时刻和文案在这里统一重算**——用户一改类型或时间，原来那个就是错的。
     */
    fun saveTask(task: AgentTask) {
        val scheduled = scheduled(task)
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) tasks[index] = scheduled else tasks.add(0, scheduled)
        persistState()
        syncNextSystemWakeup()
    }

    /** 编辑页的实时预览：按当前时间算出"下次什么时候跑"。 */
    fun nextRunPreview(recurrence: Recurrence, scheduleLabel: String): String {
        val current = now()
        return describeNextRun(
            nextRunAtFor(recurrence, scheduleLabel, current, zone()),
            recurrence, current, zone(),
        )
    }

    private fun now(): Instant = Clock.System.now()

    private fun zone(): TimeZone = TimeZone.currentSystemDefault()

    /**
     * 给任务排好下一次：调度用的**时刻**（[AgentTask.nextRunAt]）和给人看的**文案**一起算。
     *
     * 两者必须同时更新。只改文案的话，界面上字变了、真正用来判断该不该跑的时刻还是旧的。
     */
    private fun scheduled(task: AgentTask): AgentTask {
        val current = now()
        val at = nextRunAtFor(task.recurrence, task.scheduleLabel, current, zone())
        return task.copy(
            nextRunAt = at?.toEpochMilliseconds(),
            nextRunLabel = describeNextRun(at, task.recurrence, current, zone()),
        )
    }

    // ── 定时执行 ────────────────────────────────

    /**
     * 跑掉到点的任务。界面层每半分钟调一次。
     *
     * **没有平台后台调度**：App 关着的时候不会跑。关着期间刚好错过的那次，会在下次打开时补跑
     * ——补的是"一次"，不会把错过的每一天都补一遍。
     */
    suspend fun runDueTasks() = dueTasksLock.withLock {
        scheduleUnplanned()

        val boundary = now().toEpochMilliseconds()
        // 取快照再跑：跑的过程中任务会被改（改状态、排下一期）
        val due = tasks.filter {
            it.status == TaskStatus.Active && (it.nextRunAt ?: Long.MAX_VALUE) <= boundary
        }
        // 串行跑：每个任务都是一次模型调用，并发发出去既没必要也更容易撞上同一个会话
        due.forEach { runTask(it) }
    }

    /**
     * 界面心跳和系统闹钟会各自调 [runDueTasks]。每一路都先拍一张到点快照再串行跑，
     * [isBusy] 只挡得住正在跑的那个任务：快照里排在后面的任务被另一路跑完、排过期之后，
     * 这一路轮到它时照样会再跑一遍。排队之后，后一路拍快照时前一路已经全部跑完排好。
     * 复现见 TaskRunConcurrencyTest。
     */
    private val dueTasksLock = Mutex()

    /**
     * 计算当前所有活跃任务中，未来最早需要执行的唤醒时间戳（epoch 毫秒）。
     * 若无活跃的未来任务，返回 null。
     */
    internal fun nextSystemWakeupMillis(currentMillis: Long = now().toEpochMilliseconds()): Long? =
        tasks.filter { it.status == TaskStatus.Active }
            .mapNotNull { it.nextRunAt }
            .filter { it > currentMillis }
            .minOrNull()

    /**
     * 将最近的唤醒点同步给操作系统的任务调度器（Android AlarmManager / Desktop 守护线程 / iOS BGTask）。
     */
    fun syncNextSystemWakeup(currentMillis: Long = now().toEpochMilliseconds()) {
        TaskSchedulers.current.scheduleNextWakeup(nextSystemWakeupMillis(currentMillis))
    }

    /**
     * 系统级后台调度唤醒时调用的完整闭环：执行到期任务，跑完后预约下一次系统唤醒。
     */
    suspend fun runDueTasksAndNotify() {
        runDueTasks()
        syncNextSystemWakeup()
    }

    /**
     * 给还没排期的任务补上排期。旧存档里的、以及从对话里建出来的任务都没有 [AgentTask.nextRunAt]。
     *
     * 只排到"将来的下一次"，不因为它是很久以前建的就把错过的那几次全补一遍——
     * 第一次打开新版本时刷出一串执行，比不执行更吓人。
     */
    private fun scheduleUnplanned() {
        tasks.indices.forEach { index ->
            val task = tasks[index]
            val needsSchedule = task.status == TaskStatus.Active &&
                task.nextRunAt == null &&
                task.recurrence != Recurrence.Conditional
            if (needsSchedule) tasks[index] = scheduled(task)
        }
    }

    /** 跑一次任务：让它的智能体真的做一遍，结果落回那条对话。 */
    private suspend fun runTask(task: AgentTask) {
        val agent = agent(task.agentId) ?: return

        // 这个智能体正在跟用户对话时不插队——两路流会往同一条对话里写
        if (isBusy(task.agentId)) return

        // 现在真的走工具循环了，所以要发**带工具**的那一份：提示与请求两头都带工具，
        // 模型才可能真的去查、去记，而不是写一段"这件事大概怎么做"的散文。
        // 早先这里显式传 false，是为了躲"模型拿工具调用作答、而这条路又把 ToolCallsReady
        // 丢掉"的空回复——循环接上之后（它自己消费 ToolCallsReady）那个顾虑就不存在了。
        val endpoint = endpointFor(agent)
        if (endpoint == null) {
            // 没配模型也得把期排下去，否则它会一直停在"到点"，每半分钟重试一次
            reschedule(task, lastRun = "没跑：没有可用的模型")
            return
        }

        val conversation = messagesOf(task.agentId)
        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.Agent,
                body = MessageBody.Notice("定时任务「${task.title}」到点了", isError = false),
                timeLabel = "刚刚",
            )
        )

        val placeholder = ChatMessage(nextId("m"), Author.Agent, MessageBody.Text(""), "刚刚")
        val slot = TurnConversationSlot(conversation, placeholder, { nextId(it) })
        slot.attach()

        // 历史里不含刚加的这两条：Notice 和空文本都会被 historyFor 过滤掉
        val history = historyFor(task.agentId).toMutableList()
        history += LlmTurn(LlmRole.User, taskInstruction(task))

        busy[task.agentId] = true

        // 收尾：把这一期的结果写成"上次执行"的文案，并把期排到下一次。
        // 提前 return 的几条路都要经过它，否则任务会永远停在"到点"。
        fun finish(ran: Boolean, result: String? = null, artifactId: String? = null) {
            val finished = now()
            val stamp = "${describeMoment(finished, finished, zone())} ${if (ran) "已完成" else "执行失败"}"
            val taskIndex = tasks.indexOfFirst { it.id == task.id }
            if (taskIndex >= 0) {
                tasks[taskIndex] = tasks[taskIndex].copy(
                    lastRunResult = result?.take(4_000) ?: if (ran) null else "本次任务执行失败，请打开对话查看详情。",
                    lastArtifactId = artifactId,
                )
            }
            reschedule(task, lastRun = stamp)
        }

        val turnResult = try {
            turnRunner.run(
                endpoint = endpoint,
                history = history,
                registry = registryFor(agent),
                createHost = { isSuppressed -> createToolHost(task.agentId, task.agentId, isSuppressed) },
                onEvent = { event ->
                    when (event) {
                        is TurnEvent.RoundStarted -> Unit
                        is TurnEvent.TextChunk -> slot.onText(event.chunk)
                        is TurnEvent.StepsChanged -> slot.onSteps(event.steps)
                        is TurnEvent.ArtifactProduced -> Unit
                    }
                },
            )
        } catch (cancelled: TurnCancelledException) {
            slot.onCancelled(cancelled) { c, pos, arts -> appendArtifacts(c, pos, arts) }
            finish(ran = false)
            persistState()
            throw cancelled.cause
        } finally {
            busy[task.agentId] = false
        }

        when (val outcome = turnResult.outcome) {
            is LoopOutcome.Failed -> {
                slot.setNotice(outcome.message, isError = true)
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)
                finish(ran = false, artifactId = turnResult.artifacts.lastOrNull()?.id)
                return
            }
            is LoopOutcome.Stopped -> {
                // 上限停下：保留已经说出来的部分，再补一条说明——与 send() 一致
                if (turnResult.text.isEmpty()) {
                    slot.setNotice(outcome.reason)
                } else {
                    slot.addNoticeAfter(outcome.reason)
                }
                // 撞上限**不算跑完**：lastRunLabel 得如实说"执行失败"，
                // 否则用户会以为那次半截的结果是完整的（一次性任务还会因此被标成已完成）。
                appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)
                finish(ran = false, artifactId = turnResult.artifacts.lastOrNull()?.id)
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = turnResult.text
        if (raw.isBlank() && turnResult.artifacts.isEmpty()) {
            slot.setNotice("模型没有返回内容", isError = true)
            finish(ran = false)
            return
        }

        // 任务跑的这一次不该再长出任务草稿卡片——它自己就是一个任务
        val reply = resolveReply(raw, allowTasks = false)
        val spoken = when (reply) {
            is ReplyOutcome.Plain -> reply.text
            is ReplyOutcome.AsTask -> reply.text
        }
        val finalText = spoken.ifBlank { "任务已生成文件，点击下方产物预览。" }
        slot.setReply(MessageBody.Text(finalText))
        appendArtifacts(conversation, slot.nextInsertionIndex, turnResult.artifacts)
        val latestArtifact = turnResult.artifacts.lastOrNull()
        finish(ran = true, result = finalText, artifactId = latestArtifact?.id)
        Notifiers.current.notify(
            id = task.id,
            title = "定时任务「${task.title}」已完成",
            content = finalText.take(120).trim(),
            destination = latestArtifact?.let { NotificationTarget.Artifact(it.id) }
                ?: NotificationTarget.Task(task.id),
        )
    }

    /**
     * 给模型的任务指令。
     *
     * 早先这里是「请把这件事实实在在做一遍」——那是在**求模型表演**，
     * 因为它手上什么都没有。现在有工具了，指令改成指出可用手段。
     */
    private fun taskInstruction(task: AgentTask): String = buildString {
        append("【定时任务】").append(task.title)
        append("\n时间：").append(task.scheduleLabel)
        task.note?.takeIf { it.isNotBlank() }?.let { append("\n备注：").append(it) }
        append("\n\n现在到点了。用你能用的工具把这件事做出来，然后给出结果。")
        append("如果用户要日报、报告或计划，优先调用 create_artifact 生成可预览的 HTML；图片优先交付 SVG 矢量文件；已有图像可以用 image_url 引用。")
        append("如果需要查资料就用 web_fetch；如果这次得到的结论值得长期记住，用 remember 存下来。")
    }

    /** 测试用。 */
    internal fun taskInstructionForTest(task: AgentTask): String = taskInstruction(task)

    /**
     * 收尾：记下"上次"，并把期排到下一次。
     *
     * 一次性任务跑成功就到此为止（状态置为完成，不再排期）；失败或没跑成的仍然留着，
     * 否则用户再也看不到它出过问题。
     */
    private fun reschedule(task: AgentTask, lastRun: String) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        val current = tasks[index]

        val updated = if (current.recurrence == Recurrence.Once && lastRun.endsWith("已完成")) {
            current.copy(
                status = TaskStatus.Done,
                lastRunLabel = lastRun,
                nextRunAt = null,
                nextRunLabel = "已完成",
            )
        } else {
            scheduled(current.copy(lastRunLabel = lastRun))
        }

        tasks[index] = updated
        persistState()
        syncNextSystemWakeup()
    }

    fun completeTask(taskId: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) tasks[index] = tasks[index].copy(status = TaskStatus.Done, lastRunLabel = "刚刚")
        persistState()
    }

    /**
     * 新建一个空白任务，返回它的 id 供界面跳到编辑页。
     * 没有智能体可挂时返回 null。
     */
    /**
     * 新建一个空任务。**只建在内存里、不落盘**——用户可能只是想看看编辑页，
     * 直接写进列表的话，返回时就会留下一条叫"新的托管任务"的垃圾。
     * 落盘交给 [saveTask]。
     */
    fun newTask(agentId: String): AgentTask = AgentTask(
        id = nextId("task"),
        agentId = agentId,
        title = "",
        recurrence = Recurrence.Once,
        scheduleLabel = "",
        nextRunLabel = "",
    )

    fun addMemory(agentId: String, category: MemoryCategory, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        memories.add(0, MemoryItem(nextId("mem"), agentId, category, trimmed))
        persistState()
    }

    /**
     * 从工具建任务。跟 [confirmDraft] 不同的是：**这里不弹确认卡片，直接落库**。
     *
     * 为什么允许：模型的判断由工具描述约束（"只在用户明确要托管时调用"），
     * 而且工具调用对用户是可见的（执行卡片）。草稿卡片那条路保留给文本降级。
     */
    fun createTaskFromTool(
        agentId: String,
        title: String,
        recurrence: Recurrence,
        scheduleLabel: String,
        note: String?,
    ): String {
        checkSchedulerAvailable()
        val id = nextId("task")
        val task = scheduled(
            AgentTask(
                id = id,
                agentId = agentId,
                title = title,
                recurrence = recurrence,
                scheduleLabel = scheduleLabel,
                nextRunLabel = "",
                note = note,
            )
        )
        tasks.add(0, task)
        persistState()
        syncNextSystemWakeup()
        return id
    }

    /** 给模型看的任务摘要，不带 nextRunAt 这种调度字段。 */
    fun taskSummariesFor(agentId: String): List<TaskSummary> =
        tasks.filter { it.agentId == agentId }
            .filter { it.status != TaskStatus.Done }
            .map {
                TaskSummary(
                    id = it.id,
                    title = it.title,
                    recurrence = it.recurrence,
                    scheduleLabel = it.scheduleLabel,
                    nextRunLabel = it.nextRunLabel,
                    status = it.status,
                )
            }

    /** 改任务。**核对 agentId**——模型不该能改到别的智能体的任务。 */
    private fun schedulerBlockMessage(): String? = when (TaskSchedulers.current.status) {
        TaskSchedulerStatus.Available -> null
        TaskSchedulerStatus.Unavailable -> "当前设备不支持后台任务调度，任务无法创建。"
        TaskSchedulerStatus.PermissionRequired ->
            "Android 需要先授予「闹钟和提醒」特殊访问权限（SCHEDULE_EXACT_ALARM）。请到「设置 → 应用 → 星环智能 Agent → 闹钟和提醒」中允许后，再创建定时任务。"
    }

    private fun checkSchedulerAvailable() {
        schedulerBlockMessage()?.let { error(it) }
    }

    fun updateTaskFromTool(
        agentId: String,
        id: String,
        title: String?,
        scheduleLabel: String?,
    ): Boolean {
        if (!scheduleLabel.isNullOrBlank()) checkSchedulerAvailable()
        val index = tasks.indexOfFirst { it.id == id && it.agentId == agentId }
        if (index < 0) return false

        val current = tasks[index]
        val updated = current.copy(
            title = title?.takeIf { it.isNotBlank() } ?: current.title,
            scheduleLabel = scheduleLabel?.takeIf { it.isNotBlank() } ?: current.scheduleLabel,
        )
        // 改了时间就要重排期，否则它还会按旧时刻跑
        tasks[index] = if (scheduleLabel.isNullOrBlank()) updated else scheduled(updated)
        persistState()
        syncNextSystemWakeup()
        return true
    }

    /** 取消任务。**置为 Done 而不是删掉**——删了用户就看不到它出过什么。 */
    fun cancelTaskFromTool(agentId: String, id: String): Boolean {
        val index = tasks.indexOfFirst { it.id == id && it.agentId == agentId }
        if (index < 0) return false
        tasks[index] = tasks[index].copy(status = TaskStatus.Done)
        persistState()
        syncNextSystemWakeup()
        return true
    }

    fun removeMemory(memoryId: String) {
        memories.removeAll { it.id == memoryId }
        persistState()
    }

    fun toggleSkill(skillId: String) {
        val index = skills.indexOfFirst { it.id == skillId }
        if (index < 0) return
        skills[index] = skills[index].copy(installed = !skills[index].installed)
        persistState()
    }

    fun setSkillInstalled(skillId: String, installed: Boolean): Boolean {
        val index = skills.indexOfFirst { it.id == skillId }
        if (index < 0) return false
        if (skills[index].installed != installed) {
            skills[index] = skills[index].copy(installed = installed)
            persistState()
        }
        return true
    }

    fun addSkill(skill: Skill): Boolean {
        if (skills.any { it.id == skill.id }) return false
        skills.add(skill)
        persistState()
        return true
    }

    fun removeSkill(skillId: String): Boolean {
        val removed = skills.removeAll { it.id == skillId }
        if (removed) {
            // SKILL.md 由落盘那一步删；随技能装进来的 references/ 等文件落盘不认识，要在这里清掉
            if (LocalStores.available) {
                LocalStores.current.list(StateTree.skillDir(skillId))
                    .filterNot { it == StateTree.skillFile(skillId) }
                    .forEach { LocalStores.current.delete(it) }
            }
            persistState()
        }
        return removed
    }

    /** 新建或改写一个智能体创建的技能。来源检查在 AppSkillPort 里做，这里只管落地。 */
    fun saveAgentSkill(skill: Skill) {
        val index = skills.indexOfFirst { it.id == skill.id }
        if (index >= 0) skills[index] = skill else skills.add(skill)
        persistState()
    }

    /** 技能目录里除 SKILL.md 以外的文件（相对技能目录的路径），渐进披露的第三层。 */
    fun skillResources(skillId: String): List<String> {
        if (!LocalStores.available) return emptyList()
        val dir = StateTree.skillDir(skillId)
        return LocalStores.current.list(dir)
            .filterNot { it == StateTree.skillFile(skillId) }
            .map { it.removePrefix("$dir/") }
            .sorted()
    }

    /**
     * 用户在消息开头写 `/id`（Claude 的写法）或 `$id`（Codex 的写法）时，把那个技能的说明直接带进这一轮。
     * 不是技能、没启用、或标了 `user-invocable: false` 的，原样返回 null——"/" 开头的普通句子照常发。
     *
     * 只展开发给模型的那一份，对话里显示的还是用户自己打的字。
     */
    internal fun expandSkillInvocation(text: String): String? {
        val match = SKILL_INVOCATION.find(text.trimStart()) ?: return null
        val skill = skills.firstOrNull { it.id == match.groupValues[1] && it.installed && it.userInvocable } ?: return null
        val request = text.trimStart().substring(match.range.last + 1).trim()
        return buildString {
            append("【用户调用了技能「").append(skill.name).append("」（id=").append(skill.id).append("），按下面的说明处理这次请求】\n")
            append(skill.instructions.ifBlank { skill.summary })
            val resources = skillResources(skill.id)
            if (resources.isNotEmpty()) {
                append("\n\n技能附带的文件（需要时用 read_skill_file 读取）：").append(resources.joinToString("、"))
            }
            append("\n\n【用户的请求】\n")
            append(request.ifBlank { "（用户没有补充说明。按技能说明开始；缺信息时先问用户。）" })
        }
    }

    /** 把历史里最后一条用户发言的 `/id` 展开。更早的不展开：每轮都重复带一遍正文太费上下文，模型需要时会再调 use_skill。 */
    private fun withSkillInvocation(history: List<LlmTurn>): MutableList<LlmTurn> {
        val turns = history.toMutableList()
        val last = turns.indexOfLast { it.role == LlmRole.User }
        if (last >= 0) expandSkillInvocation(turns[last].text)?.let { turns[last] = turns[last].copy(text = it) }
        return turns
    }

    fun toggleChannel(channelId: String) {
        val index = channels.indexOfFirst { it.id == channelId }
        if (index < 0) return
        channels[index] = channels[index].copy(connected = !channels[index].connected)
        persistState()
    }

    fun addAgent(agent: Agent) {
        if (agents.any { it.id == agent.id }) return
        agents.add(agent)
        persistState()
    }

    fun updateAgent(agent: Agent) {
        val index = agents.indexOfFirst { it.id == agent.id }
        if (index < 0) return
        agents[index] = agent
        persistState()
    }

    // ── 内部 ────────────────────────────────────

    /**
     * 只把文本与图片发回模型；卡片、回执这些是界面结构，不属于对话内容。
     * 带图的消息即使没有文字也要发——否则模型看不到上一轮用户发的那张图。
     */
    private fun historyFor(agentId: String): List<LlmTurn> =
        messagesOf(agentId)
            .mapNotNull { message ->
                val role = if (message.author == Author.User) LlmRole.User else LlmRole.Assistant
                when (val body = message.body) {
                    is MessageBody.Text ->
                        body.text.takeIf { it.isNotBlank() }?.let { LlmTurn(role, it) }

                    is MessageBody.Image ->
                        LlmTurn(role, body.caption, body.assets)

                    else -> null
                }
            }
            .takeLast(24)

    /**
     * 系统提示的四部分：这个智能体是谁、这次要多快多深、已经记住了什么、以及工具用不了时的降级指令。
     *
     * **任务与记忆的文本块指令是条件注入的**：发得出工具时（[toolsAvailable] 为真），
     * 提示里根本不出现 ` ```task ` / ` ```memory ` 的格式要求。
     * 两套同时给的话模型会两套都用——既调了工具，又附一个文本块，用户看到重复的卡片。
     */
    private fun systemPromptFor(agent: Agent, toolsAvailable: Boolean): String = buildString {
        append("你是「").append(agent.name).append("」。")
        if (agent.persona.isNotBlank()) {
            append(agent.persona.trim().trimEnd('。', '！', '!', '.')).append("。")
        }
        append(
            when (responseMode) {
                ResponseMode.Fast -> "回答尽量简短直接，先给结论，不要客套。"
                ResponseMode.Standard -> "回答先给结论，再给必要的依据。"
                ResponseMode.Deep -> "回答前充分思考，给出推理过程与依据，必要时分点展开。"
            }
        )

        // 由各扩展独立向系统提示中注入其领域上下文（记忆列表、技能目录、设备操作规范等）
        extensionSet.promptSections(agent, toolsAvailable).forEach { section ->
            append("\n\n").append(section)
        }
    }

    /**
     * 测试用。默认按"发得出工具"来拼；显式传 `false` 可以钉住降级路径那半边的行为。
     */
    internal fun systemPromptForTest(agent: Agent, toolsAvailable: Boolean = true): String =
        systemPromptFor(agent, toolsAvailable)

    // ── 持久化 ──────────────────────────────────

    private fun keyOf(id: String) = "apikey:$id"
    private fun modelsOf(id: String) = "models:$id"

    private fun persistProvider(provider: ModelProvider) {
        SettingsStore.put(keyOf(provider.id), provider.apiKey)
        SettingsStore.put(modelsOf(provider.id), encodeList(provider.models))
        if (!provider.builtIn) {
            SettingsStore.put("custom:${provider.id}:name", provider.name)
            SettingsStore.put("custom:${provider.id}:protocol", provider.protocol.name)
            SettingsStore.put("custom:${provider.id}:openAiBaseUrl", provider.openAiBaseUrl)
            SettingsStore.put("custom:${provider.id}:anthropicBaseUrl", provider.anthropicBaseUrl)
            SettingsStore.put("custom:${provider.id}:models", encodeList(provider.models))
            SettingsStore.put("custom:${provider.id}:key", provider.apiKey)
        }
    }

    private fun restoreSettings() {
        SettingsStore.get(KEY_RESPONSE_MODE)
            ?.let { saved -> ResponseMode.entries.firstOrNull { it.name == saved } }
            ?.let { responseMode = it }

        SettingsStore.get(KEY_EXECUTION_POLICY)
            ?.let { saved -> ExecutionPolicy.entries.firstOrNull { it.name == saved } }
            ?.let { executionPolicy = it }

        // 预置模型清单更新后，要把本地缓存的那份丢掉，否则用户永远看不到新模型。
        // API Key 必须保留——只重置模型名。
        val savedSeedVersion = SettingsStore.get(KEY_SEED_VERSION)?.toIntOrNull() ?: 0
        val presetsOutdated = savedSeedVersion < SEED_VERSION

        providers.indices.forEach { index ->
            val provider = providers[index]
            val savedKey = SettingsStore.get(keyOf(provider.id)).orEmpty()
            val savedModels = if (presetsOutdated) {
                emptyList()
            } else {
                decodeList(SettingsStore.get(modelsOf(provider.id)))
            }
            providers[index] = provider.copy(
                apiKey = savedKey,
                models = savedModels.ifEmpty { provider.models },
            )
        }

        SettingsStore.put(KEY_SEED_VERSION, SEED_VERSION.toString())

        readCustomIds().forEach { id ->
            if (providerById(id) != null) return@forEach
            val name = SettingsStore.get("custom:$id:name") ?: return@forEach
            val protocol = SettingsStore.get("custom:$id:protocol")
                ?.let { saved -> LlmProtocol.entries.firstOrNull { it.name == saved } }
                ?: LlmProtocol.OpenAi
            val openAiBaseUrl = SettingsStore.get("custom:$id:openAiBaseUrl").orEmpty()
            val anthropicBaseUrl = SettingsStore.get("custom:$id:anthropicBaseUrl").orEmpty()
            val models = decodeList(SettingsStore.get("custom:$id:models"))
            val apiKey = SettingsStore.get("custom:$id:key").orEmpty()
            providers.add(
                ModelProvider(
                    id = id,
                    name = name,
                    protocol = protocol,
                    openAiBaseUrl = openAiBaseUrl,
                    anthropicBaseUrl = anthropicBaseUrl,
                    models = models,
                    apiKey = apiKey,
                    builtIn = false,
                )
            )
        }

        val savedDefault = SettingsStore.get(KEY_DEFAULT_MODEL)
        if (savedDefault != null && resolveModel(savedDefault) != null) {
            defaultModelId = savedDefault
        }
    }

    private fun readCustomIds(): List<String> = decodeList(SettingsStore.get(KEY_CUSTOM_IDS))

    private fun writeCustomIds(ids: List<String>) {
        SettingsStore.put(KEY_CUSTOM_IDS, encodeList(ids))
    }

    /**
     * 列表按 JSON 数组存。用分隔符拼字符串看着更省事，
     * 但模型名里会出现什么字符不由我们决定，JSON 是唯一不会被撑坏的写法。
     */
    private fun encodeList(values: List<String>): String =
        buildJsonArray { values.forEach { add(it) } }.toString()

    private fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * 回灌进提示词的记忆条数上限。
         *
         * 取最近的那些。记忆只增不减，全塞进去既挤占上下文，也会让半年前的琐事
         * 一直压着这两天的事——那反而不像"记得你"了。
         */
        const val MAX_PROMPT_MEMORIES = 20

        /** 发不出工具时内联进提示的单个技能正文上限，防止一个超长的 SKILL.md 挤掉整段对话。 */
        const val MAX_INLINE_SKILL_CHARS = 1_500

        /**
         * 目录里每个技能的简介上限。规范允许 1024 字，但目录每轮都发，技能一多就很可观；
         * 简介只用来判断"该不该用"，细节在正文里。
         */
        const val MAX_LISTED_SKILL_DESCRIPTION = 200

        /** `/id` 或 `$id` 开头，后面是空白或结束。id 放宽到旧版允许的字符，早先装的技能也能调。 */
        private val SKILL_INVOCATION = Regex("""^[/$]([A-Za-z0-9._-]+)(?=\s|$)""")

        const val KEY_DEFAULT_MODEL = "defaultModel"
        const val KEY_RESPONSE_MODE = "responseMode"
        const val KEY_EXECUTION_POLICY = "executionPolicy"
        const val KEY_CUSTOM_IDS = "customIds"
        const val KEY_SEED_VERSION = "seedVersion"

        /**
         * 预置服务商/模型清单的版本。
         * **每次改动 Seed 里的预置模型，就把这个数字 +1**——
         * 否则老用户本机缓存的那份清单会一直盖住新预设。
         */
        const val SEED_VERSION = 6
    }
}

/** 通过 CompositionLocal 提供状态，避免逐层透传。 */
val LocalAppState = staticCompositionLocalOf<AppState> {
    error("LocalAppState 未提供：请在 App 根部用 CompositionLocalProvider 注入")
}
