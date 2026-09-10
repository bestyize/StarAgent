package com.starringbox.staragent.core.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import com.starringbox.staragent.core.llm.ChatCompletion
import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.model.TaskDraft
import com.starringbox.staragent.core.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * 应用的单一状态源。
 *
 * 对话走 [ChatCompletion] 直连用户自己配置的大模型服务；
 * 模型服务配置、API Key、默认模型都通过 [SettingsStore] 持久化到本机。
 */
class AppState {

    val agents = mutableStateListOf<Agent>()
    val tasks = mutableStateListOf<AgentTask>()
    val memories = mutableStateListOf<MemoryItem>()
    val skills = mutableStateListOf<Skill>()
    val channels = mutableStateListOf<Channel>()
    val providers = mutableStateListOf<ModelProvider>()

    private val conversations = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    private val busy = mutableStateMapOf<String, Boolean>()

    var responseMode by mutableStateOf(ResponseMode.Standard)
        private set
    var defaultModelId by mutableStateOf("")
        private set
    var searchQuery by mutableStateOf("")

    private var idSeq = 0

    init {
        Seed.populate(this)
        defaultModelId = modelKey(providers.first().id, 0)
        restoreSettings()
    }

    fun nextId(prefix: String): String = "$prefix-${++idSeq}"

