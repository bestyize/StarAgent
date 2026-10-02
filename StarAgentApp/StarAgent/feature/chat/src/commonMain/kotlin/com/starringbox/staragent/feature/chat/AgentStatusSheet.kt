package com.starringbox.staragent.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.platform.voice.VoiceSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 面板最多占可用高度的多少。留出上方一截，一眼能看出这是面板而不是整页。 */
private const val SHEET_MAX_FRACTION = 0.86f

/** 往下拽多少就松手关闭。 */
private val DISMISS_DRAG = 110.dp

/** 面板滑入的时长。和页面转场同一档（App.kt 的 SlideSpec 也是 280），节奏才一致。 */
private const val SHEET_ENTER_MILLIS = 280

/** 出场比入场快：关东西要干脆，开东西可以稍慢。 */
private const val SHEET_EXIT_MILLIS = 200

/** 蒙层的淡入淡出，跟出场同步。 */
private const val SCRIM_FADE_MILLIS = 200

/**
 * 对话页顶部状态位的展开面板。
 *
 * 这里做了一个刻意的取舍：**响应模式在前，具体模型在后**。
 * 用户真正关心的是"这次要快还是要深"，而不是模型的名字；
 * 但"模型自由"又是产品的立身之本，所以两者都要给，只是排序不同。
 *
 * 版面分三层：抓手（固定）、内容（可滚）、完成（固定）。
 * 内容必须能滚——这个面板塞了响应模式、模型、渠道、语音四节，
 * 高度早就超过一屏了。不封顶又不给滚的话，[Column] 会把最后一个子元素
 * 按剩余空间压扁，"完成"两个字就会被截成一条。
 *
 * [visible] 而不是由调用方 `if` 掉：面板要能**滑出去**，就得在消失的过程中还留在组合树里。
 * 调用方一直组合它即可，关掉时它自己播完退场动画。蒙层和面板分开做动画——
 * 蒙层是整屏的，跟着面板一起滑会把整块屏幕一起推上去。
 */
@Composable
internal fun AgentStatusSheet(
    agent: Agent,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    val swallow = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val dragOffset = remember { Animatable(0f) }
    val dismissAt = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    var sheetHeight by remember { mutableStateOf(0f) }

    // 这个组件不再随开关进出组合树了，所以拖拽位移得自己归零：
    // 上次是拖到底关掉的，不重置的话再打开时面板会带着旧位移，一进来就在屏幕外。
    LaunchedEffect(visible) {
        if (visible) dragOffset.snapTo(0f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = maxHeight * SHEET_MAX_FRACTION
        // 跟着下拉的进度把蒙层一起淡掉：手还没松，已经能看出"这一下是要关掉它"
        val progress = if (sheetHeight > 0f) (dragOffset.value / sheetHeight).coerceIn(0f, 1f) else 0f

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(SCRIM_FADE_MILLIS)),
            exit = fadeOut(tween(SCRIM_FADE_MILLIS)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * (1f - progress)))
                    .clickable(interactionSource = swallow, indication = null) { onDismiss() },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(SHEET_ENTER_MILLIS, easing = FastOutSlowInEasing),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(SHEET_EXIT_MILLIS, easing = FastOutSlowInEasing),
                targetOffsetY = { it },
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = cap)
                    .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                    .onSizeChanged { sheetHeight = it.height.toFloat() }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(StarPalette.Surface)
                    .clickable(interactionSource = swallow, indication = null) { }
                    .dragToDismiss(dragOffset, scope, dismissAt, sheetHeight = { sheetHeight }, onDismiss = onDismiss),
            ) {
            DragHandle()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 18.dp),
            ) {
                SheetBody(agent = agent, onPickModel = onPickModel)
            }

            // 「完成」固定在底部，不跟内容一起滚，也不会被挤扁
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 22.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(StarPalette.Indigo)
                    .clickable { onDismiss() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("完成", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            }
        }
    }
}

/** 顶部抓手。它自己不吃手势——拖它的时候事件会走到外层那个可拖的 [Column]。 */
@Composable
private fun DragHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(StarPalette.SurfaceSunken),
        )
    }
}

/**
 * 下拉关闭。
 *
 * 只接"没被内容消费掉"的竖直拖动：面板里那段是可滚的，往下拽时先让内容滚回顶部，
 * 滚到顶之后剩下的位移才轮到面板。Compose 的事件分发正好是主传递从叶子往根走，
 * 所以判一下 `isConsumed` 就够，不用再套一层 nestedScroll。
 *
 * 抓手不在滚动区里，拖它的事件一定到得了这里——这也是"拖动关闭"无论如何都走得通的原因。
 */
