package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.model.SkillSource
import com.starringbox.staragent.core.platform.storage.LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 落盘的那一份应用状态。
 *
 * 只存"内容"——智能体、任务、记忆、技能、渠道、群组、对话。
 * 模型服务配置（含 API Key）走 SettingsStore，两套各管各的。
 *
 * **新增字段一律要带默认值**：旧文件里没有这个键时，解码器会填默认值，数据照常恢复。
 * 反过来，改动或删除已有字段会让旧文件解码失败，而读不出来的东西只能被挪走——
 * 用户内容会**无声消失**。这是本文件最需要守住的约束。
 */
@Serializable
internal data class PersistedState(
    val agents: List<Agent> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val groups: List<AgentGroup> = emptyList(),
    val conversations: Map<String, List<ChatMessage>> = emptyMap(),
)

/** 存档用的 JSON 配置。整个持久化层共用一份，免得两处对"多出来的键"态度不一致。 */
internal val PersistedJson = Json { ignoreUnknownKeys = true }

/**
 * 一次读档的结果。
 *
 * [fromLegacyFile] 表示这次是**从旧的单份 `state.json` 读出来的**，树还没写。
 * 调用方据此补一次落盘，把树建起来。
 *
 * [issues] 是"这次读档有几个文件没能用"——坏掉的文件已经被挪到 `backups/broken/`。
 * 它要被显示给用户：以前这类事是静默的（退回种子数据，用户内容看起来消失），
 * 现在至少是有声的。
 */
internal data class LoadResult(
    val state: PersistedState,
    val fromLegacyFile: Boolean = false,
    val issues: Int = 0,
)

/**
 * 状态的读写——**目录树形态**。
 *
 * ```
 * layout.json 存在        → 读树
 * 否则 state.json 存在    → 旧格式，读出来交给调用方补一次落盘（迁移）
 * 否则                    → 全新安装
 * ```
 *
 * **为什么拆成目录树**：单份 `state.json` 的失败模式是"一个字节坏 → 整份读不出 →
 * 退回种子 → 用户内容全没"。拆开之后坏一个只坏一个，其余照常加载。
 * 另外它让智能体能被单独带走——那正是产品要的"智能体即文件"。
 *
 * **一个实例上 [load] 和 [save] 要成对用**。它记着两件事：上次写出去的是什么（只写变了的），
 * 以及"这次是读旧存档进来的"（于是树写完才轮到旧存档退休）。换个新实例去保存，
 * 这两份记忆就都没了。[AppState] 全程只建一个，正是为此。
 */
internal class StatePersister(private val store: LocalStore) {

    /**
     * 上次写出去的东西，按路径记内容。
     *
     * 保存时逐文件比对，只写变了的——**这不是优化，是必需**：照原样把每个实体都写出去的话，
     * 25 个小文件比 1 个 JSON 更慢，等于把重构做成负优化。
     *
     * 记内容而不是记状态对象，是为了绕开一处陷阱：`MediaAsset` 刻意没有 `equals`
     * （内部是 ByteArray，自动生成的等价性会误导调用方），所以 `ChatMessage` 的
     * 结构比较在带图的消息上永远不等。比内容则完全准确。
     */
    private var lastText: Map<String, String> = emptyMap()

    /** 上次写出去的图片字节。按引用留着，比对用 `contentEquals`，不额外占内存。 */
    private var lastMedia: Map<String, ByteArray> = emptyMap()

    /** 旧存档还在、且可以安全删掉。写入树之后才清。 */
    private var legacyPendingRemoval = false

    // ── 读 ──────────────────────────────────────

    fun load(): LoadResult? {
        if (store.readText(StateTree.LAYOUT) != null) return readTree()
        val legacy = store.readText(StateTree.LEGACY_STATE) ?: return null
        return fromLegacyFile(legacy)
    }

