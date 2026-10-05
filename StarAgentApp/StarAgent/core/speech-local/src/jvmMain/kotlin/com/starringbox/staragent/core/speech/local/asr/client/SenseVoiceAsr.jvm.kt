package com.starringbox.staragent.core.speech.local.asr.client

import com.starringbox.staragent.core.speech.local.downloadVerified
import com.starringbox.staragent.core.speech.local.verifiedFile
import com.starringbox.staragent.core.speech.local.partialFileOf
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/** Desktop offline SenseVoiceSmall INT8. Model files are downloaded only on demand. */
actual object SenseVoiceAsr {
    private const val REVISION = "73eca47697f980daa3d16112404174b6b950b514"
    private const val BASE = "https://modelscope.cn/api/v1/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/repo?Revision=$REVISION"
    private const val MODEL = "model.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val MODEL_SIZE = 239_233_841L
    private const val TOKENS_SIZE = 315_894L
    private const val MODEL_SHA = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    private const val TOKENS_SHA = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    private val gate = Mutex()
    private var verified = false
    private var recognizer: OfflineRecognizer? = null

    private fun directory(): File = File(
        System.getProperty("user.home"), ".staragent/models/asr/sensevoice-small-int8-2024-07-17",
    )

    actual fun supported(): Boolean = supportedDesktopSherpa()

    actual fun installed(): Boolean = supported() && runCatching {
        File(directory(), MODEL).let { it.isFile && it.length() == MODEL_SIZE } &&
            File(directory(), TOKENS).let { it.isFile && it.length() == TOKENS_SIZE }
    }.getOrDefault(false)

    actual suspend fun install(onProgress: (Long, Long) -> Unit) = gate.withLock {
        check(supported()) { "当前 sherpa-onnx 桌面版暂不支持 ${System.getProperty("os.name")}/${System.getProperty("os.arch")}" }
        withContext(Dispatchers.IO) {
            val dir = directory().apply { check(isDirectory || mkdirs()) { "无法创建 SenseVoice 模型目录" } }
            val total = MODEL_SIZE + TOKENS_SIZE
            recognizer?.release(); recognizer = null
            verified = false
            download(File(dir, MODEL), MODEL_SIZE, MODEL_SHA, onProgress, 0L, total)
            download(File(dir, TOKENS), TOKENS_SIZE, TOKENS_SHA, onProgress, MODEL_SIZE, total)
            verified = true
        }
    }

    actual suspend fun remove() = gate.withLock {
        withContext(Dispatchers.IO) {
            verified = false
            recognizer?.release(); recognizer = null
            val dir = directory()
            listOf(MODEL, TOKENS).forEach { name ->
                val file = File(dir, name)
                check(!file.exists() || file.delete()) { "无法删除 $name" }
                partialFileOf(file).delete()
            }
        }
    }

    actual suspend fun release() = gate.withLock {
        recognizer?.release()
        recognizer = null
    }

    actual suspend fun transcribe(wav: ByteArray): String = gate.withLock {
        check(supported()) { "当前 sherpa-onnx 桌面版暂不支持 ${System.getProperty("os.name")}/${System.getProperty("os.arch")}" }
        withContext(Dispatchers.Default) {
            check(installed()) { "请先在声音设置中下载 SenseVoice 模型" }
            val dir = directory()
            if (!verified) {
                check(verifiedFile(File(dir, MODEL), MODEL_SIZE, MODEL_SHA) && verifiedFile(File(dir, TOKENS), TOKENS_SIZE, TOKENS_SHA)) {
                    "SenseVoice 模型校验失败，请删除后重新下载"
                }
                verified = true
            }
            val feature = FeatureConfig.builder().setSampleRate(16_000).setFeatureDim(80).build()
            val senseVoice = OfflineSenseVoiceModelConfig.builder()
                .setModel(File(dir, MODEL).absolutePath).setLanguage("auto").build()
            val model = OfflineModelConfig.builder().setSenseVoice(senseVoice)
                .setTokens(File(dir, TOKENS).absolutePath).setNumThreads(2).setProvider("cpu").build()
            val config = OfflineRecognizerConfig.builder().setFeatureConfig(feature).setOfflineModelConfig(model).build()
            val engine = recognizer ?: try {
                com.k2fsa.sherpa.onnx.LibraryUtils.load()
                OfflineRecognizer(config).also { recognizer = it }
            } catch (failure: LinkageError) {
                throw IllegalStateException("SenseVoice 桌面原生运行库加载失败", failure)
            }
            val samples = pcm16WavToFloat(wav)
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
            } finally { stream.release() }
        }
    }

    private suspend fun download(
        target: File, size: Long, sha: String, progress: (Long, Long) -> Unit, offset: Long, total: Long,
    ) = downloadVerified("$BASE&FilePath=${target.name}", target, size, sha, "SenseVoice 模型") { done ->
        progress(offset + done, total)
    }


}
