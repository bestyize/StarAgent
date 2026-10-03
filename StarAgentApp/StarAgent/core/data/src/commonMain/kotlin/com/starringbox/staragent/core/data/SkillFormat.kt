package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Skill

/**
 * SKILL.md 的读写，按 agentskills.io 开放规范来——Claude、Codex、Hermes、OpenClaw 都认这个格式，
 * 照着写，别家的技能拿来就能装，这边的技能也能直接拿去别家用。
 *
 * 规范只要求两个字段：`name`（技能 id，同时是目录名）和 `description`（做什么、什么时候用）。
 * 本 App 自己的展示字段放进规范留给扩展的 `metadata` 里，不占用顶层字段名。
 *
 * 读的时候兼容两种旧写法：本 App 早先的 `name` 当显示名、`summary` 当简介、`glyph` 在顶层；
 * 以及 Claude Code 扩展的 `disable-model-invocation` / `user-invocable`。
 */
internal object SkillFormat {

    /** 规范的 name 规则：小写字母、数字、连字符，不能以连字符开头或结尾，最长 64。 */
    private val ID_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    const val MAX_ID_LENGTH = 64
    const val MAX_DESCRIPTION_LENGTH = 1024

    fun isValidId(id: String): Boolean = id.length <= MAX_ID_LENGTH && ID_PATTERN.matches(id)