    private fun readTree(): LoadResult {
        var issues = 0
        val paths = store.list("")

        // 顺序读不出来不该让整棵树读不出来——退化成字母序，数据一条不少。
        val layoutText = store.readText(StateTree.LAYOUT)
        val layout = layoutText
            ?.let { runCatching { PersistedJson.decodeFromString<StateTree.LayoutFile>(it) }.getOrNull() }
            ?: run { issues++; StateTree.LayoutFile() }

        val configText = store.readText(StateTree.CONFIG)
        val config = configText
            ?.let { runCatching { PersistedJson.decodeFromString<StateTree.ConfigFile>(it) }.getOrNull() }
            ?: run { if (configText != null) issues++; StateTree.ConfigFile() }
        val installed = config.installedSkills.toSet()

        // 顺序在这里就定下来，而不是等到最后——记忆是按智能体分文件存的，
        // 收集的先后决定了记忆在列表里的先后，用扫描出来的字母序会把它打乱。
        val agents = StateTree.orderedBy(
            StateTree.entityIds(paths, "agents", "agent.json").mapNotNull { id ->
                val meta = decode<StateTree.AgentFile>(StateTree.agentFile(id)) { issues++ }
                    ?: return@mapNotNull null
                meta.toAgent(persona = store.readText(StateTree.soulFile(id)).orEmpty())
            },
            layout.agents,
        ) { it.id }

        // 记忆按智能体分文件。智能体已经不在的（删人时记忆会留下）不能让它们消失，
        // 它们各自住在 memories/<agentId>.md 里，agentId 从文件名读回来——无损。
        val memories = buildList {
            agents.forEach { agent ->
                store.readText(StateTree.memoryFile(agent.id))?.let {
                    addAll(StateTree.decodeMemories(agent.id, it))
                }
            }
            StateTree.flatIds(paths, "memories", ".md").forEach { fileId ->
                val agentId = StateTree.orphanMemoryAgentId(fileId)
                store.readText(StateTree.orphanMemoryFile(agentId))?.let {
                    addAll(StateTree.decodeMemories(agentId, it))
                }
            }
        }

        val skills = StateTree.entityIds(paths, "skills", "SKILL.md").mapNotNull { id ->
            val text = store.readText(StateTree.skillFile(id)) ?: return@mapNotNull null
            val doc = SkillFormat.parse(text)
                ?: run { issues++; quarantine(StateTree.skillFile(id), text); return@mapNotNull null }
            val source = config.skillSources[id]
                ?: if (id in Seed.builtInSkillInstructions) SkillSource.BuiltIn else SkillSource.Imported
            Skill(
                id = id,
                name = doc.displayName ?: id,
                summary = doc.description,
                glyph = doc.glyph,
                installed = id in installed,
                instructions = doc.body,
                source = source,
                modelInvocable = doc.modelInvocable,
                userInvocable = doc.userInvocable,
                // 导入的技能按原文存回去，内置和智能体创建的由字段重新生成（见 Skill.document）
                document = if (source == SkillSource.Imported) text else "",
            )
        }

        val groups = StateTree.flatIds(paths, "groups", ".json").mapNotNull { id ->
            decode<AgentGroup>(StateTree.groupFile(id)) { issues++ }
        }

        val tasks = store.readText(StateTree.TASKS)?.let { text ->
            runCatching { StateTree.decodeTasks(text) }.getOrElse { issues++; emptyList() }
        }.orEmpty()

        val conversations = mutableMapOf<String, List<ChatMessage>>()
        paths.mapNotNull { StateTree.sessionKey(it) }.forEach { key ->
            val raw = store.readText(StateTree.sessionFile(key)) ?: return@forEach
            val (messages, broken) = StateTree.decodeMessages(raw)
            issues += broken
            conversations[key] = messages.map { it.withMediaRestored(store, StateTree::mediaFile) }
        }

        val state = PersistedState(
            agents = agents,
            tasks = tasks,
            memories = memories,
            skills = StateTree.orderedBy(skills, layout.skills) { it.id },
            channels = config.channels,
            groups = StateTree.orderedBy(groups, layout.groups) { it.id },
            conversations = conversations,
        )

        // 这棵树就是我们刚读到的样子，所以记下来：下一次保存若没改动，一个文件都不用写。
        lastText = encodeAll(state)
        lastMedia = state.mediaAssets().associate { StateTree.mediaFile(it) to it.bytes }
        legacyPendingRemoval = paths.contains(StateTree.LEGACY_STATE)

        return LoadResult(state = state, fromLegacyFile = false, issues = issues)
    }

