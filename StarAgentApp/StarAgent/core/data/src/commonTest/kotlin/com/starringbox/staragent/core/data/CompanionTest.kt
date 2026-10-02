package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.BuiltInCompanion
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompanionTest {
    @AfterTest
    fun cleanup() { LocalStores.install(MemoryStore()) }

    @Test
    fun deletingTheCompanionKeepsHerIdentityAndHistory() {
        LocalStores.install(MemoryStore())
        val state = AppState()
        val original = state.companion
        state.messagesOf(original.id).add(ChatMessage("m-100", Author.User, MessageBody.Text("今天很开心"), "刚刚"))
        state.updateAgent(original.copy(name = "小月", persona = "喜欢倾听", accentIndex = 4))
        state.deleteAgent(original.id)

        val restarted = AppState()
        assertEquals("小月", restarted.companion.name)
        assertEquals("喜欢倾听", restarted.companion.persona)
        assertEquals(4, restarted.companion.accentIndex)
        assertEquals("今天很开心", (restarted.messagesOf(original.id).single().body as MessageBody.Text).text)
        assertEquals(1, restarted.agents.count { it.id == BuiltInCompanion.ID })
    }

    @Test
    fun upgradingAnEmptySaveAddsOnlyTheCompanionAndIsIdempotent() {
        val store = MemoryStore()
        StatePersister(store).save(PersistedState())
        LocalStores.install(store)
        val first = AppState()
        assertNotNull(first.agent(BuiltInCompanion.ID))
        assertEquals(1, first.agents.size)
        assertEquals(1, AppState().agents.size)
    }
}
