package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.SkillFormat
import com.starringbox.staragent.core.data.StateTree
import com.starringbox.staragent.core.model.SkillSource
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.tools.ports.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import com.starringbox.staragent.core.llm.httpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

private fun agentRoot(agentId: String) = "agent-sandbox/${agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
private fun cleanPath(path: String): String? {
    val value = path.trim().replace('\\', '/')
    if (value.isBlank() || value.startsWith('/') || value.contains("..") || value.contains('\u0000')) return null
    return value.trimStart('/')
}

class AppFilePort : FilePort {
    override fun list(agentId: String, path: String): List<FileEntry> {
        val relative = cleanPath(path) ?: ""
        val prefix = agentRoot(agentId) + if (relative.isBlank()) "/" else "/$relative/"
        val files = LocalStores.current.list(prefix.removeSuffix("/"))
        return files.map { it.removePrefix("$prefix").let { p -> FileEntry(p, LocalStores.current.size(it) ?: 0L, false) } }
    }
    override fun read(agentId: String, path: String): String? = cleanPath(path)?.let { LocalStores.current.readText("${agentRoot(agentId)}/$it") }
    override fun readBytes(agentId: String, path: String): ByteArray? =
        cleanPath(path)?.let { LocalStores.current.readBytes("${agentRoot(agentId)}/$it") }

    override fun writeBytes(agentId: String, path: String, content: ByteArray): FileOperationResult {
        val p = cleanPath(path) ?: return FileOperationResult(false, "路径不安全或为空")
        if (!LocalStores.available) return FileOperationResult(false, "本地文件存储未安装")
        LocalStores.current.writeBytes("${agentRoot(agentId)}/$p", content)
        return FileOperationResult(true, "已写入 $p")
    }

    override fun write(agentId: String, path: String, content: String): FileOperationResult {
        val p = cleanPath(path) ?: return FileOperationResult(false, "路径不安全或为空")
        if (!LocalStores.available) return FileOperationResult(false, "本地文件存储未安装")
        LocalStores.current.writeText("${agentRoot(agentId)}/$p", content)
        return FileOperationResult(true, "已写入 $p")
    }
    override fun delete(agentId: String, path: String): FileOperationResult {
        val p = cleanPath(path) ?: return FileOperationResult(false, "路径不安全或为空")
        LocalStores.current.delete("${agentRoot(agentId)}/$p")
        return FileOperationResult(true, "已删除 $p")
    }
}

/**
 * 技能端口。
 *
 * [isSuppressed] 和记忆端口是同一道闸门：这一轮读到过外部内容（网页正文等）时为真，
 * 本轮一律不能安装或创建技能。技能比记忆更危险——它是每轮都会被模型当成做事说明去执行的文字，
 * 一段网页把自己写成技能，就成了一条永久生效的注入。OpenClaw 的 ClawHub 投毒和 Hermes 的
 * 自动改写技能，都是"不受信的文字进了技能"这一类问题。
 */
