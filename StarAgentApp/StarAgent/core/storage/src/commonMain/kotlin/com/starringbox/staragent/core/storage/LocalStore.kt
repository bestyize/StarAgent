package com.starringbox.staragent.core.storage

/**
 * 本地存储：按名字读写文本与字节。
 *
 * 刻意只做到"文件"这一层，不认识 JSON、不认识任何业务类型——
 * 怎么组织数据是上层的事。平台各给一份实现（桌面写用户目录、Android 写 filesDir），
 * 所以这个模块本身零平台依赖。
 */
interface LocalStore {
    fun readText(name: String): String?
    fun writeText(name: String, text: String)

    fun readBytes(name: String): ByteArray?
    fun writeBytes(name: String, bytes: ByteArray)

    fun delete(name: String)
}

/** 没装实现时的兜底：读永远是空，写全部丢弃。 */
private object NoStore : LocalStore {
    override fun readText(name: String): String? = null
    override fun writeText(name: String, text: String) = Unit
    override fun readBytes(name: String): ByteArray? = null
    override fun writeBytes(name: String, bytes: ByteArray) = Unit
    override fun delete(name: String) = Unit
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
