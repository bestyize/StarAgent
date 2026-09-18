package com.starringbox.staragent.core.model

import kotlinx.serialization.Serializable

/**
 * 智能体。产品里"智能体即联系人"——每个智能体是一个独立的对话对象，
 * 拥有自己的人设、模型、技能与记忆范围。
 */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val glyph: String,
    val persona: String,
    val modelId: String,
    val skillIds: List<String> = emptyList(),
    val accentIndex: Int = 0,
    /**
     * 是否允许把对话变成托管任务。
     *
     * 这是能力而不是全局行为：陪聊、答疑类的智能体不该动不动就弹任务卡片。
     * 关掉时系统提示里根本不会出现任务指令，模型也就无从输出任务块。
     * 默认关闭——新智能体默认保持"纯对话"，需要时再打开。
     */
    val canScheduleTasks: Boolean = false,
    val pinned: Boolean = false,
)

/** 响应模式。用户关心的是"这次要多快、多深"，而不是具体用了哪个模型。 */
@Serializable
enum class ResponseMode(val label: String, val hint: String) {
    Fast("快速", "尽快回应，适合简单问答"),
    Standard("标准", "速度与质量的平衡"),
    Deep("深入", "多步推理，适合复杂任务"),
}

/**
 * 模型服务使用的协议。决定请求体怎么拼、流式响应怎么解析。
 * 自定义服务商时由用户二选一。
 */
@Serializable
enum class LlmProtocol(val label: String, val hint: String) {
    OpenAi("OpenAI 兼容", "DeepSeek、通义、智谱、Kimi 及多数自建端点都走这套"),
    Anthropic("Anthropic", "Claude 的原生协议"),
}

/**
 * 一个大模型服务商。
 *
 * **两种协议的地址往往不同**，所以分两个字段存，由 [protocol] 决定实际用哪个。
 * 例如 DeepSeek 的 OpenAI 端点是 `https://api.deepseek.com/v1`，
 * 而 Anthropic 兼容端点是 `https://api.deepseek.com/anthropic`——
 * 只存一个地址的话，用户切换协议就会打到错的路径上。
 *
 * 内置的几家只是预填了地址与常见模型名，用户同样可以自行添加任意服务商。
 */
@Serializable
data class ModelProvider(
    val id: String,
    val name: String,
    /** 用户选定的协议。 */
    val protocol: LlmProtocol,
    val openAiBaseUrl: String = "",
    val anthropicBaseUrl: String = "",
    val models: List<String> = emptyList(),
    val apiKey: String = "",
    val builtIn: Boolean = true,
    /**
     * 语音能力：这家的识别 / 合成模型。
     *
     * 留空表示**这家没有语音接口**——十家里只有智谱和 OpenAI 提供，
     * 其余几家（含 DeepSeek）本身就是纯文本，留空正好让语音那条链路跳过它们。
     * 这两个字段不参与对话，只在要用语音时被读，所以留空是完全正常的状态。
     */
    val asrModel: String? = null,
    /**
     * 这家**还提供哪些**识别模型，供用户在语音识别设置页里换。
     *
     * 不含 [asrModel]——那是预设的那个，[asrChoices] 会把两者并起来。
     * 留空表示"这家只有一个识别模型"，界面于是不摆选择器：
     * 一个选项的单选框只是噪音。Groq 是留这个字段的直接原因，
     * 它的 turbo 与 large-v3 是速度和中英混说准确率之间的真实取舍，
     * 锁死任一个都会让另一批用户没得选。
     */
    val asrModels: List<String> = emptyList(),
    val ttsModel: String? = null,
    /**
     * 合成用的音色名。**每家的取值不一样**（智谱 `tongtong`、OpenAI `alloy`），
     * 而合成接口把它列为必填，所以只能跟着服务商一起存。
     */
    val ttsVoice: String? = null,
) {
    /** 这家能不能用来做语音。 */
    val supportsSpeech: Boolean
        get() = apiKey.isNotBlank() && (asrModel != null || ttsModel != null) && baseUrlFor(protocol) != null

    /**
     * 这家能不能用来做**识别**。
     *
     * 和 [supportsSpeech] 分开，是因为两条能力并不总是成对出现——
     * Groq 只填了识别模型，把它当成"支持语音"就会在朗读时选中它然后失败。
     */
    val supportsAsr: Boolean
        get() = apiKey.isNotBlank() && asrModel != null && baseUrlFor(protocol) != null

    /** 这家能不能用来做**合成**。理由同 [supportsAsr]。 */
    val supportsTts: Boolean
        get() = apiKey.isNotBlank() && ttsModel != null && baseUrlFor(protocol) != null

    /**
     * 可选的识别模型全集，预设的那个排在最前。
     *
     * 三个来源合起来，**[models] 是权威**：那是「获取模型列表」从服务商真拉回来的，
     * 而 [asrModel] / [asrModels] 只是没拉之前的兜底。
     * 这个顺序解决的是一个真实的坑：文档上写着的模型未必在你这个账号/区域上有，
     * 硬编码一份清单迟早会让用户选到一个 404。拉过清单之后就该以清单为准。
     *
     * 界面只在多于一个时才摆选择器，所以这里不做"至少给一个"的兜底——
     * 没有识别能力的服务商本来就不该出现在语音识别页上。
     */
    val asrChoices: List<String>
        get() = (listOfNotNull(asrModel) + asrModels + models.filter(::looksLikeAsrModel)).distinct()

    /**
     * 能用来**对话**的模型，带上它在 [models] 里的下标。
     *
     * 下标必须一起带出来：模型的身份是 `provider:index`（见 `AppState.modelKey`），
     * 过滤之后重新编号会让已经存下来的 `modelId` 指到另一个模型上。
     *
     * 为什么要过滤：「获取模型列表」拉回来的是这家上架的**全部**模型，
     * 对话、识别、合成、安全审核混在一起。原样摆进对话模型选择器，
     * 用户就能在聊天页选中一个 whisper——然后每句话都换回一个 400。
     * 语音识别是一项能力，不是一个可以拿来聊天的模型。
     */
    val chatChoices: List<IndexedValue<String>>
        get() = models.withIndex().filter { looksLikeChatModel(it.value) }


    /** 某协议下的接口地址；这家没提供该协议时返回 null。 */
    fun baseUrlFor(protocol: LlmProtocol): String? {
        val url = when (protocol) {
            LlmProtocol.OpenAi -> openAiBaseUrl
            LlmProtocol.Anthropic -> anthropicBaseUrl
        }
        return url.ifBlank { null }
    }

    /** 这家支持的协议。界面上据此把不支持的那个选项禁掉。 */
    val availableProtocols: List<LlmProtocol>
        get() = LlmProtocol.entries.filter { baseUrlFor(it) != null }

    val configured: Boolean
        get() = apiKey.isNotBlank() && models.isNotEmpty() && baseUrlFor(protocol) != null

    /** 界面上只回显打码后的 Key，避免旁人一眼看到完整密钥。 */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> ""
            apiKey.length <= 10 -> "••••••"
            else -> apiKey.take(3) + "••••" + apiKey.takeLast(4)
        }
}

