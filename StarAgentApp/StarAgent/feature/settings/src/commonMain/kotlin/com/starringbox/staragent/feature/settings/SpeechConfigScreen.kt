package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.SpeechProvider
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.VoiceSource
import kotlinx.coroutines.launch

/** 语音配置：独立选择识别来源、朗读来源和各自需要的服务商。 */
@Composable
fun SpeechConfigScreen(navigator: Navigator) {
    val state = LocalAppState.current

    // 「这台设备装了系统识别没有」。用 installed 而不是 ready：
    // 权限还没申请时 ready 就是 false，拿它禁选项等于永远没机会授权。
    val deviceInstalled = Voice.deviceListenInstalled
    val localAsrSupported = state.speech.localAsrSupported
    val localAsrInstalled = state.speech.localAsrInstalled

    // 「云端识别」那条现在能不能用。
    //
    // 和上面**故意不对称**（一个看 installed、一个看 ready）：云端那条的 ready 只问
    // "这家填没填 Key""有没有录音实现"——两样都不需要授权，所以不存在 Device 那个死环。
    // 而这两样恰好就是"选了它能不能出声"，拿它当开关正合适。
    val modelReady = Voice.modelListenReady

    val active = state.speech.activeSpeechProvider()

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "语音配置",
            subtitle = state.speech.asrSummary,
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SpeechSectionLabel("识别方式") }
            item { SourcePicker(state, deviceInstalled, modelReady, localAsrSupported, localAsrInstalled) }

            // 依据识别方式，仅露出当前选中类别的详细选项
            when (state.speech.voiceSource) {
                VoiceSource.Device -> {
                    item { SpeechSectionLabel("系统识别") }
                    item {
                        StarCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("系统原生语音引擎", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (deviceInstalled) {
                                        "当前设备已就绪系统语音识别服务。语音转文字完全在设备底层处理，极速响应，无需网络流量与 API Key。"
                                    } else {
                                        "当前设备未检测到可用的系统语音识别服务。建议切换至「离线模型」或「云端识别」。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (deviceInstalled) StarPalette.TextMuted else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                VoiceSource.Local -> {
                    item { SpeechSectionLabel("离线识别模型") }
                    item { LocalSpeechCard(state, showAsr = true, showTts = false) }
                }

                VoiceSource.Model -> {
                    item { SpeechSectionLabel("云端识别服务商") }
                    item {
                        StarCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                ProviderGrid(state.speech.speechProviders.filter { it.canTranscribe }, active?.id) { provider ->
                                    state.speech.selectSpeechProvider(provider.id)
                                    state.speech.selectVoiceSource(VoiceSource.Model)
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "这里的 Key 和「模型服务」里填的是两份，分开存、互不影响。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StarPalette.TextFaint,
                                )
                            }
                        }
                    }

                    if (active != null) {
                        item { SpeechSectionLabel("${active.name} 的 API Key") }
                        item { ApiKeyCard(state, active) }

                        if (active.asrChoices.size > 1) {
                            item { SpeechSectionLabel("识别模型") }
                            item { AsrModelPicker(state, active) }
                        }
                    }
                }

                null -> {
                    item { SpeechSectionLabel("自动策略说明") }
                    item {
                        StarCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("智能降级顺序", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "优先尝试设备系统识别；不可用时使用离线 SenseVoice 模型；模型未就绪时自动由云端服务商兜底。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StarPalette.TextMuted,
                                )
                            }
                        }
                    }
                    item { SpeechSectionLabel("兜底云端服务商") }
                    item {
                        StarCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                ProviderGrid(state.speech.speechProviders.filter { it.canTranscribe }, active?.id) { provider ->
                                    state.speech.selectSpeechProvider(provider.id)
                                }
                            }
                        }
                    }
                    if (active != null) {
                        item { SpeechSectionLabel("${active.name} 的 API Key") }
                        item { ApiKeyCard(state, active) }
                    }
                }
            }

            item { SpeechSectionLabel("朗读") }
            item { ReadingSettings(state) }

            item { SpeechSectionLabel("试用语音") }
            item { SpeechTestCard(state) }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * 自动、系统、云端和离线识别来源。
 *
 * 和对话页状态面板里那个三选写同一份 `state.speech.voiceSource`——两处入口、一份状态，
 * 面板是聊天中的快捷切换，这一页是完整配置。
 */
