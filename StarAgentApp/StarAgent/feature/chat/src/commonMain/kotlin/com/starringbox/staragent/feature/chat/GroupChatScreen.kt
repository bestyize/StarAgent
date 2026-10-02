package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.component.StarImageViewer
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaRules
import com.starringbox.staragent.core.platform.media.MediaSavers
import com.starringbox.staragent.core.platform.media.suggestedFileName
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 群聊页。用户抛出一个问题，群里的智能体按顺序依次发言。
 *
 * 结构与 [ChatScreen] 同源（消息列表 + 输入坞 + 流式跟随），差异在两处：
 * 每条消息要标出发言人；一次发送触发的不是"一次回复"而是一整场讨论。
 */
@Composable
fun GroupChatScreen(navigator: Navigator, groupId: String) {
    val state = LocalAppState.current
    val group = state.group(groupId)
    if (group == null) {
        LaunchedEffect(groupId) { navigator.pop() }
        return
    }

    val messages = state.messagesOfGroup(groupId)
    val members = state.membersOf(group)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var turn by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<ChatMessage?>(null) }
    // 全屏图片查看：记住是哪条消息、从第几张开始看
    var viewingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var viewingIndex by remember { mutableStateOf(0) }
    var attachments by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var notice by remember { mutableStateOf<DockNotice?>(null) }
    val busy = state.isGroupBusy(groupId)
    val ready = state.groupReady(group)

    // 与单聊同一套：条数变了跳到底；文字在长就跟随，但用户自己往回翻了就不硬拽
    val streamingText = (messages.lastOrNull()?.body as? MessageBody.Text)?.text.orEmpty()
    val waitingFirstToken = busy && streamingText.isBlank()
    val currentSpeaker = messages.lastOrNull()?.speakerAgentId?.let { state.agent(it) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

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

    // 离开这一页就别再念了。同单聊页，理由见 ChatScreen。
    DisposableEffect(state) {
        onDispose { state.stopSpeaking() }
    }

    // 按住说话。转写成功直接发到群里，群里照样按轮流/点名去讨论。
    val voice = rememberVoiceHold(
        state = state,
        scope = scope,
        onHeard = { text ->
            turn = scope.launch {
                state.sendToGroup(groupId, text)
                readReplyAloud()
            }
        },
        onProblem = { notice = DockNotice(it, isError = true) },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GroupHeader(
                group = group,
                memberCount = members.size,
                onBack = { navigator.pop() },
                onEdit = { navigator.push(Route.GroupEditor(group.id)) },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    // 只有多轮讨论才需要分隔条；一轮的话每条都标一次反而是噪音
                    val round = message.roundIndex
                    if (group.rounds > 1 && round != null && round != messages.getOrNull(index - 1)?.roundIndex) {
                        RoundDivider(round)
                    }
                    GroupMessageRow(
                        message = message,
                        speaker = message.speakerAgentId?.let { state.agent(it) },
                        onLongPress = { picked = message },
                        onImageClick = { imageIndex ->
                            viewingMessage = message
                            viewingIndex = imageIndex
                        },
                        onOpenArtifact = { id -> navigator.push(Route.ArtifactPreview(id)) },
                    )
                }
                if (waitingFirstToken) {
                    item(key = "thinking") { ThinkingRow(speakerName = currentSpeaker?.name) }
                }
            }

            picked?.let { message ->
                PickedMessageBar(
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.copyableText()))
                        picked = null
                    },
                    onDelete = {
                        state.removeGroupMessage(groupId, message.id)
                        picked = null
                    },
                    onCancel = { picked = null },
                )
            }

            // 没有可用的模型时先说清楚，别让用户对着输入框猜为什么没人说话
            if (!ready) {
                ConfigureModelBanner(onClick = { navigator.push(Route.ModelConfig) })
            } else {
                CostStrip(
                    text = buildString {
                        append("发一条 = 依次请 ").append(members.size).append(" 位成员发言")
                        if (group.rounds > 1) append(" × ").append(group.rounds).append(" 轮")
                        append(" = ").append(state.callsPerMessage(group)).append(" 次模型调用")
                        append(" · @ 某位则只叫他说")
                    },
                )
            }

            notice?.let { current ->
                DockNoticeStrip(
                    text = current.text,
                    isError = current.isError,
                    onDismiss = { notice = null },
                )
            }

            InputDock(
                enabled = ready,
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
                    turn = scope.launch { state.sendToGroup(groupId, text, outgoing) }
                },
                onStop = { turn?.cancel() },
                mentions = members,
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

        // 全屏看图。放在最外层，盖住输入坞。
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

@Composable
private fun GroupHeader(
    group: AgentGroup,
    memberCount: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
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
            CircleIconButton(StarIconGlyph.Back, onClick = onBack)
            AgentAvatar(
                glyph = group.glyph,
                accentIndex = group.accentIndex,
                size = 36.dp,
                cornerRadius = 12.dp,
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = StarPalette.Text,
                    maxLines = 1,
                )
                Text(
                    text = "$memberCount 位成员 · 每轮每人一次 · 共 ${group.rounds} 轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarPalette.TextMuted,
                    maxLines = 1,
                )
            }
            CircleIconButton(StarIconGlyph.More, onClick = onEdit)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(StarPalette.Divider))
    }
}

/** 多轮讨论里的一条淡分隔：「第 N 轮」。 */
@Composable
private fun RoundDivider(round: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "第 $round 轮",
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextFaint,
        )
    }
}

/** 输入坞上方那条成本提示——一条消息会触发多少次模型调用，先说清楚。 */
@Composable