/**
 * 这个模型名看起来是不是**识别**模型。
 *
 * 按名字里的特征认，而不是维护一份白名单：拉回来的清单里对话、识别、合成、
 * 安全审核模型是混在一起的（Groq 那份里 `whisper-*` 和 `canopylabs/orpheus-*`、
 * `llama-prompt-guard-*` 并排），而各家迟早会加新版本。
 * 认特征至少不会在出新版本时漏掉，也不会把合成模型错认成识别。
 *
 * 宁可漏认不可错认：漏了用户还能用预设那个，错认则是选中之后一个 400。
 */
internal fun looksLikeAsrModel(model: String): Boolean {
    val name = model.lowercase()
    return name.contains("whisper") || name.contains("transcribe") || name.contains("-asr")
}

/**
 * 这个模型名看起来能不能用来**对话**。
 *
 * 反着认：把已知**不是**对话模型的几类排掉，剩下的算对话模型。
 * 这个方向和 [looksLikeAsrModel] 相反，因为两边错判的代价不一样——
 * 漏掉一个非对话模型，用户会选中它然后每句话吃一个 400；
 * 而错杀一个对话模型只是它不在选择器里，服务商详情页仍然看得到完整清单。
 *
 * 排的是家族名而不是 `guard` 这种词根：`prompt-guard`、`llama-guard` 是分类器，
 * 但 `gpt-oss-safeguard` 是能对话的，按词根排会把它一起误杀。
 */
internal fun looksLikeChatModel(model: String): Boolean {
    val name = model.lowercase()
    if (looksLikeAsrModel(model)) return false
    return !name.contains("tts") &&
        !name.contains("orpheus") &&
        !name.contains("-speech") &&
        !name.contains("prompt-guard") &&
        !name.contains("llama-guard") &&
        !name.contains("embed") &&
        !name.contains("rerank") &&
        !name.contains("moderation")
}

/**
 * IM 渠道。绑定后智能体以"联系人"形式出现在该渠道里，
 * 与 App 内共用同一条会话。
 */
@Serializable
data class Channel(
    val id: String,
    val name: String,
    val glyph: String,
    val connected: Boolean = false,
)

/** Skill：可插拔的能力单元。 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val summary: String,
    val glyph: String,
    val installed: Boolean = false,
)
