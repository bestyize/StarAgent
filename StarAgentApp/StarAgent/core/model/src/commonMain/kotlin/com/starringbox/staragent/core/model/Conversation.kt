package com.starringbox.staragent.core.model

enum class Author { User, Agent }

/**
 * 消息体。对话流里不只有文字——任务草稿、执行过程、产物、记忆提示
 * 都是一等公民，它们以卡片形式内联在消息流里，让能力"从对话里长出来"。
 *
 * 注意：这里的分支名不要与 [Artifact] 等模型类型重名，
 * 否则嵌套类会遮蔽同名类型，属性类型会意外解析成它自己。
 */
sealed interface MessageBody {
    data class Text(val text: String) : MessageBody

    /**
     * 系统提示：未配置模型、调用失败、被限流等。
     * 和普通回复区分开，因为它是需要用户去处理的信息，不是模型说的话。
     */
    data class Notice(val text: String, val isError: Boolean = false) : MessageBody

    data class Execution(val steps: List<ExecutionStep>) : MessageBody
    data class Draft(val draft: TaskDraft) : MessageBody
    data class Created(val task: AgentTask) : MessageBody
    data class Delivered(val artifact: Artifact) : MessageBody
    data class MemoryNote(val text: String) : MessageBody
}

data class ExecutionStep(val label: String, val done: Boolean)

data class ChatMessage(
    val id: String,
    val author: Author,
    val body: MessageBody,
    val timeLabel: String,
)

enum class ArtifactKind(val label: String) {
    Digest("汇总"),
    Report("报告"),
    Checklist("清单"),
    Draft("草稿"),
}

data class ArtifactSection(val heading: String, val lines: List<String>)

/**
 * 产物。一期智能体没有"手"，交付感全靠它：
 * 把结果从"聊天流里的一段文字"变成"一个能带走的成果"。
 */
data class Artifact(
    val id: String,
    val title: String,
    val kind: ArtifactKind,
    val summary: String,
    val sections: List<ArtifactSection> = emptyList(),
)