private fun Modifier.dragToDismiss(
    dragOffset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    dismissAt: Float,
    sheetHeight: () -> Float,
    onDismiss: () -> Unit,
): Modifier = pointerInput(Unit) {
    detectVerticalDragGestures(
        onVerticalDrag = { change, dy ->
            // 内容还能往上滚，或者手指在往上推，都不该动面板
            if (change.isConsumed) return@detectVerticalDragGestures
            if (dy < 0f && dragOffset.value <= 0f) return@detectVerticalDragGestures

            change.consume()
            scope.launch {
                dragOffset.snapTo((dragOffset.value + dy).coerceAtLeast(0f))
            }
        },
        onDragEnd = {
            scope.launch {
                if (dragOffset.value > dismissAt) {
                    dragOffset.animateTo(sheetHeight().coerceAtLeast(dragOffset.value), tween(180))
                    onDismiss()
                } else {
                    dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        },
        onDragCancel = {
            scope.launch { dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        },
    )
}

@Composable
private fun SheetBody(agent: Agent, onPickModel: (String) -> Unit) {
    val state = LocalAppState.current

    SectionLabel("响应模式")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResponseMode.entries.forEach { mode ->
            val selected = state.responseMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
                    .clickable { state.selectResponseMode(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else StarPalette.TextMuted,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = state.responseMode.hint,
        style = MaterialTheme.typography.bodySmall,
        color = StarPalette.TextFaint,
    )

    Spacer(Modifier.height(20.dp))
    SectionLabel("模型")
    val usable = state.providers.filter { it.configured }
    if (usable.isEmpty()) {
        Text(
            text = "还没有配置模型服务。去「我的 → 模型服务」填一个 API Key，这里就能切换模型了。",
            style = MaterialTheme.typography.bodySmall,
            color = StarPalette.TextFaint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    } else {
        usable.forEach { provider ->
            // 只摆能对话的。拉回来的清单里混着识别/合成/审核模型，
            // 选中一个 whisper 去聊天就是一句一个 400——语音在「我的 → 语音」里配。
            provider.chatChoices.forEach { (index, label) ->
                val key = state.modelKey(provider.id, index)
                val selected = key == agent.modelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPickModel(key) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(
                        glyph = StarIconGlyph.Chip,
                        tint = if (selected) StarPalette.Indigo else StarPalette.TextFaint,
                        containerColor = if (selected) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                        size = 30.dp,
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarPalette.Text,
                        )
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )
                    }
                    if (selected) {
                        StarIcon(
                            icon = StarIconGlyph.Check,
                            iconSize = 17.dp,
                            tint = StarPalette.Indigo,
                            strokeWidth = 2.2f,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("渠道")
    state.channels.forEach { channel ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.glyph, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(11.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = StarPalette.Text,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = channel.connected,
                onCheckedChange = { state.toggleChannel(channel.id) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StarPalette.Indigo,
                    checkedBorderColor = StarPalette.Indigo,
                ),
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("语音")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "朗读回复",
                style = MaterialTheme.typography.bodyMedium,
                color = StarPalette.Text,
            )
            Text(
                text = readingEngineLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
            )
        }
        Switch(
            checked = state.speakReplies,
            onCheckedChange = { state.setReadingReplies(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = StarPalette.Indigo,
                checkedBorderColor = StarPalette.Indigo,
            ),
        )
    }

    Spacer(Modifier.height(10.dp))
    // 引擎选择。默认「自动」：优先系统能力，它不要 Key、不花钱。
    //
    // 走不通的那条不给点，和语音设置页同一个规则（那边有完整说明）：
    // 指定某一档是**独占**的，没配服务商时点一下「大模型」，
    // 会把本来能用的设备识别整个关掉，而界面看着毫无变化。
    val deviceInstalled = Voice.deviceListenInstalled
    val modelReady = Voice.modelListenReady
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val choices = listOf(null, VoiceSource.Device, VoiceSource.Model, VoiceSource.Local)
        choices.forEach { source ->
            val enabled = when (source) {
                VoiceSource.Device -> deviceInstalled
                VoiceSource.Model -> modelReady
                VoiceSource.Local -> state.localAsrSupported
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
                        if (enabled) Modifier.clickable { state.selectVoiceSource(source) } else Modifier
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
    Spacer(Modifier.height(6.dp))
    Text(
        text = when {
            // 选中的那条其实用不了，先说这一条：它比下面那句笼统的"用不了语音"更具体，
            // 也直接说了该去哪修
            state.voiceSource == VoiceSource.Model && !modelReady ->
                "云端识别还用不了：去「我的 → 语音」选一家服务商，并把它的 Key 填上。"

            state.voiceSource == VoiceSource.Device && !deviceInstalled ->
                "这台设备不支持系统识别，实际会走云端识别那一路。"

            !state.canListen && !state.canSpeak ->
                "现在还用不了语音：这台设备没有可用的识别与合成，也没配语音服务商。"

            state.voiceSource == null ->
                "自动：优先用系统能力，它不要 Key 也不花钱；用不了时才走云端识别。"

            else -> state.voiceSource?.hint.orEmpty()
        },
        style = MaterialTheme.typography.bodySmall,
        color = StarPalette.TextFaint,
    )
}

/**
 * 朗读现在走哪条路。
 *
 * 朗读没有自己的档位，它跟着「我的 → 语音」里选的那家走：那家能做合成
 * （智谱 GLM、OpenAI）就用它，不能（Groq 只有识别）就退回设备自带。
 * 这里**不能写死"用设备自带"**——配了能合成的服务商之后那句话就是假的。
 */
private fun readingEngineLabel(state: AppState): String {
    val vendor = state.activeSpeechProvider()
    val onCloud = vendor != null && vendor.canSynthesize && state.speechConfigured(vendor.id)
    return if (onCloud) {
        "用 ${vendor.name} 的合成，按量计费"
    } else {
        "用设备自带的朗读，不用配 Key 也不花钱"
    }
}
