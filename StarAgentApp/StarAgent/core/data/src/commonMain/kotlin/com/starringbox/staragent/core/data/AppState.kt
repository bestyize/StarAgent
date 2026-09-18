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
import com.starringbox.staragent.core.llm.ModelSynthesizer
import com.starringbox.staragent.core.llm.ModelTranscriber
import com.starringbox.staragent.core.llm.SpeechEndpoint
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.platform.voice.VoiceSource
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
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
import com.starringbox.staragent.core.model.describeMoment
import com.starringbox.staragent.core.model.describeNextRun
import com.starringbox.staragent.core.model.nextRunAtFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
 */
class AppState(
    private val stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = ChatCompletion::stream,
) {

    val agents = mutableStateListOf<Agent>()
    val tasks = mutableStateListOf<AgentTask>()
    val memories = mutableStateListOf<MemoryItem>()
    val skills = mutableStateListOf<Skill>()
    val channels = mutableStateListOf<Channel>()
    val providers = mutableStateListOf<ModelProvider>()
    val groups = mutableStateListOf<AgentGroup>()

    private val conversations = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    private val busy = mutableStateMapOf<String, Boolean>()

    var responseMode by mutableStateOf(ResponseMode.Standard)
        private set

    /** 语音引擎偏好。null = 自动：优先设备能力（不要 Key、不花钱）。 */
    var voiceSource by mutableStateOf<VoiceSource?>(null)
        private set

    /**
     * 用哪家服务商做语音识别。null = 自动（取第一家可用的）。
     *
     * 只管识别。合成一直是自动选的——见 [ttsEndpoint]。
     */
    var speechProviderId by mutableStateOf<String?>(null)
        private set

    /** 识别用哪个模型。null = 用那家的预设。存的是模型名本身，不是下标。 */
    var asrModelId by mutableStateOf<String?>(null)
        private set

    /** 回复要不要念出来。默认关——朗读会真的产生费用，不能默默开始花钱。 */
    var speakReplies by mutableStateOf(false)
        private set
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
            skills.addAll(saved.skills)
            channels.addAll(saved.channels)
            groups.addAll(saved.groups)
            saved.conversations.forEach { (agentId, messages) -> messagesOf(agentId).addAll(messages) }

            // 修过 id 就顺手落盘，否则这份存档每次启动都要重修一遍。
            // 必须等上面填完再写——persistState 是从这些列表里取的，提前调会写出空存档。
            if (saved != loaded.state || loaded.fromLegacyFile) persistState()
        } else {
            Seed.populateDemo(this)
            // 首次运行立刻落盘：否则用户这次删的东西，下次启动又会被种子灌回来
            persistState()
        }

        // 语音的模型后端在这里装：它要用"用户当前配置的服务商"，
        // 所以只有数据层装得了。设备能力那一路由各端入口装（Android 有，桌面没有）。
        Voice.installModel(
            transcriber = ModelTranscriber { asrEndpoint() },
            synthesizer = ModelSynthesizer { ttsEndpoint() },
        )

        // 兜底默认模型取第一家的第一个；没有服务商时留空，交给 SettingsStore 里用户自己的选择
        providers.firstOrNull()?.let { defaultModelId = modelKey(it.id, 0) }
        restoreSettings()
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
    }

    // ── 语音 ────────────────────────────────────

    /**
     * 界面上能出现在「语音识别」页里的服务商：**填了识别模型的那些**。
     *
     * 刻意不筛 Key——没配 Key 的也要列出来，页面才能把"这家能做识别，但你还没配 Key"
     * 说清楚并给一个去配置的入口。只列已配好的话，用户根本不知道 Groq 是个选项。
     */
    fun asrProviders(): List<ModelProvider> = providers.filter { it.asrModel != null }

    /**
     * 用来做**识别**的服务商，按用户在语音识别页里选的那家。
     *
     * [speechProviderId] 为 null（没选过）或指向一家已经不能用的（Key 被清掉、
     * 自定义服务商被删）时，退回第一家可用的——设置项失效不该让语音直接哑掉。
     */
    private fun asrProvider(): ModelProvider? =
        speechProviderId?.let { id -> providers.firstOrNull { it.id == id && it.supportsAsr } }
            ?: providers.firstOrNull { it.supportsAsr }

    /**
     * 识别用的接口信息。
     *
     * 只填 [SpeechEndpoint.asrModel]——**合成那条另算**，见 [ttsEndpoint]。
     * 具体模型按用户选的来；选过的那个后来从清单里消失了（换了预设版本），
     * 退回这家的预设模型。
     */
    fun asrEndpoint(): SpeechEndpoint? {
        val provider = asrProvider() ?: return null
        val baseUrl = provider.baseUrlFor(provider.protocol) ?: return null
        val model = asrModelId?.takeIf { it in provider.asrChoices } ?: provider.asrModel
        return SpeechEndpoint(baseUrl = baseUrl, apiKey = provider.apiKey, asrModel = model)
    }

    /**
     * 合成用的接口信息。取第一家配好 Key 且有合成模型的。
     *
     * **和识别分开取**，这不是重复代码：两条能力并不总在同一家。Groq 只有识别，
     * 合起来取的话，用户把识别设成 Groq 就会连朗读一起失去——
     * 而他并没有要求改朗读。识别是用户选的，合成保持自动。
     */
    fun ttsEndpoint(): SpeechEndpoint? {
        val provider = providers.firstOrNull { it.supportsTts } ?: return null
        val baseUrl = provider.baseUrlFor(provider.protocol) ?: return null
        return SpeechEndpoint(
            baseUrl = baseUrl,
            apiKey = provider.apiKey,
            ttsModel = provider.ttsModel,
            ttsVoice = provider.ttsVoice,
        )
    }

    /** 现在能不能语音输入。界面据此决定麦克风入口显不显示。 */
    val canListen: Boolean get() = Voice.canListen

    /** 现在能不能朗读。 */
    val canSpeak: Boolean get() = Voice.canSpeak

    /** 正在听。界面据此把输入区换成"正在听…"。 */
    val isListening: Boolean get() = Voice.isListening

    suspend fun startListening(): SpeechOutcome<Unit> = Voice.startListening()

    suspend fun stopListening(): SpeechOutcome<String> = Voice.stopListening()

    fun cancelListening() = Voice.cancelListening()

    /** 念一段文字。回复里只有纯文本会被念——卡片不是"话"。 */
    suspend fun speak(text: String): SpeechOutcome<Unit> = Voice.speak(text)

    fun stopSpeaking() = Voice.stopSpeaking()

    /** 注意：不能叫 setVoiceSource——那会跟属性自带的 setter 撞 JVM 签名。 */
    fun selectVoiceSource(source: VoiceSource?) {
        voiceSource = source
        SettingsStore.put(KEY_VOICE_SOURCE, source?.name.orEmpty())
    }

    /**
     * 选做识别的服务商。传 null 回到自动。
     *
     * 换家的同时把模型选择清掉：模型名是**跟着服务商**的，
     * `whisper-large-v3-turbo` 拿到智谱去就是个不存在的模型。
     */
    fun selectSpeechProvider(id: String?) {
        speechProviderId = id
        SettingsStore.put(KEY_SPEECH_PROVIDER, id.orEmpty())
        selectAsrModel(null)
    }

    /** 选识别模型。传 null 回到这家的预设。 */
    fun selectAsrModel(model: String?) {
        asrModelId = model
        SettingsStore.put(KEY_ASR_MODEL, model.orEmpty())
    }

    /**
     * 语音识别现在实际会走哪条路，一句话说清。「我的」页那行副标题用它。
     *
     * 顺序照 [Voice] 的选取逻辑来，否则副标题会跟真实行为不一致——
     * 那比不显示更糟。
     */
    val asrSummary: String
        get() {
            val device = Voice.deviceListenInstalled
            val provider = asrProvider()
            return when {
                voiceSource == VoiceSource.Device && device -> "系统能力"
                voiceSource == VoiceSource.Device -> "系统能力 · 这台设备不支持"
                voiceSource == VoiceSource.Model && provider != null -> provider.name
                voiceSource == VoiceSource.Model -> "大模型 · 待配置"
                device -> "自动 · 优先系统能力"
                provider != null -> "自动 · ${provider.name}"
                else -> "还没有可用的识别"
            }
        }

    /** 同理，不能叫 setSpeakReplies。 */
    fun setReadingReplies(enabled: Boolean) {
        speakReplies = enabled
        SettingsStore.put(KEY_SPEAK_REPLIES, enabled.toString())
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
    fun endpointFor(agent: Agent): LlmEndpoint? = endpointFor(agent, systemPromptFor(agent))

    /**
     * 同上，但由调用方给系统提示。群聊走这条：模型与 Key 的解析完全一样，
     * 只是提示里要写群上下文而不是单聊人设。
     */
    fun endpointFor(agent: Agent, systemPrompt: String): LlmEndpoint? {
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

    fun setAgentModel(agentId: String, modelId: String) {
        val index = agents.indexOfFirst { it.id == agentId }
        if (index >= 0) agents[index] = agents[index].copy(modelId = modelId)
        persistState()
    }

    // ── 对话 ────────────────────────────────────

    suspend fun send(agentId: String, text: String, images: List<MediaAsset> = emptyList()) {
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
            stream(endpoint, history).collect { event ->
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
        val outcome = resolveReply(raw, agent.canScheduleTasks)
        val spoken = when (outcome) {
            is ReplyOutcome.Plain -> outcome.text
            is ReplyOutcome.AsTask -> outcome.text
        }

        // 记忆块从剥完任务块的正文里再剥一层：两件事可以同时发生
        //（「以后每周一提醒我复盘」既可以建任务，也值得记住这个习惯）。
        val memory = MemoryBlock.extract(spoken)
        val answer = when {
            memory == null -> spoken
            // 只回了一个记忆块、没有正文时补一句，否则气泡会是空的
            memory.remainingText.isBlank() -> "好的，我记下了。"
            else -> memory.remainingText
        }
        conversation[index] = placeholder.copy(body = MessageBody.Text(answer))

        if (outcome is ReplyOutcome.AsTask) {
            conversation.add(
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Draft(outcome.draft),
                    timeLabel = "刚刚",
                )
            )
        }

        // 落库之后在对话里留一张卡片：记忆是最容易变成隐形功能的能力，
        // 不说一声的话，用户根本不知道它记住了什么。
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

        // 一轮回复结束才落盘：流式过程中每个字都写一次磁盘没有必要
        persistState()
    }

    /** 用户点「创建」才真正落一条任务——草稿只是建议。 */
    fun confirmDraft(agentId: String, draft: TaskDraft) {
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
    }

    // ── 群组讨论 ────────────────────────────────

    /**
     * 在群里发一条消息，随后成员依次发言。
     *
     * 讨论是**串行**的：一个成员收完流才轮到下一个，所以后面发言的人天然看得到前面刚说的话，
     * 讨论就是这样串起来的。一条消息的真实成本 = 成员数 × 轮数 次模型调用。
     */
    suspend fun sendToGroup(groupId: String, text: String, images: List<MediaAsset> = emptyList()) {
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
                    speakOnce(key, group, member, round, members, addressed = member.id in addressed)
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
        val history = GroupTalk.historyFor(conversation, self = member, roster = roster)

        val placeholder = ChatMessage(
            id = nextId("m"),
            author = Author.Agent,
            body = MessageBody.Text(""),
            timeLabel = "刚刚",
            speakerAgentId = member.id,
            roundIndex = round,
        )
        conversation.add(placeholder)
        // 本成员发言期间不会再往列表里 append，index 是稳的
        val index = conversation.lastIndex

        val buffer = StringBuilder()
        var failure: String? = null
        try {
            stream(endpoint, history).collect { event ->
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
            // 用户点了停止：已经说出的半句留着，再往上抛，让整场讨论一起停下来
            if (buffer.isEmpty()) {
                conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            }
            throw cancelled
        }

        val failureMessage = failure
        if (failureMessage != null) {
            conversation[index] = placeholder.copy(body = MessageBody.Notice(failureMessage, isError = true))
            return
        }

        val raw = buffer.toString()
        if (raw.isBlank()) {
            conversation[index] = placeholder.copy(body = MessageBody.Notice("模型没有返回内容", isError = true))
            return
        }

        conversation[index] = when (val outcome = resolveGroupReply(raw)) {
            is GroupReplyOutcome.Speak -> placeholder.copy(body = MessageBody.Text(outcome.text))
            is GroupReplyOutcome.Skip -> placeholder.copy(body = MessageBody.Skipped(outcome.reason))
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
    suspend fun runDueTasks() {
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
        conversation.add(placeholder)
        val index = conversation.lastIndex

        // 历史里不含刚加的这两条：Notice 和空文本都会被 historyFor 过滤掉
        val history = historyFor(task.agentId) + LlmTurn(LlmRole.User, taskInstruction(task))

        busy[task.agentId] = true
        val buffer = StringBuilder()
        var failure: String? = null
        try {
            stream(endpoint, history).collect { event ->
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
            if (buffer.isEmpty()) conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            throw cancelled
        } finally {
            busy[task.agentId] = false
        }

        val raw = buffer.toString()
        val failureMessage = failure
        val ran = failureMessage == null && raw.isNotBlank()

        conversation[index] = when {
            failureMessage != null ->
                placeholder.copy(body = MessageBody.Notice(failureMessage, isError = true))

            raw.isBlank() ->
                placeholder.copy(body = MessageBody.Notice("模型没有返回内容", isError = true))

            // 任务跑的这一次不该再长出任务草稿卡片——它自己就是一个任务
            else -> when (val outcome = resolveReply(raw, allowTasks = false)) {
                is ReplyOutcome.Plain -> placeholder.copy(body = MessageBody.Text(outcome.text))
                is ReplyOutcome.AsTask -> placeholder.copy(body = MessageBody.Text(outcome.text))
            }
        }

        val finished = now()
        val stamp = "${describeMoment(finished, finished, zone())} ${if (ran) "已完成" else "执行失败"}"
        reschedule(task, lastRun = stamp)
    }

    /** 交给模型的任务指令。带上备注——它本来就是"要提醒自己注意什么"。 */
    private fun taskInstruction(task: AgentTask): String = buildString {
        append("【定时任务】").append(task.title)
        append("\n时间：").append(task.scheduleLabel)
        task.note?.takeIf { it.isNotBlank() }?.let { append("\n备注：").append(it) }
        append("\n\n现在到点了，请把这件事实实在在做一遍，直接给出结果。")
    }

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

    fun toggleChannel(channelId: String) {
        val index = channels.indexOfFirst { it.id == channelId }
        if (index < 0) return
        channels[index] = channels[index].copy(connected = !channels[index].connected)
        persistState()
    }

    fun addAgent(agent: Agent) {
        agents.add(agent)
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
        // 已经记住的事要回灌进来，否则"记住"就只是把它存进了某个用户看不见也问不着的地方。
        // 取最近的若干条而不是全部：记忆会越攒越多，全塞进去既挤占上下文，
        // 也会让早期的琐事一直压着近期的对话。
        val known = memoriesOf(agent.id).take(MAX_PROMPT_MEMORIES)
        if (known.isNotEmpty()) {
            append("\n\n【你已经记得的关于他的事】")
            known.forEach { append('\n').append("- ").append(it.text) }
            append("\n自然地用上这些，不要刻意复述，也不要逐条罗列。")
        }

        if (agent.canScheduleTasks) {
            append("\n\n")
            append(TaskBlock.INSTRUCTION)
        }
        // 记忆不跟着任务开关走：任务是"替不替你干活"，记忆是"认不认识你"，
        // 陪聊型的智能体同样该记得住你说过的事。
        append("\n\n")
        append(MemoryBlock.INSTRUCTION)
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
            SettingsStore.put("custom:${provider.id}:openAiBaseUrl", provider.openAiBaseUrl)
            SettingsStore.put("custom:${provider.id}:anthropicBaseUrl", provider.anthropicBaseUrl)
            SettingsStore.put("custom:${provider.id}:models", encodeList(provider.models))
            SettingsStore.put("custom:${provider.id}:key", provider.apiKey)
        }
    }

    private fun restoreSettings() {
        SettingsStore.get(KEY_VOICE_SOURCE)
            ?.takeIf { it.isNotBlank() }
            ?.let { saved -> VoiceSource.entries.firstOrNull { it.name == saved } }
            ?.let { selectVoiceSource(it) }
        speakReplies = SettingsStore.get(KEY_SPEAK_REPLIES) == "true"

        // 识别服务商与模型。这两个只是"偏好"，指向的东西不在了也不校验——
        // asrEndpoint() 每次都会重新核对一遍，那里退回自动。在这儿校验反而不对：
        // 还原时 providers 的 Key 才刚灌完，此刻的可用性不代表用起来那一刻的。
        speechProviderId = SettingsStore.get(KEY_SPEECH_PROVIDER)?.takeIf { it.isNotBlank() }
        asrModelId = SettingsStore.get(KEY_ASR_MODEL)?.takeIf { it.isNotBlank() }

        SettingsStore.get(KEY_RESPONSE_MODE)
            ?.let { saved -> ResponseMode.entries.firstOrNull { it.name == saved } }
            ?.let { responseMode = it }

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

        const val KEY_DEFAULT_MODEL = "defaultModel"
        const val KEY_RESPONSE_MODE = "responseMode"
        const val KEY_CUSTOM_IDS = "customIds"
        const val KEY_VOICE_SOURCE = "voiceSource"
        const val KEY_SPEECH_PROVIDER = "speechProvider"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_SPEAK_REPLIES = "speakReplies"
        const val KEY_SEED_VERSION = "seedVersion"

        /**
         * 预置服务商/模型清单的版本。
         * **每次改动 Seed 里的预置模型，就把这个数字 +1**——
         * 否则老用户本机缓存的那份清单会一直盖住新预设。
         */
        const val SEED_VERSION = 3
    }
}

/** 通过 CompositionLocal 提供状态，避免逐层透传。 */
val LocalAppState = staticCompositionLocalOf<AppState> {
    error("LocalAppState 未提供：请在 App 根部用 CompositionLocalProvider 注入")
}
