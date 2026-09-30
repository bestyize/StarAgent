package com.starringbox.staragent.core.data

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
import com.starringbox.staragent.core.model.SpeechProvider
import com.starringbox.staragent.core.model.TaskDraft
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import com.starringbox.staragent.core.tools.builtin.BuiltinTools
import com.starringbox.staragent.core.tools.ports.TaskSummary
import com.starringbox.staragent.core.tools.ports.WebPort
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
 *
 * [webPort] 同样是缝，而且**声明在 [stream] 之前**：Kotlin 的尾随 lambda 只绑最后一个参数，
 * `stream` 必须留在末位，`AppState { ... }` 这种现有写法才不会被迫改写。它留成参数是为了
 * 让"这一轮取过外部内容就不写记忆"这条闸门能被单测钉住——`WebPort` 是 core:data 侧
 * 唯一要发真实 HTTP 的能力，不换掉它就没法在不联网的前提下测那条路。
 */
class AppState(
    private val webPort: WebPort = AppWebPort(),
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

    private val conversations = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    private val busy = mutableStateMapOf<String, Boolean>()

    var responseMode by mutableStateOf(ResponseMode.Standard)
        private set

    /** 语音引擎偏好。null = 自动：优先设备能力（不要 Key、不花钱）。 */
    var voiceSource by mutableStateOf<VoiceSource?>(null)
        private set

    /**
     * 用哪家语音服务商。null = 还没选（[activeSpeechProvider] 会退回第一家）。
     *
     * **识别和朗读共用这一条配置**：选了谁、填了谁的 Key，识别就走它；
     * 它要是有合成模型，朗读也走它，没有就退回设备自带。
     */
    var speechProviderId by mutableStateOf<String?>(null)
        private set

    /** 识别用哪个模型。null = 用那家的预设。存的是模型名本身，不是下标。 */
    var asrModelId by mutableStateOf<String?>(null)
        private set

    /**
     * 语音侧的全部候选。常量——每次启动由 [Seed.speechProviders] 重建，之后不变。
     *
     * ⚠️ **声明位置必须在 [init] 之前**：属性初始化器和 init 块按声明顺序交错执行，
     * 而 init 里那句 [restoreSettings] 就要往 [speechKeys] 里写。
     */
    val speechProviders: List<SpeechProvider> = Seed.speechProviders()

    /** 语音侧的 Key，按服务商分开存。理由见 [setSpeechKey]。snapshot map，界面跟着它刷新。 */
    private val speechKeys = mutableStateMapOf<String, String>()

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

        // 「自动 / 系统能力 / 云端识别」这一档也要接过来。
        // [Voice] 刻意不自己存它——上一版两边各存一份、而这边忘了同步，
        // 结果这个选择在真机上一直是失效的。现在这边是唯一的真相。
        Voice.installPreference { voiceSource }

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

    // ── 语音 ────────────────────────────────────

    /**
     * 当前生效的那家语音服务商。
     *
     * [speechProviderId] 为 null（没选过）或指向一家已经没有的（上一版存下来的某个
     * 自定义服务商 id、或者预置里删掉过一家）时，**退回第一家**——
     * 设置项失效不该让语音直接哑掉。
     */
    fun activeSpeechProvider(): SpeechProvider? =
        speechProviderId?.let { id -> speechProviders.firstOrNull { it.id == id } }
            ?: speechProviders.firstOrNull()

    /** 这家配了 Key 没有。 */
    fun speechKeyOf(providerId: String): String = speechKeys[providerId].orEmpty()

    fun speechConfigured(providerId: String): Boolean = speechKeyOf(providerId).isNotBlank()

    /**
     * 写这家的 Key。传空串等于清掉配置。
     *
     * **按家分开存**，不是"一把 Key"：用户从 Groq 切到智谱时，Groq 的 Key 拿到智谱去
     * 必然 401，而把它留在输入框里等着被误发更糟。按家分开是唯一不会把 A 家的钥匙
     * 插到 B 家门上的存法；界面上一次只显示一家的输入框，读起来仍然是"就一条配置"。
     *
     * 落盘键 `speechKey:<id>` 与对话那边的 `apikey:<id>` **是两回事**——
     * 拆开的意义就在于两边互不影响。
     */
    fun setSpeechKey(providerId: String, key: String) {
        val trimmed = key.trim()
        speechKeys[providerId] = trimmed
        SettingsStore.put(speechKeyName(providerId), trimmed)
    }

    /**
     * 这家现在实际用的识别模型：选过且在候选里就用选的，否则退回预设。
     *
     * 界面和 [asrEndpoint] 都走这一个出口——分头写两遍迟早会不一致，
     * 而"界面显示 A、实际请求 B"是最难查的那种毛病。
     */
    fun activeAsrModel(provider: SpeechProvider): String =
        asrModelId?.takeIf { it in provider.asrChoices } ?: provider.asrModel

    /**
     * 识别用的接口信息。没选服务商、或这家没填 Key，都返回 null。
     *
     * 返回 null 时 `ModelTranscriber.ready` 为 false（见 `core:llm` 的 ModelSpeech），
     * 界面据此把「云端识别」那一档禁用，并把原因说清楚。
     */
    fun asrEndpoint(): SpeechEndpoint? {
        val provider = activeSpeechProvider() ?: return null
        val key = speechKeyOf(provider.id)
        if (key.isBlank()) return null
        return SpeechEndpoint(
            baseUrl = provider.baseUrl.trimEnd('/'),
            apiKey = key,
            // 形状跟着服务商走。少了这一句，火山方舟那条会拿着 OpenAI 的路径发出去
            protocol = provider.protocol,
            asrModel = activeAsrModel(provider),
        )
    }

    /**
     * 合成用的接口信息。
     *
     * **跟着用户选的那家走**，不再"自动挑一家能做合成的"——这是拆分配置的直接结果：
     * 语音侧只有一条配置，用户选了谁就是谁。这家不能合成（Groq）时返回 null，
     * `ModelSynthesizer.ready` 于是为 false，[Voice] 会把朗读退回设备自带。
     */
    fun ttsEndpoint(): SpeechEndpoint? {
        val provider = activeSpeechProvider() ?: return null
        val model = provider.ttsModel ?: return null
        val key = speechKeyOf(provider.id)
        if (key.isBlank()) return null
        return SpeechEndpoint(
            baseUrl = provider.baseUrl.trimEnd('/'),
            apiKey = key,
            protocol = provider.protocol,
            ttsModel = model,
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
     * 选语音服务商。传 null 回到"还没选"（[activeSpeechProvider] 会退回第一家）。
     *
     * 换家的同时把模型选择清掉：模型名是**跟着服务商**的，
     * `whisper-large-v3-turbo` 拿到智谱去就是个不存在的模型。
     *
     * Key **不清**——它是按家分开存的，各留各的，切回去不用重填。
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
     * 语音现在实际会走哪条路，一句话说清。「我的」页那行副标题用它。
     *
     * 顺序照 [Voice] 的选取逻辑来，否则副标题会跟真实行为不一致——那比不显示更糟。
     */
    val asrSummary: String
        get() {
            val device = Voice.deviceListenInstalled
            val provider = activeSpeechProvider()
            val configured = provider != null && speechConfigured(provider.id)
            return when {
                voiceSource == VoiceSource.Device && device -> "系统能力"
                voiceSource == VoiceSource.Device -> "系统能力 · 这台设备不支持"
                // 「云端识别」是用户点名要的，没配好就得直说是哪一步没做
                voiceSource == VoiceSource.Model && configured -> provider!!.name
                voiceSource == VoiceSource.Model && provider == null -> "云端识别 · 还没选服务商"
                voiceSource == VoiceSource.Model -> "云端识别 · 还没填 Key"
                device -> "自动 · 优先系统能力"
                configured -> "自动 · ${provider!!.name}"
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
     * 这个智能体能用哪些工具。
     *
     * 智能体的 [Agent.canScheduleTasks] 控制 `create_task` 是否注册（设计规范 §附表）：
     * 关掉时（如陪练、学习类智能体）不给 `create_task`，彻底杜绝模型主动弹任务卡片。
     *
     * 其余三个任务工具（list_tasks / update_task / cancel_task）保留提供：
     * 若该智能体过去曾建过任务，或用户在任务中心给它指派了任务，它仍需能够查看、调整或取消已有任务；
     * 且这三个是被动响应式的，不会引起模型主动新建任务的误触发。
     */
    internal fun registryFor(agent: Agent): ToolRegistry =
        if (agent.canScheduleTasks) {
            BuiltinTools.all
        } else {
            BuiltinTools.withoutTaskCreation
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

        val placeholder = ChatMessage(
            id = nextId("m"),
            author = Author.Agent,
            body = MessageBody.Text(""),
            timeLabel = "刚刚",
        )
        conversation.add(placeholder)
        val index = conversation.lastIndex

        // 执行卡片是**一张会变的卡**，不是一步一个气泡：记下它在对话里的位置，
        // 之后每有进展就重写那一条——和上面流式占位符是同一个手法。
        val stepCalls = mutableListOf<ToolCall>()
        val stepResults = mutableListOf<ToolResult>()
        var executionIndex = -1

        // 这一轮有没有拿到过外部来源的内容（网页正文等）。
        //
        // 它是**本次调用的局部量**，所以天然不会泄漏到下一轮——不需要额外的复位，
        // 端口那个 lambda 每次读的也是这一份（闭包捕获的是变量，不是快照）。
        // 别把它挪成字段：一旦共享，一轮的外部抓取会把下一轮的正常记忆一起静默掐掉，
        // 而且绝不报错，只表现为"它怎么不记得了"。
        var externalFetchThisTurn = false

        busy[agentId] = true
        val buffer = StringBuilder()
        var outcome: LoopOutcome

        try {
            val loop = AgentLoop(stream = stream)
            val host = ToolHost(
                // 记忆的两条路走同一道闸门：文本块那条在下面直接用这个变量，
                // 工具那条在端口里读它。真值判定统一成"读到过外部内容"（与网页相反）。
                memory = AppMemoryPort(this) { externalFetchThisTurn },
                tasks = AppTaskPort(this),
                web = webPort,
                userInput = userInputPort,
                agentId = agentId,
                conversationKey = agentId,
            )

            outcome = loop.run(
                endpoint = endpoint,
                history = historyFor(agentId).toMutableList(),
                host = host,
                registry = registryFor(agent),
                onText = { chunk ->
                    buffer.append(chunk)
                    conversation[index] = placeholder.copy(body = MessageBody.Text(buffer.toString()))
                },
                onToolStart = { call ->
                    stepCalls += call
                    val steps = executionStepsFor(stepCalls, stepResults)
                    if (executionIndex < 0) {
                        // 第一次调工具时才插卡片，别让纯对话也顶一张空的
                        conversation.add(
                            ChatMessage(
                                id = nextId("m"),
                                author = Author.Agent,
                                body = MessageBody.Execution(steps),
                                timeLabel = "刚刚",
                            )
                        )
                        executionIndex = conversation.lastIndex
                    } else {
                        conversation[executionIndex] =
                            conversation[executionIndex].copy(body = MessageBody.Execution(steps))
                    }
                },
                onToolEnd = { _, result ->
                    stepResults += result
                    // 只**累积**（取或），不写成 `= result.externalContext`：一轮里多个结果时
                    // 后者是"最后一个说了算"，取或才是"只要有一个"。要用的属性只增不减。
                    if (result.externalContext) externalFetchThisTurn = true
                    if (executionIndex >= 0) {
                        conversation[executionIndex] = conversation[executionIndex].copy(
                            body = MessageBody.Execution(executionStepsFor(stepCalls, stepResults)),
                        )
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            // 用户点了停止：已经收到的部分保留下来，一个字都没有就留个说明。
            // 取消必须原样抛出——吞掉的话用户按了停止却停不下来。
            if (buffer.isEmpty()) {
                conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            }
            throw cancelled
        } finally {
            busy[agentId] = false
        }

        when (outcome) {
            is LoopOutcome.Failed -> {
                conversation[index] = placeholder.copy(
                    body = MessageBody.Notice(outcome.message, isError = true),
                )
                return
            }
            is LoopOutcome.Stopped -> {
                // 上限停下：保留已经说出来的部分，再补一条说明。
                // 两条路都必须就此收尾——空正文时下面那个 raw.isBlank() 会把刚写的
                // "这个任务需要的步骤有点多"覆盖成「模型没有返回内容」，用户就看不到停下的原因了。
                if (buffer.isEmpty()) {
                    conversation[index] = placeholder.copy(body = MessageBody.Notice(outcome.reason))
                } else {
                    conversation.add(notice(outcome.reason))
                }
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = outcome.let { if (it is LoopOutcome.Done) it.text else buffer.toString() }
        if (raw.isBlank()) {
            conversation[index] = placeholder.copy(
                body = MessageBody.Notice("模型没有返回内容", isError = true),
            )
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
            memory == null -> spoken
            // 只回了一个记忆块、没有正文时补一句，否则气泡会是空的
            memory.remainingText.isBlank() -> "好的，我记下了。"
            else -> memory.remainingText
        }
        conversation[index] = placeholder.copy(body = MessageBody.Text(answer))

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
        if (!externalFetchThisTurn) {
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

        // 每位成员各有**一张自己的执行卡**（index 逐位各记一份）：成员是轮流发言的，
        // 共用一张的话后一位的工具步骤会写进上一位那张卡里，看起来像一个人干的。
        val stepCalls = mutableListOf<ToolCall>()
        val stepResults = mutableListOf<ToolResult>()
        var executionIndex = -1

        // 与单聊、定时执行同一道闸门：这一轮取过外部网页就不写记忆。
        var externalFetchThisTurn = false

        val buffer = StringBuilder()
        var outcome: LoopOutcome
        try {
            val loop = AgentLoop(stream = stream)
            val host = ToolHost(
                memory = AppMemoryPort(this) { externalFetchThisTurn },
                tasks = AppTaskPort(this),
                web = webPort,
                userInput = userInputPort,
                // 记忆与任务都是**按智能体**隔离的，所以是成员自己的 id，不是群 id——
                // 用群 id 的话群里每个人会共享同一份记忆和任务清单，那显然是错的。
                agentId = member.id,
                // 会话键仍是群：这些消息本来就落在群里。
                conversationKey = key,
            )

            outcome = loop.run(
                endpoint = endpoint,
                history = history.toMutableList(),
                host = host,
                registry = registryFor(member),
                onText = { chunk ->
                    buffer.append(chunk)
                    conversation[index] = placeholder.copy(body = MessageBody.Text(buffer.toString()))
                },
                onToolStart = { call ->
                    stepCalls += call
                    val steps = executionStepsFor(stepCalls, stepResults)
                    if (executionIndex < 0) {
                        conversation.add(
                            ChatMessage(
                                id = nextId("m"),
                                author = Author.Agent,
                                body = MessageBody.Execution(steps),
                                timeLabel = "刚刚",
                                // 卡片也要挂在这位成员名下，群里才看得出是谁在动手
                                speakerAgentId = member.id,
                                roundIndex = round,
                            )
                        )
                        executionIndex = conversation.lastIndex
                    } else {
                        conversation[executionIndex] =
                            conversation[executionIndex].copy(body = MessageBody.Execution(steps))
                    }
                },
                onToolEnd = { _, result ->
                    stepResults += result
                    // 只累积（取或）：一轮里多个结果时"最后一个说了算"会把闸门重新打开
                    if (result.externalContext) externalFetchThisTurn = true
                    if (executionIndex >= 0) {
                        conversation[executionIndex] = conversation[executionIndex].copy(
                            body = MessageBody.Execution(executionStepsFor(stepCalls, stepResults)),
                        )
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            // 用户点了停止：已经说出的半句留着，再往上抛，让整场讨论一起停下来
            if (buffer.isEmpty()) {
                conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            }
            throw cancelled
        }

        when (outcome) {
            is LoopOutcome.Failed -> {
                conversation[index] = placeholder.copy(body = MessageBody.Notice(outcome.message, isError = true))
                return
            }
            is LoopOutcome.Stopped -> {
                // 上限停下：保留已经说出的部分，再补一条说明——与单聊一致
                if (buffer.isEmpty()) {
                    conversation[index] = placeholder.copy(body = MessageBody.Notice(outcome.reason))
                } else {
                    conversation.add(notice(outcome.reason).copy(speakerAgentId = member.id, roundIndex = round))
                }
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = outcome.let { if (it is LoopOutcome.Done) it.text else buffer.toString() }
        if (raw.isBlank()) {
            conversation[index] = placeholder.copy(body = MessageBody.Notice("模型没有返回内容", isError = true))
            return
        }

        conversation[index] = when (val resolved = resolveGroupReply(raw)) {
            is GroupReplyOutcome.Speak -> placeholder.copy(body = MessageBody.Text(resolved.text))
            is GroupReplyOutcome.Skip -> placeholder.copy(body = MessageBody.Skipped(resolved.reason))
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
        conversation.add(placeholder)
        val index = conversation.lastIndex

        // 执行卡片和 send() 一样是**一张会变的卡**：记下它在对话里的位置，
        // 之后每有进展就重写那一条，而不是一步一个气泡。
        val stepCalls = mutableListOf<ToolCall>()
        val stepResults = mutableListOf<ToolResult>()
        var executionIndex = -1

        // 这一轮取过外部网页就不写记忆，与 send() 是同一道闸门：
        // 任务跑着的时候抓回来的网页正文同样可能"教"模型写一条假记忆。
        var externalFetchThisTurn = false

        // 历史里不含刚加的这两条：Notice 和空文本都会被 historyFor 过滤掉
        val history = historyFor(task.agentId).toMutableList()
        history += LlmTurn(LlmRole.User, taskInstruction(task))

        busy[task.agentId] = true
        val buffer = StringBuilder()
        var outcome: LoopOutcome

        // 收尾：把这一期的结果写成"上次执行"的文案，并把期排到下一次。
        // 提前 return 的几条路都要经过它，否则任务会永远停在"到点"。
        fun finish(ran: Boolean) {
            val finished = now()
            val stamp = "${describeMoment(finished, finished, zone())} ${if (ran) "已完成" else "执行失败"}"
            reschedule(task, lastRun = stamp)
        }

        try {
            val loop = AgentLoop(stream = stream)
            val host = ToolHost(
                memory = AppMemoryPort(this) { externalFetchThisTurn },
                tasks = AppTaskPort(this),
                web = webPort,
                userInput = userInputPort,
                agentId = task.agentId,
                conversationKey = task.agentId,
            )

            outcome = loop.run(
                endpoint = endpoint,
                history = history,
                host = host,
                registry = registryFor(agent),
                onText = { chunk ->
                    buffer.append(chunk)
                    conversation[index] = placeholder.copy(body = MessageBody.Text(buffer.toString()))
                },
                onToolStart = { call ->
                    stepCalls += call
                    val steps = executionStepsFor(stepCalls, stepResults)
                    if (executionIndex < 0) {
                        // 第一次调工具时才插卡片，别让纯任务叙述也顶一张空的
                        conversation.add(
                            ChatMessage(
                                id = nextId("m"),
                                author = Author.Agent,
                                body = MessageBody.Execution(steps),
                                timeLabel = "刚刚",
                            )
                        )
                        executionIndex = conversation.lastIndex
                    } else {
                        conversation[executionIndex] =
                            conversation[executionIndex].copy(body = MessageBody.Execution(steps))
                    }
                },
                onToolEnd = { _, result ->
                    stepResults += result
                    // 只累积（取或）：一轮里多个结果时"最后一个说了算"会把闸门重新打开
                    if (result.externalContext) externalFetchThisTurn = true
                    if (executionIndex >= 0) {
                        conversation[executionIndex] = conversation[executionIndex].copy(
                            body = MessageBody.Execution(executionStepsFor(stepCalls, stepResults)),
                        )
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            if (buffer.isEmpty()) conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
            throw cancelled
        } finally {
            busy[task.agentId] = false
        }

        when (outcome) {
            is LoopOutcome.Failed -> {
                conversation[index] = placeholder.copy(
                    body = MessageBody.Notice(outcome.message, isError = true),
                )
                finish(ran = false)
                return
            }
            is LoopOutcome.Stopped -> {
                // 上限停下：保留已经说出来的部分，再补一条说明——与 send() 一致
                if (buffer.isEmpty()) {
                    conversation[index] = placeholder.copy(body = MessageBody.Notice(outcome.reason))
                } else {
                    conversation.add(notice(outcome.reason))
                }
                // 撞上限**不算跑完**：lastRunLabel 得如实说"执行失败"，
                // 否则用户会以为那次半截的结果是完整的（一次性任务还会因此被标成已完成）。
                finish(ran = false)
                return
            }
            is LoopOutcome.Done -> Unit
        }

        val raw = outcome.let { if (it is LoopOutcome.Done) it.text else buffer.toString() }
        if (raw.isBlank()) {
            conversation[index] = placeholder.copy(
                body = MessageBody.Notice("模型没有返回内容", isError = true),
            )
            finish(ran = false)
            return
        }

        // 任务跑的这一次不该再长出任务草稿卡片——它自己就是一个任务
        val reply = resolveReply(raw, allowTasks = false)
        val spoken = when (reply) {
            is ReplyOutcome.Plain -> reply.text
            is ReplyOutcome.AsTask -> reply.text
        }
        conversation[index] = placeholder.copy(body = MessageBody.Text(spoken))
        finish(ran = true)
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
    fun updateTaskFromTool(
        agentId: String,
        id: String,
        title: String?,
        scheduleLabel: String?,
    ): Boolean {
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
        return true
    }

    /** 取消任务。**置为 Done 而不是删掉**——删了用户就看不到它出过什么。 */
    fun cancelTaskFromTool(agentId: String, id: String): Boolean {
        val index = tasks.indexOfFirst { it.id == id && it.agentId == agentId }
        if (index < 0) return false
        tasks[index] = tasks[index].copy(status = TaskStatus.Done)
        persistState()
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

        // 已经记住的事要回灌进来，否则"记住"就只是把它存进了某个用户看不见也问不着的地方。
        // 取最近的若干条而不是全部：记忆会越攒越多，全塞进去既挤占上下文，
        // 也会让早期的琐事一直压着近期的对话。
        val known = memoriesOf(agent.id).take(MAX_PROMPT_MEMORIES)
        if (known.isNotEmpty()) {
            append("\n\n【你已经记得的关于他的事】")
            known.forEach { append('\n').append("- ").append(it.text) }
            append("\n自然地用上这些，不要刻意复述，也不要逐条罗列。")
        }

        // 一期**无条件发工具**：单聊、群聊、定时执行三个调用点全传 true，没有"这家不支持就
        // 退回文本块"的探测、也没有 400 重试。这意味着一个拒收 `tools` 字段的服务商会直接 400
        // ——而被让出去的，正是下面这段"不发工具时靠 ```task / ```memory 兜底"的路。
        //
        // 所以这段**现在是死代码**：没有任何生产调用点传 false（Task 12 把最后一个
        // ——runTask——也接进了工具循环）。它留着是给将来那条完整的降级路径用的
        // （先发工具、被拒、再退回文本协议重试），那件事还没做；
        // **别把这里当成已生效的兜底**，它今天一次都不会跑。测试里传 false 才能走到。
        if (!toolsAvailable) {
            // 记忆不跟着任务开关走——记忆是"认不认识你"，陪聊型智能体也该记得住。
            if (agent.canScheduleTasks) {
                append("\n\n")
                append(TaskBlock.INSTRUCTION)
            }
            append("\n\n")
            append(MemoryBlock.INSTRUCTION)
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

    /**
     * 语音侧的 Key 落盘键。
     *
     * **故意和 [keyOf] 不同名**——同名就等于又回到"两边共用一把"，
     * 那正是这次要拆掉的东西。旧键一律不读、不删：它们是用户的对话配置，留着无害。
     */
    private fun speechKeyName(providerId: String) = "speechKey:$providerId"

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

        // 语音服务商与模型。这两个只是"偏好"，指向的东西不在了也不校验——
        // activeSpeechProvider() / asrEndpoint() 每次都会重新核对一遍，那里退回第一家。
        // 在这儿校验反而不对：还原时语音的 Key 才刚灌完，此刻的可用性不代表用起来那一刻的。
        //
        // 上一版存下来的 speechProvider 可能是某个**对话**服务商的 id，
        // 读出来落到 activeSpeechProvider() 上会自然退回第一家——不需要为它写迁移。
        speechProviderId = SettingsStore.get(KEY_SPEECH_PROVIDER)?.takeIf { it.isNotBlank() }
        asrModelId = SettingsStore.get(KEY_ASR_MODEL)?.takeIf { it.isNotBlank() }

        // 每一家的 Key 都要读回来。漏读一家的后果是"每次冷启都要重填"，
        // 而这件事不写测试根本发现不了——id 和模型名都还原得好好的，看着一切正常。
        speechProviders.forEach { provider ->
            SettingsStore.get(speechKeyName(provider.id))
                ?.takeIf { it.isNotBlank() }
                ?.let { speechKeys[provider.id] = it }
        }

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