@Composable
private fun SourcePicker(
    state: AppState,
    deviceInstalled: Boolean,
    modelReady: Boolean,
    localSupported: Boolean,
    localInstalled: Boolean,
) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, VoiceSource.Device, VoiceSource.Model, VoiceSource.Local).forEach { source ->
                    // 走不通的那条不给点。理由不只是"点了没用"——指定这一档是**独占**的，
                    // Voice.pickTranscriber 认准它之后不会再退回另一条，
                    // 所以点一下「云端识别」会把本来能用的语音输入整个关掉，而界面看着毫无变化。
                    val enabled = when (source) {
                        VoiceSource.Device -> deviceInstalled
                        VoiceSource.Model -> modelReady
                        // 支持此功能的设备允许先选中；下面会提示下载模型。
                        VoiceSource.Local -> localSupported
                        null -> true
                    }
                    val selected = state.speech.voiceSource == source
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    selected -> StarPalette.Indigo
                                    enabled -> StarPalette.SurfaceAlt
                                    else -> StarPalette.SurfaceSunken
                                }
                            )
                            .then(
                                if (enabled) {
                                    Modifier.clickable { state.speech.selectVoiceSource(source) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = source?.label ?: "自动",
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                selected -> Color.White
                                enabled -> StarPalette.TextMuted
                                else -> StarPalette.TextFaint
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = sourceHint(state, deviceInstalled, modelReady, localSupported, localInstalled),
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
            )
        }
    }
}

/**
 * 当前这一档在说什么。
 *
 * 两条规则：
 *
 * 1. **选中的那一档用不了，先直说**——那是用户此刻最需要知道的。
 * 2. **灰着的选项要在用户点它之前就把原因说清楚**。用户是先看到选项变灰、再去找原因的，
 *    等他点中了才解释就晚了；而灰的那条恰恰点不动，这句提示是唯一能说话的地方。
 */
private fun sourceHint(
    state: AppState,
    deviceInstalled: Boolean,
    modelReady: Boolean,
    localSupported: Boolean,
    localInstalled: Boolean,
): String {
    // 规则 1：选中的那条其实用不了
    when {
        state.speech.voiceSource == VoiceSource.Device && !deviceInstalled ->
            return "这台设备不支持系统识别，实际会走云端识别那一路。"

        state.speech.voiceSource == VoiceSource.Model && !modelReady ->
            return "云端识别还用不了：在下面选一家服务商，并把它的 API Key 填上。"

        state.speech.voiceSource == VoiceSource.Local && !localSupported ->
            return "这台设备暂不支持离线识别。"

        state.speech.voiceSource == VoiceSource.Local && !localInstalled ->
            return "离线识别模型还没下载，可在下面的离线模型卡片中下载。"
    }

    val current = when (state.speech.voiceSource) {
        VoiceSource.Device -> VoiceSource.Device.hint
        VoiceSource.Model -> VoiceSource.Model.hint
        VoiceSource.Local -> if (localInstalled) VoiceSource.Local.hint else "离线识别需要先下载模型。"
        null -> when {
            deviceInstalled -> "自动优先使用系统识别；不可用时再尝试离线识别和云端服务商。"
            localInstalled -> "自动优先使用已下载的离线识别；不可用时再使用云端服务商。"
            !localSupported -> "自动使用下面选择的云端服务商进行识别。"
            else -> "自动会优先使用可用的离线识别；模型未下载时使用云端服务商。"
        }
    }

    // 规则 2：灰着的那条为什么灰。
    // 系统识别那条的理由上面两句「自动」里已经说了（有没有系统识别，两种都说），
    // 所以这里只补云端那条。
    if (!modelReady && state.speech.voiceSource != VoiceSource.Model) {
        return "$current 要用「云端识别」得先在下面选一家服务商并填上 Key。"
    }

    return current
}