class AppSkillPort(
    private val state: AppState,
    private val isSuppressed: () -> Boolean = { false },
) : SkillPort {
    override fun list() = state.skills.map {
        SkillSummary(it.id, it.name, it.summary, it.installed, it.source.label, it.modelInvocable)
    }

    override fun setInstalled(id: String, installed: Boolean): Boolean {
        return state.setSkillInstalled(id, installed)
    }

    override fun installFromFile(agentId: String, path: String): SkillInstallResult {
        if (isSuppressed()) return suppressed()
        val clean = cleanPath(path)?.trimEnd('/') ?: return SkillInstallResult(false, message = "Skill 路径不安全")
        val root = agentRoot(agentId)
        val file = if (clean.endsWith(".md", ignoreCase = true)) clean else "$clean/SKILL.md"
        val text = LocalStores.current.readText("$root/$file")
            ?: return SkillInstallResult(false, message = "找不到 Skill 文件：$file")

        // 没有 frontmatter 的纯 markdown 也能装：整段当正文，id 和名字取自路径
        val parsed = SkillFormat.parse(text)
        val body = parsed?.body ?: SkillFormat.body(text)
        val isSkillFile = file.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)
        val dir = file.substringBeforeLast('/', "")
        val fromPath = if (isSkillFile) dir.substringAfterLast('/') else file.substringAfterLast('/').substringBeforeLast('.')
        val id = parsed?.id ?: SkillFormat.normalizeId(fromPath)
            ?: return SkillInstallResult(false, message = "Skill id 不合法：frontmatter 的 name 和路径里都取不出合法的 id")
        if (state.skills.any { it.id == id }) return SkillInstallResult(false, id, "Skill 已存在：$id。先卸载旧的再装。")

        val description = parsed?.description?.takeIf { it.isNotBlank() }
            ?: body.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val skill = Skill(
            id = id,
            name = parsed?.displayName ?: id,
            summary = description.take(SkillFormat.MAX_DESCRIPTION_LENGTH),
            glyph = parsed?.glyph?.takeIf { it.isNotBlank() } ?: "🧩",
            installed = true,
            instructions = body,
            source = SkillSource.Imported,
            modelInvocable = parsed?.modelInvocable ?: true,
            userInvocable = parsed?.userInvocable ?: true,
            document = text,
        )

        // 只有 SKILL.md 在子目录里时才带上同目录的文件：在沙箱根目录的话，"同目录"就是整个沙箱
        val copied = if (isSkillFile && dir.isNotEmpty()) copyResources(from = "$root/$dir", skillId = id) else 0
        state.addSkill(skill)
        val extra = if (copied > 0) "，附带 $copied 个文件" else ""
        return SkillInstallResult(true, id, "已安装 Skill：${skill.name}（id=$id$extra）")
    }

    override fun remove(id: String): SkillInstallResult {
        val skill = state.skills.firstOrNull { it.id == id }
            ?: return SkillInstallResult(false, id, "找不到可卸载的 Skill：$id")
        // 内置技能只能停用：删掉之后没有任何地方能把它装回来
        if (skill.source == SkillSource.BuiltIn) {
            return SkillInstallResult(false, id, "「${skill.name}」是内置技能，不能删除，只能停用（set_skill_enabled）。")
        }
        state.removeSkill(id)
        return SkillInstallResult(true, id, "Skill 已卸载：${skill.name}")
    }

    override fun read(id: String): SkillDetail? = state.skills.firstOrNull { it.id == id }?.let {
        SkillDetail(it.id, it.name, it.summary, it.installed, it.instructions, it.modelInvocable, state.skillResources(it.id))
    }

    override fun readFile(id: String, path: String): String? {
        val skill = state.skills.firstOrNull { it.id == id && it.installed } ?: return null
        val clean = cleanPath(path) ?: return null
        if (clean.equals("SKILL.md", ignoreCase = true)) return skill.instructions
        return LocalStores.current.readText("${StateTree.skillDir(skill.id)}/$clean")
    }

    override fun create(draft: SkillDraft): SkillInstallResult {
        if (isSuppressed()) return suppressed()
        val id = draft.id.trim()
        if (!SkillFormat.isValidId(id)) {
            return SkillInstallResult(false, message = "id 不合法：只能用小写字母、数字和连字符，不能以连字符开头或结尾，最长 64 个字符")
        }
        val description = draft.description.replace('\n', ' ').trim()
        val instructions = draft.instructions.trim()
        if (description.isBlank() || instructions.isBlank()) {
            return SkillInstallResult(false, id, "description 和 instructions 都不能为空")
        }
        if (description.length > MAX_AGENT_DESCRIPTION) {
            return SkillInstallResult(false, id, "description 太长（上限 $MAX_AGENT_DESCRIPTION 字）：写清做什么、什么时候用即可")
        }
        if (instructions.length > MAX_AGENT_INSTRUCTIONS) {
            return SkillInstallResult(false, id, "instructions 太长（上限 $MAX_AGENT_INSTRUCTIONS 字），请精简")
        }

        // Hermes 的教训：自我改进的循环会改写内置和第三方技能。这里只许改它自己建的
        val existing = state.skills.firstOrNull { it.id == id }
        if (existing != null && existing.source != SkillSource.Agent) {
            return SkillInstallResult(false, id, "「${existing.name}」是${existing.source.label}技能，智能体不能改写。换一个 id 新建。")
        }
        if (existing == null && state.skills.count { it.source == SkillSource.Agent } >= MAX_AGENT_SKILLS) {
            return SkillInstallResult(false, id, "智能体创建的技能已达上限（$MAX_AGENT_SKILLS 个），请用户先清理不用的。")
        }

        val skill = Skill(
            id = id,
            name = draft.name.trim().ifBlank { id },
            summary = description,
            glyph = existing?.glyph ?: "✨",
            installed = existing?.installed ?: true,
            instructions = instructions,
            source = SkillSource.Agent,
        )
        state.saveAgentSkill(skill)
        val verb = if (existing == null) "创建" else "更新"
        return SkillInstallResult(true, id, "已${verb}技能「${skill.name}」（id=$id）")
    }

    private fun copyResources(from: String, skillId: String): Int {
        var copied = 0
        LocalStores.current.list(from)
            .map { it to it.removePrefix("$from/") }
            .filterNot { (_, relative) -> relative.equals("SKILL.md", ignoreCase = true) }
            .take(MAX_RESOURCE_FILES)
            .forEach { (full, relative) ->
                val bytes = LocalStores.current.readBytes(full) ?: return@forEach
                if (bytes.size > MAX_RESOURCE_BYTES) return@forEach
                LocalStores.current.writeBytes("${StateTree.skillDir(skillId)}/$relative", bytes)
                copied++
            }
        return copied
    }

    private fun suppressed() = SkillInstallResult(
        false,
        message = "这一轮读取过外部内容（网页等），为防止外部文字被写成技能，本轮不能安装或创建技能。请向用户说明，在新的一轮里再试。",
    )

    private companion object {
        const val MAX_AGENT_SKILLS = 50
        const val MAX_AGENT_DESCRIPTION = 300
        const val MAX_AGENT_INSTRUCTIONS = 8_000
        const val MAX_RESOURCE_FILES = 64
        const val MAX_RESOURCE_BYTES = 1_000_000
    }
}

