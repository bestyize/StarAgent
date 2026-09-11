package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.storage.LocalStore
import com.starringbox.staragent.core.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 启动路径的回归测试。
 *
 * 内置服务商曾经和演示内容挤在同一个 `Seed.populate` 里。改成"有存档就还原"之后，
 * 走还原路径时 `providers` 是空的，而 AppState 初始化里有一句 `providers.first()`——
 * 结果是：**用户一旦有了存档，App 就再也起不来**。
 *
 * 所以每个用例都构造两次 AppState：第一次落盘，第二次走还原路径。
 */
class AppStateStartupTest {

    private class MemoryStore : LocalStore {
        private val texts = mutableMapOf<String, String>()
        private val blobs = mutableMapOf<String, ByteArray>()

        override fun readText(name: String): String? = texts[name]

        override fun writeText(name: String, text: String) {
            texts[name] = text
        }

        override fun readBytes(name: String): ByteArray? = blobs[name]

        override fun writeBytes(name: String, bytes: ByteArray) {
            blobs[name] = bytes
        }

        override fun delete(name: String) {
            texts.remove(name)
            blobs.remove(name)
        }
    }

    /** LocalStores 是全局单例，跑完换回一个干净的，免得影响同模块的其它测试。 */
    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun restorePathStillHasTheBuiltInProviders() {
        LocalStores.install(MemoryStore())

        AppState() // 首次：灌种子并落盘
        val restarted = AppState() // 再次：走的是还原路径

        assertTrue(
            restarted.providers.isNotEmpty(),
            "还原路径下内置服务商不能为空——否则初始化会直接崩在 providers.first() 上",
        )
        assertTrue(restarted.providers.any { it.id == "deepseek" }, "内置服务商应当完整")
    }

    @Test
    fun deletedAgentDoesNotComeBackAfterRestart() {
        LocalStores.install(MemoryStore())

        val first = AppState()
        val victim = first.agents.first().id
        first.deleteAgent(victim)

        val restarted = AppState()

        assertTrue(
            restarted.agents.none { it.id == victim },
            "删掉的智能体不该在冷启后复活——那正是持久化要解决的问题",
        )
    }
}