/** 服务商多于三家时自动换行，避免横向挤压名称。 */
@Composable
private fun ProviderGrid(
    providers: List<SpeechProvider>,
    selectedId: String?,
    onSelect: (SpeechProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.chunked(2).forEach { rowProviders ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowProviders.forEach { provider ->
                    VendorChip(
                        provider = provider,
                        selected = selectedId == provider.id,
                        onClick = { onSelect(provider) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowProviders.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 一家语音服务商。选它就是把整条语音配置切到这家。 */
@Composable
private fun VendorChip(
    provider: SpeechProvider,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = provider.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else StarPalette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        // 「仅识别」这几个字要显眼——选了它朗读就只剩设备自带那条路，
        // 用户得在点之前就知道，而不是选完发现朗读不见了
        Text(
            text = provider.capabilityLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White.copy(alpha = 0.8f) else StarPalette.TextFaint,
        )
    }
}

/**
 * 这家的 API Key。
 *
 * 输入用本地的草稿态、点「保存」才落盘——每敲一个字符就写一次 SettingsStore
 * 既没必要，也会让撤销变得不可能。`dirty` 由"草稿和已存的值不一样"自然推出来，
 * 不需要额外的状态。
 */
@Composable
private fun ApiKeyCard(state: AppState, provider: SpeechProvider) {
    if (!provider.requiresApiKey) {
        StarCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("免 API Key", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Edge TTS 使用服务内置的短期鉴权参数，不需要在这里填写凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextMuted,
                )
            }
        }
        return
    }

    // 换家时草稿要跟着换成那家的 Key（remember 的 key 就是 provider.id）
    val saved = state.speech.speechKeyOf(provider.id)
    val draft = remember(provider.id) { mutableStateOf(saved) }
    val dirty = draft.value.trim() != saved

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            StarTextField(
                value = draft.value,
                onValueChange = { draft.value = it },
                placeholder = "sk-...",
                // 和「模型接入」那边一致：默认藏起来，点右边的眼睛才明文
                masked = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (saved.isBlank()) "还没填" else "已保存（${masked(saved)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextFaint,
                    modifier = Modifier.weight(1f),
                )
                SaveButton(enabled = dirty, onClick = { state.speech.setSpeechKey(provider.id, draft.value) })
            }
        }
    }
}

@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) StarPalette.Indigo else StarPalette.SurfaceSunken)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "保存",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else StarPalette.TextFaint,
        )
    }
}

/** 只露个头尾，和「模型接入」那边回显 Key 的做法一致。 */
private fun masked(key: String): String =
    if (key.length <= 8) "••••" else key.take(4) + "••••" + key.takeLast(4)

