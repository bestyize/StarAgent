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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.SpeechProvider
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.platform.voice.VoiceSource

/**
 * 语音配置。从「我的 → 语音」进来，和「模型接入」平级但**完全独立**。
 *
 * 这里只有**一条**配置：选一家服务商 + 填这家的 Key +（可选）选识别模型。
 * 不做服务商列表、不做多家切换、不做自定义端点——那套是「模型接入」的形状，
 * 而两者管的是两件不相干的事。
 *
 * **为什么和对话的服务商分开**：原先语音是"借用"对话那份配置的（同一个 `ModelProvider`、
 * 同一把 Key、同一个模型清单）。后果是这一页上每家的"待配置"其实说的是
 * **对话那把 Key 配没配**，点"去配置"跳到的也是对话的详情页，
 * 用户根本看不出这两件事已经被绑在一起了。拆开的代价是要填两遍 Key，
 * 这个代价是明知的，换的是两个页面各自自洽。
 */
@Composable
fun SpeechConfigScreen(navigator: Navigator) {
    val state = LocalAppState.current

    // 「这台设备装了系统识别没有」。用 installed 而不是 ready：
    // 权限还没申请时 ready 就是 false，拿它禁选项等于永远没机会授权。
    val deviceInstalled = Voice.deviceListenInstalled

    // 「云端识别」那条现在能不能用。
    //
    // 和上面**故意不对称**（一个看 installed、一个看 ready）：云端那条的 ready 只问
    // "这家填没填 Key""有没有录音实现"——两样都不需要授权，所以不存在 Device 那个死环。
    // 而这两样恰好就是"选了它能不能出声"，拿它当开关正合适。
    val modelReady = Voice.modelListenReady

    val active = state.activeSpeechProvider()

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "语音配置",
            subtitle = state.asrSummary,
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SpeechSectionLabel("识别方式") }
            item { SourcePicker(state, deviceInstalled, modelReady) }

            item { SpeechSectionLabel("语音服务商") }
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.speechProviders.forEach { provider ->
                                VendorChip(
                                    provider = provider,
                                    selected = active?.id == provider.id,
                                    onClick = {
                                        // 选家的同时把方式切到「云端识别」——否则在自动档下
                                        // 设备那路仍然优先，看起来像是没生效。
                                        state.selectSpeechProvider(provider.id)
                                        state.selectVoiceSource(VoiceSource.Model)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "这里的 Key 和「模型接入」里填的是两份，分开存、互不影响。",
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

                item { SpeechSectionLabel("朗读") }
                item { ReadingNote(active) }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * 「自动 / 系统能力 / 云端识别」三选。
 *
 * 和对话页状态面板里那个三选写同一份 `state.voiceSource`——两处入口、一份状态，
 * 面板是聊天中的快捷切换，这一页是完整配置。
 */
@Composable
private fun SourcePicker(state: AppState, deviceInstalled: Boolean, modelReady: Boolean) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, VoiceSource.Device, VoiceSource.Model).forEach { source ->
                    // 走不通的那条不给点。理由不只是"点了没用"——指定这一档是**独占**的，
                    // Voice.pickTranscriber 认准它之后不会再退回另一条，
                    // 所以点一下「云端识别」会把本来能用的语音输入整个关掉，而界面看着毫无变化。
                    val enabled = when (source) {
                        VoiceSource.Device -> deviceInstalled
                        VoiceSource.Model -> modelReady
                        null -> true
                    }
                    val selected = state.voiceSource == source
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
                                    Modifier.clickable { state.selectVoiceSource(source) }
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
                text = sourceHint(state, deviceInstalled, modelReady),
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
private fun sourceHint(state: AppState, deviceInstalled: Boolean, modelReady: Boolean): String {
    // 规则 1：选中的那条其实用不了
    when {
        state.voiceSource == VoiceSource.Device && !deviceInstalled ->
            return "这台设备不支持系统识别，实际会走云端识别那一路。"

        state.voiceSource == VoiceSource.Model && !modelReady ->
            return "云端识别还用不了：在下面选一家服务商，并把它的 API Key 填上。"
    }

    val current = when (state.voiceSource) {
        VoiceSource.Device -> VoiceSource.Device.hint
        VoiceSource.Model -> VoiceSource.Model.hint
        null -> if (deviceInstalled) {
            "自动：优先系统能力，它不要 Key 也不花钱；用不了时才走下面选的服务商。"
        } else {
            "这台设备没有系统识别（桌面端就没有），所以自动模式实际走的是下面选的服务商。"
        }
    }

    // 规则 2：灰着的那条为什么灰。
    // 系统识别那条的理由上面两句「自动」里已经说了（有没有系统识别，两种都说），
    // 所以这里只补云端那条。
    if (!modelReady && state.voiceSource != VoiceSource.Model) {
        return "$current 要用「云端识别」得先在下面选一家服务商并填上 Key。"
    }

    return current
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
    // 换家时草稿要跟着换成那家的 Key（remember 的 key 就是 provider.id）
    val saved = state.speechKeyOf(provider.id)
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
                SaveButton(enabled = dirty, onClick = { state.setSpeechKey(provider.id, draft.value) })
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

/**
 * 朗读这一节。
 *
 * 朗读**没有自己的档位**——它跟着识别选的那家走：这家能做合成（智谱 GLM、OpenAI）
 * 就用它，不能（Groq 只有识别）就退回设备自带。这句话必须写出来，
 * 否则用户选了 Groq 之后发现朗读换了声音，只会以为坏了。
 */
@Composable
private fun ReadingNote(provider: SpeechProvider) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (provider.canSynthesize) {
                    "${provider.name} 也能合成语音，朗读会走这家。"
                } else {
                    "${provider.name} 只做识别、不做合成，所以朗读仍用这台设备自带的。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = StarPalette.Text,
            )
            // 能合成时不必再劝他换一家——那句话只在"朗读没走上云端"时才有信息量。
            // 同样不列名单：加了服务商就要回来改一句文案，漏改就是"明明有却不告诉用户"
            if (!provider.canSynthesize) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "换个能合成的服务商就能让朗读也走云端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextFaint,
                )
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
                val chosen = state.activeAsrModel(provider) == model
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) StarPalette.Indigo.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { state.selectAsrModel(model) }
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
