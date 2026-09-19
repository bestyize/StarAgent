package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.model.ArtifactKind
import com.starringbox.staragent.core.model.ArtifactSection
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.model.SpeechProtocol
import com.starringbox.staragent.core.model.SpeechProvider
import com.starringbox.staragent.core.model.TaskPriority

/**
 * 一期的种子数据。
 *
 * 分两部分，调用时机不同：
 * [installProviders] 是**结构**，每次启动都要装；[populateDemo] 是**演示内容**，只在首次启动灌一次。
 * 混在一起会导致：走存档还原时服务商列表是空的。
 */
internal object Seed {

    /**
     * 内置服务商。只预填接口地址与常见模型名，**不含任何 API Key**——
     * 用户需要自己去「我的 → 模型服务」填一个，填完就能真正对话。
     *
     * 用户对服务商的改动（Key、模型清单、自建端点）存在 SettingsStore 里，
     * 这里提供的是每次启动都该存在的基础层，所以**不能**放进"仅首次"的分支。
     */
    fun installProviders(state: AppState) {
        state.providers.addAll(
            listOf(
                ModelProvider(
                    id = "deepseek",
                    name = "DeepSeek",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.deepseek.com/v1",
                    // DeepSeek 两种协议路径不同：OpenAI 在根下，Anthropic 在 /anthropic
                    anthropicBaseUrl = "https://api.deepseek.com/anthropic",
                    // deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用，改成了 V4 系列
                    // deepseek-v4-pro 也将在 2026-09-14 之后统一路由到 V4.1 Flash
                    models = listOf("deepseek-flash", "deepseek-v4-pro"),
                ),
                ModelProvider(
                    id = "qwen",
                    name = "通义千问",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    anthropicBaseUrl = "https://dashscope.aliyuncs.com/apps/anthropic",
                    models = listOf("qwen3.8-max", "qwen3.8-max-0902"),
                ),
                ModelProvider(
                    id = "zhipu",
                    name = "智谱 GLM",
                    protocol = LlmProtocol.OpenAi,
                    // 公司改名为 Z.AI，但大陆端点没变（api.z.ai 是新增的国际端点）
                    openAiBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
                    anthropicBaseUrl = "https://open.bigmodel.cn/api/anthropic",
                    models = listOf("glm-5.3", "glm-5.3-flash", "glm-5.2"),
                ),
                ModelProvider(
                    id = "kimi",
                    name = "Kimi",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.moonshot.cn/v1",
                    anthropicBaseUrl = "https://api.moonshot.cn/anthropic",
                    // 旧的 kimi-k2-*-preview 系列已于 2026-05-25 停服
                    models = listOf("kimi-k3", "kimi-k2.7-code", "kimi-k2.6"),
                ),
                ModelProvider(
                    id = "minimax",
                    name = "MiniMax",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.minimaxi.com/v1",
                    anthropicBaseUrl = "https://api.minimaxi.com/anthropic",
                    // 模型名区分大小写
                    models = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"),
                ),
                ModelProvider(
                    id = "mimo",
                    name = "小米 MiMo",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.xiaomimimo.com/v1",
                    anthropicBaseUrl = "https://api.xiaomimimo.com/anthropic",
                    models = listOf("xiaomi/mimo-v2.5", "xiaomi/mimo-v2.5-pro"),
                ),
                ModelProvider(
                    id = "ark-coding",
                    name = "火山 CodingPlan",
                    protocol = LlmProtocol.OpenAi,
                    // 必须带 coding 段；/api/v3 是按量计费接口，走那里不消耗套餐额度、会另外计费
                    openAiBaseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3",
                    // CodingPlan 的 Anthropic 端点比 OpenAI 的少一段 v3
                    anthropicBaseUrl = "https://ark.cn-beijing.volces.com/api/coding",
                    models = listOf(
                        "ark-code-latest",
                        "doubao-seed-evolving",
                        "doubao-seed-2.1-turbo",
                        "glm-5.3",
                        "kimi-k2.7-code",
                    ),
                ),
                ModelProvider(
                    id = "ark-plan",
                    name = "火山 Agent Plan",
                    protocol = LlmProtocol.OpenAi,
                    // **Agent Plan 和 Coding Plan 是两份订阅、两把 Key**，Base URL 也各是各的：
                    // Agent Plan 走 /api/plan，Coding Plan 走 /api/coding。两边混用的后果不是
                    // 报错而是**静默按量计费**——不消耗套餐额度，账单上才看得见。
                    // 所以这是独立的一家，不是把上面那家改个地址。
                    // 同样地，两种协议的段也不一样：Anthropic 那条没有 v3
                    openAiBaseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
                    anthropicBaseUrl = "https://ark.cn-beijing.volces.com/api/plan",
                    // 取自官方「套餐概览」列出的 Agent Plan 模型。
                    // ⚠️ 这家的 /models 是 **404**，所以详情页的「获取模型列表」在这儿用不了，
                    // 清单只能手填——这是它和别家不一样的地方，不是这里的 bug。
                    models = listOf(
                        "ark-code-latest",
                        "doubao-seed-2.1-turbo",
                        "doubao-seed-evolving",
                        "glm-5.3",
                        "kimi-k3",
                        "deepseek-v4-pro",
                    ),
                ),
                ModelProvider(
                    id = "groq",
                    name = "Groq",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.groq.com/openai/v1",
                    // 对话模型清单**故意留空**：Groq 上架的是各家开源模型，名字换得很勤，
                    // 预填一份过期的名字比不填更坏——用户会拿它去发请求然后吃一个 404。
                    // 详情页的「获取模型列表」会真去拉一次当前可用的，这里不猜。
                    models = emptyList(),
                ),
                ModelProvider(
                    id = "openai",
                    name = "OpenAI",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://api.openai.com/v1",
                    // GPT-5.6 家族分三档，裸 gpt-5.6 是 gpt-5.6-sol 的别名
                    models = listOf("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
                ),
                ModelProvider(
                    id = "anthropic",
                    name = "Anthropic",
                    protocol = LlmProtocol.Anthropic,
                    anthropicBaseUrl = "https://api.anthropic.com/v1",
                    models = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001"),
                ),
                ModelProvider(
                    id = "openrouter",
                    name = "OpenRouter",
                    protocol = LlmProtocol.OpenAi,
                    openAiBaseUrl = "https://openrouter.ai/api/v1",
                    // 模型名是「厂商/模型」；openrouter/auto 由它自动挑。
                    // 这里只放几个稳定入口，真实目录有 400+ 个，点「获取模型列表」拉。
                    models = listOf(
                        "openrouter/auto",
                        "anthropic/claude-opus-5",
                        "openai/gpt-6-astra",
                        "openai/gpt-5.6-terra",
                    ),
                ),
            )
        )
    }

    /**
     * 语音服务商的预置。**和 [installProviders] 是两份互不相干的清单**。
     *
     * 拆开的理由见 [SpeechProvider] 的注释：语音的可用性不该取决于对话那把 Key 配没配。
     * 代价是 `baseUrl` 与对话那边的 `openAiBaseUrl` 有重复——**这份重复是刻意接受的**，
     * 读对话的 providers 正是要拆掉的那个耦合。将来某家换域名，两处都要改。
     *
     * 只列**确实能用的**：
     *
     * - 前四家（智谱、Groq、OpenAI、火山方舟）的识别与合成都有对应实现，
     *   各家的 `protocol` 决定走哪一支；
     * - Groq 的备选只列**真机拉到的那份清单里确实有**的。文档上还有个
     *   `distil-whisper-large-v3-en`，但 2026-09 实际拉回来的清单里没有它——
     *   预填一个不存在的模型，用户选中就是一个 404；
     * - **Groq 的合成留空**：它确实有 `/audio/speech`，但当前那条模型线
     *   （PlayAI-TTS 已换成 Orpheus）的模型名与音色取值没能核实到，填错的直接后果是 400。
     *   留空之后朗读会自动退回设备自带。
     *
     * [SpeechProvider.protocol] 是这一层唯一按家分岔的地方：前几家都是
     * [SpeechProtocol.OpenAi]，只有火山方舟是 [SpeechProtocol.VolcArk]——
     * 它的两份接口都不在 `/audio/...` 上。新加一家**形状不同**的，除了在这里补一条，
     * 还要到 `core:llm` 的 `ModelSpeech` 里把它那一支写出来。
     */
    fun speechProviders(): List<SpeechProvider> = listOf(
        SpeechProvider(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            // 模型 ID 取自它的 OpenAPI 定义
            asrModel = "glm-asr-2512",
            ttsModel = "glm-tts",
            ttsVoice = "tongtong",
        ),
        SpeechProvider(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            // 默认给 turbo：速度与质量的平衡点
            asrModel = "whisper-large-v3-turbo",
            asrModels = listOf("whisper-large-v3"),
        ),
        SpeechProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            asrModel = "gpt-4o-transcribe",
            ttsModel = "gpt-4o-mini-tts",
            ttsVoice = "alloy",
        ),
        SpeechProvider(
            id = "volc-ark",
            name = "火山方舟 Agent Plan",
            // 语音在 openspeech.bytedance.com 上，不在方舟的 ark.cn-beijing.volces.com；
            // 识别那条还要把它换成 wss://（代码里换，这里填根域名）。
            baseUrl = "https://openspeech.bytedance.com",
            protocol = SpeechProtocol.VolcArk,
            // 火山的"模型"就是资源 ID，语义正好对上"识别模型"这一栏。
            // 这一条是**带 /plan/ 的专属端点**认的那个：Agent Plan 的 Key 不能去调
            // 普通语音控制台的 /api/v3/auc/…，两边的凭据不通用
            asrModel = "volc.seedasr.sauc.duration",
            ttsModel = "seed-tts-2.0",
            // 音色必须和资源 ID 同代：seed-tts-2.0 配 …_uranus_bigtts，
            // 配成 1.0 的 …_moon_bigtts 会被服务端以"音色与资源不匹配"拒掉
            ttsVoice = "zh_female_vv_uranus_bigtts",
        ),
    )

