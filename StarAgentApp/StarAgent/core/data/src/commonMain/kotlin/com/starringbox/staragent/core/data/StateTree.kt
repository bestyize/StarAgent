package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Skill
import kotlinx.serialization.Serializable

/**
 * 存档的目录树：路径怎么摆、每样东西怎么编解码。
 *
 * 这一层只管**格式**，不认识 AppState，也不做迁移编排——那是 `StatePersister` 的事。
 * 分开是因为格式是"被读被写"的，编排是"什么时候读写"，改动的原因不一样。
 *
 * ```
 * <root>/
 * ├── layout.json                版本 + 顺序。**最后写**，它的存在 = 这棵树完整
 * ├── config.json                渠道、已安装的技能
 * ├── agents/<id>/
 * │   ├── SOUL.md                人格正文（纯散文，用户手写）
 * │   ├── agent.json             名字/图标/配色/模型/技能/置顶/能否托管任务
 * │   └── MEMORY.md              记忆，按分类分节
 * ├── skills/<id>/SKILL.md       技能定义（frontmatter）
 * ├── groups/<id>.json
 * ├── sessions/agents/<id>.jsonl
 * ├── sessions/groups/<id>.jsonl
 * ├── tasks/tasks.json
 * ├── media/<id>.<ext>
 * └── backups/
 *     ├── state.v1.json          迁移前的旧存档（原样留档）
 *     └── broken/                读不出来的文件挪到这
 * ```
 *
 * **为什么 `SOUL.md` 和 `agent.json` 是两个文件**：前者是用户手写的散文，
 * 后者是 App 会改的状态（置顶、换模型）。合成一个的话，用户每次点"置顶"，
 * App 就要把他手写的人格重写一遍——既churn 又容易覆盖手改。
 *
 * **为什么 `sessions/` 分 agents 和 groups 两层**：会话的键里有 `group:g-1` 这种，
 * 而 `:` 在 Windows 文件名里非法。分层是不用做键编码就绕开它的办法。
 */
internal object StateTree {

    const val LAYOUT = "layout.json"
    const val CONFIG = "config.json"
    const val TASKS = "tasks/tasks.json"

    /** 旧格式的存档。迁移完就删；它还在，就说明树还没写完整。 */
    const val LEGACY_STATE = "state.json"

    /** 读不出来时的地方（旧的单存档格式留下的习惯，保留同一个名字）。 */
    const val CORRUPT_FILE = "state.corrupt.json"

    const val LEGACY_BACKUP = "backups/state.v1.json"
    const val BROKEN_DIR = "backups/broken"

    /**
     * 孤儿记忆：智能体已经不在了、但记忆还在的那一批。
     *
     * 删智能体时不会顺手删它的记忆（那是用户的内容），所以这些记忆得有个家。
     * **按智能体 id 一个文件、格式和 `MEMORY.md` 一模一样**——这样 `agentId`
     * 可以从文件名里读回来，迁移对它们是**无损**的。换成把所有孤儿塞进一个文件的话，
     * 它们的 `agentId` 就只能抹掉，那是一次静默的信息丢失。
     */
    fun orphanMemoryFile(agentId: String) = "memories/${agentId.ifBlank { NO_AGENT }}.md"

    /** 孤儿文件名还原成 agentId。和 [orphanMemoryFile] 必须成对改。 */
    fun orphanMemoryAgentId(fileId: String) = if (fileId == NO_AGENT) "" else fileId

    /** 没有归属的记忆在文件名里用的占位。真存档里不会出现，只是不让文件名空掉。 */
    const val NO_AGENT = "_orphaned"

    /**
     * 当前的格式版本。
     *
     * v1 = 单份 `state.json`；v2 = 目录树。
     * 版本号写在 [LAYOUT] 里——没有它，拿到一个目录无从判断它该怎么迁。
     */
    const val VERSION = 2

    private const val AGENTS = "agents"
    private const val SKILLS = "skills"
    private const val GROUPS = "groups"
    private const val SESSIONS = "sessions"

    /** 群会话在内存里的键前缀，与 [GroupTalk.key] 是同一个约定。 */
    private const val GROUP_PREFIX = "group:"

    // ── 路径 ────────────────────────────────────

    fun agentFile(agentId: String) = "$AGENTS/$agentId/agent.json"
    fun soulFile(agentId: String) = "$AGENTS/$agentId/SOUL.md"
    fun memoryFile(agentId: String) = "$AGENTS/$agentId/MEMORY.md"

    fun skillFile(skillId: String) = "$SKILLS/$skillId/SKILL.md"
    fun groupFile(groupId: String) = "$GROUPS/$groupId.json"

    /**
     * 会话文件。内存里的键原样传进来（`a-xing` 或 `group:g-1`），
     * 这里负责把它落到一个**合法文件名**上。
     */
    fun sessionFile(key: String): String =
        if (key.startsWith(GROUP_PREFIX)) {
            "$SESSIONS/groups/${key.removePrefix(GROUP_PREFIX)}.jsonl"
        } else {
            "$SESSIONS/agents/$key.jsonl"
        }