@Composable
private fun ReadingSettings(state: AppState) {
    val scope = rememberCoroutineScope()
    val source = state.speech.ttsVoiceSource
    val inheritedTtsProvider = state.speech.activeTtsProvider()
    val ttsProvider = inheritedTtsProvider?.takeIf { it.canSynthesize }
    val voiceDraft = remember(state.speech.ttsVoiceId) { mutableStateOf(state.speech.ttsVoiceId.orEmpty()) }
    val testMessage = remember { mutableStateOf<String?>(null) }
    val speakReplies = state.speech.speakReplies

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StarCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("朗读方式", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, VoiceSource.Device, VoiceSource.Model, VoiceSource.Local).forEach { option ->
                        val enabled = when (option) {
                            null, VoiceSource.Device -> true
                            VoiceSource.Model -> state.speech.speechProviders.any { it.canSynthesize }
                            VoiceSource.Local -> state.speech.localTtsSupported
                        }
                        val selected = source == option
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        selected -> StarPalette.Indigo
                                        enabled -> StarPalette.SurfaceAlt
                                        else -> StarPalette.SurfaceSunken
                                    },
                                )
                                .then(if (enabled) Modifier.clickable { state.speech.selectTtsVoiceSource(option) } else Modifier)
                                .padding(vertical = 10.dp, horizontal = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (option == VoiceSource.Model) "云端服务" else option?.label ?: "跟随识别",
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    selected -> Color.White
                                    enabled -> StarPalette.TextMuted
                                    else -> StarPalette.TextFaint
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val note = when {
                    source == VoiceSource.Local && !state.speech.localTtsInstalled -> "离线朗读模型尚未下载，可在离线模型卡片中下载。"
                    source == VoiceSource.Model -> "朗读使用单独选择的云端服务商；免费服务无需 API Key。"
                    source == VoiceSource.Device -> "使用这台设备提供的朗读声音。"
                    source == VoiceSource.Local -> "在本机离线朗读。"
                    else -> "跟随识别方式选择朗读来源。"
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
            }
        }

        when {
            source == VoiceSource.Local || (source == null && state.speech.voiceSource == VoiceSource.Local) -> {
                LocalSpeechCard(state, showAsr = false, showTts = true)
            }
            source == VoiceSource.Model || (source == null && (state.speech.voiceSource == VoiceSource.Model || state.speech.voiceSource == null)) -> {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("云端朗读服务商", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                        Spacer(Modifier.height(8.dp))
                        ProviderGrid(state.speech.speechProviders.filter { it.canSynthesize }, state.speech.ttsProviderId) { provider ->
                            state.speech.selectTtsProvider(provider.id)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "跟随识别服务商",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = StarPalette.TextMuted,
                            )
                            ActionLabel(
                                text = if (state.speech.ttsProviderId == null) "已选择" else "使用",
                                enabled = state.speech.ttsProviderId != null,
                                onClick = { state.speech.selectTtsProvider(null) },
                            )
                        }
                        if (state.speech.ttsProviderId == null && inheritedTtsProvider?.canSynthesize != true) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "当前识别服务商没有云端朗读，可在上方选择一家。",
                                style = MaterialTheme.typography.bodySmall,
                                color = StarPalette.TextFaint,
                            )
                        }
                    }
                }
                ttsProvider?.let { provider ->
                    Column {
                        SpeechSectionLabel("${provider.name} 的 API Key")
                        ApiKeyCard(state, provider)
                    }
                }
            }
            source == VoiceSource.Device -> {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("系统设备原生声音", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "使用当前设备系统的 Text-To-Speech (TTS) 引擎进行朗读，无需云端服务商和 API Key。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextMuted,
                        )
                    }
                }
            }
        }

        if (source != VoiceSource.Local) {
            StarCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("音色", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                    Spacer(Modifier.height(8.dp))
                    StarTextField(
                        value = voiceDraft.value,
                        onValueChange = { voiceDraft.value = it },
                        placeholder = "默认音色（可留空）",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "音色名称会按所选朗读方式保存。",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )
                        SaveButton(
                            enabled = voiceDraft.value != state.speech.ttsVoiceId.orEmpty(),
                            onClick = { state.speech.selectTtsVoice(voiceDraft.value.trim().ifBlank { null }) },
                        )
                    }
                }
            }
        }

        StarCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("朗读回复", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                        Text("收到回复后自动读出来", style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
                    }
                    Switch(checked = speakReplies, onCheckedChange = state.speech::setReadingReplies)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("流式播报", style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
                        Text(
                            "支持的朗读来源会边生成边朗读；否则等回复完成后再读。失败时文字回复仍会保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )
                    }
                    Switch(checked = state.speech.streamReplies, onCheckedChange = state.speech::setStreamingReplies)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionLabel("试音", enabled = true) {
                        scope.launch {
                            testMessage.value = when (val result = state.speech.speak("你好，我是 StarAgent。")) {
                                is SpeechOutcome.Ok -> "已播放试音。"
                                is SpeechOutcome.Failed -> result.message
                            }
                        }
                    }
                    testMessage.value?.let {
                        Text(it, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechTestCard(state: AppState) {
    val scope = rememberCoroutineScope()
    val listening = remember { mutableStateOf(false) }
    val resultText = remember { mutableStateOf<String?>(null) }
    DisposableEffect(state) {
        onDispose {
            if (listening.value) state.speech.cancelListening()
            state.speech.stopSpeaking()
        }
    }

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("点击开始录音，再点击结束并查看识别结果。", style = MaterialTheme.typography.bodySmall, color = StarPalette.TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionLabel(
                    text = if (listening.value) "结束识别" else "开始识别",
                    enabled = true,
                ) {
                    scope.launch {
                        if (!listening.value) {
                            resultText.value = null
                            when (val result = state.speech.startListening()) {
                                is SpeechOutcome.Ok -> listening.value = true
                                is SpeechOutcome.Failed -> resultText.value = result.message
                            }
                        } else {
                            when (val result = state.speech.stopListening()) {
                                is SpeechOutcome.Ok -> resultText.value = result.value.ifBlank { "没有识别到内容。" }
                                is SpeechOutcome.Failed -> resultText.value = result.message
                            }
                            listening.value = false
                        }
                    }
                }
                resultText.value?.let {
                    Text(it, Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.bodySmall, color = StarPalette.TextMuted)
                }
            }
        }
    }
}

/** 识别模型。只有这家提供多个时才摆——一个选项的单选框是噪音。 */
@Composable
private fun AsrModelPicker(state: AppState, provider: SpeechProvider) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            provider.asrChoices.forEach { model ->
                val chosen = state.speech.activeAsrModel(provider) == model
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) StarPalette.Indigo.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { state.speech.selectAsrModel(model) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarPalette.Text,
                            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        modelNote(model)?.let { note ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = StarPalette.TextFaint,
                            )
                        }
                    }
                    if (chosen) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.labelLarge,
                            color = StarPalette.Indigo,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 模型名旁边那句人话。
 *
 * 按名字里的特征匹配而不是硬编码整个模型名：各家迟早会加新版本，
 * 匹配特征至少不会在改名之后变成一句错的说明。匹配不上就不说话。
 */
private fun modelNote(model: String): String? {
    val name = model.lowercase()
    return when {
        name.contains("distil") -> "最快，只认英文"
        name.contains("turbo") -> "速度与质量的平衡，日常够用"
        name.contains("whisper-large-v3") -> "质量最好，中英混说更稳"
        else -> null
    }
}

@Composable
private fun SpeechSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = StarPalette.TextFaint,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}
