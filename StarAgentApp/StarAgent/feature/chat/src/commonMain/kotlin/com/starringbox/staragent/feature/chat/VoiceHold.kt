package com.starringbox.staragent.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.voice.SpeechOutcome
import com.starringbox.staragent.core.voice.VoiceRules
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 「按住说话」当前处在哪一步。界面只需要知道这个。 */
internal enum class HoldPhase { Idle, Recording, Cancelling, Transcribing }

/** 松手之后该做什么。 */
internal enum class HoldOutcome {
    /** 这次压根没录上（没权限、引擎起不来），什么都别做。 */
    Ignore,

    /** 上滑取消。什么都不发，也不提示——取消是用户主动的，不该再弹一句。 */
    Cancel,

    /** 松手太快，按误触处理。 */
    TooShort,

    /** 正常收尾：停止识别，把文字发出去。 */
    Finish,
}

/**
 * 松手时的判断。
 *
 * 抽成纯函数是为了能测——这几条分支的差别只在真机上的某个瞬间才显现，
 * 而"哪个瞬间松的手"是没法在测试里复现的。
 */
internal fun holdOutcome(live: Boolean, cancelled: Boolean, heldMillis: Long): HoldOutcome = when {
    !live -> HoldOutcome.Ignore
    cancelled -> HoldOutcome.Cancel
    heldMillis < VoiceRules.MIN_DURATION_MILLIS -> HoldOutcome.TooShort
    else -> HoldOutcome.Finish
}

/**
 * 「按住说话」的接线：手势、识别、发送三者之间的状态机。
 *
 * 抽出来是因为两个对话页（单聊、群聊）用同一套，只差最后把文字发给谁。
 */
@Stable
internal class VoiceHold(
    val phase: HoldPhase,
    val mode: Boolean,
    val onModeChange: (Boolean) -> Unit,
    val onStart: () -> Unit,
    val onCancelZone: (Boolean) -> Unit,
    val onRelease: (cancelled: Boolean, heldMillis: Long) -> Unit,
)

/**
 * 跨重组保留的一次按压。
 *
 * 不放进 `mutableStateOf`：它只被手势回调读写，改它不需要触发重组。
 */
private class HoldRuntime {
    /** 这次按压的"开始听"还没跑完时挂在这里，松手要等它。 */
    var startJob: Job? = null

    /** 这次按压有没有真的在录。没录上（没权限、起不来）就别再走结束那套。 */
    var live: Boolean = false
}

/**
 * 按住说话：按下开始、松手结束。
 *
 * 松手时不足 [VoiceRules.MIN_DURATION_MILLIS] 按误触处理。这道判断放在这里而不是
 * 交给后端，是因为设备后端（系统识别）压根不经过我们的录音器，也就不会走
 * [VoiceRules] 那道准入——但"说话时间太短"这个反馈该是一样的。
 */
@Composable
internal fun rememberVoiceHold(
    state: AppState,
    scope: CoroutineScope,
    onHeard: (String) -> Unit,
    onProblem: (String) -> Unit,
): VoiceHold {
    var phase by remember { mutableStateOf(HoldPhase.Idle) }
    var mode by remember { mutableStateOf(false) }
    val runtime = remember { HoldRuntime() }
    val heard by rememberUpdatedState(onHeard)
    val problem by rememberUpdatedState(onProblem)

    // 离开这个页面时把麦克风收掉。手势被打断（页面切走、进程被回收）时
    // 就走不到 onRelease 了，不能指望它来关。
    DisposableEffect(Unit) {
        onDispose {
            runtime.startJob = null
            runtime.live = false
            state.cancelListening()
        }
    }

    return VoiceHold(
        phase = phase,
        mode = mode,
        onModeChange = { mode = it },
        onStart = {
            phase = HoldPhase.Recording
            runtime.live = false
            runtime.startJob = scope.launch {
                val started = state.startListening()
                when (started) {
                    is SpeechOutcome.Ok -> runtime.live = true
                    is SpeechOutcome.Failed -> {
                        // 起不来就立刻收掉浮层，别让用户对着一个假的"正在录"说话
                        phase = HoldPhase.Idle
                        problem(started.message)
                    }
                }
            }
        },
        onCancelZone = { cancelling ->
            if (runtime.live) {
                phase = if (cancelling) HoldPhase.Cancelling else HoldPhase.Recording
            }
        },
        onRelease = { cancelled, heldMillis ->
            val pending = runtime.startJob
            runtime.startJob = null
            val live = runtime.live
            runtime.live = false

            scope.launch {
                // 按下到松开可能只有几十毫秒，而"开始听"要申请权限、要起引擎。
                // 不等它跑完就收尾，会撞上"没有正在进行中的录音"。
                pending?.join()

                when (holdOutcome(live, cancelled, heldMillis)) {
                    HoldOutcome.Ignore -> phase = HoldPhase.Idle

                    HoldOutcome.Cancel -> {
                        phase = HoldPhase.Idle
                        state.cancelListening()
                    }

                    HoldOutcome.TooShort -> {
                        phase = HoldPhase.Idle
                        state.cancelListening()
                        problem("说话时间太短")
                    }

                    HoldOutcome.Finish -> {
                        // 松手到出文字之间隔着一整个识别往返，系统识别在静音时还要等它自己的超时，
                        // 实测有三秒左右。这段时间必须留在"识别中"上：一是让用户知道按了有反应，
                        // 二是挡住第二次按压——识别器只有一个回调位，中途再按会把这次结果丢掉。
                        phase = HoldPhase.Transcribing
                        val result = state.stopListening()
                        phase = HoldPhase.Idle
                        when (result) {
                            is SpeechOutcome.Ok -> heard(result.value)
                            is SpeechOutcome.Failed -> problem(result.message)
                        }
                    }
                }
            }
        },
    )
}