    /** 首次启动的演示内容：智能体、任务、记忆、技能、渠道与示例对话。 */
    fun populateDemo(state: AppState) {
        state.channels.addAll(
            listOf(
                Channel("wechat", "微信", "💬", connected = true),
                Channel("feishu", "飞书", "🔷"),
                Channel("dingtalk", "钉钉", "🔔"),
                Channel("telegram", "Telegram", "✈️"),
            )
        )

        state.skills.addAll(
            listOf(
                Skill("web", "网页检索", "联网搜索并读取网页正文", "🔍", installed = true),
                Skill("digest", "信息汇总", "把多来源信息整理成结构化摘要", "📋", installed = true),
                Skill("translate", "翻译", "多语种互译与表达润色", "🌐", installed = true),
                Skill("doc", "文档处理", "读写与整理文档、表格", "📄"),
                Skill("mail", "邮件助手", "撰写、归类与回复邮件", "✉️"),
                Skill("price", "比价", "跨平台比价与历史价格追踪", "💰"),
                Skill("remind", "提醒", "到点提醒与周期播报", "⏰"),
            )
        )

        state.agents.addAll(
            listOf(
                Agent(
                    id = "a-xing",
                    name = "小星",
                    glyph = "星",
                    persona = "通用助手，回答直接，不绕弯子",
                    modelId = "deepseek:0",
                    skillIds = listOf("web", "digest"),
                    accentIndex = 0,
                    canScheduleTasks = true,
                ),
                Agent(
                    id = "a-work",
                    name = "工作助手",
                    glyph = "工",
                    persona = "面向工作场景：周报、邮件、会议纪要",
                    modelId = "deepseek:0",
                    skillIds = listOf("doc", "mail"),
                    accentIndex = 1,
                    canScheduleTasks = true,
                ),
                Agent(
                    id = "a-english",
                    name = "英语老师",
                    glyph = "英",
                    persona = "陪你练英语，纠正表达时说明原因",
                    modelId = "deepseek:0",
                    skillIds = listOf("translate"),
                    accentIndex = 3,
                    // 陪练类的智能体不开任务能力：聊天就该是聊天。
                    canScheduleTasks = false,
                ),
            )
        )

        state.tasks.addAll(
            listOf(
                AgentTask(
                    id = "task-seed-1",
                    agentId = "a-xing",
                    title = "AI 新闻汇总",
                    recurrence = Recurrence.Daily,
                    scheduleLabel = "每天 09:00",
                    nextRunLabel = "明天 09:00",
                    lastRunLabel = "今天 09:02 已完成",
                    priority = TaskPriority.Medium,
                    categoryLabel = "工作",
                    remindLabel = "提前 10 分钟",
                    note = "整理要点并同步到知识库",
                ),
                AgentTask(
                    id = "task-seed-2",
                    agentId = "a-work",
                    title = "周报生成",
                    recurrence = Recurrence.Weekly,
                    scheduleLabel = "每周一 09:00",
                    nextRunLabel = "9 月 15 日 09:00",
                    priority = TaskPriority.High,
                    categoryLabel = "工作",
                    remindLabel = "提前 30 分钟",
                ),
            )
        )

        state.memories.addAll(
            listOf(
                MemoryItem("mem-1", "a-xing", MemoryCategory.Preference, "偏好简洁直接的答复，不要客套话"),
                MemoryItem("mem-2", "a-xing", MemoryCategory.Habit, "习惯在早上通勤时看资讯汇总"),
                MemoryItem("mem-3", "a-xing", MemoryCategory.Person, "常一起吃饭的同事是小林"),
                MemoryItem("mem-4", "a-work", MemoryCategory.Preference, "周报必须包含风险项，不要只写进展"),
                MemoryItem("mem-5", "a-work", MemoryCategory.Decision, "一期不做真机操作，先把对话与任务打磨好"),
                MemoryItem("mem-6", "a-work", MemoryCategory.Experience, "上个月做过一轮同类竞品的调研"),
                MemoryItem("mem-7", "a-english", MemoryCategory.Preference, "希望纠错时说明原因，而不是直接给答案"),
            )
        )

        seedConversations(state)

        // 一个现成的群，让首次打开就能看到「群组」这个形态
        state.groups.add(
            AgentGroup(
                id = "g-1",
                name = "三人行",
                memberIds = listOf("a-xing", "a-work", "a-english"),
                rounds = 1,
            )
        )
    }

