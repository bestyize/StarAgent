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

    /**
     * 群聊里某个成员这一轮没开口。
     *
     * 不塞进 [Notice]：Notice 的心智是"需要你去处理的事"，而跳过是讨论过程的一部分，
     * 界面上是一行淡淡的居中提示，不是一张待办卡片。
     */
    @Serializable
    data class Skipped(val reason: String = "") : MessageBody
}

@Serializable
data class ExecutionStep(val label: String, val done: Boolean)

/**
 * 一条消息。
 *
 * [author] 只区分"用户 / 智能体"两种角色位；**具体是哪个智能体**由 [speakerAgentId] 承载。
 * 两者正交，所以群聊不需要动 [Author]——把它改成密封类型会改变已有存档的序列化形状，
 * 旧数据解码失败会静默退化成种子数据（见 Persistence.kt 的注释）。
 *
 * 约定：[Author.User] + null = 用户发言；[Author.Agent] + null = 单聊里的智能体；
 * [Author.Agent] + 非空 = 群聊里的某个成员。
 */
@Serializable
data class ChatMessage(
    val id: String,
    val author: Author,
    val body: MessageBody,
    val timeLabel: String,
    /** 群聊里这条消息由哪个智能体说的；用户消息与单聊消息为 null。 */
    val speakerAgentId: String? = null,
    /** 群聊里这条消息属于第几轮（从 1 开始）；单聊为 null。 */
    val roundIndex: Int? = null,
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
