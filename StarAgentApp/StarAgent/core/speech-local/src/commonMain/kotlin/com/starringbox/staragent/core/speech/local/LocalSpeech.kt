package com.starringbox.staragent.core.speech.local

import com.starringbox.staragent.core.platform.voice.*
import com.starringbox.staragent.core.speech.local.asr.client.SenseVoiceAsr
import com.starringbox.staragent.core.speech.local.asr.vad.filterSpeechWav
import com.starringbox.staragent.core.speech.local.asr.vad.hasSpokenContent
import com.starringbox.staragent.core.speech.local.tts.client.PiperTts
import com.starringbox.staragent.core.speech.local.tts.model.TtsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalTranscriber : Transcriber {
    override val inputLevel: Float get() = Recorders.inputLevel
    override val speechEnded: Boolean get() = Recorders.speechEnded
    private var request: Job? = null
    override val ready: Boolean get() = SenseVoiceAsr.installed() && Recorders.available
    override suspend fun start(): Boolean = Recorders.start()
    override suspend fun finish(): SpeechOutcome<String> = coroutineScope {
        request = coroutineContext.job
        try {
            val clip = Recorders.stop() ?: return@coroutineScope SpeechOutcome.Failed("没有录到声音")
            VoiceRules.rejection(clip)?.let { return@coroutineScope SpeechOutcome.Failed(it.message) }
            val wav = withContext(Dispatchers.Default) { filterSpeechWav(clip.bytes) }
                ?: return@coroutineScope SpeechOutcome.Failed("没听到说话，再说一次")
            val text = SenseVoiceAsr.transcribe(wav).trim()
            if (text.hasSpokenContent()) SpeechOutcome.Ok(text) else SpeechOutcome.Failed("没有听清，再说一次")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SpeechOutcome.Failed(failure.message ?: "离线识别失败")
        } catch (failure: Throwable) {
            SpeechOutcome.Failed("离线识别运行库加载失败，请检查安装包")
        } finally { if (request === coroutineContext.job) request = null }
    }
    override fun cancel() { request?.cancel(); Recorders.cancel() }
}

class LocalSynthesizer : StreamingSynthesizer {
    private var request: Job? = null
    override val ready: Boolean get() = PiperTts.installed() && Players.available
    override val streamReady: Boolean get() = PiperTts.installed()
    override suspend fun stream(text: String, onAudio: suspend (PcmAudioChunk) -> Unit): SpeechOutcome<Unit> = coroutineScope {
        if (text.isBlank()) return@coroutineScope SpeechOutcome.Ok(Unit)
        request?.cancel()
        request = coroutineContext.job
        try {
            PiperTts.stream(text) { onAudio(PcmAudioChunk(it.pcm16, it.sampleRate)) }
            SpeechOutcome.Ok(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { SpeechOutcome.Failed(failure.message ?: "离线朗读失败") }
        catch (failure: Throwable) { SpeechOutcome.Failed("离线朗读运行库加载失败") }
        finally { if (request === coroutineContext.job) request = null }
    }
    override suspend fun speak(text: String): SpeechOutcome<Unit> = coroutineScope {
        if (text.isBlank()) return@coroutineScope SpeechOutcome.Ok(Unit)
        request?.cancel()
        request = coroutineContext.job
        try {
            when (val result = PiperTts.synthesize(text)) {
                is TtsResult.Success -> {
                    val clip = VoiceClip(result.audio, result.contentType, Wav.durationMillis(result.audio))
                    if (Players.play(clip)) SpeechOutcome.Ok(Unit) else SpeechOutcome.Failed("音频没能播放出来")
                }
                is TtsResult.Failure -> SpeechOutcome.Failed(result.message)
            }
        } finally { if (request === coroutineContext.job) request = null }
    }
    override fun stop() { request?.cancel(); request = null; Players.stop(); PcmPlayers.stop() }
}
