package com.starringbox.staragent.core.platform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 存储名字的准入规则。
 *
 * 这组测试守的是一条以前靠约定、现在靠代码的边界：`LocalStore` 的名字能带 `/`，
 * 于是 `..` 或绝对路径就能写到根目录外面去。目录树落地之后名字会变多
 * （`agents/<id>/MEMORY.md` 这种），所以这条边界值得有测试钉住。
 */
class StorePathsTest {

    // ── 合法的名字 ───────────────────────────────

    @Test
    fun aPlainNameComesBackUnchanged() {
        assertEquals("state.json", StorePaths.normalize("state.json"))
    }

    @Test
    fun nestedPathsAreKeptAsWritten() {
        assertEquals("agents/m-1/MEMORY.md", StorePaths.normalize("agents/m-1/MEMORY.md"))
    }

    @Test
    fun windowsAuthoredSeparatorsBecomeForwardSlashes() {
        // 名字由代码写死，但写的人可能顺手用反斜杠；两种分隔符该收敛成一种，
        // 否则同一个文件会在不同平台上被当成两个名字。
        assertEquals("agents/m-1/MEMORY.md", StorePaths.normalize("agents\\m-1\\MEMORY.md"))
    }

    @Test
    fun redundantSeparatorsAndCurrentDirectorySegmentsAreCollapsed() {
        assertEquals("agents/m-1", StorePaths.normalize("agents//m-1"))
        assertEquals("agents/m-1", StorePaths.normalize("./agents/./m-1"))
    }

    @Test
    fun anIdWithDotsInsideIsNotMistakenForTraversal() {
        // `..` 是**整段**等于才危险；`v1..2` 或 `a.md` 只是普通文件名。
        assertEquals("backups/state.v1..json", StorePaths.normalize("backups/state.v1..json"))
        assertEquals("a.md", StorePaths.normalize("a.md"))
    }

    // ── 拒绝 ────────────────────────────────────

    @Test
    fun traversalIsRejectedWhereverItAppears() {
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("../escape") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("agents/../../escape") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("agents/..") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("..\\escape") }
    }

    @Test
    fun absolutePathsAreRejected() {
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("\\Windows\\system32") }
    }

    @Test
    fun driveLettersAreRejected() {
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("C:/Users/x") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("C:foo") }
    }

    @Test
    fun blankAndSeparatorOnlyNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("   ") }
        // 全都是分隔符和 `.`，规范化之后什么都不剩
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("./.") }
        assertFailsWith<IllegalArgumentException> { StorePaths.normalize("/") }
    }

    /**
     * 抛的是 `IllegalArgumentException` 而**不是**被悄悄改写。
     *
     * 悄悄去掉 `..` 会让两个不同的名字映射到同一个文件，比直接崩更难查。
     * `list()` 那条路径上抛异常会被 `runCatching` 吞掉退化成空列表——
     * 那是**读**的降级；这里守的是**写**，写必须响亮地失败。
     */
    @Test
    fun rejectionIsLoudAndDoesNotRewriteTheName() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StorePaths.normalize("agents/../../state.json")
        }
        assertEquals("存储名字不能跑出根目录：agents/../../state.json", failure.message)
    }
}
