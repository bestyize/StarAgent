package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语音识别服务商的选取。
 *
 * 这一组守的是拆分 `speechEndpoint()` 的那个理由：识别和合成**不在同一家**。
 * Groq 只提供识别，合起来取的话，用户把识别设成 Groq 就会连朗读一起失去——
 * 而他并没有要求改朗读。
 */
class SpeechProviderTest {

    /**
     * `SettingsStore` 在 JVM 上是真的 `java.util.prefs`，而且和跑起来的桌面端**同一个节点**。
     * 所以这几个键是借的：用例前清空（否则上一条的选择会漏进下一条），
     * 用例后原样还回去（否则删的是用户自己填的东西）。见 [SettingsSandbox]。
     */
    private val sandbox = SettingsSandbox(
        listOf(
            "speechProvider", "asrModel", "voiceSource",
            "apikey:groq", "apikey:zhipu", "apikey:openai",
            "models:groq", "models:zhipu", "models:openai",
        )
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

    /** 给某家填上 Key，让它真的可用。 */
    private fun AppState.configure(id: String) {
        val provider = assertNotNull(providerById(id), "预置里应当有 $id")
        updateProvider(provider.copy(apiKey = "sk-test"))
    }

    @Test
    fun groqIsAPresetAsrProvider() {
        val state = AppState()
        val groq = assertNotNull(state.providerById("groq"), "预置服务商里应当有 Groq")

        assertEquals("https://api.groq.com/openai/v1", groq.openAiBaseUrl)
        assertEquals("whisper-large-v3-turbo", groq.asrModel)
        assertTrue(
            groq.id in state.asrProviders().map { it.id },
            "Groq 填了识别模型，就该出现在语音识别页的候选里",
        )
        assertNull(groq.ttsModel, "Groq 的合成模型没核实到，预置里应当留空而不是猜一个")
    }

    /**
     * 「获取模型列表」拉回来的清单里，对话、识别、合成、安全审核模型是混在一起的。
     * 识别模型选择器只能挑出识别那些——把 orpheus（合成）或 prompt-guard（审核）
     * 当识别模型选中，结果是一个 400。
     *
     * 用的是 2026-09 从 Groq 真拉回来的那份清单。
     */
    @Test
    fun asrChoicesComeFromTheRealModelListAndOnlyPickAsrOnes() {
        val state = AppState()
        val groq = assertNotNull(state.providerById("groq"))

        val pulled = listOf(
            "allam-2-7b",
            "canopylabs/orpheus-arabic-saudi",
            "canopylabs/orpheus-v1-english",
            "groq/compound",
            "meta-llama/llama-prompt-guard-2-22m",
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b",
            "whisper-large-v3",
            "whisper-large-v3-turbo",
        )
        state.updateProvider(groq.copy(apiKey = "sk-test", models = pulled))

        val choices = assertNotNull(state.providerById("groq")).asrChoices

        assertEquals(
            listOf("whisper-large-v3-turbo", "whisper-large-v3"),
            choices,
            "只该挑出识别模型，预设的那个排最前",
        )
        assertTrue(
            choices.none { it.contains("orpheus") },
            "orpheus 是合成模型，错认成识别的话选中就是 400",
        )
        assertTrue(choices.none { it.contains("prompt-guard") }, "审核模型不是识别模型")
    }

    @Test
    fun chosenProviderWins() {
        val state = AppState()
        state.configure("zhipu")
        state.configure("groq")

        // 不选的时候取第一家可用的——预置顺序里智谱在 Groq 前面
        assertEquals("glm-asr-2512", state.asrEndpoint()?.asrModel, "没选过时应当取第一家可用的")

        state.selectSpeechProvider("groq")

        assertEquals(
            "whisper-large-v3-turbo",
            state.asrEndpoint()?.asrModel,
            "选了 Groq 就该用 Groq 的识别模型",
        )
        assertEquals("https://api.groq.com/openai/v1", state.asrEndpoint()?.baseUrl)
    }

    @Test
    fun choosingGroqForAsrDoesNotMuteReading() {
        val state = AppState()
        state.configure("zhipu")
        state.configure("groq")
        state.selectSpeechProvider("groq")

        val tts = assertNotNull(
            state.ttsEndpoint(),
            "Groq 没有合成模型，朗读应当自动落到智谱，而不是一起失效——" +
                "这正是把识别和合成拆成两个 endpoint 的原因",
        )
        assertEquals("glm-tts", tts.ttsModel)
    }

    @Test
    fun modelChoiceIsRememberedAndScopedToItsProvider() {
        val state = AppState()
        state.configure("groq")
        state.selectSpeechProvider("groq")
        state.selectAsrModel("whisper-large-v3")

        assertEquals("whisper-large-v3", state.asrEndpoint()?.asrModel)

        // 选了一个这家没有的模型，退回预设而不是发一个 404 出去
        state.selectAsrModel("distil-whisper-large-v3-en")
        assertEquals(
            "whisper-large-v3-turbo",
            state.asrEndpoint()?.asrModel,
            "清单里没有的模型应当退回预设",
        )
        state.selectAsrModel("whisper-large-v3")

        // 换家要把模型选择清掉：模型名是跟着服务商的，
        // whisper-large-v3 拿到智谱去就是个不存在的模型
        state.configure("zhipu")
        state.selectSpeechProvider("zhipu")

        assertNull(state.asrModelId, "换服务商应当清掉上一家的模型选择")
        assertEquals("glm-asr-2512", state.asrEndpoint()?.asrModel)
    }

    @Test
    fun staleChoiceFallsBackInsteadOfBreaking() {
        val state = AppState()
        state.configure("zhipu")
        // 指向一家不存在的服务商（自定义服务商被删掉就是这个状态）
        state.selectSpeechProvider("gone")

        assertEquals(
            "glm-asr-2512",
            state.asrEndpoint()?.asrModel,
            "设置项失效应当退回自动，而不是让语音直接哑掉",
        )
    }

    @Test
    fun withoutAnyKeyThereIsNoAsrEndpoint() {
        val state = AppState()
        assertNull(state.asrEndpoint(), "一个 Key 都没配时不该凑出一个发不出去的请求")
    }

    @Test
    fun choiceSurvivesARestart() {
        val first = AppState()
        first.configure("groq")
        first.selectSpeechProvider("groq")
        first.selectAsrModel("whisper-large-v3")

        val restarted = AppState()

        assertEquals("groq", restarted.speechProviderId, "选的服务商要跟着重启回来")
        assertEquals("whisper-large-v3", restarted.asrModelId)
        assertEquals("whisper-large-v3", restarted.asrEndpoint()?.asrModel)
    }
}
