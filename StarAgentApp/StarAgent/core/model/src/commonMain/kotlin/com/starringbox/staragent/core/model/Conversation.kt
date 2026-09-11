package com.starringbox.staragent.core.model

import kotlinx.serialization.Serializable
import com.starringbox.staragent.core.media.MediaAsset

@Serializable
enum class Author { User, Agent }

/**
 * 消息体。对话流里不只有文字——任务草稿、执行过程、产物、记忆提示
 * 都是一等公民，它们以卡片形式内联在消息流里，让能力"从对话里长出来"。
 *
 * 注意：这里的分支名不要与 [Artifact] 等模型类型重名，
 * 否则嵌套类会遮蔽同名类型，属性类型会意外解析成它自己。
 */
@Serializable
sealed interface MessageBody {
    @Serializable
    data class Text(val text: String) : MessageBody

    /**
     * 带图片的消息。图片与文字可以同时存在——
     * 用户常见的用法是"拍一张 + 一句问题"。
     */
    @Serializable
    data class Image(
        val assets: List<MediaAsset>,
        val caption: String = "",
    ) : MessageBody

    /**
     * 系统提示：未配置模型、调用失败、被限流等。
     * 和普通回复区分开，因为它是需要用户去处理的信息，不是模型说的话。
     */
    @Serializable
    data class Notice(val text: String, val isError: Boolean = false) : MessageBody

    @Serializable

    data class Execution(val steps: List<ExecutionStep>) : MessageBody
    @Serializable
    data class Draft(val draft: TaskDraft) : MessageBody
    @Serializable
    data class Created(val task: AgentTask) : MessageBody
    @Serializable
    data class Delivered(val artifact: Artifact) : MessageBody
    @Serializable
    data class MemoryNote(val text: String) : MessageBody
}

@Serializable
data class ExecutionStep(val label: String, val done: Boolean)

@Serializable
data class ChatMessage(
    val id: String,
    val author: Author,
    val body: MessageBody,
    val timeLabel: String,
)

@Serializable
enum class ArtifactKind(val label: String) {
    Digest("汇总"),
    Report("报告"),
    Checklist("清单"),
    Draft("草稿"),
}

@Serializable
data class ArtifactSection(val heading: String, val lines: List<String>)

/**
 * 产物。一期智能体没有"手"，交付感全靠它：
 * 把结果从"聊天流里的一段文字"变成"一个能带走的成果"。
 */
@Serializable
data class Artifact(
    val id: String,
    val title: String,
    val kind: ArtifactKind,
    val summary: String,
    val sections: List<ArtifactSection> = emptyList(),
)
