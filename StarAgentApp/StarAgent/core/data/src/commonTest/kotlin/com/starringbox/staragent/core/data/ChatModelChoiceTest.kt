package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对话模型选择器里该出现什么。
 *
 * 守的是一条边界：**语音识别是一项能力，不是一个能拿来聊天的模型**。
 * 「获取模型列表」拉回来的是这家上架的全部模型——对话、识别、合成、审核混在一起，
 * 原样摆进聊天页的模型选择器，用户选中一个 whisper 之后每句话都换回一个 400。
 */
class ChatModelChoiceTest {

    /** 借用即还——JVM 上这些键就是桌面端在用的那一份。见 [SettingsSandbox]。 */
    private val sandbox = SettingsSandbox(listOf("apikey:zhipu", "models:zhipu"))

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

    /** 2026-09 从 Groq 真拉回来的那份清单，顺序就是界面存下来的顺序。 */
    private val groqPulled = listOf(
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

    private fun groq(models: List<String>) = ModelProvider(
        id = "groq",
        name = "Groq",
        protocol = LlmProtocol.OpenAi,
        openAiBaseUrl = "https://api.groq.com/openai/v1",
        models = models,
        apiKey = "sk-test",
        asrModel = "whisper-large-v3-turbo",
    )

    @Test
    fun recognitionModelsAreNotChatModels() {
        val choices = groq(groqPulled).chatChoices.map { it.value }

        assertEquals(
            listOf("allam-2-7b", "groq/compound", "openai/gpt-oss-120b", "qwen/qwen3.8-27b"),
            choices,
            "对话选择器只该有对话模型：识别(whisper)、合成(orpheus)、审核(prompt-guard)都不是",
        )
    }

    /**
     * 模型的身份是 `provider:index`，下标指向 `models` 原来的位置。
     * 过滤之后重新编号的话，用户存下来的 `modelId` 会静默指到另一个模型上——
     * 这比列表里多几个不能用的选项坏得多。
     */
    @Test
    fun filteringKeepsTheOriginalIndex() {
        val provider = groq(groqPulled)

        provider.chatChoices.forEach { (index, label) ->
            assertEquals(provider.models[index], label, "下标必须还能取回同一个模型")
        }
        assertEquals(
            listOf(0, 3, 5, 6),
            provider.chatChoices.map { it.index },
            "过滤不重新编号",
        )
    }

    /**
     * 排的是家族名而不是 `guard` 这种词根：`gpt-oss-safeguard` 是能对话的，
     * 按词根排会把它一起误杀。
     */
    @Test
    fun safeguardIsAChatModelButClassifiersAreNot() {
        val choices = groq(
            listOf(
                "openai/gpt-oss-safeguard-20b",
                "meta-llama/llama-guard-4-12b",
                "meta-llama/llama-prompt-guard-2-86m",
            )
        ).chatChoices.map { it.value }

        assertEquals(listOf("openai/gpt-oss-safeguard-20b"), choices)
    }

    /** 预置那几家的清单里本来就没有语音模型，过滤不该把它们改动一分。 */
    @Test
    fun presetChatProvidersAreUntouched() {
        val state = AppState()

        listOf("deepseek", "zhipu", "openai", "anthropic").forEach { id ->
            val provider = state.providerById(id) ?: error("预置里应当有 $id")
            assertEquals(
                provider.models,
                provider.chatChoices.map { it.value },
                "$id 的预置清单是纯对话模型，不该被过滤掉任何一个",
            )
        }
    }

    /**
     * 智谱的语音模型只在拉过清单之后才会进 `models`。
     * 那时候它们同样不该出现在对话选择器里——这不是 Groq 独有的问题。
     */
    @Test
    fun pullingSpeechModelsDoesNotPolluteChatChoices() {
        val state = AppState()
        val zhipu = state.providerById("zhipu") ?: error("预置里应当有智谱")
        state.updateProvider(
            zhipu.copy(
                apiKey = "sk-test",
                models = listOf("glm-5.3", "glm-asr-2512", "glm-tts", "glm-embedding-3"),
            )
        )

        val choices = (state.providerById("zhipu") ?: error("刚更新过")).chatChoices.map { it.value }

        assertEquals(listOf("glm-5.3"), choices)
        assertTrue(choices.none { it.contains("asr") || it.contains("tts") })
    }
}
