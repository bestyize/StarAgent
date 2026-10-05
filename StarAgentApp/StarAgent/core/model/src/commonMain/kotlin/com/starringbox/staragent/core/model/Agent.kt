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

/**
 * 工具执行权限策略。
 *
 * 自动：智能体自主调用工具，无需每次向用户请示。
 * 按需：对有副作用的操作（如新建/修改/取消任务），系统会自动弹出提问卡片征求用户同意。
 */
@Serializable
enum class ExecutionPolicy(val label: String, val summary: String) {
    Auto("自动", "智能体自主调用工具，无需每次打扰"),
    OnDemand("按需", "创建或修改任务等写入操作需用户二次确认"),
}

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
) {
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
 * Skill：可插拔的能力单元，和 agentskills.io 开放规范（Claude / Codex / Hermes / OpenClaw 通用）同形。
 *
 * 渐进披露分三层：[summary] 每轮都随系统提示发给模型，让它知道有这个技能；[instructions]
 * （SKILL.md 正文）只在模型调 `use_skill` 或用户输入 `/id` 时才读进上下文；技能目录里附带的
 * 其他文件（references/、assets/…）再按需用 `read_skill_file` 读——技能一多，全塞进提示会挤掉对话本身。
 *
 * [id] 就是规范里的 `name`、也是目录名；[name] 是给人看的名字（规范外，存在 `metadata.display-name`）。
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val summary: String,
    val glyph: String,
    val installed: Boolean = false,
    val instructions: String = "",
    val source: SkillSource = SkillSource.Imported,
    /** 规范的 `disable-model-invocation` 取反。false 时不进技能目录，只能由用户 `/id` 调用。 */
    val modelInvocable: Boolean = true,
    /** Claude Code 的 `user-invocable`。false 时用户不能 `/id` 调用，只给模型用。 */
    val userInvocable: Boolean = true,
    /**
     * 导入时的 SKILL.md 原文。非空时按原文落盘，`license`、`allowed-tools`、`metadata`
     * 这些本 App 不认识的字段才不会在保存时丢掉；内置和智能体创建的技能为空，由字段重新生成。
     */
    val document: String = "",
)

/** 技能从哪来。决定能不能被智能体改写：只有 [Agent] 来源的才能。 */
@Serializable
enum class SkillSource(val label: String) {
    BuiltIn("内置"),
    Imported("导入"),
    Agent("智能体创建"),
}