    // ── 会话查询 ────────────────────────────────

    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id }

    fun messagesOf(agentId: String): SnapshotStateList<ChatMessage> =
        conversations.getOrPut(agentId) { mutableStateListOf() }

    fun isBusy(agentId: String): Boolean = busy[agentId] == true

    fun lastMessage(agentId: String): ChatMessage? = conversations[agentId]?.lastOrNull()

    /** 删掉对话里的某一条消息。 */
    fun removeMessage(agentId: String, messageId: String) {
        conversations[agentId]?.removeAll { it.id == messageId }
    }

    fun tasksOf(agentId: String): List<AgentTask> = tasks.filter { it.agentId == agentId }

    fun memoriesOf(agentId: String): List<MemoryItem> = memories.filter { it.agentId == agentId }

    fun activeChannels(): List<Channel> = channels.filter { it.connected }

    fun activeChannelLabel(): String {
        val active = activeChannels()
        return if (active.isEmpty()) "未连接渠道" else "已连接${active.joinToString("、") { it.name }}"
    }

    /** 列表页显示的消息摘要——取最有信息量的一条，而不是时间戳。 */
    fun previewOf(message: ChatMessage): String = when (val body = message.body) {
        is MessageBody.Text -> body.text.ifBlank { "正在回复…" }
        is MessageBody.Notice -> body.text
        is MessageBody.Execution -> "正在处理…"
        is MessageBody.Draft -> "任务草稿：${body.draft.title}"
        is MessageBody.Created -> "已创建任务 · ${body.task.title}"
        is MessageBody.Delivered -> "产物 · ${body.artifact.title}"
        is MessageBody.MemoryNote -> body.text
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
    fun endpointFor(agent: Agent): LlmEndpoint? {
        val (provider, model) = resolveModel(agent.modelId) ?: return null
        if (!provider.configured) return null
        return LlmEndpoint(
            protocol = provider.protocol,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = model,
            systemPrompt = systemPromptFor(agent),
        )
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

    /** 新增一个自定义服务商，协议由用户二选一。 */
    fun addCustomProvider(
        name: String,
        protocol: LlmProtocol,
        baseUrl: String,
        models: List<String>,
        apiKey: String,
    ): ModelProvider {
        val provider = ModelProvider(
            id = nextId("custom"),
            name = name.trim(),
            protocol = protocol,
            baseUrl = baseUrl.trim(),
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
            setDefaultModel(modelKey(providers.first().id, 0))
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

    fun setAgentModel(agentId: String, modelId: String) {
        val index = agents.indexOfFirst { it.id == agentId }
        if (index >= 0) agents[index] = agents[index].copy(modelId = modelId)
    }

    // ── 对话 ────────────────────────────────────

    suspend fun send(agentId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isBusy(agentId)) return

        val agent = agent(agentId) ?: return
        val conversation = messagesOf(agentId)

        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.User,
                body = MessageBody.Text(trimmed),
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

        val history = historyFor(agentId)
        val placeholder = ChatMessage(
            id = nextId("m"),
            author = Author.Agent,
            body = MessageBody.Text(""),
            timeLabel = "刚刚",
        )
        conversation.add(placeholder)
        val index = conversation.lastIndex

        busy[agentId] = true
        val buffer = StringBuilder()
        var failure: String? = null

        try {
            ChatCompletion.stream(endpoint, history).collect { event ->
                when (event) {
                    is ChatStreamEvent.Delta -> {
                        buffer.append(event.text)
                        conversation[index] = placeholder.copy(body = MessageBody.Text(buffer.toString()))
                    }

                    is ChatStreamEvent.Failed -> failure = event.message
                    ChatStreamEvent.Completed -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            // 用户点了停止：已经收到的部分保留下来，一个字都没有就留个说明。
            if (buffer.isEmpty()) {
                conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            }
            throw cancelled
        } finally {
            busy[agentId] = false
        }

        val failureMessage = failure
        if (failureMessage != null) {
            conversation[index] = placeholder.copy(
                body = MessageBody.Notice(failureMessage, isError = true),
            )
            return
        }

        val raw = buffer.toString()
        if (raw.isBlank()) {
            conversation[index] = placeholder.copy(
                body = MessageBody.Notice("模型没有返回内容", isError = true),
            )
            return
        }

        // 模型如果判断出这是可托管的任务，会附一个任务块——把它变成草稿卡片。
        // 但这个智能体没开任务能力时，即便模型多吐了块，也只把块抹掉，不给用户看原始 JSON。
        when (val outcome = resolveReply(raw, agent.canScheduleTasks)) {
            is ReplyOutcome.Plain ->
                conversation[index] = placeholder.copy(body = MessageBody.Text(outcome.text))

            is ReplyOutcome.AsTask -> {
                conversation[index] = placeholder.copy(body = MessageBody.Text(outcome.text))
                conversation.add(
                    ChatMessage(
                        id = nextId("m"),
                        author = Author.Agent,
                        body = MessageBody.Draft(outcome.draft),
                        timeLabel = "刚刚",
                    )
                )
            }
        }
    }

    /** 用户点「创建」才真正落一条任务——草稿只是建议。 */
    fun confirmDraft(agentId: String, draft: TaskDraft) {
        val task = draft.toTask(
            id = nextId("task"),
            agentId = agentId,
            nextRunLabel = nextRunFor(draft),
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
    }

    // ── 任务 / 记忆 / 技能 / 渠道 ────────────────

    fun toggleTaskPaused(taskId: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val task = tasks[index]
        tasks[index] = task.copy(
            status = if (task.status == TaskStatus.Active) TaskStatus.Paused else TaskStatus.Active,
        )
    }

    fun removeTask(taskId: String) {
        tasks.removeAll { it.id == taskId }
    }

    fun addMemory(agentId: String, category: MemoryCategory, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        memories.add(0, MemoryItem(nextId("mem"), agentId, category, trimmed))
    }

    fun removeMemory(memoryId: String) {
        memories.removeAll { it.id == memoryId }
    }

    fun toggleSkill(skillId: String) {
        val index = skills.indexOfFirst { it.id == skillId }
        if (index < 0) return
        skills[index] = skills[index].copy(installed = !skills[index].installed)
    }

    fun toggleChannel(channelId: String) {
        val index = channels.indexOfFirst { it.id == channelId }
        if (index < 0) return
        channels[index] = channels[index].copy(connected = !channels[index].connected)
    }

    fun addAgent(agent: Agent) {
        agents.add(agent)
    }

    // ── 内部 ────────────────────────────────────

    /** 只把文本类消息发给模型；卡片、回执这些是界面结构，不属于对话内容。 */
    private fun historyFor(agentId: String): List<LlmTurn> =
        messagesOf(agentId)
            .mapNotNull { message ->
                val body = message.body as? MessageBody.Text ?: return@mapNotNull null
                if (body.text.isBlank()) return@mapNotNull null
                LlmTurn(
                    role = if (message.author == Author.User) LlmRole.User else LlmRole.Assistant,
                    text = body.text,
                )
            }
            .takeLast(24)

    /**
     * 系统提示由三部分组成：这个智能体是谁、这次要多快多深、以及要不要带任务识别。
     *
     * 任务识别是**按智能体开关**的：关掉时提示里根本不出现任务指令，
     * 模型也就无从输出任务块——陪聊、答疑类的智能体不该动不动就弹任务卡片。
     */
    private fun systemPromptFor(agent: Agent): String = buildString {
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
        if (agent.canScheduleTasks) {
            append("\n\n")
            append(TaskBlock.INSTRUCTION)
        }
    }

    private fun nextRunFor(draft: TaskDraft): String {
        val time = Regex("""(\d{1,2}:\d{2})""")
            .find(draft.scheduleLabel)
            ?.groupValues
            ?.get(1)
            ?.let { if (it.length == 4) "0$it" else it }
            ?: "09:00"
        return when (draft.recurrence) {
            Recurrence.Once -> "待触发"
            Recurrence.Daily -> "明天 $time"
            Recurrence.Weekly -> "下周一 $time"
            Recurrence.Monthly -> "下月 1 日 $time"
        }
    }

    // ── 持久化 ──────────────────────────────────

    private fun keyOf(id: String) = "apikey:$id"
    private fun modelsOf(id: String) = "models:$id"

    private fun persistProvider(provider: ModelProvider) {
        SettingsStore.put(keyOf(provider.id), provider.apiKey)
        SettingsStore.put(modelsOf(provider.id), encodeList(provider.models))
        if (!provider.builtIn) {
            SettingsStore.put("custom:${provider.id}:name", provider.name)
            SettingsStore.put("custom:${provider.id}:protocol", provider.protocol.name)
            SettingsStore.put("custom:${provider.id}:baseUrl", provider.baseUrl)
            SettingsStore.put("custom:${provider.id}:models", encodeList(provider.models))
            SettingsStore.put("custom:${provider.id}:key", provider.apiKey)
        }
    }

    private fun restoreSettings() {
        SettingsStore.get(KEY_RESPONSE_MODE)
            ?.let { saved -> ResponseMode.entries.firstOrNull { it.name == saved } }
            ?.let { responseMode = it }

        providers.indices.forEach { index ->
            val provider = providers[index]
            val savedKey = SettingsStore.get(keyOf(provider.id)).orEmpty()
            val savedModels = decodeList(SettingsStore.get(modelsOf(provider.id)))
            providers[index] = provider.copy(
                apiKey = savedKey,
                models = savedModels.ifEmpty { provider.models },
            )
        }

        readCustomIds().forEach { id ->
            if (providerById(id) != null) return@forEach
            val name = SettingsStore.get("custom:$id:name") ?: return@forEach
            val protocol = SettingsStore.get("custom:$id:protocol")
                ?.let { saved -> LlmProtocol.entries.firstOrNull { it.name == saved } }
                ?: LlmProtocol.OpenAi
            val baseUrl = SettingsStore.get("custom:$id:baseUrl").orEmpty()
            val models = decodeList(SettingsStore.get("custom:$id:models"))
            val apiKey = SettingsStore.get("custom:$id:key").orEmpty()
            providers.add(
                ModelProvider(id, name, protocol, baseUrl, models, apiKey, builtIn = false)
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
        const val KEY_DEFAULT_MODEL = "defaultModel"
        const val KEY_RESPONSE_MODE = "responseMode"
        const val KEY_CUSTOM_IDS = "customIds"
    }
}

/** 通过 CompositionLocal 提供状态，避免逐层透传。 */
val LocalAppState = staticCompositionLocalOf<AppState> {
    error("LocalAppState 未提供：请在 App 根部用 CompositionLocalProvider 注入")
}