    /**
     * 从旧的单份 `state.json` 读。
     *
     * **写树不在这里**——它由调用方随后那次落盘完成。这样"迁移"就只是
     * "读旧格式 + 正常保存一次"，不多一条会走歪的代码路径。
     *
     * 备份要先做：那份 `backups/state.v1.json` 是万一新树出问题时的退路，
     * 而且 `state.json` 只有在它存在时才允许被删。
     */
    private fun fromLegacyFile(raw: String): LoadResult? {
        // 已经有一份就不覆盖——它可能是上一次成功迁移留下的好副本，
        // 而这次读到的 state.json 未必比它可靠。
        if (store.readText(StateTree.LEGACY_BACKUP) == null) {
            runCatching { store.writeText(StateTree.LEGACY_BACKUP, raw) }
        }
        // 备份确实在，才允许最后删掉 state.json。写失败就让它留着，宁可多一个文件。
        legacyPendingRemoval = store.readText(StateTree.LEGACY_BACKUP) == raw

        val decoded = runCatching { PersistedJson.decodeFromString<PersistedState>(raw) }.getOrNull()
            ?: run {
                // 解析不了就退化为种子数据，而不是崩在启动路径上。
                // 但退化的代价是"用户内容看起来消失了"，所以先把原始内容另存一份再放弃——
                // 真出事时至少还能人工找回，不至于被种子数据盖掉。
                runCatching { store.writeText(StateTree.CORRUPT_FILE, raw) }
                return null
            }

        val restored = decoded.copy(
            // 单文件存档里的技能没有 source 字段，反序列化出来一律是默认的 Imported。按读树时同样的规则补：
            // 内置 id 是 BuiltIn；其余是 Imported，并补上原文——读树时导入技能的 document 就是 SKILL.md 原文，
            // 不补的话迁移前后读出来的不是同一个技能。
            skills = decoded.skills.map { skill ->
                if (skill.id in Seed.builtInSkillInstructions) {
                    skill.copy(source = SkillSource.BuiltIn, document = "")
                } else {
                    skill.copy(source = SkillSource.Imported, document = SkillFormat.encode(skill))
                }
            },
            conversations = decoded.conversations.mapValues { (_, messages) ->
                messages.map { it.withMediaRestored(store) { asset -> StateTree.legacyMediaFile(asset.id) } }
            },
        )

        // 旧格式的图片叫 media/<id>.bin，新格式叫 media/<id>.<ext>。
        // 把旧名字记进 lastMedia，保存时（layout 写完之后）它们才会被清掉——
        // 顺序很关键：先删图再写树，中途断电就把图弄丢了。
        lastMedia = restored.mediaAssets().associate { StateTree.legacyMediaFile(it.id) to it.bytes }

        return LoadResult(state = restored, fromLegacyFile = true)
    }

    // ── 写 ──────────────────────────────────────

    /**
     * 把当前状态落盘。
     *
     * 顺序是有讲究的，每一步都在为"写到一半断电"做准备：
     *
     * 1. 实体文件（只写变了的）
     * 2. 图片（只写变了的）
     * 3. **`layout.json` 最后写**——它的存在等于"这棵树是完整的"
     * 4. 删掉不该再存在的文件（包括旧格式的图片）
     * 5. 树确认完整了，才删掉旧的 `state.json`
     *
     * 中途断电的话，下次启动看到的还是"只有 state.json、没有 layout.json"，
     * 于是重新走一遍迁移——幂等，而且旧数据一直是完整的。
     */
    fun save(state: PersistedState) {
        val files = encodeAll(state)

        // 1 + 3：layout.json 由 encodeAll 排在最后，顺着写即可
        files.forEach { (path, content) ->
            if (lastText[path] != content) store.writeText(path, content)
        }

        // 2：图片。比字节而不是比大小——字节本来就在内存里，比对是免费的，
        // 而大小相同不等于内容相同。
        val wanted = state.mediaAssets()
        wanted.forEach { asset ->
            if (asset.bytes.isEmpty()) return@forEach
            val path = StateTree.mediaFile(asset)
            if (lastMedia[path]?.contentEquals(asset.bytes) == true) return@forEach
            store.writeBytes(path, asset.bytes)
        }

        // 4：不再属于这棵树的文件
        (lastText.keys - files.keys).forEach { store.delete(it) }

        // media/ 里只该有当前在用的图片。这一条**按磁盘上实际有什么对账**，而不是按
        // "我上次写了什么"——因为要收掉的东西恰恰可能是这个 persister 没写过的：
        // 迁移之前留下的 `.bin`、进程重启前删掉的会话留下的图。靠记忆的话，
        // 换一个 persister 实例保存就漏了。
        val wantedPaths = wanted.map { StateTree.mediaFile(it) }.toSet()
        (store.list("media").toSet() - wantedPaths).forEach { store.delete(it) }

        // 5：树完整了，旧存档才退休
        if (legacyPendingRemoval) {
            store.delete(StateTree.LEGACY_STATE)
            legacyPendingRemoval = false
        }

        lastText = files
        lastMedia = wanted.associate { StateTree.mediaFile(it) to it.bytes }
    }