    /**
     * 预置两段对话。注意这里只放"没有模型也能看懂"的内容——
     * 真实的模型回复要等用户配好 API Key 之后才会出现。
     */
    private fun seedConversations(state: AppState) {
        state.messagesOf("a-xing").addAll(
            listOf(
                ChatMessage(
                    id = "seed-m1",
                    author = Author.User,
                    body = MessageBody.Text("每天早上 9 点帮我汇总一下 AI 新闻"),
                    timeLabel = "昨天 21:04",
                ),
                ChatMessage(
                    id = "seed-m2",
                    author = Author.Agent,
                    body = MessageBody.Created(state.tasks.first { it.id == "task-seed-1" }),
                    timeLabel = "昨天 21:04",
                ),
                ChatMessage(
                    id = "seed-m3",
                    author = Author.Agent,
                    body = MessageBody.Delivered(dailyDigest()),
                    timeLabel = "今天 09:02",
                ),
            )
        )

        state.messagesOf("a-work").addAll(
            listOf(
                ChatMessage(
                    id = "seed-m4",
                    author = Author.User,
                    body = MessageBody.Text("帮我把这周的进展整理成周报"),
                    timeLabel = "昨天 18:20",
                ),
                ChatMessage(
                    id = "seed-m5",
                    author = Author.Agent,
                    body = MessageBody.Delivered(weeklyDraft()),
                    timeLabel = "昨天 18:21",
                ),
            )
        )

        state.messagesOf("a-english").addAll(
            listOf(
                ChatMessage(
                    id = "seed-m6",
                    author = Author.User,
                    body = MessageBody.Text("今天学点什么？"),
                    timeLabel = "周一 08:30",
                ),
                ChatMessage(
                    id = "seed-m7",
                    author = Author.Agent,
                    body = MessageBody.Text("今天的 5 个单词已经推送到微信了。先从最容易混的 affect / effect 开始吧。"),
                    timeLabel = "周一 08:30",
                ),
            )
        )
    }

