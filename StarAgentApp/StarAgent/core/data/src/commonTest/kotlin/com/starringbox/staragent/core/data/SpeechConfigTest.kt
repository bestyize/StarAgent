package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.SpeechProtocol
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.Transcriber
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.VoiceSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语音那一套配置。
 *
 * 这一组守的核心不变量是：**语音和对话是两份互不影响的配置**。
 * 之前它们共用同一个 `ModelProvider`（同一把 Key、同一个模型清单），
 * 于是语音页上每家的"待配置"其实说的是对话那把 Key 配没配。
 *
 * 另外守两条容易复发的：
 * - 识别和朗读**不是同一件事**（Groq 只做识别，选它不能把朗读一起带走）；
 * - 「自动 / 系统能力 / 云端识别」这个选择**真的传到了 Voice 层**
 *   （上一版没传，界面上选什么都等于没选，而界面看着一切正常）。
 */
class SpeechConfigTest {

    /**
     * `SettingsStore` 在 JVM 上是真的 `java.util.prefs`，而且和跑起来的桌面端**同一个节点**。
     * 所以这几个键是借的：用例前清空（否则上一条的选择会漏进下一条），
     * 用例后原样还回去（否则删的是用户自己填的东西）。见 [SettingsSandbox]。
     *
     * `speechKey:*` 尤其要紧——那是明文 Key，忘了登记就等于一次 `./gradlew test`
     * 抹掉用户在语音侧填的那把。`apikey:*` 留着是因为下面有用例要证明
     * "配了对话那边也不会顺带动到语音"。
     */
    private val sandbox = SettingsSandbox(
        listOf(
            "speechProvider", "asrModel", "voiceSource", "ttsVoiceSource", "ttsProvider", "ttsVoice",
            "speechKey:mimo",
            "speechKey:groq", "speechKey:zhipu", "speechKey:openai", "speechKey:volc-ark",
            "apikey:groq", "apikey:zhipu", "apikey:openai", "apikey:ark-plan",
            "models:groq", "models:zhipu", "models:openai", "models:ark-plan",
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
        // Voice 是全局单例，而且 AppState 构造时会往里装东西——不清干净会影响别的用例
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.installPreference(null)
        Voice.installSpeakingPreference(null)
        Voice.installLocal()
    }

    /** 对话那边配好一家（走的是它自己那套键）。 */
    private fun AppState.configureChat(id: String) {
        val provider = assertNotNull(providerById(id), "预置里应当有 $id")
        updateProvider(provider.copy(apiKey = "sk-chat"))
    }

    /**
     * 给某家填上 Key。
     *
     * **刻意不顺手选中它**：那会把"配好了但没选"和"配好了并且选着"混成一件事，
     * 而这两者的区别正是好几条用例要守的东西。选哪家由用例自己调
     * [SpeechController.selectSpeechProvider] 说清楚。
     */
    private fun AppState.configureSpeech(id: String, key: String = "sk-speech") = speech.setSpeechKey(id, key)

    // ── 预置 ────────────────────────────────────

    @Test
    fun thePresetsAreTheOnesThatActuallyWork() {
        val state = AppState()

        assertEquals(
            listOf("zhipu", "groq", "openai", "volc-ark", "mimo", "mimo-token-plan", "edge-tts"),
            state.speech.speechProviders.map { it.id },
            "顺序是显式的：没选过时取第一家。新加一家要加在**末尾**，别动这个兜底",
        )

        val groq = assertNotNull(state.speech.speechProviders.firstOrNull { it.id == "groq" })
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals("whisper-large-v3-turbo", groq.asrModel)
        assertNull(groq.ttsModel, "Groq 的合成模型没核实到，预置里应当留空而不是猜一个")
        assertFalse(groq.canSynthesize)
    }

    /**
     * 除火山方舟外，预置里其余几家都走 OpenAI 那套形状。
     *
     * 单独断言"谁不是"，是因为协议字段**有默认值**——漏填一家不会有编译错误，
     * 只会让它在运行时拿着 `/audio/transcriptions` 去撞一个不存在的端点。
     */
    @Test
    fun everyOtherPresetIsOpenAiShaped() {
        val state = AppState()

        state.speech.speechProviders.filter {
            it.id !in setOf("volc-ark", "mimo", "mimo-token-plan", "edge-tts")
        }.forEach { provider ->
            assertEquals(
                SpeechProtocol.OpenAi,
                provider.protocol,
                "${provider.id} 走的是 OpenAI 那套（/audio/transcriptions 与 /audio/speech）",
            )
        }
    }

    @Test
    fun edgeTtsIsTtsOnlyAndNeedsNoKey() {
        val state = AppState()
        val edge = assertNotNull(state.speech.speechProviders.firstOrNull { it.id == "edge-tts" })
        assertFalse(edge.canTranscribe)
        assertTrue(edge.canSynthesize)
        assertFalse(edge.requiresApiKey)

        state.speech.selectSpeechProvider("edge-tts")
        assertNull(state.speech.asrEndpoint())

        state.speech.selectTtsProvider("edge-tts")
        val tts = assertNotNull(state.speech.ttsEndpoint())
        assertEquals("edge-tts", tts.ttsModel)
        assertEquals("zh-CN-XiaoxiaoNeural", tts.ttsVoice)
    }

    /**
     * 火山方舟这一家：**语音和对话是两条产品线**，而且语音那边还分 Agent Plan 与普通控制台两套凭据。
     *
     * 三处写错都不是编译错误、而是运行时一个看不懂的失败：域名是
     * `openspeech.bytedance.com` 而不是方舟的 `ark.cn-beijing.volces.com`；
     * 资源 ID 是 Agent Plan 那套（`volc.seedasr…`）而不是普通控制台的
     * `volc.bigasr…`——两个 Key 不通用，填错了就是鉴权失败；
     * 而"模型"这一栏对它是**资源 ID**。
     */
    @Test
    fun theArkPresetIsItsOwnProductLine() {
        val state = AppState()
        val ark = assertNotNull(state.speech.speechProviders.firstOrNull { it.id == "volc-ark" })

        assertEquals("https://openspeech.bytedance.com", ark.baseUrl, "语音在另一个域名上")
        assertEquals(SpeechProtocol.VolcArk, ark.protocol)
        assertEquals("volc.seedasr.sauc.duration", ark.asrModel, "Agent Plan 认的是 seedasr 那套资源 ID")
        assertEquals("seed-tts-2.0", ark.ttsModel)
        assertTrue(ark.canSynthesize, "这家识别和合成都有")
    }

    /**
     * 协议要一路传到 `SpeechEndpoint` ——`ModelSpeech` 就是照它分派的。
     *
     * 少了这一环的症状：界面显示、Key、模型名全都对，请求却发到了 OpenAI 的路径上。
     */
    @Test
    fun theProtocolReachesTheEndpoint() {
        val state = AppState()

        state.configureSpeech("volc-ark")
        state.speech.selectSpeechProvider("volc-ark")
        assertEquals(SpeechProtocol.VolcArk, assertNotNull(state.speech.asrEndpoint()).protocol)
        assertEquals(SpeechProtocol.VolcArk, assertNotNull(state.speech.ttsEndpoint()).protocol)

        state.configureSpeech("openai")
        state.speech.selectSpeechProvider("openai")
        assertEquals(SpeechProtocol.OpenAi, assertNotNull(state.speech.asrEndpoint()).protocol)
    }

    /**
     * 对话侧的「火山 Agent Plan」是**独立的一家**，不是把 CodingPlan 改个地址。
     *
     * Agent Plan 与 Coding Plan 是两份订阅、两把 Key、两套 Base URL。混用的后果不是报错，
     * 而是**静默按量计费**——不消耗套餐额度，要到账单上才看得见。所以两边都得在，
     * 各自的地址都得对。
     */
    @Test
    fun theArkPlansAreTwoSeparateProviders() {
        val state = AppState()

        val coding = assertNotNull(state.providerById("ark-coding"))
        val plan = assertNotNull(state.providerById("ark-plan"))

        assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3", coding.openAiBaseUrl)
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", plan.openAiBaseUrl)
        assertEquals("https://ark.cn-beijing.volces.com/api/coding", coding.anthropicBaseUrl)
        assertEquals("https://ark.cn-beijing.volces.com/api/plan", plan.anthropicBaseUrl)
        assertTrue(plan.models.isNotEmpty(), "这家的 /models 是 404，清单只能预置")
    }

    /**
     * 识别模型的候选**只来自这家预置**。
     *
     * 以前它还会去捞对话那份 `models`（`looksLikeAsrModel` 过滤），
     * 于是用户拉一次对话模型列表就顺带改掉了识别可选项。现在没有这条通道了。
     */
    @Test
    fun asrChoicesComeFromThePresetOnly() {
        val state = AppState()
        val groq = assertNotNull(state.speech.speechProviders.firstOrNull { it.id == "groq" })

        assertEquals(
            listOf("whisper-large-v3-turbo", "whisper-large-v3"),
            groq.asrChoices,
            "预设的那个排最前",
        )
        assertTrue(groq.asrChoices.none { it.contains("orpheus") }, "orpheus 是合成模型")
        assertTrue(groq.asrChoices.none { it.contains("guard") }, "审核模型不是识别模型")
    }

    // ── 两份配置互不影响（核心不变量）──────────────

    /**
     * **这是这次拆分要守的那一条。**
     *
     * 对话那边配得再全，语音这边也该是空的——反过来也一样。
     * 以前两者共用 `apiKey`，所以配了对话就等于配了语音。
     */
    @Test
    fun aConversationKeyNeverBecomesASpeechKey() {
        val state = AppState()
        state.configureChat("zhipu")

        assertTrue(state.providerById("zhipu")?.configured == true, "对话那边确实配好了")
        assertTrue(state.speech.speechKeyOf("zhipu").isBlank(), "语音这边不该跟着有 Key")
        assertNull(state.speech.asrEndpoint(), "对话的 Key 不该顺带把语音也配好")
    }

    @Test
    fun aSpeechKeyNeverBecomesAConversationKey() {
        val state = AppState()
        state.configureSpeech("zhipu")

        assertNotNull(state.speech.asrEndpoint(), "语音这边配好了")
        assertTrue(
            state.providerById("zhipu")?.configured == false,
            "对话那边不该被语音的 Key 顶成已配置",
        )
    }

    // ── 选取 ────────────────────────────────────

    @Test
    fun chosenProviderWins() {
        val state = AppState()
        state.configureSpeech("zhipu")
        state.configureSpeech("groq")

        // 没选过时取第一家——这里的"第一"是预置清单的顺序，不是 providers 的插入顺序
        assertEquals("glm-asr-2512", state.speech.asrEndpoint()?.asrModel)

        state.speech.selectSpeechProvider("groq")

        assertEquals("whisper-large-v3-turbo", state.speech.asrEndpoint()?.asrModel)
        assertEquals("https://api.groq.com/openai/v1", state.speech.asrEndpoint()?.baseUrl)
    }

    @Test
    fun modelChoiceIsRememberedAndScopedToItsProvider() {
        val state = AppState()
        state.configureSpeech("groq")
        state.speech.selectSpeechProvider("groq")
        state.speech.selectAsrModel("whisper-large-v3")

        assertEquals("whisper-large-v3", state.speech.asrEndpoint()?.asrModel)

        // 选了一个这家没有的模型，退回预设而不是发一个 404 出去
        state.speech.selectAsrModel("distil-whisper-large-v3-en")
        assertEquals(
            "whisper-large-v3-turbo",
            state.speech.asrEndpoint()?.asrModel,
            "候选里没有的模型应当退回预设",
        )
        state.speech.selectAsrModel("whisper-large-v3")

        // 换家要把模型选择清掉：模型名是跟着服务商的，
        // whisper-large-v3 拿到智谱去就是个不存在的模型
        state.configureSpeech("zhipu")
        state.speech.selectSpeechProvider("zhipu")

        assertNull(state.speech.asrModelId, "换服务商应当清掉上一家的模型选择")
        assertEquals("glm-asr-2512", state.speech.asrEndpoint()?.asrModel)
    }

    @Test
    fun aStaleChoiceFallsBackInsteadOfBreaking() {
        val state = AppState()
        state.configureSpeech("zhipu")
        // 上一版存下来的可能是某个对话服务商的 id，或者一家已经删掉的
        state.speech.selectSpeechProvider("gone")

        assertEquals(
            "glm-asr-2512",
            state.speech.asrEndpoint()?.asrModel,
            "设置项失效应当退回第一家，而不是让语音直接哑掉",
        )
    }

    @Test
    fun withoutAnyKeyThereIsNoAsrEndpoint() {
        val state = AppState()
        state.speech.selectSpeechProvider("zhipu")

        assertNull(state.speech.asrEndpoint(), "选了家但没填 Key，不该凑出一个发不出去的请求")
    }

    @Test
    fun selectingAVendorKeepsTheOtherVendorsKeys() {
        val state = AppState()
        state.configureSpeech("groq", key = "sk-groq")
        state.configureSpeech("zhipu", key = "sk-zhipu")

        // 切回去不用重填——Key 是按家存的。共用一把的话，Groq 的 Key 拿到智谱去必然 401
        state.speech.selectSpeechProvider("groq")
        assertEquals("sk-groq", state.speech.asrEndpoint()?.apiKey)

        state.speech.selectSpeechProvider("zhipu")
        assertEquals("sk-zhipu", state.speech.asrEndpoint()?.apiKey)
    }

    // ── 朗读 ────────────────────────────────────

    /**
     * 朗读跟着选的那家走：能做合成（智谱、OpenAI）就用它，不能（Groq）就交给设备。
     *
     * 这一条是**有意反转**了旧行为：以前合成是"自动挑一家能做合成的"，
     * 所以选 Groq 做识别时朗读会落到智谱。现在语音只有一条配置，
     * 选了谁就是谁——Groq 不能合成，`ttsEndpoint()` 返回 null，
     * `Voice` 那边会退回设备自带（见 `VoiceTest.anUnreadyModelSynthesizerFallsBackToTheDevice`）。
     */
    @Test
    fun aVendorWithoutSynthesisHandsReadingBackToTheDevice() {
        val state = AppState()
        state.configureSpeech("groq")
        state.speech.selectSpeechProvider("groq")
        assertNull(state.speech.ttsEndpoint(), "Groq 不做合成，朗读该交给设备")

        state.configureSpeech("zhipu")
        state.speech.selectSpeechProvider("zhipu")
        val tts = assertNotNull(state.speech.ttsEndpoint())
        assertEquals("glm-tts", tts.ttsModel)
        assertEquals("tongtong", tts.ttsVoice)
    }

    @Test
    fun synthesisAlsoNeedsTheKey() {
        val state = AppState()
        state.speech.selectSpeechProvider("openai")

        assertNull(state.speech.ttsEndpoint(), "没填 Key 就不该凑出一个发不出去的请求")
    }

    // ── 跨重启 ──────────────────────────────────

    @Test
    fun theChoiceAndTheKeySurviveARestart() {
        val first = AppState()
        first.configureSpeech("groq", key = "sk-groq")
        first.speech.selectSpeechProvider("groq")
        first.speech.selectAsrModel("whisper-large-v3")

        val restarted = AppState()

        assertEquals("groq", restarted.speech.speechProviderId, "选的服务商要跟着重启回来")
        assertEquals("whisper-large-v3", restarted.speech.asrModelId)
        assertEquals("whisper-large-v3", restarted.speech.asrEndpoint()?.asrModel)
        // Key 那条单独断一句：漏读一家的后果是"每次冷启都要重填"，
        // 而只断言 id 和模型名的话，Key 没落盘也看不出来
        assertEquals("sk-groq", restarted.speech.asrEndpoint()?.apiKey, "Key 也要跟着回来")
    }

    // ── 那一档真的传到了 Voice ──────────────────

    /**
     * 「自动 / 系统能力 / 云端识别」必须真的传进 [Voice]。
     *
     * 上一版这里断着：`SpeechController.selectVoiceSource` 只写了自己的字段和 SettingsStore，
     * 而 `Voice.preference` 一直是个没人写的 `var`，于是 `pickTranscriber` 永远走
     * "设备优先、不可用才退回模型"那条分支——**界面上选什么都等于没选**，
     * 而界面显示得好好的（`asrSummary` 会说"大模型 · Groq"）。
     *
     * 判别方式：指定「云端识别」时那一档是**独占**的。此时模型那路没配好
     * （没填 Key → `ModelTranscriber.ready` 为 false），
     * 所以正确的行为是"听不了"；要是这个选择没传下去，就会退回设备那路、听得了。
     */
    @Test
    fun theSpeechPreferenceActuallyReachesTheVoiceLayer() {
        val state = AppState()
        state.speech.selectVoiceSource(VoiceSource.Model)

        // 只装设备那一路的替身。模型那路由 AppState 构造时装好了（真的 ModelTranscriber），
        // 而它没配 Key 就是 not ready——不用自己造。
        Voice.installDevice(transcriber = AlwaysReadyTranscriber())

        assertFalse(
            Voice.canListen,
            "指定了云端识别就该独占：它没配好时不该偷偷用设备。这条红了就说明选择没传到 Voice。",
        )
    }

    private class AlwaysReadyTranscriber : Transcriber {
        override val ready: Boolean get() = true
        override suspend fun start(): Boolean = true
        override suspend fun finish(): SpeechOutcome<String> = SpeechOutcome.Ok("设备听出来的")
        override fun cancel() = Unit
    }
}