/**
 * 按住触发、松手结束。上滑越过一定距离算取消。
 *
 * 用 [awaitEachGesture] 而不是 `detectTapGestures(onPress = ...)`：后者拿不到手指位移，
 * 而"上滑取消"正是靠位移判断的。按下即开始，没有长按等待——微信也是按下就录。
 *
 * [enabled] **刻意不进 `pointerInput` 的 key**，只在按下那一刻读一次。放进去会出人命：
 * 按下后界面立刻重组（浮层出现、横条改文案），key 一变手势协程就被取消，
 * 松手事件永远等不到，`onRelease` 不会跑，麦克风就一直开着。
 */
@Composable
internal fun Modifier.holdToTalk(
    enabled: Boolean,
    onStart: () -> Unit,
    onCancelZone: (Boolean) -> Unit,
    onRelease: (cancelled: Boolean, heldMillis: Long) -> Unit,
): Modifier {
    // 用 rememberUpdatedState 收口：手势一旦开始就活在旧的闭包里，
    // 而按住期间界面会重组好几次（浮层出现、取消态变色），不能让它读到过期的回调。
    val start by rememberUpdatedState(onStart)
    val cancelZone by rememberUpdatedState(onCancelZone)
    val release by rememberUpdatedState(onRelease)
    val active by rememberUpdatedState(enabled)

    return this.pointerInput(Unit) {
        val slop = 60.dp.toPx()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 判断放在这里：这一刻能不能录。手势本身无论如何都要跟到底。
            if (!active) return@awaitEachGesture

            val startedAt = TimeSource.Monotonic.markNow()
            start()

            var cancelled = false
            var reported: Boolean? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val now = (change.position.y - down.position.y) <= -slop
                if (now != reported) {
                    reported = now
                    cancelled = now
                    cancelZone(now)
                }
            }
            release(cancelled, startedAt.elapsedNow().inWholeMilliseconds)
        }
    }
}

/**
 * 录音浮层。盖在整页中央，只在按住期间出现。
 *
 * 不带 pointerInput，所以不吃触摸事件——用户的手指还在下面那条横条上。
 */
@Composable
internal fun VoiceHoldOverlay(phase: HoldPhase, modifier: Modifier = Modifier) {
    if (phase == HoldPhase.Idle) return
    val cancelling = phase == HoldPhase.Cancelling
    val transcribing = phase == HoldPhase.Transcribing

    // 识别中那几秒手指已经离开屏幕了，没有任何东西在动，卡片会像卡住。
    // 让麦克风缓慢呼吸一下，表示还在忙。
    val pulse = rememberInfiniteTransition(label = "voice-mic")
    val micAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-alpha",
    )

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(190.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (cancelling) StarPalette.Danger else OverlayBackdrop)
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StarIcon(
                icon = StarIconGlyph.Mic,
                iconSize = 30.dp,
                tint = Color.White.copy(alpha = if (transcribing) micAlpha else 1f),
            )
            Spacer(Modifier.height(14.dp))
            // 占位撑住同高，免得卡片在"录音 → 识别中"之间跳一下
            if (transcribing) {
                Box(Modifier.height(30.dp))
            } else {
                VoiceBars(tint = Color.White.copy(alpha = 0.92f))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    cancelling -> "松开手指，取消发送"
                    transcribing -> "识别中…"
                    else -> "手指上滑，取消发送"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

private val OverlayBackdrop = Color(0xFF1F2233).copy(alpha = 0.94f)

/**
 * 跳动的小竖条。
 *
 * **纯装饰**：它不反映真实音量，只表示"正在录"。真实电平要 Transcriber 往回报，
 * 那是给契约加东西的事，不和这次的手势改造混在一起。
 */
@Composable
private fun VoiceBars(tint: Color) {
    val transition = rememberInfiniteTransition(label = "voice-bars")

    Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { index ->
                val bar by transition.animateFloat(
                    initialValue = 0.28f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 560,
                            delayMillis = index * 90,
                            easing = LinearEasing,
                        ),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bar-$index",
                )
                Box(
                    Modifier
                        .width(4.dp)
                        .height(28.dp * bar)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
        }
    }
}
