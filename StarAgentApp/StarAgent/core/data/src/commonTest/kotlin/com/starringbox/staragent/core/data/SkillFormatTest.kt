package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SkillFormat`：agentskills.io 开放规范的读写子集。
 *
 * 覆盖三件事：真实世界里 Claude/Codex 风格的 SKILL.md 能解出正确的字段；
 * 本 App 早先的旧格式（`name` 当显示名、`summary`、顶层 `glyph`）还能解；
 * `encode` 写出来的东西 `parse` 能原样读回来。
 */
class SkillFormatTest {

    @Test
    fun parsesARealClaudeCodexStyleSkillMd() {
        // 引号里带冒号、`description: >-` 折叠多行、嵌套 metadata、allowed-tools 列表、
        // license 这些本 App 不认识的字段——都是别家技能实际会写的样子。
        val text = """
            ---
            name: pdf-filler
            description: >-
              Fill in PDF forms: given a PDF with form fields and a set
              of values, produce a filled-out copy.
            license: MIT
            allowed-tools:
              - read_file
              - write_file
            metadata:
              display-name: "PDF 填表：自动化"
              glyph: 📄
            ---

            # PDF Filler

            1. 读取表单字段。
            2. 填入对应的值。
        """.trimIndent()

        val parsed = SkillFormat.parse(text)

        assertNotNull(parsed)
        assertEquals("pdf-filler", parsed.id)
        assertEquals("PDF 填表：自动化", parsed.displayName)
        assertEquals(
            "Fill in PDF forms: given a PDF with form fields and a set of values, produce a filled-out copy.",
            parsed.description,
        )
        assertEquals("📄", parsed.glyph)
        assertTrue(parsed.modelInvocable)
        assertTrue(parsed.userInvocable)
        assertTrue(parsed.body.contains("# PDF Filler"))
        assertTrue(parsed.body.contains("1. 读取表单字段。"))
    }

    @Test
    fun parsesAQuotedDescriptionContainingAColon() {
        // 规范里 description 常见的写法：一句话里带冒号，必须加引号才不会被当成新的键
        val text = """
            ---
            name: web-search
            description: "用于需要最新信息的问题：先搜索，再核实来源"
            ---
        """.trimIndent()

        val parsed = SkillFormat.parse(text)

        assertEquals("用于需要最新信息的问题：先搜索，再核实来源", parsed?.description)
    }

    @Test
    fun disableModelInvocationAndUserInvocableFlagsAreRespected() {
        val text = """
            ---
            name: manual-only
            description: 只能手动调用
            disable-model-invocation: true
            ---
        """.trimIndent()
        val parsed = SkillFormat.parse(text)
        assertNotNull(parsed)
        assertFalse(parsed.modelInvocable)
        assertTrue(parsed.userInvocable)

        val text2 = """
            ---
            name: model-only
            description: 只能模型调用
            user-invocable: false
            ---
        """.trimIndent()
        val parsed2 = SkillFormat.parse(text2)
        assertNotNull(parsed2)
        assertTrue(parsed2.modelInvocable)
        assertFalse(parsed2.userInvocable)
    }

    @Test
    fun legacyFormatWithDisplayNameSummaryAndTopLevelGlyphStillParses() {
        // 本 App 早先的写法：name 是显示名，不是 id；summary 不是 description；glyph 在顶层
        val text = "---\nname: 网页检索\nglyph: 🔍\nsummary: 联网搜索并读取网页正文\n---\n正文内容"

        val parsed = SkillFormat.parse(text)

        assertNotNull(parsed)
        assertNull(parsed.id, "旧写法里 name 是显示名，不是合法 id，不该被当成 id")
        assertEquals("网页检索", parsed.displayName)
        assertEquals("联网搜索并读取网页正文", parsed.description)
        assertEquals("🔍", parsed.glyph)
        assertEquals("正文内容", parsed.body)
    }

    @Test
    fun legacyDisplayNameWithoutMetadataFallsBackToTopLevelName() {
        val text = "---\nname: 网页检索\nsummary: 说明\n---\n"
        val parsed = SkillFormat.parse(text)
        assertEquals("网页检索", parsed?.displayName)
    }

    @Test
    fun noFrontmatterReturnsNull() {
        assertNull(SkillFormat.parse("这是一段没有 frontmatter 的纯文本。"))
    }

    @Test
    fun bodyWithoutFrontmatterIsTheWholeText() {
        val text = "整段都是正文，没有 frontmatter。"
        assertEquals(text, SkillFormat.body(text))
    }

    @Test
    fun frontmatterNestedMetadataFlattensToDotKeys() {
        val text = """
            ---
            name: x
            description: d
            metadata:
              display-name: 展示名
              glyph: 🧩
            ---
        """.trimIndent()
        val fields = SkillFormat.frontmatter(text)
        assertNotNull(fields)
        assertEquals("展示名", fields["metadata.display-name"])
        assertEquals("🧩", fields["metadata.glyph"])
    }

    @Test
    fun frontmatterDashListAndInlineListBothJoinWithCommaSpace() {
        val text = """
            ---
            name: x
            description: d
            allowed-tools:
              - read_file
              - write_file
            tags: [a, b, c]
            ---
        """.trimIndent()
        val fields = SkillFormat.frontmatter(text)
        assertNotNull(fields)
        assertEquals("read_file, write_file", fields["allowed-tools"])
        assertEquals("a, b, c", fields["tags"])
    }

    @Test
    fun blockScalarPipeKeepsNewlinesWhileFoldedGreaterThanJoinsLines() {
        val text = """
            ---
            name: x
            literal: |
              line one
              line two
            folded: >
              line one
              line two
            description: d
            ---
        """.trimIndent()
        val fields = SkillFormat.frontmatter(text)
        assertNotNull(fields)
        assertEquals("line one\nline two", fields["literal"])
        assertEquals("line one line two", fields["folded"])
    }

    // ── encode → parse 往返 ──────────────────────────────

    @Test
    fun encodeThenParseRoundTripsNameSummaryGlyphAndBody() {
        val skill = Skill(
            id = "weekly-report",
            name = "周报整理",
            summary = "把一周的进展整理成周报",
            glyph = "📊",
            installed = true,
            instructions = "1. 收集进展。\n2. 按模板整理。",
        )

        val encoded = SkillFormat.encode(skill)
        val parsed = SkillFormat.parse(encoded)

        assertNotNull(parsed)
        assertEquals(skill.id, parsed.id)
        assertEquals(skill.name, parsed.displayName)
        assertEquals(skill.summary, parsed.description)
        assertEquals(skill.glyph, parsed.glyph)
        assertEquals(skill.instructions, parsed.body)
        assertTrue(parsed.modelInvocable)
        assertTrue(parsed.userInvocable)
    }

    @Test
    fun encodeThenParseRoundTripsModelInvocableFalse() {
        val skill = Skill(
            id = "manual-only",
            name = "仅手动",
            summary = "只能用户手动调用",
            glyph = "🔧",
            modelInvocable = false,
        )

        val parsed = SkillFormat.parse(SkillFormat.encode(skill))

        assertNotNull(parsed)
        assertFalse(parsed.modelInvocable)
        assertTrue(parsed.userInvocable)
    }

    @Test
    fun encodeThenParseRoundTripsUserInvocableFalse() {
        val skill = Skill(
            id = "model-only",
            name = "仅模型",
            summary = "只能模型调用",
            glyph = "🤖",
            userInvocable = false,
        )

        val parsed = SkillFormat.parse(SkillFormat.encode(skill))

        assertNotNull(parsed)
        assertTrue(parsed.modelInvocable)
        assertFalse(parsed.userInvocable)
    }

    @Test
    fun encodeQuotesADescriptionContainingAColonSoItRoundTrips() {
        // 不加引号的话 "做什么：怎么做" 会被当成多出一个键，往返就会丢数据
        val skill = Skill(
            id = "x",
            name = "x",
            summary = "做什么：怎么做",
            glyph = "🧩",
        )

        val parsed = SkillFormat.parse(SkillFormat.encode(skill))

        assertEquals("做什么：怎么做", parsed?.description)
    }

    @Test
    fun encodeOmitsEmptyInstructionsBodySection() {
        val skill = Skill(id = "x", name = "x", summary = "s", glyph = "🧩", instructions = "")
        val encoded = SkillFormat.encode(skill)
        assertEquals("", SkillFormat.body(encoded))
    }

    // ── isValidId / normalizeId ──────────────────────────────

    @Test
    fun isValidIdAcceptsLowercaseAlphanumericAndHyphens() {
        assertTrue(SkillFormat.isValidId("pdf-filler"))
        assertTrue(SkillFormat.isValidId("a"))
        assertTrue(SkillFormat.isValidId("a1-b2"))
    }

    @Test
    fun isValidIdRejectsUppercaseSpacesAndEdgeHyphens() {
        assertFalse(SkillFormat.isValidId("PDF-Filler"), "大写不合法")
        assertFalse(SkillFormat.isValidId("pdf filler"), "空格不合法")
        assertFalse(SkillFormat.isValidId("-pdf"), "不能以连字符开头")
        assertFalse(SkillFormat.isValidId("pdf-"), "不能以连字符结尾")
        assertFalse(SkillFormat.isValidId(""), "空字符串不合法")
    }

    @Test
    fun isValidIdRejectsIdsLongerThanSixtyFourChars() {
        val tooLong = "a".repeat(65)
        assertFalse(SkillFormat.isValidId(tooLong))
        val exactly64 = "a".repeat(64)
        assertTrue(SkillFormat.isValidId(exactly64))
    }

    @Test
    fun normalizeIdLowercasesAndCollapsesNonAlphanumericToHyphens() {
        assertEquals("pdf-filler", SkillFormat.normalizeId("PDF Filler"))
        assertEquals("pdf-filler", SkillFormat.normalizeId("pdf_filler!!"))
        assertEquals("a-b-c", SkillFormat.normalizeId("a   b---c"))
    }

    @Test
    fun normalizeIdTrimsLeadingAndTrailingHyphens() {
        assertEquals("abc", SkillFormat.normalizeId("--abc--"))
    }

    @Test
    fun normalizeIdReturnsNullWhenResultIsEmpty() {
        assertNull(SkillFormat.normalizeId("!!!"))
        assertNull(SkillFormat.normalizeId("   "))
        assertNull(SkillFormat.normalizeId(""))
    }

    @Test
    fun normalizeIdTruncatesToSixtyFourChars() {
        val raw = "a".repeat(100)
        val normalized = SkillFormat.normalizeId(raw)
        assertNotNull(normalized)
        assertTrue(normalized.length <= SkillFormat.MAX_ID_LENGTH)
    }
}
