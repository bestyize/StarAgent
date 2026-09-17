package com.starringbox.staragent.core.platform.storage

/**
 * 本地存储：按名字读写文本与字节。
 *
 * 刻意只做到"文件"这一层，不认识 JSON、不认识任何业务类型——
 * 怎么组织数据是上层的事。平台各给一份实现（桌面写用户目录、Android 写 filesDir），
 * 所以这个模块本身零平台依赖。
 *
 * **名字里可以带 `/`**，实现会按需建出中间目录（`media/<id>.bin` 一直就是这么用的）。
 * 但名字由调用方保证安全：**绝不能把用户输入拼进来**，否则 `..` 或绝对路径会跑出根目录。
 */
interface LocalStore {
    fun readText(name: String): String?
    fun writeText(name: String, text: String)

    fun readBytes(name: String): ByteArray?
    fun writeBytes(name: String, bytes: ByteArray)

    fun delete(name: String)

    /**
     * 列出某个前缀下的**文件**，返回相对路径（正斜杠分隔），**按路径排序**。
     *
     * 目录不单独返回，由调用方从路径里推导——这样实现不必关心"空目录"这种
     * 在文件系统之外没有意义的状态。
     *
     * [prefix] 为空表示整棵树。匹配按**目录**算：`list("agents")` 不会捎带上 `agents-extra/`。
     *
     * **不抛异常**：读不到就返回空列表。它会被用在启动路径上，那里宁可少读也不能崩。
     */
    fun list(prefix: String): List<String>

    /**
     * 这个文件占多少字节；不存在返回 null。
     *
     * 两个用处：文件浏览器显示大小；以及**判断一张图片是不是已经写过了**——
     * 之前每次保存都会把所有图片重写一遍，哪怕一个字都没变。
     */
    fun size(name: String): Long?

    /**
     * 根目录的绝对路径，**只用于展示**。
     *
     * 给文件浏览器显示"东西到底存在哪"用。桌面上是 `~/.staragent`，
     * Android 上是应用沙箱里的一个目录。没有实现存储的平台返回 null——
     * 界面据此不显示路径，而不是显示一个假路径。
     */
    val rootPath: String?
}

/** 没装实现时的兜底：读永远是空，写全部丢弃，也列不出任何东西。 */
private object NoStore : LocalStore {
    override fun readText(name: String): String? = null
    override fun writeText(name: String, text: String) = Unit
    override fun readBytes(name: String): ByteArray? = null
    override fun writeBytes(name: String, bytes: ByteArray) = Unit
    override fun delete(name: String) = Unit
    override fun list(prefix: String): List<String> = emptyList()
    override fun size(name: String): Long? = null
    override val rootPath: String? = null
}

object LocalStores {

    private var installed: LocalStore = NoStore

    /** 各端入口调用一次。桌面在 main()，Android 在 MainActivity，iOS 在 MainViewController。 */
    fun install(store: LocalStore) {
        installed = store
    }

    /**
     * 当前平台能不能落盘。
     * 不能的话上层就该跳过持久化，而不是每写一次都白跑一遍序列化。
     */
    val available: Boolean get() = installed !== NoStore

    val current: LocalStore get() = installed
}