    /** 会话文件名还原成内存里的键。和 [sessionFile] 必须成对改。 */
    fun sessionKey(relativePath: String): String? {
        val parts = relativePath.split('/')
        if (parts.size != 3 || parts[0] != SESSIONS) return null
        val name = parts[2].removeSuffix(".jsonl")
        if (name.isEmpty()) return null
        return when (parts[1]) {
            "agents" -> name
            "groups" -> GROUP_PREFIX + name
            else -> null
        }
    }

    /**
     * 图片文件名。**带真实扩展名**——这棵树是要被用户在自己的文件管理器里打开的，
     * 一张 `.bin` 双击看不了，等于白存。类型从 mimeType 来，认不出就退回 `.bin`。
     */
    fun mediaFile(asset: MediaAsset): String = "media/${asset.id}.${extensionFor(asset.mimeType)}"

    fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/bmp" -> "bmp"
        else -> "bin"
    }

    /** 迁移之前所有图片都叫这个名字。 */
    fun legacyMediaFile(assetId: String) = "media/$assetId.bin"

    // ── 目录里有哪些实体 ────────────────────────

    /**
     * 从列举结果里挑出某一层的实体 id。
     *
     * 例：[entityIds]("agents", "agent.json") 会把 `agents/a-xing/agent.json`
     * 认成 `a-xing`。名字里带 `/` 的更深一层（比如将来 `skills/x/scripts/y`）不会误伤，
     * 因为只认**正好**一层。
     */
    fun entityIds(paths: List<String>, dir: String, fileName: String): List<String> {
        val prefix = "$dir/"
        val suffix = "/$fileName"
        return paths.mapNotNull { path ->
            if (!path.startsWith(prefix) || !path.endsWith(suffix)) return@mapNotNull null
            val id = path.substring(prefix.length, path.length - suffix.length)
            id.takeIf { it.isNotEmpty() && !it.contains('/') }
        }.distinct().sorted()
    }

    /**
     * 一层目录下"一个文件一个实体"的 id，文件名是 `<id><extension>`。
     * `groups/<id>.json` 用的就是这个。
     */
    fun flatIds(paths: List<String>, dir: String, extension: String): List<String> {
        val prefix = "$dir/"
        return paths.mapNotNull { path ->
            if (!path.startsWith(prefix) || !path.endsWith(extension)) return@mapNotNull null
            val id = path.substring(prefix.length, path.length - extension.length)
            id.takeIf { it.isNotEmpty() && !it.contains('/') }
        }.distinct().sorted()
    }

    // ── 各类文件的编解码 ────────────────────────

    /**
     * 智能体的状态部分。人设正文不在这里，它在 `SOUL.md`。
     */
    @Serializable
    internal data class AgentFile(
        val id: String,
        val name: String,
        val glyph: String,
        val modelId: String,
        val skillIds: List<String> = emptyList(),
        val accentIndex: Int = 0,
        val canScheduleTasks: Boolean = false,
        val pinned: Boolean = false,
    ) {
        fun toAgent(persona: String) = Agent(
            id = id,
            name = name,
            glyph = glyph,
            persona = persona,
            modelId = modelId,
            skillIds = skillIds,
            accentIndex = accentIndex,
            canScheduleTasks = canScheduleTasks,
            pinned = pinned,
        )

        companion object {
            fun of(agent: Agent) = AgentFile(
                id = agent.id,
                name = agent.name,
                glyph = agent.glyph,
                modelId = agent.modelId,
                skillIds = agent.skillIds,
                accentIndex = agent.accentIndex,
                canScheduleTasks = agent.canScheduleTasks,
                pinned = agent.pinned,
            )
        }
    }

    @Serializable
    internal data class ConfigFile(
        val channels: List<Channel> = emptyList(),
        val installedSkills: List<String> = emptyList(),
    )

    /**
     * 树的元信息。
     *
     * [agents] / [skills] / [groups] 是**顺序**，不是清单——内容来自各自的文件，
     * 扫描目录只能得到字母序（`m-1`、`m-10`、`m-2`），而列表顺序在界面上是可见的
     * （`MeScreen` 直接按顺序铺，技能还 `take(4)` 上首页）。
     *
     * 把顺序放在这里，是因为它**坏了只坏观感**：内容各在各的文件里，
     * 这份丢了最坏情况是列表变成字母序，不会少一条数据。这正符合
     * "坏一个只坏一个"的取舍。
     */
    @Serializable
    internal data class LayoutFile(
        val version: Int = VERSION,
        val agents: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val groups: List<String> = emptyList(),
    )

    /**
     * 技能定义。写成 frontmatter + 正文留白的形状，和 Claude Code 的
     * `.claude/skills/<name>/SKILL.md` 同形——将来要加 `scripts/`、`references/`
     * 时不用改结构。正文暂时是空的（技能内容还只有这几行占位数据）。
     *
     * `installed` 不在这里：那是**这台设备上装没装**，是 App 的状态，
     * 跟着 `config.json` 走。同一个 SKILL.md 分享给别人，不该把"我装过"也带过去。
     */
    internal data class SkillDoc(
        val name: String,
        val summary: String,
        val glyph: String,
    ) {
        fun encode(): String = buildString {
            append("---\n")
            append("name: ").append(name).append('\n')
            append("glyph: ").append(glyph).append('\n')
            append("summary: ").append(summary).append('\n')
            append("---\n")
        }

        companion object {
            fun decode(text: String): SkillDoc? {
                val fields = frontmatter(text) ?: return null
                return SkillDoc(
                    name = fields["name"] ?: return null,
                    summary = fields["summary"].orEmpty(),
                    glyph = fields["glyph"].orEmpty(),
                )
            }
        }
    }

    /**
     * 取 frontmatter 里的键值。没有 frontmatter 就返回 null。
     *
     * 值里可以有冒号（"文档处理：读写"是全角，但也可能有人写半角），
     * 所以只按**第一个**冒号切。
     */
    internal fun frontmatter(text: String): Map<String, String>? {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null

        val fields = mutableMapOf<String, String>()
        lines.subList(1, end + 1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        return fields
    }

    // ── MEMORY.md ───────────────────────────────

    /**
     * 记忆写成 markdown 分节，节名就是设计稿上那五个分类标签。
     *
     * **不往正文里塞 id**：markdown 是给人看和手改的，一行看不见的
     * `<!-- mem-3 -->` 只会让人不敢动它。读回来的 id 由**位置**生成，
     * 形状是 `mem-<agentId>-<n>`——和 `nextId("mem")` 产出的 `mem-<n>` 不会撞。
     */
    fun encodeMemories(agentId: String, memories: List<MemoryItem>): String = buildString {
        val mine = memories.filter { it.agentId == agentId }
        if (mine.isEmpty()) return ""
        MemoryCategory.entries.forEach { category ->
            val items = mine.filter { it.category == category }
            if (items.isEmpty()) return@forEach
            append("## ").append(category.label).append("\n\n")
            items.forEach { append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            append('\n')
        }
    }

    fun decodeMemories(agentId: String, text: String): List<MemoryItem> {
        val byLabel = MemoryCategory.entries.associateBy { it.label }
        val out = mutableListOf<MemoryItem>()
        var category: MemoryCategory? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("##")) {
                category = byLabel[line.removePrefix("##").trim()]
            } else {
                val current = category
                if (current != null && line.startsWith("- ")) {
                    val body = line.removePrefix("- ").trim()
                    if (body.isNotEmpty()) {
                        out += MemoryItem(
                            id = "mem-$agentId-${out.size + 1}",
                            agentId = agentId,
                            category = current,
                            text = body,
                        )
                    }
                }
            }
        }
        return out
    }

    // ── 会话 JSONL ──────────────────────────────

    /**
     * 一行一条消息，行内是完整的 JSON。
     *
     * 用 JSONL 而不是一个大数组：追加一条消息只需要改一行，将来也不必为了
     * "读最后 20 条"把整段会话解出来。图片字节不在这里（`MediaAsset.bytes`
     * 标了 `@Transient`），它们各在各的文件里，这里只留 id 与类型。
     */
    fun encodeMessages(messages: List<ChatMessage>): String =
        if (messages.isEmpty()) "" else messages.joinToString(separator = "\n", postfix = "\n") {
            PersistedJson.encodeToString(it)
        }

    /** 读回一段会话。坏行跳过并计数，不因为一行坏掉就丢掉整段对话。 */
    fun decodeMessages(text: String): Pair<List<ChatMessage>, Int> {
        var broken = 0
        val messages = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { PersistedJson.decodeFromString<ChatMessage>(line) }
                    .onFailure { broken++ }
                    .getOrNull()
            }
            .toList()
        return messages to broken
    }

    // ── 顺序 ────────────────────────────────────

    /**
     * 按 [order] 重排 [items]，顺序里没有的排在后面。
     *
     * 顺序丢了（layout.json 读不出来）时就退化成扫描出来的字母序，
     * 而不是把某条数据丢掉——这也是把顺序和内容分开存的意义。
     */
    fun <T> orderedBy(items: List<T>, order: List<String>, idOf: (T) -> String): List<T> {
        if (order.isEmpty()) return items
        val byId = items.associateBy(idOf)
        val known = order.mapNotNull { byId[it] }
        val rest = items.filter { idOf(it) !in order.toSet() }
        return known + rest
    }

    /** 生成 [LayoutFile] 用的 id 序列。 */
    fun <T> idsOf(items: List<T>, idOf: (T) -> String): List<String> = items.map(idOf)

    /** 把 [AgentTask] 列表编成 `tasks.json`——它本身就是完整内容，不用分文件。 */
    fun encodeTasks(tasks: List<AgentTask>): String = PersistedJson.encodeToString(tasks)

    fun decodeTasks(text: String): List<AgentTask> = PersistedJson.decodeFromString(text)

    fun encodeGroup(group: AgentGroup): String = PersistedJson.encodeToString(group)

    fun decodeGroup(text: String): AgentGroup = PersistedJson.decodeFromString(text)
}
