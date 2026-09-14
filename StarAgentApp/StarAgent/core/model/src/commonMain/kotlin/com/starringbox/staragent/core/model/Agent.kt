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
