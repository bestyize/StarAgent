package com.starringbox.staragent.core.platform.storage

/**
 * 存储名字的准入。
 *
 * [LocalStore] 的名字可以带 `/`（`media/<id>.bin` 一直这么用），实现会按需建出中间目录。
 * 代价是**名字里的 `..` 或绝对路径能跑出根目录**——今天所有调用方传的都是字面量和 UUID，
 * 所以没出过事，但这条保证一直没人守着。
 *
 * 这里把它变成显式的。不合规的名字**直接抛**，因为那是代码错误、不是运行期状况；
 * 而且抛在一个不会被吞掉的地方——[StatePersister][com.starringbox.staragent.core.data.StatePersister]
 * 的 `save()` 外面套着 `runCatching`，所以调用方得自己保证名字干净，别指望这里兜底。
 *
 * 刻意**不做"悄悄改写"**（去掉 `..`、替换非法字符）：那会让两个不同的名字映射到同一个文件，
 * 比崩更难查。要处理不合法的 id 请用 [StorageNames.fileNameFor]，它是单射的。
 */
internal object StorePaths {

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * 校验并规范化一个存储名字。合法则返回以 `/` 分隔的相对路径。
     *
     * 允许相对路径与常规文件名字符；拒绝绝对路径、盘符、以及任何 `..` 段。
     */
    fun normalize(name: String): String {
        require(name.isNotBlank()) { "存储名字不能为空" }
        require(!name.startsWith("/") && !name.startsWith("\\")) { "存储名字必须是相对路径：$name" }
        require(!DRIVE.containsMatchIn(name)) { "存储名字不能带盘符：$name" }

        val segments = name.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "存储名字不能跑出根目录：$name" }
        require(segments.isNotEmpty()) { "存储名字不能只由分隔符组成：$name" }

        return segments.joinToString("/")
    }
}
