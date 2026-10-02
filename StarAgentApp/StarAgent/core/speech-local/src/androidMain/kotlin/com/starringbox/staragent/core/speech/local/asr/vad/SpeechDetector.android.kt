package com.starringbox.staragent.core.speech.local.asr.vad

import com.starringbox.staragent.core.speech.local.LocalSpeechContext
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

actual fun createSpeechDetector(): SpeechDetector? = try {
    val context = checkNotNull(LocalSpeechContext.context()) { "Android Context 未初始化" }
    AndroidSileroDetector(bundledSileroModel(File(context.noBackupFilesDir, "asr")))
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (failure: Throwable) {
    android.util.Log.w("SpeechDetector", "Silero VAD unavailable; noise gating is off", failure)
    null
}

private class AndroidSileroDetector(model: File) : WindowedSpeechDetector() {
    private val vad = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = model.absolutePath, threshold = SileroSettings.THRESHOLD,
            minSilenceDuration = SileroSettings.MIN_SILENCE_SECONDS, minSpeechDuration = SileroSettings.MIN_SPEECH_SECONDS,
            windowSize = SileroSettings.WINDOW, maxSpeechDuration = SileroSettings.MAX_SPEECH_SECONDS,
        ),
        sampleRate = SpeechAudio.SAMPLE_RATE, numThreads = 1,
    ))

    override fun acceptWindow(window: FloatArray) = vad.acceptWaveform(window)
    override fun speechDetected() = vad.isSpeechDetected()
    override fun flushNative() = vad.flush()
    override fun drainSegments() = buildList {
        while (!vad.empty()) { vad.front().let { add(it.start to it.samples.size) }; vad.pop() }
    }
    override fun close() = vad.release()
}
