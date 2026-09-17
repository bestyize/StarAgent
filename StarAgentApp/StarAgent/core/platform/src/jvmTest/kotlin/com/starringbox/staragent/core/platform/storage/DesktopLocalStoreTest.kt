package com.starringbox.staragent.core.platform.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 桌面实现上真正会碰文件系统的那几件事：递归列出、按大小跳过、以及**不许跑出根目录**。
 *
 * 这条存储契约是目录树和文件浏览器的地基，而它的两个实现（桌面 / Android）逻辑是一份抄的——
 * 这里测住桌面这份，Android 那份同理。
 *
 * 每个用例用**自己的临时目录**当根，互不干扰，也不会碰用户真实的 `~/.staragent`。
 */
class DesktopLocalStoreTest {

    private val root: File = Files.createTempDirectory("staragent-store-test").toFile()

    private val store = DesktopLocalStore(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    // ── list ────────────────────────────────────

    @Test
    fun listingTheRootWalksTheWholeTree() {
        store.writeText("agents/m-1/agent.json", "{}")
        store.writeText("agents/m-1/MEMORY.md", "# 记忆")
        store.writeText("tasks/tasks.json", "{}")

        assertContentEquals(
            listOf("agents/m-1/MEMORY.md", "agents/m-1/agent.json", "tasks/tasks.json"),
            store.list(""),
        )
    }

    @Test
    fun listingReturnsForwardSlashesRegardlessOfPlatform() {
        // 路径要拿去和目录树的名字对齐，也必须能直接显示在界面上，
        // 所以不能原样把 Windows 的反斜杠透出去。
        store.writeText("agents/m-1/agent.json", "{}")

        val listed = store.list("agents")
        assertContentEquals(listOf("agents/m-1/agent.json"), listed)
        assertFalse(listed.single().contains('\\'), "路径里不该出现反斜杠")
    }

    @Test
    fun listingASubtreeReturnsPathsRelativeToTheRoot() {
        store.writeText("agents/m-1/agent.json", "{}")
        store.writeText("tasks/tasks.json", "{}")

        assertContentEquals(listOf("agents/m-1/agent.json"), store.list("agents"))
    }

    /**
     * 前缀按**目录**算，不是按字符串。
     *
     * 否则 `list("agents")` 会捎带上 `agents-extra/`，而未来的迁移和浏览器都会照着
     * 这份列表去决定"树里有什么"。
     */
    @Test
    fun aPrefixDoesNotLeakSiblingDirectories() {
        store.writeText("agents/m-1/agent.json", "{}")
        store.writeText("agents-extra/note.txt", "x")

        assertContentEquals(listOf("agents/m-1/agent.json"), store.list("agents"))
    }

    @Test
    fun onlyFilesAreListedNeverBareDirectories() {
        store.writeText("agents/m-1/agent.json", "{}")

        // `agents` 和 `agents/m-1` 都是目录，不该作为条目出现
        assertFalse(store.list("").contains("agents"))
        assertFalse(store.list("").contains("agents/m-1"))
    }

    @Test
    fun anEmptyStoreListsNothing() {
        assertTrue(store.list("").isEmpty())
    }

    // ── 不该崩 ──────────────────────────────────

    /**
     * `list()` 会被用在启动路径上——那里宁可少读也不能崩。
     *
     * 所以"目录不存在"和"名字根本不合法"都退化成空列表，而不是抛出来把启动打断。
     */
    @Test
    fun listingSomethingThatIsNotThereReturnsEmptyInsteadOfThrowing() {
        assertTrue(store.list("nothing/here").isEmpty())
    }

    @Test
    fun anUnsafePrefixIsSwallowedRatherThanEscaping() {
        assertTrue(store.list("../..").isEmpty(), "越界的前缀不该抛，也不该列出根目录之外的东西")
    }

    // ── 写：越界必须响亮地失败 ──────────────────

    /**
     * 写这条路上**不**吞异常，这是和 `list()` 有意的区别：
     * 读到空顶多是少显示点东西，写到根目录外面则是真出事。
     */
    @Test
    fun writingOutsideTheRootFailsLoudlyAndLeavesNoFile() {
        val outside = File(root.parentFile, "escaped.txt")
        assertFalse(outside.exists())

        assertFailsWith<IllegalArgumentException> { store.writeText("../escaped.txt", "逃逸") }

        assertFalse(outside.exists(), "被拒绝的写入不该在根目录之外留下文件")
    }

    @Test
    fun anAbsoluteNameIsRefused() {
        val target = File(root.parentFile, "absolute.txt")
        assertFailsWith<IllegalArgumentException> { store.writeText(target.absolutePath, "x") }
        assertFalse(target.exists())
    }

    @Test
    fun nestedNamesCreateTheirIntermediateDirectories() {
        // `media/<id>.bin` 一直就是这么用的
        store.writeBytes("media/8e61928d.bin", byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), store.readBytes("media/8e61928d.bin"))
    }

    // ── size ────────────────────────────────────

    @Test
    fun sizeIsNullForWhatIsNotThereAndBytesForWhatIs() {
        assertNull(store.size("media/missing.bin"))

        val bytes = ByteArray(870_000)
        store.writeBytes("media/big.bin", bytes)
        assertEquals(870_000, store.size("media/big.bin"))
    }

    /**
     * `size()` 存在的理由之一：保存时先问一句"这个文件已经有同样大小了吗"，
     * 跳过没变的图片。
     *
     * 它和 `list()` 的宽严**有意不同**：`list()` 在启动路径上，读不到就退化成空列表；
     * 而 `size()` 和其余四个读写访问器一样走 `file()`——名字不合法是**代码错误**，
     * 该在写测试时就炸出来，而不是返回一个 null 让人以为"这个文件不存在"。
     * 名字从 `list()` 的返回值里来，合法路径上永远不会碰到这条。
     */
    @Test
    fun anUnsafeNameIsRefusedFirmlyRatherThanReportedAsMissing() {
        assertFailsWith<IllegalArgumentException> { store.size("../../etc/passwd") }
    }

    // ── rootPath ────────────────────────────────

    @Test
    fun rootPathIsTheAbsoluteDirectoryBeingWrittenTo() {
        // 浏览器顶部要显示"东西到底存在哪"，所以这里必须和构造函数给的是同一个目录
        assertEquals(root.absolutePath, store.rootPath)
    }

    // ── delete ──────────────────────────────────

    @Test
    fun deletingRemovesTheFileButLeavesTheRestOfTheTreeAlone() {
        store.writeText("agents/m-1/agent.json", "{}")
        store.writeText("agents/m-1/MEMORY.md", "# 记忆")

        store.delete("agents/m-1/agent.json")

        assertNull(store.readText("agents/m-1/agent.json"))
        assertEquals("# 记忆", store.readText("agents/m-1/MEMORY.md"))
    }

    @Test
    fun deletingSomethingThatIsNotThereIsANoOp() {
        store.delete("agents/never-existed.json")
    }
}