    private fun dailyDigest(): Artifact = Artifact(
        id = "art-seed-1",
        title = "AI 资讯汇总 · 今日",
        kind = ArtifactKind.Digest,
        summary = "整理自 8 个来源，按重要性排序",
        sections = listOf(
            ArtifactSection(
                heading = "模型与产品",
                lines = listOf(
                    "新模型在长上下文与工具调用上提升明显，Agent 场景可用性增强",
                    "主流助手开始把「任务」提升为与对话并列的一级入口",
                ),
            ),
            ArtifactSection(
                heading = "值得关注的动向",
                lines = listOf(
                    "移动端 Agent 的操作能力正从插件/接口转向纯视觉 GUI 操控",
                    "模型选择器逐渐被「快速 / 深入」这类档位式交互取代",
                ),
            ),
        ),
    )

    private fun weeklyDraft(): Artifact = Artifact(
        id = "art-seed-2",
        title = "本周周报草稿",
        kind = ArtifactKind.Draft,
        summary = "共 4 项进展，2 项风险",
        sections = listOf(
            ArtifactSection(
                heading = "进展",
                lines = listOf(
                    "一期信息架构定稿，底部三 Tab 结构确认",
                    "对话即任务创建的交互方案完成设计",
                ),
            ),
            ArtifactSection(
                heading = "风险",
                lines = listOf(
                    "模型配置漏斗可能拦住非技术用户",
                    "技能生态一期内容不足，暂不设独立入口",
                ),
            ),
        ),
    )
}
