package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MemoryCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 记忆块的解析。
 *
 * 这段代码的存在本身就是为了堵一个真机上的漏：智能体在对话里说"记住了"，
 * 而记忆页里空空如也——因为压根没有人写。所以这里的用例重点不在"格式多好看"，
 * 而在**该写的一定写得进去、不该写的绝不误写**。
 */
class MemoryBlockTest {

    @Test
    fun aBlockIsLiftedOutOfTheReply() {
        val parsed = MemoryBlock.extract(
            """
            好的，我记下了。
            ```memory
            {"text":"用户 1995 年生","category":"person"}
            ```
            """.trimIndent()
        )!!

        assertEquals(1, parsed.items.size)
        assertEquals("用户 1995 年生", parsed.items.first().text)
        assertEquals(MemoryCategory.Person, parsed.items.first().category)
        // 正文里不能再留着那个块，否则用户会看到一串 JSON
        assertEquals("好的，我记下了。", parsed.remainingText)
    }

    @Test
    fun everyCategoryMapsToItsOwnBucket() {
        fun category(raw: String) = MemoryBlock.extract(
            "```memory\n{\"text\":\"x\",\"category\":\"$raw\"}\n```"
        )!!.items.first().category

        assertEquals(MemoryCategory.Preference, category("preference"))
        assertEquals(MemoryCategory.Person, category("person"))
        assertEquals(MemoryCategory.Habit, category("habit"))
        assertEquals(MemoryCategory.Experience, category("experience"))
        assertEquals(MemoryCategory.Decision, category("decision"))
    }

    /** 认不出来的分类归到最常见的一类，而不是整条丢掉——记住了比归对类更重要。 */
    @Test
    fun anUnknownCategoryFallsBackInsteadOfDropping() {
        val parsed = MemoryBlock.extract(
            "```memory\n{\"text\":\"用户喜欢喝美式\",\"category\":\"coffee\"}\n```"
        )!!

        assertEquals(MemoryCategory.Preference, parsed.items.first().category)
    }

    /** 「我 95 年的，住杭州，养了只猫」这种一句话里三件事的，不能只记第一条。 */
    @Test
    fun severalBlocksBecomeSeveralMemories() {
        val parsed = MemoryBlock.extract(
            """
            好，都记下了。
            ```memory
            {"text":"用户 1995 年生","category":"person"}
            ```
            ```memory
            {"text":"用户住在杭州","category":"habit"}
            ```
            """.trimIndent()
        )!!

        assertEquals(2, parsed.items.size)
        assertEquals(listOf("用户 1995 年生", "用户住在杭州"), parsed.items.map { it.text })
        assertEquals("好，都记下了。", parsed.remainingText)
    }

    /** 模型写坏了就整条放过：宁可让它那句原样显示，也不要假装记下了。 */
    @Test
    fun aMalformedBlockIsLeftAlone() {
        val raw = "好的。\n```memory\n{这不是 JSON}\n```"
        assertNull(MemoryBlock.extract(raw))
    }

    @Test
    fun aBlockWithoutTextIsIgnored() {
        assertNull(MemoryBlock.extract("好的。\n```memory\n{\"category\":\"person\"}\n```"))
        assertNull(MemoryBlock.extract("好的。\n```memory\n{\"text\":\"   \",\"category\":\"person\"}\n```"))
    }

    /** 大多数回复里根本没有记忆块，这条路径必须原样返回——不能顺手改标点或去空白。 */
    @Test
    fun anOrdinaryReplyIsNotTouched() {
        assertNull(MemoryBlock.extract("今天杭州有雨，记得带伞。"))
    }

    /** 代码块以外的文本一个字都不能丢。 */
    @Test
    fun theTextAroundTheBlockSurvivesIntact() {
        val parsed = MemoryBlock.extract(
            """
            第一行。
            ```memory
            {"text":"用户喜欢喝美式","category":"preference"}
            ```
            最后一行。
            """.trimIndent()
        )!!

        assertTrue(parsed.remainingText.contains("第一行。"))
        assertTrue(parsed.remainingText.contains("最后一行。"))
        assertTrue(!parsed.remainingText.contains("memory"))
    }
}
