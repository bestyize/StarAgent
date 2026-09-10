package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Agent
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

/**
 * 一期的种子数据。
 *
 * 内置服务商只预填了接口地址与常见模型名，**不含任何 API Key**——
 * 用户需要自己去「我的 → 模型服务」填一个，填完就能真正对话。
 */
internal object Seed {

    fun populate(state: AppState) {
        state.providers.addAll(
            listOf(
                ModelProvider(
                    id = "deepseek",
                    name = "DeepSeek",
                    protocol = LlmProtocol.OpenAi,
                    baseUrl = "https://api.deepseek.com/v1",
                    models = listOf("deepseek-chat", "deepseek-reasoner"),
                ),
                ModelProvider(
                    id = "qwen",
                    name = "通义千问",
                    protocol = LlmProtocol.OpenAi,
                    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    models = listOf("qwen-max", "qwen-plus"),
                ),
                ModelProvider(
                    id = "zhipu",
                    name = "智谱 GLM",
                    protocol = LlmProtocol.OpenAi,
                    baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                    models = listOf("glm-4-plus", "glm-4-flash"),
                ),
                ModelProvider(
                    id = "openai",
                    name = "OpenAI",
                    protocol = LlmProtocol.OpenAi,
                    baseUrl = "https://api.openai.com/v1",
                    models = listOf("gpt-5.3", "gpt-5.3-mini"),
                ),
                ModelProvider(
                    id = "anthropic",
                    name = "Anthropic",
                    protocol = LlmProtocol.Anthropic,
                    baseUrl = "https://api.anthropic.com/v1",
                    models = listOf("claude-sonnet-5", "claude-opus-5"),
                ),
            )
        )

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
                ),
                AgentTask(
                    id = "task-seed-2",
                    agentId = "a-work",
                    title = "周报生成",
                    recurrence = Recurrence.Weekly,
                    scheduleLabel = "每周一 09:00",
                    nextRunLabel = "9 月 15 日 09:00",
                ),
            )
        )

        state.memories.addAll(
            listOf(
                MemoryItem("mem-1", "a-xing", MemoryCategory.Preference, "偏好简洁直接的答复，不要客套话"),
                MemoryItem("mem-2", "a-xing", MemoryCategory.Habit, "习惯在早上通勤时看资讯汇总"),
                MemoryItem("mem-3", "a-xing", MemoryCategory.Fact, "常驻城市是杭州"),
                MemoryItem("mem-4", "a-work", MemoryCategory.Preference, "周报必须包含风险项，不要只写进展"),
                MemoryItem("mem-5", "a-work", MemoryCategory.Project, "移动端 Agent 项目处于一期立项阶段"),
                MemoryItem("mem-6", "a-english", MemoryCategory.Preference, "希望纠错时说明原因，而不是直接给答案"),
            )
        )

        seedConversations(state)
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