    /** 把任意名字（目录名、文件名、旧版 id）规整成合法 id；规整完是空的返回 null。 */
    fun normalizeId(raw: String): String? = raw.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_ID_LENGTH)
        .trimEnd('-')
        .takeIf { it.isNotEmpty() }

    /** 一份 SKILL.md 解出来的内容。[id] 是 frontmatter 里合法的 `name`，不合法或缺省时为 null，由调用方按目录名补。 */
    data class Parsed(
        val id: String?,
        val displayName: String?,
        val description: String,
        val glyph: String,
        val modelInvocable: Boolean,
        val userInvocable: Boolean,
        val body: String,
    )

    /** 没有 frontmatter 返回 null。 */
    fun parse(text: String): Parsed? {
        val fields = frontmatter(text) ?: return null
        val name = fields["name"]?.takeIf { it.isNotBlank() }
        val validId = name?.takeIf { isValidId(it) }
        return Parsed(
            id = validId,
            // 旧写法的 name 是显示名（"网页检索"），不是 id；新写法的显示名在 metadata 里
            displayName = fields["metadata.display-name"]?.takeIf { it.isNotBlank() } ?: name,
            description = (fields["description"] ?: fields["summary"]).orEmpty().take(MAX_DESCRIPTION_LENGTH),
            glyph = (fields["metadata.glyph"] ?: fields["glyph"]).orEmpty(),
            modelInvocable = fields["disable-model-invocation"]?.lowercase() != "true",
            userInvocable = fields["user-invocable"]?.lowercase() != "false",
            body = body(text),
        )
    }

    /** 按规范格式写出。只写本 App 认识的字段；导入技能的原文另存在 [Skill.document] 里，不经过这里。 */
    fun encode(skill: Skill): String = buildString {
        append("---\n")
        append("name: ").append(skill.id).append('\n')
        append("description: ").append(yamlScalar(skill.summary)).append('\n')
        if (!skill.modelInvocable) append("disable-model-invocation: true\n")
        if (!skill.userInvocable) append("user-invocable: false\n")
        append("metadata:\n")
        append("  display-name: ").append(yamlScalar(skill.name)).append('\n')
        if (skill.glyph.isNotBlank()) append("  glyph: ").append(yamlScalar(skill.glyph)).append('\n')
        append("---\n")
        if (skill.instructions.isNotBlank()) append('\n').append(skill.instructions.trim()).append('\n')
    }

    /**
     * 取 frontmatter 里的键值。没有 frontmatter 返回 null。
     *
     * 是 YAML 的一个子集，覆盖 SKILL.md 里实际会出现的写法：普通值、单双引号值、`|` / `>` 块值、
     * 一层嵌套的映射（`metadata:` 下缩进的键，展开成 `metadata.键`）、`- 项` 列表和 `[a, b]` 行内列表
     * （都合成一个逗号分隔的字符串）。别家技能常把 description 写成多行块值，旧的"按第一个冒号切"
     * 会把它读成一个 `>` 字符。
     */
    fun frontmatter(text: String): Map<String, String>? {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        val block = lines.subList(1, end + 1)

        val fields = linkedMapOf<String, String>()
        var i = 0
        while (i < block.size) {
            val line = block[i]
            i++
            if (line.isBlank() || line.trimStart().startsWith("#") || indentOf(line) > 0) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val raw = line.substring(colon + 1).trim()

            // 这一键下面缩进的那些行
            val childStart = i
            while (i < block.size && (block[i].isBlank() || indentOf(block[i]) > 0)) i++
            val children = block.subList(childStart, i)

            when {
                raw.startsWith("|") || raw.startsWith(">") -> fields[key] = blockScalar(children, folded = raw.startsWith(">"))
                raw.isEmpty() -> nested(key, children, fields)
                else -> fields[key] = scalar(raw)
            }
        }
        return fields
    }

    /** frontmatter 之后的正文，去掉首尾空白。没有 frontmatter 时整段都算正文。 */
    fun body(text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return text.trim()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return text.trim()
        return lines.drop(end + 2).joinToString("\n").trim()
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    private fun nested(key: String, children: List<String>, into: MutableMap<String, String>) {
        val items = children.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (items.isEmpty()) {
            into[key] = ""
            return
        }
        if (items.first().trimStart().startsWith("- ") || items.first().trim() == "-") {
            into[key] = items.map { scalar(it.trim().removePrefix("-").trim()) }.filter { it.isNotEmpty() }.joinToString(", ")
            return
        }
        // 只认一层：子键的缩进以第一个子键为准，更深的行不单独展开
        val childIndent = indentOf(items.first())
        items.filter { indentOf(it) == childIndent }.forEach { child ->
            val colon = child.indexOf(':')
            if (colon > 0) into["$key.${child.substring(0, colon).trim()}"] = scalar(child.substring(colon + 1).trim())
        }
    }

    /** `|` 保留换行，`>` 把相邻行折成一行、空行变换行。去掉公共缩进和首尾空白。 */
    private fun blockScalar(children: List<String>, folded: Boolean): String {
        val indent = children.filter { it.isNotBlank() }.minOfOrNull { indentOf(it) } ?: return ""
        val lines = children.map { if (it.isBlank()) "" else it.drop(indent) }
        if (!folded) return lines.joinToString("\n").trim()
        return lines.joinToString("\n")
            .split(Regex("\n{2,}"))
            .joinToString("\n") { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
            .trim()
    }

    private fun scalar(raw: String): String {
        val value = raw.trim()
        return when {
            value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                value.substring(1, value.length - 1)
                    .replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
            value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
                value.substring(1, value.length - 1).replace("''", "'")
            value.startsWith("[") && value.endsWith("]") ->
                value.substring(1, value.length - 1).split(',').map { scalar(it) }.filter { it.isNotEmpty() }.joinToString(", ")
            // 行尾注释：YAML 里 " #" 之后是注释
            else -> value.substringBefore(" #").trim()
        }
    }

    /** 需要时加双引号。描述里常有冒号、井号，原样写出去别家的 YAML 解析器会读错。 */
    private fun yamlScalar(value: String): String {
        val single = value.replace('\n', ' ').trim()
        val reserved = single.isEmpty() ||
            single.lowercase() in setOf("true", "false", "yes", "no", "null", "~") ||
            single.toDoubleOrNull() != null ||
            single.first() in "-?:,[]{}#&*!|>'\"%@`" ||
            ": " in single || " #" in single || single.endsWith(":")
        if (!reserved) return single
        return "\"" + single.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
