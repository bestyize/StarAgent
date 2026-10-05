package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 所有智能体共用全局默认模型。
 *
 * 守的是：智能体自己存的 `modelId` 不再参与解析。预置智能体写死的 `deepseek:0`
 * 不能压过用户在「模型服务」里选的默认模型，否则配好了别家也会显示"未配置"。
 */
class SharedModelTest {

    private val sandbox = SettingsSandbox(
        listOf("apikey:deepseek", "models:deepseek", "apikey:kimi", "models:kimi", "defaultModel"),
    )

    @BeforeTest
    fun setUp() {
        sandbox.enter()
        LocalStores.install(MemoryStore())
    }

    @AfterTest
    fun tearDown() {
        sandbox.leave()
        LocalStores.install(MemoryStore())
    }

    private fun AppState.configure(providerId: String, model: String) {
        val provider = assertNotNull(providerById(providerId))
        updateProvider(provider.copy(apiKey = "sk-test", models = listOf(model)))
    }

    @Test
    fun anAgentPinnedToAnUnconfiguredProviderStillTalksThroughTheDefault() {
        val state = AppState()
        state.configure("kimi", "kimi-test")
        state.setDefaultModel("kimi:0")

        val pinned = state.agents.first().copy(modelId = "deepseek:0")

        val endpoint = assertNotNull(state.endpointFor(pinned), "deepseek 没配，但默认模型配好了，应当能对话")
        assertEquals("kimi-test", endpoint.model)
    }

    @Test
    fun changingTheDefaultMovesEveryAgent() {
        val state = AppState()
        state.configure("deepseek", "deepseek-test")
        state.configure("kimi", "kimi-test")

        state.setDefaultModel("deepseek:0")
        assertEquals(setOf("deepseek-test"), state.agents.mapNotNull { state.endpointFor(it)?.model }.toSet())

        state.setDefaultModel("kimi:0")
        assertEquals(setOf("kimi-test"), state.agents.mapNotNull { state.endpointFor(it)?.model }.toSet())
    }
}