    // ── 编码 ────────────────────────────────────

    /**
     * 整棵树编成"路径 → 内容"。
     *
     * **`layout.json` 必须排在最后**——[save] 是按这个顺序写的，而它的存在是
     * "这棵树完整"的标志。
     */
    private fun encodeAll(state: PersistedState): Map<String, String> {
        val files = LinkedHashMap<String, String>()

        files[StateTree.CONFIG] = PersistedJson.encodeToString(
            StateTree.ConfigFile(
                channels = state.channels,
                installedSkills = state.skills.filter { it.installed }.map { it.id },
                skillSources = state.skills.associate { it.id to it.source },
            )
        )

        state.agents.forEach { agent ->
            files[StateTree.agentFile(agent.id)] = PersistedJson.encodeToString(StateTree.AgentFile.of(agent))
            // 人格是纯散文，原样进原样出——它是用户手写的东西，不该被格式化打扰
            files[StateTree.soulFile(agent.id)] = agent.persona

            val memory = StateTree.encodeMemories(agent.id, state.memories)
            if (memory.isNotEmpty()) files[StateTree.memoryFile(agent.id)] = memory
        }

        // 智能体已经不在的记忆（删人时留下的）单独存一份，不让它们无声消失
        state.memories
            .filter { memory -> state.agents.none { it.id == memory.agentId } }
            .groupBy { it.agentId }
            .forEach { (agentId, orphans) ->
                val doc = StateTree.encodeMemories(agentId, orphans)
                if (doc.isNotEmpty()) files[StateTree.orphanMemoryFile(agentId)] = doc
            }

        state.skills.forEach { skill ->
            files[StateTree.skillFile(skill.id)] =
                skill.document.takeIf { skill.source == SkillSource.Imported && it.isNotBlank() }
                    ?: SkillFormat.encode(skill)
        }

        state.groups.forEach { group ->
            files[StateTree.groupFile(group.id)] = StateTree.encodeGroup(group)
        }

        if (state.tasks.isNotEmpty()) files[StateTree.TASKS] = StateTree.encodeTasks(state.tasks)

        state.conversations.forEach { (key, messages) ->
            files[StateTree.sessionFile(key)] = StateTree.encodeMessages(messages)
        }

        files[StateTree.LAYOUT] = PersistedJson.encodeToString(
            StateTree.LayoutFile(
                version = StateTree.VERSION,
                agents = state.agents.map { it.id },
                skills = state.skills.map { it.id },
                groups = state.groups.map { it.id },
            )
        )

        return files
    }

    private inline fun <reified T> decode(path: String, onIssue: () -> Unit): T? {
        val text = store.readText(path) ?: return null
        return runCatching { PersistedJson.decodeFromString<T>(text) }.getOrElse {
            onIssue()
            quarantine(path, text)
            null
        }
    }

    /**
     * 把读不出来的文件挪到 `backups/broken/`，而不是就地丢掉。
     *
     * 挪而不是删，是因为"读不出来"不等于"没价值"——可能只是这一版解不开，
     * 手工看一眼往往能救回来。文件名带上原路径，找得回去。
     */
    private fun quarantine(path: String, content: String) {
        runCatching {
            store.writeText("${StateTree.BROKEN_DIR}/${path.replace('/', '_')}", content)
            store.delete(path)
        }
    }
}

/**
 * 把这条消息里的图片字节从文件读回来。
 *
 * 图片的本体不进 JSON（base64 会让存档膨胀），按 [MediaAsset.id] 单独存文件，
 * 这里按 [fileFor] 给出的规则找回去。
 */
private fun ChatMessage.withMediaRestored(
    store: LocalStore,
    fileFor: (MediaAsset) -> String,
): ChatMessage {
    val body = body
    if (body !is MessageBody.Image) return this

    val restored = body.assets.map { asset ->
        MediaAsset(
            id = asset.id,
            mimeType = asset.mimeType,
            bytes = store.readBytes(fileFor(asset)) ?: ByteArray(0),
        )
    }
    return copy(body = MessageBody.Image(assets = restored, caption = body.caption))
}

/** 摊平出这份状态里所有的图片，供保存与清理使用。 */
private fun PersistedState.mediaAssets(): List<MediaAsset> =
    conversations.values
        .flatten()
        .mapNotNull { (it.body as? MessageBody.Image)?.assets }
        .flatten()
