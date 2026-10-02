package com.starringbox.staragent.core.model

/**
 * 语音接口的协议形状。
 *
 * 这一层原先**没有**这个概念：`ModelSpeech` 把 `/audio/transcriptions` 与 `/audio/speech`
 * 两条路径写死，预置里就只收形状一致的服务商——"只有一种形状"是当时的准入条件。
 *
 * 火山方舟是这个前提的第一个反例：它的识别在 `/api/v3/auc/bigmodel/recognize/flash`、
 * 合成在 `/api/v3/tts/unidirectional/sse`，一条是普通 POST 但**成败看响应头**，
 * 另一条干脆是流式事件、音频还被切成一片片各自 base64。两条都得单独写一遍请求与解析。
 *
 * 于是把"形状"提上来做成一个字段。加一家的代价因此**明确地**变成两件事：
 * 建一个预置条目，再到 `ModelSpeech` 里把它那一支写出来。这是明知的取舍——
 * 好过在 `ModelSpeech` 里按 id 堆 if，也好过把能接的家一律拒之门外。
 */
enum class SpeechProtocol {
    /** OpenAI 那套：`/audio/transcriptions` 是 multipart，`/audio/speech` 是 JSON 回来一段音频。 */
    OpenAi,
    /** 小米语音使用 chat/completions 音频 JSON，与 audio 路径接口不同。 */
    MiMo,

    /**
     * 火山方舟（豆包语音，新版控制台）：**一把 `X-Api-Key`**。
     *
     * 注意它和火山那套旧的语音接口（要 APP ID + Access Token 两个凭据）**不是一回事**，
     * 凭据也不通用——拿方舟的 Key 去填旧接口只会得到鉴权失败。
     */
    VolcArk,
}

/**
 * 一家**语音**服务商。
 *
 * 和 [ModelProvider] **彻底无关**——两者只是碰巧都叫"服务商"。
 * 不参与对话、不进 `providers`、不落盘成对象；只有"选了谁"和各自的 Key 各存一条字符串。
 *
 * 为什么不复用 [ModelProvider]：那样会让语音的可用性取决于**对话那把 Key 配没配**，
 * 用户看到的"待配置"其实说的是另一件事；而识别模型的候选还会被对话的模型清单污染
 * （拉一次对话模型列表就顺带改掉了识别可选项）。拆开之后两份配置各自自洽，
 * 代价是用户可能要填两遍 Key——这个代价是明知的。
 *
 * 协议形状由 [protocol] 决定，`ModelSpeech` 照着分派。**预置里只收真的能用的**：
 * 一家没有对应实现，加进来就只是给用户一个必然失败的选项。
 *
 * MiniMax 仍然被挡在外面：它的识别在 `/v1/speech_to_text`、合成在 `/v1/t2a_v2`
 * （请求体是嵌套的 `voice_setting`/`audio_setting`，返回的还是 JSON 里的一段音频数据
 * 而不是音频本身）。这不是"再加一行预置"能接上的——要先写第三条协议分支。
 * （网上那些 `/v1/audio/transcriptions` 的 MiniMax 说法指向的都是第三方代理，不是官方。）
 */
data class SpeechProvider(
    val id: String,
    val name: String,
    /**
     * 语音端点的根。
     *
     * - [SpeechProtocol.OpenAi]：`/audio/transcriptions` 与 `/audio/speech` 都挂在它下面；
     * - [SpeechProtocol.VolcArk]：`/api/v3/...` 挂在它下面，所以填 `https://openspeech.bytedance.com`。
     */
    val baseUrl: String,
    val protocol: SpeechProtocol = SpeechProtocol.OpenAi,
    /**
     * 预设识别模型。
     *
     * 对 [SpeechProtocol.VolcArk] 来说这里是它的**资源 ID**（`volc.bigasr.auc_turbo`）——
     * 火山用这个字段选模型版本，语义正好对得上，界面上照原样显示。
     */
    val asrModel: String,
    /** 备选识别模型，**不含** [asrModel]；空表示这家只有一个可选。 */
    val asrModels: List<String> = emptyList(),
    /** 合成模型（火山上同样是资源 ID）。null = 这家不做合成，朗读据此退回设备自带。 */
    val ttsModel: String? = null,
    /** 合成音色。每家的取值不一样，跟着服务商一起存。 */
    val ttsVoice: String? = null,
) {
    /** 可选的识别模型全集，预设的排最前。 */
    val asrChoices: List<String> get() = (listOf(asrModel) + asrModels).distinct()

    /** 这家能不能合成。不能就退回设备自带（决定见 [ttsModel]）。 */
    val canSynthesize: Boolean get() = ttsModel != null

    /** 界面上那句话：这家能做什么。 */
    val capabilityLabel: String get() = if (canSynthesize) "识别 · 朗读" else "仅识别"
}
