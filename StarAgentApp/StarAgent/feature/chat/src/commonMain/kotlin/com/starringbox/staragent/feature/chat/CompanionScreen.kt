package com.starringbox.staragent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starringbox.staragent.core.data.CompanionPhase
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.StarActionSheet
import com.starringbox.staragent.core.designsystem.component.StarConfirmDialog
import com.starringbox.staragent.core.designsystem.component.StarImageViewer
import com.starringbox.staragent.core.designsystem.component.StarSheetAction
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaRules
import com.starringbox.staragent.core.speech.SpeechOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

private data class CompanionColors(val background: Color, val surface: Color, val text: Color, val muted: Color, val bubble: Color)

/** A permanent companion space: one identity and history, with text and live voice presentations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val agent = state.companion
    val voice = state.companionVoice
    val scope = rememberCoroutineScope()
    val messages = state.messagesOf(agent.id)
    val dark = state.companionDarkMode ?: isSystemInDarkTheme()
    val colors = if (dark) CompanionColors(Color(0xFF080A12), Color(0xFF20232D), Color(0xFFF3F4FA), Color(0xFF9399AC), Color(0xFF25294C))
        else CompanionColors(Color(0xFFF9FAFF), Color.White, Color(0xFF202435), Color(0xFF9297AA), Color(0xFFE9EDFF))
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var turn by remember { mutableStateOf<Job?>(null) }
    var dictationJob by remember { mutableStateOf<Job?>(null) }
    var dictating by remember { mutableStateOf(false) }
    var startingDictation by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var viewing by remember { mutableStateOf<MessageBody.Image?>(null) }
    var imageIndex by remember { mutableStateOf(0) }
    val busy = state.isBusy(agent.id) || turn?.isActive == true
    val configured = state.endpointFor(agent) != null
    val phase = if (state.speakingAgentId == agent.id) CompanionPhase.Speaking
        else if (!voiceMode && busy) CompanionPhase.Thinking else voice.phase
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val lastText = (messages.lastOrNull { it.body is MessageBody.Text }?.body as? MessageBody.Text)?.text.orEmpty()

    DisposableEffect(state) {
        onDispose { voice.stop(); state.cancelListening(); state.stopSpeaking() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(lastText) {
        val layout = listState.layoutInfo
        if (lastText.isNotBlank() && messages.isNotEmpty() &&
            (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 2) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    fun leaveVoice() {
        voice.stop()
        voiceMode = false
    }
    fun enterVoice() {
        dictationJob?.cancel()
        state.cancelListening()
        dictating = false
        transcribing = false
        notice = null
        voiceMode = true
        voice.start(scope)
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompanionAction(StarIconGlyph.Chat, "对话记录", colors, onClick = { history = true })
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(agent.name, color = colors.text, style = MaterialTheme.typography.titleLarge)
                Text("你的智能伙伴", color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            CompanionAction(StarIconGlyph.More, "伙伴设置", colors, onClick = { menu = true })
        }

        if (voiceMode || messages.isEmpty()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxHeight < 220.dp
                val orbSize = minOf(maxWidth * 0.72f, maxHeight * (if (compact) 0.45f else 0.66f), 360.dp)
                Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CompanionOrb(Modifier.size(orbSize), phase, voice.inputLevel, agent.accentIndex)
                    Spacer(Modifier.height(if (compact) 4.dp else 18.dp))
                    val status = if (!voiceMode) "我在这里，想聊点什么？" else when (phase) {
                        CompanionPhase.Listening -> "我在听"
                        CompanionPhase.Transcribing -> "听到了，稍等一下"
                        CompanionPhase.Thinking -> "让我想一想"
                        CompanionPhase.Speaking -> "正在和你说话"
                        CompanionPhase.Paused -> "麦克风已暂停"
                        CompanionPhase.Idle -> "随时可以开始"
                    }
                    Text(status, color = colors.text, fontSize = if (voiceMode || compact) 18.sp else 22.sp, fontWeight = FontWeight.Medium)
                    if (!compact) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                        if (voiceMode && phase == CompanionPhase.Listening) "说完稍作停顿，或轻点下方发送"
                        else if (voiceMode) "像平常聊天一样，慢慢说就好"
                        else "聊聊今天，或一起想想接下来要做的事",
                        color = colors.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                if (voiceMode && lastText.isNotBlank()) {
                    Text(lastText, color = colors.muted, fontSize = 13.sp, maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 12.dp))
                }
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    CompanionMessage(message, colors,
                        onCopy = { clipboard.setText(AnnotatedString(message.copyableText())) },
                        onImage = { index -> viewing = message.body as? MessageBody.Image; imageIndex = index },
                        onArtifact = { navigator.push(Route.ArtifactPreview(it)) })
                }
                if (busy && (messages.lastOrNull()?.body as? MessageBody.Text)?.text.isNullOrBlank()) {
                    item(key = "companion-thinking") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompanionOrb(Modifier.size(36.dp), CompanionPhase.Thinking, accentIndex = agent.accentIndex)
                            Spacer(Modifier.width(8.dp))
                            Text("让我想一想…", color = colors.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (!configured) {
            Text("为她选择一个模型，开始你们的对话  ›", color = StarPalette.Indigo, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    .clickable { leaveVoice(); navigator.push(Route.ModelConfig) }.padding(12.dp))
        }
        val error = notice ?: if (voiceMode) voice.error else null
        if (error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = StarPalette.Danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (voiceMode) Text("语音设置", color = StarPalette.Indigo, fontSize = 12.sp,
                    modifier = Modifier.clickable { leaveVoice(); navigator.push(Route.SpeechConfig) }.padding(8.dp))
            }
        }

        if (voiceMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CompanionAction(StarIconGlyph.Keyboard, "切回文字", colors, onClick = { leaveVoice() })
                if (!voice.active) {
                    CompanionAction(StarIconGlyph.Mic, "开始语音对话", colors, accent = true, onClick = { voice.start(scope) })
                } else {
                    CompanionAction(if (voice.muted) StarIconGlyph.MicOff else StarIconGlyph.Mic,
                        if (voice.muted) "恢复麦克风" else "暂停麦克风", colors,
                        onClick = { voice.setMicrophoneMuted(!voice.muted) })
                    if (phase == CompanionPhase.Listening) {
                        CompanionAction(StarIconGlyph.Send, "说完了，发送", colors, accent = true,
                            onClick = { voice.submitUtterance() })
                    }
                }
                CompanionAction(StarIconGlyph.Close, "结束语音对话", colors, onClick = { leaveVoice() })
            }
        } else {
            if (attachments.isNotEmpty()) {
                Text("已添加 ${attachments.size} 张图片 · 点击移除", color = colors.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 26.dp).clickable { attachments = emptyList() })
            }
            val skillMatches = remember(draft, state.skills) { skillSuggestions(draft, state.skills) }
            if (skillMatches.isNotEmpty()) {
                SkillSuggestionPicker(
                    candidates = skillMatches,
                    onPick = { skill -> draft = "/" + skill.id + " " },
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp)).background(colors.surface).padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (MediaPickers.available) CompanionAction(StarIconGlyph.Plus, "添加图片", colors,
                    enabled = !busy && !dictating && !transcribing, onClick = {
                        scope.launch {
                            try {
                                val asset = MediaPickers.pickImage() ?: return@launch
                                val rejection = MediaRules.rejection(asset)
                                if (rejection != null) notice = rejection.message else attachments = attachments + asset
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { notice = failure.message ?: "没能添加这张图片" }
                        }
                    })
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 14.dp)) {
                    if (draft.isEmpty()) Text(
                        if (dictating) "正在听，再点麦克风结束…" else if (transcribing) "正在转成文字…" else "和${agent.name}说点什么…",
                        color = colors.muted, fontSize = 14.sp,
                    )
                    BasicTextField(
                        value = draft, onValueChange = { draft = it },
                        enabled = !dictating && !transcribing,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                        cursorBrush = SolidColor(StarPalette.Indigo), maxLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (busy) {
                    CompanionAction(StarIconGlyph.Pause, "停止回复", colors, onClick = { turn?.cancel(); state.stopSpeaking() })
                } else if (draft.isNotBlank() || attachments.isNotEmpty()) {
                    CompanionAction(StarIconGlyph.Send, "发送消息", colors, accent = true,
                        enabled = configured && !dictating && !transcribing, onClick = {
                            val text = draft
                            val images = attachments
                            draft = ""
                            attachments = emptyList()
                            notice = null
                            turn = scope.launch {
                                try { state.send(agent.id, text, images) }
                                finally { turn = null }
                            }
                        })
                } else {
                    CompanionAction(StarIconGlyph.Mic, if (dictating) "结束语音输入" else "语音输入", colors,
                        enabled = configured && !transcribing && !startingDictation, accent = dictating, onClick = {
                            notice = null
                            if (!dictating) startingDictation = true
                            dictationJob = scope.launch {
                                try {
                                    if (dictating) {
                                        dictating = false
                                        transcribing = true
                                        when (val result = state.stopListening()) {
                                            is SpeechOutcome.Ok -> draft = result.value
                                            is SpeechOutcome.Failed -> notice = result.message
                                        }
                                    } else when (val result = state.startListening()) {
                                        is SpeechOutcome.Ok -> dictating = true
                                        is SpeechOutcome.Failed -> notice = result.message
                                    }
                                } finally { transcribing = false; startingDictation = false }
                            }
                        })
                    CompanionAction(StarIconGlyph.Waveform, "进入语音对话", colors, accent = true,
                        enabled = configured && !transcribing && !dictating && !startingDictation, onClick = { enterVoice() })
                }
            }
        }
    }

    if (menu) {
        StarActionSheet(listOf(
            StarSheetAction("编辑${agent.name}"),
            StarSheetAction("语音与音色"),
            StarSheetAction(if (dark) "切换浅色外观" else "切换深色外观"),
            StarSheetAction("外观跟随系统"),
            StarSheetAction("清空对话记录", destructive = true),
        ), onAction = { index ->
            menu = false
            when (index) {
                0 -> { leaveVoice(); navigator.push(Route.AgentEditor(agent.id)) }
                1 -> { leaveVoice(); navigator.push(Route.SpeechConfig) }
                2 -> state.setCompanionAppearance(!dark)
                3 -> state.setCompanionAppearance(null)
                4 -> { leaveVoice(); clear = true }
            }
        }, onDismiss = { menu = false })
    }
    if (clear) StarConfirmDialog(
        title = "清空对话记录", message = "清空和${agent.name}的对话？她的设置与记忆会保留。", confirmLabel = "清空",
        onConfirm = {
            scope.launch { turn?.cancelAndJoin(); state.clearMessages(agent.id); clear = false }
        }, onDismiss = { clear = false },
    )
    if (history) ModalBottomSheet(onDismissRequest = { history = false }, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("和${agent.name}的对话", color = colors.text, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            if (messages.isEmpty()) Text("你们的故事，从第一句话开始。", color = colors.muted, modifier = Modifier.padding(22.dp))
            else LazyColumn(Modifier.height(420.dp), contentPadding = PaddingValues(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(messages, key = { it.id }) { message ->
                    CompanionMessage(message, colors,
                        onCopy = { clipboard.setText(AnnotatedString(message.copyableText())) },
                        onImage = { index -> viewing = message.body as? MessageBody.Image; imageIndex = index },
                        onArtifact = { history = false; leaveVoice(); navigator.push(Route.ArtifactPreview(it)) })
                }
            }
            Text("回到文字对话  ›", color = StarPalette.Indigo,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp).clickable { history = false; leaveVoice() })
        }
    }
    viewing?.let { body ->
        StarImageViewer(images = body.assets.map { it.bytes }, initialIndex = imageIndex, onDismiss = { viewing = null })
    }
}

@Composable
private fun CompanionMessage(
    message: ChatMessage, colors: CompanionColors, onCopy: () -> Unit,
    onImage: (Int) -> Unit, onArtifact: (String) -> Unit,
) {
    val state = LocalAppState.current
    val scope = rememberCoroutineScope()
    var readJob by remember { mutableStateOf<Job?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    val body = message.body
    if (body is MessageBody.Text && body.text.isNotBlank()) {
        if (message.author == Author.User) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(body.text, color = colors.text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(24.dp))
                    .background(colors.bubble).padding(horizontal = 18.dp, vertical = 12.dp))
        } else Column {
            AgentTextBubble(body.text, onOpenArtifact = onArtifact, immersive = true, textColor = colors.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("复制", color = colors.muted, fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onCopy).padding(end = 18.dp, top = 6.dp, bottom = 6.dp))
                if (state.canSpeak) Text(if (readJob?.isActive == true) "停止朗读" else "听她说", color = colors.muted, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        state.companionVoice.stop()
                        if (readJob?.isActive == true) {
                            readJob?.cancel()
                            state.stopSpeaking()
                        } else {
                            readError = null
                            readJob = scope.launch {
                                try {
                                    state.stopSpeaking()
                                    val result = state.speak(body.text)
                                    if (result is SpeechOutcome.Failed) readError = result.message
                                } finally { readJob = null }
                            }
                        }
                    }.padding(vertical = 6.dp))
            }
            readError?.let { Text(it, color = StarPalette.Danger, fontSize = 12.sp) }
        }
    } else MessageBodyView(body, message.author,
        onConfirmDraft = { state.confirmDraft(state.companion.id, it) }, onImageClick = onImage,
        onAnswerQuestion = { id, answer -> state.answerQuestion(state.companion.id, id, answer) }, onOpenArtifact = onArtifact)
}

@Composable
private fun CompanionAction(
    glyph: StarIconGlyph, label: String, colors: CompanionColors, accent: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(Modifier.size(44.dp).clip(CircleShape)
        .background(if (accent) StarPalette.Indigo.copy(alpha = if (enabled) 1f else 0.35f) else colors.surface)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        StarIcon(glyph, iconSize = 22.dp,
            tint = if (accent) Color.White else if (enabled) colors.text else colors.muted)
    }
}
