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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.SettingsRow
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.platform.voice.VoiceSource

/**
 * 语音识别配置。从「我的 → 语音识别」进来，和「模型接入」平级。
 *
 * 这一页只管**识别**，不管朗读——朗读一直是自动选的（见 `AppState.ttsEndpoint`）。
 * 分开的理由很具体：Groq 只提供识别，把两件事绑在一个选择上，
 * 用户选了 Groq 做识别就会连朗读一起失去，而他并没要求改朗读。
 *
 * 页面结构是三段，顺序就是用户的决策顺序：
 *
 * 1. **走哪条路**（自动 / 系统能力 / 大模型）。系统能力在没有的设备上是禁用的，
 *    并且说明白"这台设备不支持"——而不是给一个点了没反应的选项。
 * 2. **哪家服务商**。只在走大模型时才需要，所以放在下面。
 * 3. **哪个识别模型**。只有在这家提供多个时才出现。
 */
@Composable
fun SpeechConfigScreen(navigator: Navigator) {
    val state = LocalAppState.current

    // 「这台设备装了系统识别没有」。用 installed 而不是 ready：
    // 权限还没申请时 ready 就是 false，拿它禁选项等于永远没机会授权。
    val deviceInstalled = Voice.deviceListenInstalled

    // 「大模型」那条现在能不能用。
    //
    // 和上面**故意不对称**（一个看 installed、一个看 ready）：Model 的 ready 只问两件事——
    // 配没配好服务商、有没有录音实现——两样都不需要授权，所以不存在 Device 那个死环。
    // 而这两样恰好就是"选了它能不能出声"，拿它当开关正合适。
    val modelReady = Voice.modelListenReady

    val providers = state.asrProviders()

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "语音识别",
            subtitle = state.asrSummary,
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SpeechSectionLabel("识别方式") }
            item { SourcePicker(state, deviceInstalled, modelReady) }

            item { SpeechSectionLabel("识别服务商") }
            if (providers.isEmpty()) {
                item {
                    StarCard(modifier = Modifier.fillMaxWidth()) {
                        SettingsRow(
                            glyph = StarIconGlyph.Info,
                            title = "没有可用的识别服务商",
                            subtitle = "预置里 Groq、智谱 GLM、OpenAI 提供识别接口",
                            containerColor = StarPalette.SurfaceSunken,
                            tint = StarPalette.Text,
                        )
                    }
                }
            } else {
                items(providers, key = { it.id }) { provider ->
                    ProviderChoiceCard(state, provider, navigator)
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * 「自动 / 系统能力 / 大模型」三选。
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
                    // [Voice.pickTranscriber] 认准它之后不会再退回另一条，
                    // 所以点一下「大模型」会把本来能用的语音输入整个关掉，而界面看着毫无变化。
                    // 没配服务商时它一直可点，这条路径就是这么断的。
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
            return "这台设备不支持系统识别，实际会走大模型那一路。"

        state.voiceSource == VoiceSource.Model && !modelReady ->
            return "还没有配好能识别的服务商，这条路现在用不了——在下面选一家把 Key 填上。"
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
    // 所以这里只补大模型那条。
    if (!modelReady && state.voiceSource != VoiceSource.Model) {
        return "$current 要用「大模型」得先在下面配一家服务商。"
    }

    return current
}

/**
 * 一家可选的识别服务商。
 *
 * 三种状态各给不同的落点：**没配 Key** 就点进详情页去配（当前这一页帮不上忙）；
 * 配好了就点一下选中；已经选中的展开识别模型选择。
 */
@Composable
private fun ProviderChoiceCard(
    state: AppState,
    provider: ModelProvider,
    navigator: Navigator,
) {
    // 自动模式下实际会用到的也算"生效中"，否则用户看不出自动到底选了谁
    val selected = state.speechProviderId == provider.id ||
        (state.speechProviderId == null && state.asrEndpoint()?.baseUrl == provider.baseUrlFor(provider.protocol))
    val configured = provider.supportsAsr

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            SettingsRow(
                glyph = StarIconGlyph.Mic,
                title = provider.name,
                subtitle = when {
                    !configured -> "还没配 API Key，点这里去配"
                    selected -> "识别模型 · ${activeModel(state, provider)}"
                    else -> "已可用，点一下切到这家"
                },
                tint = if (configured) StarPalette.Indigo else StarPalette.TextFaint,
                trailing = {
                    Text(
                        text = when {
                            !configured -> "待配置"
                            selected -> "使用中"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) StarPalette.Success else StarPalette.TextFaint,
                    )
                },
                onClick = {
                    if (configured) {
                        // 选一家的同时把方式切到「大模型」——不然用户在自动模式下
                        // 选了 Groq，设备那路仍然优先，看起来像是没生效。
                        state.selectSpeechProvider(provider.id)
                        state.selectVoiceSource(VoiceSource.Model)
                    } else {
                        navigator.push(Route.ProviderDetail(provider.id))
                    }
                },
            )

            // 模型选择只在"这家在用 + 真的有多个可选"时出现。
            // 一个选项的单选框是噪音，两个才是选择。
            if (selected && configured && provider.asrChoices.size > 1) {
                AsrModelPicker(state, provider)
            }
        }
    }
}

/** 这家现在实际用的识别模型。 */
private fun activeModel(state: AppState, provider: ModelProvider): String =
    state.asrModelId?.takeIf { it in provider.asrChoices }
        ?: provider.asrModel
        ?: "未设置"

@Composable
private fun AsrModelPicker(state: AppState, provider: ModelProvider) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(StarPalette.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "识别模型",
            style = MaterialTheme.typography.labelMedium,
            color = StarPalette.TextMuted,
        )
        Spacer(Modifier.height(8.dp))

        provider.asrChoices.forEach { model ->
            val chosen = activeModel(state, provider) == model
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
