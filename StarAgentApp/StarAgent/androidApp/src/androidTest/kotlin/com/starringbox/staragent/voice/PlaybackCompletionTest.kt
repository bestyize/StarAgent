package com.starringbox.staragent.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starringbox.staragent.core.platform.voice.AndroidVoicePlayer
import com.starringbox.staragent.core.speech.VoiceClip
import com.starringbox.staragent.core.speech.Wav
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** 验证真实 MediaPlayer 生命周期，避免“假播放器等待、真播放器提前返回”的假绿。 */
@RunWith(AndroidJUnit4::class)
class PlaybackCompletionTest {
    private fun player() = AndroidVoicePlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun successWaitsUntilAudioActuallyFinishes() = runBlocking {
        val output = player()
        try {
            val start = SystemClock.elapsedRealtime()
            assertTrue(withTimeout(10_000) { output.play(tone(1_000)) })
            assertTrue("Playback returned before the utterance finished", SystemClock.elapsedRealtime() - start >= 850)
        } finally { output.stop() }
    }

    @Test
    fun stoppingPlaybackUnblocksWaitingCallerWithoutReportingSuccess() = runBlocking {
        val output = player()
        try {
            val playing = async { output.play(tone(3_000)) }
            delay(250)
            assertFalse("Playback should still be pending", playing.isCompleted)
            output.stop()
            assertFalse(withTimeout(2_000) { playing.await() })
            assertTrue(withTimeout(10_000) { output.play(tone(400)) })
        } finally { output.stop() }
    }

    @Test
    fun cancellationReleasesPlaybackAndAllowsAnotherUtterance() = runBlocking {
        val output = player()
        try {
            val playing = async { output.play(tone(3_000)) }
            delay(200)
            withTimeout(2_000) { playing.cancelAndJoin() }
            assertTrue(playing.isCancelled)
            assertTrue(withTimeout(10_000) { output.play(tone(400)) })
        } finally { output.stop() }
    }

    @Test
    fun invalidAudioFailsInsteadOfLeavingTheConversationWaiting() = runBlocking {
        val output = player()
        try {
            assertFalse(withTimeout(5_000) { output.play(VoiceClip(byteArrayOf(1, 2, 3), Wav.MIME_TYPE, 0)) })
        } finally { output.stop() }
    }

    private fun tone(durationMillis: Int): VoiceClip {
        val samples = Wav.SAMPLE_RATE * durationMillis / 1_000
        val pcm = ByteArray(samples * 2)
        repeat(samples) { index ->
            val sample = (sin(2 * Math.PI * 440 * index / Wav.SAMPLE_RATE) * 600).toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        return VoiceClip(Wav.wrap(pcm), Wav.MIME_TYPE, durationMillis.toLong())
    }
}
