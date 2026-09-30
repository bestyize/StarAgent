package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.tools.ports.MemoryPort

/**
 * 记忆端口的实现。薄到几乎只是转发——**这是故意的**，
 * 端口存在的意义是让 core:tools 不认识 AppState，不是在这里放业务逻辑。
 */
class AppMemoryPort(private val state: AppState) : MemoryPort {

    override suspend fun recall(agentId: String, query: String, limit: Int): List<MemoryItem> =
        state.recallMemories(agentId, query, limit)

    override suspend fun remember(agentId: String, category: MemoryCategory, text: String) {
        state.addMemory(agentId, category, text)
    }
}
