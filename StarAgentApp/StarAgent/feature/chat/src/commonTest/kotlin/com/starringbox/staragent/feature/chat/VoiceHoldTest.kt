package com.starringbox.staragent.feature.chat

import com.starringbox.staragent.core.speech.VoiceRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 松手之后走哪条路。
 *
 * 这是「按住说话」里唯一不依赖屏幕的一段判断，也是唯一会在真机上"看起来没坏但悄悄错"的一段：
 * 取消和太短的差别只在松手的那一瞬间，肉眼很难复现。
 */
class VoiceHoldTest {

    @Test
    fun aNormalHoldFinishes() {
        assertEquals(
            HoldOutcome.Finish,
            holdOutcome(live = true, cancelled = false, heldMillis = 2_000),
        )
    }

    /** 上滑取消是用户主动的，不能再弹一句"没听清"回去。 */
    @Test
    fun slidingUpCancelsSilently() {
        assertEquals(
            HoldOutcome.Cancel,
            holdOutcome(live = true, cancelled = true, heldMillis = 2_000),
        )
    }

    /** 取消优先于时长：上滑之后再松手，不该因为"说够 2 秒了"就发出去。 */
    @Test
    fun cancellingBeatsLongEnough() {
        assertEquals(
            HoldOutcome.Cancel,
            holdOutcome(live = true, cancelled = true, heldMillis = 30_000),
        )
    }

    @Test
    fun aQuickReleaseIsTreatedAsAMisTap() {
        assertEquals(
            HoldOutcome.TooShort,
            holdOutcome(live = true, cancelled = false, heldMillis = VoiceRules.MIN_DURATION_MILLIS - 1),
        )
    }

    /** 刚好到门槛算数——门槛是"最短"，不是"必须超过"。 */
    @Test
    fun exactlyTheMinimumCounts() {
        assertEquals(
            HoldOutcome.Finish,
            holdOutcome(live = true, cancelled = false, heldMillis = VoiceRules.MIN_DURATION_MILLIS),
        )
    }

    /** 没录上（没权限、引擎起不来）时，松手不该再去调结束——那会撞上一句"没有正在进行中的录音"。 */
    @Test
    fun aHoldThatNeverStartedDoesNothing() {
        assertEquals(
            HoldOutcome.Ignore,
            holdOutcome(live = false, cancelled = false, heldMillis = 5_000),
        )
        assertEquals(
            HoldOutcome.Ignore,
            holdOutcome(live = false, cancelled = true, heldMillis = 5_000),
        )
    }
}
