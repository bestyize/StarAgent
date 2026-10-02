package com.starringbox.staragent.core.speech.local.asr.vad

import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

actual fun createSpeechDetector(): SpeechDetector? = try {
    LibraryUtils.load()
    DesktopSileroDetector(bundledSileroModel(File(System.getProperty("user.home"), ".staragent/models/asr/vad")))
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (failure: Throwable) {
    System.err.println("Silero VAD unavailable; noise gating is off: $failure")
    null
}

private class DesktopSileroDetector(model: File) : WindowedSpeechDetector() {
    private val vad = Vad(VadModelConfig.builder()
        .setSileroVadModelConfig(SileroVadModelConfig.builder()
            .setModel(model.absolutePath).setThreshold(SileroSettings.THRESHOLD)
            .setMinSilenceDuration(SileroSettings.MIN_SILENCE_SECONDS).setMinSpeechDuration(SileroSettings.MIN_SPEECH_SECONDS)
            .setWindowSize(SileroSettings.WINDOW).setMaxSpeechDuration(SileroSettings.MAX_SPEECH_SECONDS).build())
        .setSampleRate(SpeechAudio.SAMPLE_RATE).setNumThreads(1).build())

    override fun acceptWindow(window: FloatArray) = vad.acceptWaveform(window)
    override fun speechDetected() = vad.isSpeechDetected
    override fun flushNative() = vad.flush()
    override fun drainSegments() = buildList {
        while (!vad.empty()) { vad.front().let { add(it.start to it.samples.size) }; vad.pop() }
    }
    override fun close() = vad.release()
}
