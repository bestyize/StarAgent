package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.TaskDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 对话页。整个产品的交互主干：其他能力都从这里"长出来"——
 * 任务在对话里创建、产物在对话里沉淀、记忆在对话里露出。
 */
@Composable
fun ChatScreen(navigator: Navigator, agentId: String) {
    val state = LocalAppState.current
    val agent = state.agent(agentId)
    if (agent == null) {
        LaunchedEffect(agentId) { navigator.pop() }
        return
    }

    val messages = state.messagesOf(agentId)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var sheetOpen by remember { mutableStateOf(false) }
    var turn by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<ChatMessage?>(null) }
    val busy = state.isBusy(agentId)
    val configured = state.endpointFor(agent) != null

    // 流式回复时消息条数不变、只有最后一条在变长——
    // 只监听条数的话，新吐出来的字会长到屏幕外，看起来就像没有流式。
    val streamingText = (messages.lastOrNull()?.body as? MessageBody.Text)?.text.orEmpty()
    val waitingFirstToken = busy && streamingText.isBlank()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // 内容增长时跟着滚，但用户自己往回翻了就别硬拽回来。
    LaunchedEffect(streamingText) {
        if (streamingText.isEmpty() || messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= info.totalItemsCount - 2) listState.scrollToItem(messages.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                agent = agent,
                statusText = "${state.modelLabel(agent.modelId)} · ${state.activeChannelLabel()}",
                onBack = { navigator.pop() },
                onEdit = { navigator.push(Route.AgentEditor(agent.id)) },
                onStatusClick = { sheetOpen = true },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        onConfirmDraft = { draft -> state.confirmDraft(agentId, draft) },
                        onLongPress = { picked = message },
                    )
                }
                if (waitingFirstToken) {
                    item(key = "thinking") { ThinkingRow() }
                }
            }

            picked?.let { message ->
                PickedMessageBar(
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.copyableText()))
                        picked = null
                    },
                    onDelete = {
                        state.removeMessage(agentId, message.id)
                        picked = null
                    },
                    onCancel = { picked = null },
                )
            }

            // 没有可用的模型服务时，先把话说明白，别让用户对着输入框猜为什么没反应。
            if (!configured) {
                ConfigureModelBanner(onClick = { navigator.push(Route.ModelConfig) })
            }

            InputDock(
                enabled = configured,
                busy = busy,
                onSend = { text -> turn = scope.launch { state.send(agentId, text) } },
                onStop = { turn?.cancel() },
            )
        }

        if (sheetOpen) {
            AgentStatusSheet(
                agent = agent,
                onDismiss = { sheetOpen = false },
                onPickModel = { state.setAgentModel(agentId, it) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    onConfirmDraft: (TaskDraft) -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            // 长按选中这条消息，再决定复制还是删除。
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        when (message.author) {
            Author.User -> {
                val text = (message.body as? MessageBody.Text)?.text
                if (!text.isNullOrBlank()) UserBubble(text)
            }

            Author.Agent -> when (val body = message.body) {
                // 流式回复刚开头、还没有内容时先不画空气泡，交给下方的「···」占位。
                is MessageBody.Text -> if (body.text.isNotBlank()) AgentTextBubble(body.text)
                is MessageBody.Notice -> NoticeCard(body.text, body.isError)
                is MessageBody.Execution -> ExecutionCard(body.steps)
                is MessageBody.Draft -> DraftCard(body.draft, onConfirm = { onConfirmDraft(body.draft) })
                is MessageBody.Created -> CreatedCard(body.task)
                is MessageBody.Delivered -> ArtifactCard(body.artifact)
                is MessageBody.MemoryNote -> MemoryNoteCard(body.text)
            }
        }
    }
}

/** 长按选中一条消息后的操作条。 */
@Composable
private fun PickedMessageBar(onCopy: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StarPalette.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选中这条消息",
            style = MaterialTheme.typography.labelMedium,
            color = StarPalette.TextMuted,
            modifier = Modifier.weight(1f),
        )
        BarAction("复制", StarPalette.Indigo, onCopy)
        Spacer(Modifier.width(16.dp))
        BarAction("删除", StarPalette.Danger, onDelete)
        Spacer(Modifier.width(16.dp))
        BarAction("取消", StarPalette.TextMuted, onCancel)
    }
}

@Composable
private fun BarAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.clickable { onClick() },
    )
}

/** 把一条消息摊平成可复制的文本——卡片的字段也一并带上。 */
private fun ChatMessage.copyableText(): String = when (val body = body) {
    is MessageBody.Text -> body.text
    is MessageBody.Notice -> body.text
    is MessageBody.Execution -> body.steps.joinToString("\n") { it.label }
    is MessageBody.Draft ->
        "任务草稿：${body.draft.title}｜${body.draft.scheduleLabel}｜${body.draft.channelLabel}"
    is MessageBody.Created -> "任务：${body.task.title}（${body.task.scheduleLabel}）"
    is MessageBody.Delivered -> "${body.artifact.title}\n${body.artifact.summary}"
    is MessageBody.MemoryNote -> body.text
}

/**
 * 没有可用模型时的提示条。它的存在是为了消除"发了消息却没反应"这种困惑——
 * 直接把原因和去处摆出来。
 */
@Composable
private fun ConfigureModelBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF4E0))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(
            icon = StarIconGlyph.Info,
            iconSize = 16.dp,
            tint = StarPalette.Warning,
            strokeWidth = 2f,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "还没配置模型服务，点这里填一个 API Key",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A5A00),
            modifier = Modifier.weight(1f),
        )
        StarIcon(
            icon = StarIconGlyph.ChevronRight,
            iconSize = 14.dp,
            tint = Color(0xFF8A5A00),
            strokeWidth = 2f,
        )
    }
}

/**
 * 顶栏常驻状态位。把"模型自由"和"IM 连接"两个卖点做成**持续可见的状态**，
 * 而不是藏在设置里的开关——点它就能当场切换。
 */
@Composable
private fun ChatHeader(
    agent: Agent,
    statusText: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onStatusClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StarPalette.Surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(glyph = StarIconGlyph.Back, onClick = onBack)
            Spacer(Modifier.width(2.dp))
            AgentAvatar(
                glyph = agent.glyph,
                accentIndex = agent.accentIndex,
                size = 36.dp,
                cornerRadius = 12.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = StarPalette.Text,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onStatusClick() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(StarPalette.Success),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = StarPalette.TextMuted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(3.dp))
                    StarIcon(
                        icon = StarIconGlyph.ChevronDown,
                        iconSize = 12.dp,
                        tint = StarPalette.TextFaint,
                        strokeWidth = 2.2f,
                    )
                }
            }
            CircleIconButton(glyph = StarIconGlyph.More, onClick = onEdit)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StarPalette.Divider),
        )
    }
}
