package com.starringbox.staragent.core.data

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.runtime.TurnCancelledException

/**
 * 管理单轮回复在对话消息列表中的呈现槽位。
 *
 * 封装：
 * 1. 占位消息（placeholder）与工具执行卡片（executionCard）的插入与相对位置维护；
 * 2. 流式文本与步骤更新的就地刷新；
 * 3. 产物卡片追加位置管理；
 * 4. 用户取消、错误与停机提示展示。
 */
internal class TurnConversationSlot(
    private val conversation: SnapshotStateList<ChatMessage>,
    private val placeholder: ChatMessage,
    private val nextId: (String) -> String,
    private val speakerAgentId: String? = null,
    private val roundIndex: Int? = null,
) {
    private var index: Int = -1
    private var executionIndex: Int = -1
    private val buffer = StringBuilder()

    fun attach() {
        conversation.add(placeholder)
        index = conversation.lastIndex
    }

    fun onText(chunk: String) {
        buffer.append(chunk)
        conversation[index] = placeholder.copy(body = MessageBody.Text(buffer.toString()))
    }

    fun onSteps(steps: List<ExecutionStep>) {
        if (executionIndex < 0) {
            // 工具卡必须位于回复占位之前，用户看到的顺序才是「工具执行 → 最终回复」
            conversation.add(
                index,
                ChatMessage(
                    id = nextId("m"),
                    author = Author.Agent,
                    body = MessageBody.Execution(steps),
                    timeLabel = "刚刚",
                    speakerAgentId = speakerAgentId,
                    roundIndex = roundIndex,
                )
            )
            executionIndex = index
            index++
        } else {
            conversation[executionIndex] =
                conversation[executionIndex].copy(body = MessageBody.Execution(steps))
        }
    }

    fun onCancelled(
        cancelled: TurnCancelledException,
        appendArtifacts: (SnapshotStateList<ChatMessage>, Int, List<Artifact>) -> Unit,
    ) {
        if (cancelled.accumulatedText.isEmpty()) {
            conversation[index] = placeholder.copy(body = MessageBody.Notice("已停止"))
        }
        appendArtifacts(conversation, index + 1, cancelled.artifacts)
    }

    fun setNotice(message: String, isError: Boolean = false) {
        conversation[index] = placeholder.copy(body = MessageBody.Notice(message, isError = isError))
    }

    fun setReply(body: MessageBody) {
        conversation[index] = placeholder.copy(body = body)
    }

    fun addNoticeAfter(reason: String) {
        conversation.add(
            ChatMessage(
                id = nextId("m"),
                author = Author.Agent,
                body = MessageBody.Notice(reason),
                timeLabel = "刚刚",
                speakerAgentId = speakerAgentId,
                roundIndex = roundIndex,
            )
        )
    }

    fun currentText(): String = buffer.toString()

    val nextInsertionIndex: Int get() = index + 1
}
