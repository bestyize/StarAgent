package com.starringbox.staragent.core.speech.local.tts.client

import com.starringbox.staragent.core.speech.local.tts.model.TtsResult
import com.starringbox.staragent.core.speech.local.tts.model.SpeechAudioChunk
import com.starringbox.staragent.core.speech.local.tts.model.floatSamplesToPcm16
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlin.coroutines.ContinuationInterceptor
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private fun supportedDesktopSherpa(): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("windows") -> arch in setOf("amd64", "x86_64", "aarch64", "arm64")
        os.contains("linux") || os.contains("mac") -> arch in setOf("amd64", "x86_64", "aarch64", "arm64")
        else -> false
    }
}

/** Windows desktop Piper backend. Its CPU runtime is sherpa-onnx's official native JVM build. */
actual object PiperTts {
    private const val REVISION = "19375e11252db7afffa04181e114adab3db4219b"
    private const val BASE = "https://huggingface.co/csukuangfj2/vits-piper-zh_CN-chaowen-medium-int8/resolve/$REVISION/"
    private const val MODEL = "zh_CN-chaowen-medium.onnx"
    private const val TOKENS = "tokens.txt"
    private const val LEXICON = "lexicon.txt"
    private const val PHONE = "phone.fst"
    private const val NUMBER = "number.fst"
    private const val DATE = "date.fst"
    private val files = listOf(
        ModelFile(MODEL, 18_652_796L, "d5ad252f165b26bcecb01759d07cc6cf4cf14045bac8752947a5fb35080c5e6b"),
        ModelFile(TOKENS, 485L, "e9e6d2838a2676749adbf0376c4cf3c3cd28eb27ed953dcc2adcab8db3c33ba7"),
        ModelFile(LEXICON, 2_064_505L, "003e76f89fec389ec109b557f47cc315a1ac31a99091f0cc9d15949f9024416b"),
        ModelFile(PHONE, 88_630L, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
        ModelFile(NUMBER, 64_482L, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
        ModelFile(DATE, 59_154L, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
    )
    private val gate = Mutex()
    private var verified = false
    private var tts: OfflineTts? = null
    @Volatile private var runtimeReady = false

    private fun directory(): File = File(System.getProperty("user.home"), ".staragent/models/tts/piper-zh-cn-chaowen-medium-int8")

    actual fun supported(): Boolean = supportedDesktopSherpa() && runtimeReady

    actual fun prepareRuntime(): Boolean {
        if (!supportedDesktopSherpa()) return false
        return runCatching {
            com.k2fsa.sherpa.onnx.LibraryUtils.load()
            runtimeReady = true
        }.isSuccess
    }

    actual fun installed(): Boolean = supported() && runCatching {
        files.all { File(directory(), it.name).let { f -> f.isFile && f.length() == it.size } }
    }.getOrDefault(false)

    actual suspend fun install(onProgress: (Long, Long) -> Unit) = gate.withLock {
        check(supportedDesktopSherpa()) { "当前 sherpa-onnx 桌面版暂不支持 ${System.getProperty("os.name")}/${System.getProperty("os.arch")}" }
        check(prepareRuntime()) { "无法初始化 sherpa-onnx 桌面运行库" }
        withContext(Dispatchers.IO) {
            val dir = directory().apply { check(isDirectory || mkdirs()) { "无法创建 Piper 模型目录" } }
            val total = files.sumOf { it.size }
            var offset = 0L
            tts?.release(); tts = null
            verified = false
            files.forEach { file ->
                download(File(dir, file.name), file, onProgress, offset, total)
                offset += file.size
            }
            verified = true
        }
    }

    actual suspend fun remove() {
        gate.withLock {
            withContext(Dispatchers.IO) {
                verified = false
                tts?.release(); tts = null
                files.forEach { entry ->
                    val target = File(directory(), entry.name)
                    check(!target.exists() || target.delete()) { "无法删除 Piper 文件 ${entry.name}" }
                }
                directory().listFiles()?.forEach { check(it.delete()) { "无法删除 Piper 临时文件" } }
            }
        }
    }

    actual suspend fun release() = gate.withLock {
        withContext(Dispatchers.IO) {
            tts?.release()
            tts = null
        }
    }

    actual suspend fun synthesize(text: String): TtsResult = try {
        require(text.isNotBlank()) { "请输入要合成的文字" }
        check(supported()) { "当前 sherpa-onnx 桌面版暂不支持 ${System.getProperty("os.name")}/${System.getProperty("os.arch")}" }
        gate.withLock {
            withContext(Dispatchers.Default) {
                val engine = readyEngine()
                val audio = engine.generate(text, 0, 1.0f)
                TtsResult.Success(floatSamplesToWav(audio.samples, audio.sampleRate), "audio/wav")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LinkageError) {
        TtsResult.Failure("Piper 桌面原生运行库加载失败，请检查当前系统架构的原生依赖")
    } catch (error: Exception) {
        TtsResult.Failure(error.message ?: "Piper 语音合成失败")
    }

    actual suspend fun stream(text: String, onAudio: suspend (SpeechAudioChunk) -> Unit) {
        require(text.isNotBlank()) { "请输入要合成的文字" }
        try {
        gate.withLock {
            withContext(Dispatchers.Default) {
                val engine = readyEngine()
                val context = currentCoroutineContext()
                val sampleRate = engine.sampleRate
                var callbackFailure: Throwable? = null
                var received = false
                try {
                    engine.generateWithCallback(text, 0, 1.0f, com.k2fsa.sherpa.onnx.OfflineTtsCallback { samples ->
                        if (!context.isActive) 0 else try {
                            // Convert/copy JNI-owned samples before returning from this callback.
                            val pcm = floatSamplesToPcm16(samples)
                            runBlocking(context.minusKey(ContinuationInterceptor)) {
                                var offset = 0
                                while (offset < pcm.size) {
                                    ensureActive()
                                    val end = minOf(offset + (sampleRate / 4) * 2, pcm.size)
                                    onAudio(SpeechAudioChunk(pcm.copyOfRange(offset, end), sampleRate))
                                    received = true; offset = end
                                }
                            }
                            1
                        } catch (failure: Throwable) { callbackFailure = failure; 0 }
                    })
                } catch (failure: LinkageError) {
                    throw IllegalStateException("Piper sherpa-onnx 桌面原生运行库加载失败，请检查当前系统架构的原生依赖", failure)
                }
                context.ensureActive()
                callbackFailure?.let { throw it }
                check(received) { "Piper 没有生成有效音频" }
            }
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            throw IllegalStateException("Piper sherpa-onnx 桌面原生运行库加载失败，请检查当前系统架构的原生依赖", failure)
        }
    }

    private fun readyEngine(): OfflineTts {
        check(installed()) { "请先在声音设置中下载 Piper 中文语音模型" }
        val dir = directory()
        if (!verified) {
            check(files.all { valid(File(dir, it.name), it.size, it.sha256) }) { "Piper 模型校验失败，请删除后重新下载" }
            verified = true
        }
        try {
            com.k2fsa.sherpa.onnx.LibraryUtils.load()
        } catch (failure: LinkageError) {
            throw IllegalStateException("Piper sherpa-onnx 桌面原生运行库加载失败，请检查当前系统架构的原生依赖", failure)
        }
        val engine = tts ?: try { createEngine(dir).also { tts = it } }
            catch (failure: LinkageError) {
                val extra = failure.message?.let { ": $it" }.orEmpty()
                throw IllegalStateException("Piper sherpa-onnx 桌面原生运行库加载失败。请检查当前 OS/CPU 架构的原生依赖$extra", failure)
            }
        return engine
    }

    private fun createEngine(dir: File): OfflineTts {
        val vits = OfflineTtsVitsModelConfig.builder().setModel(File(dir, MODEL).absolutePath)
            .setLexicon(File(dir, LEXICON).absolutePath).setTokens(File(dir, TOKENS).absolutePath)
            .setNoiseScale(0.667f).setNoiseScaleW(0.8f).setLengthScale(1.0f).build()
        val model = OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setProvider("cpu").build()
        val rules = listOf(PHONE, DATE, NUMBER).joinToString(",") { File(dir, it).absolutePath }
        val config = OfflineTtsConfig.builder().setModel(model).setRuleFsts(rules).setMaxNumSentences(1).build()
        return OfflineTts(config)
    }

    private suspend fun download(target: File, spec: ModelFile, progress: (Long, Long) -> Unit, offset: Long, total: Long) {
        if (valid(target, spec.size, spec.sha256)) { progress(offset + spec.size, total); return }
        val temp = File(target.parentFile, "${target.name}.part")
        val connection = URL(BASE + spec.name).openConnection() as HttpURLConnection
        val disconnectWatcher = CoroutineScope(currentCoroutineContext()).launch(
            context = Dispatchers.IO,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = true
            check(connection.responseCode == 200) { "Piper 文件下载失败：${spec.name}（HTTP ${connection.responseCode}）" }
            var received = 0L
            connection.inputStream.use { input -> temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    received += count; check(received <= spec.size) { "Piper 文件大小不符：${spec.name}" }
                    output.write(buffer, 0, count); progress(offset + received, total)
                }
            } }
            check(valid(temp, spec.size, spec.sha256)) { "Piper 文件 SHA-256 校验失败：${spec.name}" }
            if (target.exists()) check(target.delete()) { "无法替换旧 Piper 文件" }
            check(temp.renameTo(target)) { "无法保存 Piper 文件：${spec.name}" }
        } finally {
            disconnectWatcher.cancel()
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun valid(file: File, size: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    private fun floatSamplesToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        require(samples.isNotEmpty() && sampleRate > 0) { "Piper 没有生成有效音频" }
        val pcm = floatSamplesToPcm16(samples)
        return ByteArrayOutputStream(44 + pcm.size).apply {
            fun le16(v: Int) { write(v and 255); write((v shr 8) and 255) }
            fun le32(v: Int) { repeat(4) { write((v shr (it * 8)) and 255) } }
            write("RIFF".encodeToByteArray()); le32(36 + pcm.size)
            write("WAVEfmt ".encodeToByteArray()); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            write("data".encodeToByteArray()); le32(pcm.size); write(pcm)
        }.toByteArray()
    }

    private data class ModelFile(val name: String, val size: Long, val sha256: String)
}
