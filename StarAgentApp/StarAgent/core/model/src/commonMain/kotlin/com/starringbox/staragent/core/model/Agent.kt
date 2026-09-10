package com.starringbox.staragent.core.model

/**
 * 智能体。产品里"智能体即联系人"——每个智能体是一个独立的对话对象，
 * 拥有自己的人设、模型、技能与记忆范围。
 */
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
)

/** 响应模式。用户关心的是"这次要多快、多深"，而不是具体用了哪个模型。 */
enum class ResponseMode(val label: String, val hint: String) {
    Fast("快速", "尽快回应，适合简单问答"),
    Standard("标准", "速度与质量的平衡"),
    Deep("深入", "多步推理，适合复杂任务"),
}

/**
 * 模型服务使用的协议。决定请求体怎么拼、流式响应怎么解析。
 * 自定义服务商时由用户二选一。
 */
enum class LlmProtocol(val label: String, val hint: String) {
    OpenAi("OpenAI 兼容", "DeepSeek、通义、智谱、Kimi 及多数自建端点都走这套"),
    Anthropic("Anthropic", "Claude 的原生协议"),
}

/**
 * 一个大模型服务商。
 *
 * 内置的几家只是预填了地址与常见模型名，用户同样可以自行添加任意服务商，
 * 并在两种协议之间选择——这是"模型自由"的落点。
 */
data class ModelProvider(
    val id: String,
    val name: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val models: List<String>,
    val apiKey: String = "",
    val builtIn: Boolean = true,
) {
    val configured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && models.isNotEmpty()

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
data class Channel(
    val id: String,
    val name: String,
    val glyph: String,
    val connected: Boolean = false,
)

/** Skill：可插拔的能力单元。 */
data class Skill(
    val id: String,
    val name: String,
    val summary: String,
    val glyph: String,
    val installed: Boolean = false,
)
