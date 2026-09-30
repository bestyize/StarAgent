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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.starringbox.staragent.core.designsystem.component.StarActionSheet
import com.starringbox.staragent.core.designsystem.component.StarConfirmDialog
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.component.StarImageViewer
import com.starringbox.staragent.core.designsystem.component.StarSheetAction
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaRules
import com.starringbox.staragent.core.platform.media.MediaSavers
import com.starringbox.staragent.core.platform.media.suggestedFileName
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.TaskDraft
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
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
    // 右上角「更多」的操作面板。和设置面板分开两个状态：一个是菜单，一个是被菜单打开的设置。
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var turn by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<ChatMessage?>(null) }
    // 全屏图片查看：记住是哪条消息、从第几张开始看
    var viewingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var viewingIndex by remember { mutableStateOf(0) }
    // 待发送的图片，以及一条提示（选图被拒 / 保存结果共用）
    var attachments by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var notice by remember { mutableStateOf<DockNotice?>(null) }
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


    // 念出最后一条回复。只有开了朗读、且现在念得了的时候才做，其余情况静默跳过——
    // 念不出来不该打断对话。卡片类消息不念：它们不是"话"。
    val readReplyAloud: suspend () -> Unit = {
        if (state.speakReplies && state.canSpeak) {
            val reply = messages.lastOrNull { it.author == Author.Agent }
                ?.let { (it.body as? MessageBody.Text)?.text }
                ?.takeIf { it.isNotBlank() }
            if (reply != null) {
                val spoken = state.speak(reply)
                if (spoken is SpeechOutcome.Failed) {
                    notice = DockNotice(spoken.message, isError = true)
                }
            }
        }
    }

    // 离开这一页就别再念了。朗读是跟着这条会话走的，人都走了还在响很突兀；
    // 而且朗读是后台在播，不主动停的话它会一直念完为止。
    DisposableEffect(state) {
        onDispose { state.stopSpeaking() }
    }

    // 按住说话。转写成功直接发出去——"语音对话"不该还要再点一次发送。
    val voice = rememberVoiceHold(
        state = state,
        scope = scope,
        onHeard = { text ->
            turn = scope.launch {
                state.send(agentId, text, emptyList())
                readReplyAloud()
            }
        },
        onProblem = { notice = DockNotice(it, isError = true) },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                agent = agent,
                statusText = "${state.modelLabel(agent.modelId)} · ${state.activeChannelLabel()}",
                onBack = { navigator.pop() },
                onMore = { menuOpen = true },
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
                        onAnswerQuestion = { qId, answer -> state.answerQuestion(agentId, qId, answer) },
                        onLongPress = { picked = message },
                        onImageClick = { index ->
                            viewingMessage = message
                            viewingIndex = index
                        },
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

            notice?.let { current ->
                DockNoticeStrip(
                    text = current.text,
                    isError = current.isError,
                    onDismiss = { notice = null },
                )
            }

            InputDock(
                enabled = configured,
                busy = busy,
                attachments = attachments,
                canPickImage = MediaPickers.available,
                onPickImage = {
                    notice = null
                    scope.launch {
                        val asset = MediaPickers.pickImage() ?: return@launch
                        val rejection = MediaRules.rejection(asset)
                        if (rejection != null) {
                            notice = DockNotice(rejection.message, isError = true)
                        } else {
                            attachments = attachments + asset
                        }
                    }
                },
                onRemoveAttachment = { asset -> attachments = attachments.filterNot { it.id == asset.id } },
                onSend = { text ->
                    val outgoing = attachments
                    attachments = emptyList()
                    turn = scope.launch {
                        state.send(agentId, text, outgoing)
                        readReplyAloud()
                    }
                },
                onStop = { turn?.cancel() },
                canVoice = state.canListen,
                voiceMode = voice.mode,
                recognizing = voice.phase == HoldPhase.Transcribing,
                onVoiceModeChange = {
                    notice = null
                    voice.onModeChange(it)
                },
                onHoldStart = {
                    notice = null
                    voice.onStart()
                },
                onHoldCancelZone = voice.onCancelZone,
                onHoldRelease = voice.onRelease,
            )
        }

        // 录音浮层。放在最外层，盖住消息列表和输入坞。
        VoiceHoldOverlay(voice.phase)

        // 右上角「更多」。顶栏原本有两个入口（状态位=设置、三点=编辑），
        // 现在并成一个菜单——顶栏只留一个按钮，状态位退回成纯展示。
        if (menuOpen) {
            StarActionSheet(
                actions = listOf(
                    StarSheetAction("设置对话"),
                    StarSheetAction("编辑智能体"),
                    // 破坏性的放最后并标红：这是菜单里唯一不可逆的一项
                    StarSheetAction("清除聊天记录", destructive = true),
                ),
                onAction = { index ->
                    // 先关菜单再开下一个：两个面板叠着出现会互相顶掉
                    menuOpen = false
                    when (index) {
                        0 -> sheetOpen = true
                        1 -> navigator.push(Route.AgentEditor(agent.id))
                        2 -> confirmClear = true
                    }
                },
                onDismiss = { menuOpen = false },
            )
        }

        if (confirmClear) {
            StarConfirmDialog(
                title = "清除聊天记录",
                message = "这条会话里的消息会全部删除，删了找不回来。它记住的事不受影响。",
                confirmLabel = "删除",
                onConfirm = {
                    state.clearMessages(agentId)
                    confirmClear = false
                },
                onDismiss = { confirmClear = false },
            )
        }

        // 常驻组合、由 visible 驱动：关掉时要留在树里把退场动画播完。
        // 它自身在隐藏时不留任何可见内容，也不吃触摸。
        AgentStatusSheet(
            agent = agent,
            visible = sheetOpen,
            onDismiss = { sheetOpen = false },
            onPickModel = { state.setAgentModel(agentId, it) },
        )

        // 全屏看图。放在最外层，盖住底栏与输入坞。
        (viewingMessage?.body as? MessageBody.Image)?.let { body ->
            val assets = body.assets
            StarImageViewer(
                images = assets.map { it.bytes },
                initialIndex = viewingIndex,
                onDismiss = { viewingMessage = null },
                onSave = if (MediaSavers.available) {
                    { index ->
                        val asset = assets.getOrNull(index)
                        if (asset != null) {
                            scope.launch {
                                val saved = MediaSavers.save(asset, asset.suggestedFileName())
                                notice = if (saved != null) {
                                    DockNotice("已保存到 $saved", isError = false)
                                } else {
                                    DockNotice("没能保存这张图片", isError = true)
                                }
                                // 成功就退回对话，让那条提示看得见
                                viewingMessage = null
                            }
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    onConfirmDraft: (TaskDraft) -> Unit,
    onAnswerQuestion: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onImageClick: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            // 长按选中这条消息，再决定复制还是删除。
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        MessageBodyView(
            body = message.body,
            author = message.author,
            onConfirmDraft = onConfirmDraft,
            onImageClick = onImageClick,
            onAnswerQuestion = onAnswerQuestion,
        )
    }
}

/**
 * 一条消息的内容本身（不含"长按选中"这层交互，也不含群聊的发言人抬头）。
 *
 * 单聊和群聊的消息长得不一样：群聊要在气泡上方多一行发言人。所以把这层抽出来共用，
 * 两边的差异各自在外面加。
 *
 * [speakerName] 只有群聊会传，仅用于渲染"跳过了本轮"这类需要点名的提示。
 */
@Composable
internal fun MessageBodyView(
    body: MessageBody,
    author: Author,
    onConfirmDraft: (TaskDraft) -> Unit,
    onImageClick: (Int) -> Unit,
    onAnswerQuestion: (String, String) -> Unit = { _, _ -> },
    speakerName: String? = null,
) {
    when (author) {
        Author.User -> when (body) {
            is MessageBody.Text -> if (body.text.isNotBlank()) UserBubble(body.text)
            is MessageBody.Image -> UserImageBubble(body.assets, body.caption, onImageClick)
            else -> Unit
        }

        Author.Agent -> when (body) {
            // 目前模型只回文本；图片消息都是用户发的
            is MessageBody.Image -> Unit
            // 流式回复刚开头、还没有内容时先不画空气泡，交给下方的「···」占位。
            is MessageBody.Text -> if (body.text.isNotBlank()) AgentTextBubble(body.text)
            is MessageBody.Notice -> NoticeCard(body.text, body.isError)
            is MessageBody.Execution -> ExecutionCard(body.steps)
            is MessageBody.Draft -> DraftCard(body.draft, onConfirm = { onConfirmDraft(body.draft) })
            is MessageBody.Created -> CreatedCard(body.task)
            is MessageBody.Delivered -> ArtifactCard(body.artifact)
            is MessageBody.MemoryNote -> MemoryNoteCard(body.text)
            is MessageBody.Skipped -> SkippedRow(speakerName.orEmpty(), body.reason)
            is MessageBody.Question -> QuestionCard(
                question = body,
                onAnswer = { answer -> onAnswerQuestion(body.id, answer) },
            )
        }
    }
}

/**
 * 群聊里的一条消息。
 *
 * 与单聊唯一的区别是**多一行发言人抬头**——群里每条消息都得一眼看出是谁说的，
 * 只靠左右对齐不够。用户自己的消息不加抬头。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupMessageRow(
    message: ChatMessage,
    speaker: Agent?,
    onLongPress: () -> Unit,
    onImageClick: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val isUser = message.author == Author.User
            // 抬头只加在"某位成员真的说了话"的消息上——跳过是过程提示，加抬头反而累赘
            if (!isUser && message.body !is MessageBody.Skipped && speaker != null) {
                SpeakerHeader(speaker)
            }
            MessageBodyView(
                body = message.body,
                author = message.author,
                onConfirmDraft = {},
                onImageClick = onImageClick,
                speakerName = speaker?.name,
            )
        }
    }
}

/** 群聊里气泡上方那一行：这条是谁说的。 */
@Composable
private fun SpeakerHeader(agent: Agent) {
    Row(
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(
            glyph = agent.glyph,
            accentIndex = agent.accentIndex,
            size = 20.dp,
            cornerRadius = 7.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = agent.name,
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextMuted,
        )
    }
}

/** 「某某 跳过了本轮」。讨论过程的一部分，所以是一行淡的居中提示，不是待办卡片。 */
@Composable
private fun SkippedRow(speakerName: String, reason: String) {
    val who = speakerName.ifBlank { "成员" }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (reason.isBlank()) "$who 跳过了本轮" else "$who 跳过了：$reason",
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
        )
    }
}

/** 长按选中一条消息后的操作条。 */
@Composable
internal fun PickedMessageBar(onCopy: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
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
internal fun ChatMessage.copyableText(): String = when (val body = body) {
    is MessageBody.Text -> body.text
    is MessageBody.Image -> buildString {
        append("[图片 ×").append(body.assets.size).append(']')
        if (body.caption.isNotBlank()) append(' ').append(body.caption)
    }

    is MessageBody.Notice -> body.text
    is MessageBody.Execution -> body.steps.joinToString("\n") { it.label }
    is MessageBody.Draft ->
        "任务草稿：${body.draft.title}｜${body.draft.scheduleLabel}｜${body.draft.channelLabel}"
    is MessageBody.Created -> "任务：${body.task.title}（${body.task.scheduleLabel}）"
    is MessageBody.Delivered -> "${body.artifact.title}\n${body.artifact.summary}"
    is MessageBody.MemoryNote -> body.text
    is MessageBody.Skipped -> if (body.reason.isBlank()) "跳过了本轮" else "跳过了：${body.reason}"
    is MessageBody.Question -> "提问：${body.question}${body.selectedAnswer?.let { "｜已选择：$it" }.orEmpty()}"
}

/** 输入坞上方的一条提示。选图被拒、保存成功/失败都走它。 */
internal data class DockNotice(val text: String, val isError: Boolean)

@Composable
internal fun DockNoticeStrip(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val background = if (isError) Color(0xFFFDECEC) else Color(0xFFE8F7F1)
    val foreground = if (isError) Color(0xFF9B1C1C) else Color(0xFF0F6B4F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(
            icon = if (isError) StarIconGlyph.Info else StarIconGlyph.Check,
            iconSize = 16.dp,
            tint = foreground,
            strokeWidth = 2f,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            modifier = Modifier.weight(1f),
        )
        StarIcon(
            icon = StarIconGlyph.Close,
            iconSize = 14.dp,
            tint = foreground,
            strokeWidth = 2.2f,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

/**
 * 没有可用模型时的提示条。它的存在是为了消除"发了消息却没反应"这种困惑——
 * 直接把原因和去处摆出来。
 */
@Composable
internal fun ConfigureModelBanner(onClick: () -> Unit) {
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
 * 而不是藏在设置里的开关。
 *
 * 它自己不再是入口：模型、渠道、语音这些统一收到右上角「更多」里的「设置对话」，
 * 顶栏只负责告诉你现在是什么状态。所以连那个下拉小箭头也去掉了——
 * 留着它就还是个按钮的样子。
 */
@Composable
private fun ChatHeader(
    agent: Agent,
    statusText: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StarPalette.Surface)
                // 底色往上铺到状态栏后面，内容躲开状态栏。见 App.kt 里关于顶栏归属的说明。
                .statusBarsPadding()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
            }
            CircleIconButton(glyph = StarIconGlyph.More, onClick = onMore)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StarPalette.Divider),
        )
    }
}