class AppSearchPort : SearchPort {
    override suspend fun search(query: String, limit: Int): SearchResult = runCatching {
        val url = "https://html.duckduckgo.com/html/?q=" + encode(query)
        val html = httpClient().get(url).bodyAsText()
        val items = Regex("(?is)<a[^>]+class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>")
            .findAll(html).take(limit).map { m ->
                SearchItem(strip(m.groupValues[2]), m.groupValues[1], "")
            }.toList()
        SearchResult(true, items)
    }.getOrElse { SearchResult(false, emptyList(), "搜索失败：${it.message ?: "网络错误"}") }

    private fun encode(value: String) = value.trim().replace(" ", "+")
    private fun strip(value: String) = value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

class AppBrowserPort(private val web: WebPort) : BrowserPort {
    override suspend fun open(url: String): BrowserResult {
        if (!url.startsWith("https://")) return BrowserResult(false, error = "只支持 https 地址")
        return if (com.starringbox.staragent.core.platform.platformOpenUrl(url)) {
            BrowserResult(true, "已在系统浏览器打开：$url")
        } else {
            BrowserResult(false, error = "当前平台无法打开系统浏览器")
        }
    }

    override suspend fun read(url: String): BrowserResult {
        val result = web.fetch(url)
        return if (result.ok) BrowserResult(true, result.text, externalContext = true)
        else BrowserResult(false, error = result.error ?: "网页读取失败")
    }
}
