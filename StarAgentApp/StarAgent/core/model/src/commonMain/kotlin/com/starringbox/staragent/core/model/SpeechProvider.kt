package com.starringbox.staragent.core.model

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
 * 刻意**不带 `LlmProtocol`**：语音只有一种协议形状（OpenAI 那套 `/audio/...`），
 * 一个 [baseUrl] 就够。协议的分叉只在对话那边存在。
 *
 * 这条"只有一种形状"是**准入条件**，不是巧合——`ModelSpeech` 把
 * `/audio/transcriptions` 与 `/audio/speech` 两条路径写死了。所以这里只收形状一致的服务商。
 *
 * MiniMax 就是被这一条挡在外面的例子：2026-09 核对过官方文档，它的识别在
 * `/v1/speech_to_text`、合成在 `/v1/t2a_v2`（请求体是嵌套的 `voice_setting`/`audio_setting`，
 * 返回的还是 JSON 里的一段音频数据而不是音频本身），**两条都得单独写一遍请求与解析**。
 * 所以它不是"再加一行预置"就能接上的。真要加，先给这一层引入"按服务商分协议"，
 * 别在 `ModelSpeech` 里堆 if。（网上那些 `/v1/audio/transcriptions` 的 MiniMax 说法
 * 指向的都是第三方代理，不是官方。）
 */
data class SpeechProvider(
    val id: String,
    val name: String,
    /** 语音端点的根。`/audio/transcriptions` 与 `/audio/speech` 都挂在它下面。 */
    val baseUrl: String,
    /** 预设识别模型。 */
    val asrModel: String,
    /** 备选识别模型，**不含** [asrModel]；空表示这家只有一个可选。 */
    val asrModels: List<String> = emptyList(),
    /** 合成模型。null = 这家不做合成（Groq），朗读据此退回设备自带。 */
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
