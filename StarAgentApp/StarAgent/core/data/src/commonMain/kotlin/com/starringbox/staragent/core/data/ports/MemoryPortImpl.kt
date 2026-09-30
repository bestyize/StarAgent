package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.tools.ports.MemoryPort

/**
 * 记忆端口的实现。薄到几乎只是转发——**这是故意的**，
 * 端口存在的意义是让 core:tools 不认识 AppState，不是在这里放业务逻辑。
 *
 * **例外是 `remember` 那道闸门**，见 [AppMemoryPort.isSuppressed]。
 */
class AppMemoryPort(
    private val state: AppState,
    /**
     * 本轮记忆写入闸门：为真表示**这一轮取过外部来源的内容**（网页正文等），
     * 本轮产生的记忆一律不落库。
     *
     * 为什么这道闸门必须在端口里、而不是只堵调用方：写记忆有**两条路**——
     * 模型吐 ```` ```memory ```` 文本块（`send()` 里直接写），
     * 和它调 `remember` 工具（走这里）。只堵前一条的话，被网页诱导的模型改调
     * `remember` 就绕过去了，而且那条路连"记住了"的卡片都不弹——
     * 用户会**双重地**不知道。两条路读同一个标志，才是一道闸门而不是两道。
     *
     * [recall_memory] 不受影响：它只读不写，外部内容能不能被查到不是这里管的事。
     */
    private val isSuppressed: () -> Boolean,
) : MemoryPort {

    override suspend fun recall(agentId: String, query: String, limit: Int): List<MemoryItem> =
        state.recallMemories(agentId, query, limit)

    override suspend fun remember(agentId: String, category: MemoryCategory, text: String) {
        // 这一轮的不信任源已经把模型的输出染过了，记下来就不再是"用户说的"
        if (isSuppressed()) return
        state.addMemory(agentId, category, text)
    }
}
