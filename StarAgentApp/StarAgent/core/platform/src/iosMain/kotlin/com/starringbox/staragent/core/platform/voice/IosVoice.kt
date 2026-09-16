package com.starringbox.staragent.core.platform.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * iOS 录音。
 *
 * 用 `AVAudioRecorder` 按 16kHz / 单声道 / 16bit / LinearPCM **直接录成 `.wav` 文件**，
 * 而不是 `AVAudioEngine` 收裸 PCM 再自己拼头：文件本来就带 WAV 头，读回来就是整条链路
 * 约定的格式，省掉一层转换。这也是 `Wav.wrap` 在这里用不上的原因。
 *
 * 没有麦克风的设备极罕见（iPhone 上不存在这种情况），所以 `available` 恒为 true；
 * 真正会失败的是**权限**，那由 [start] 去申请——权限不算能力，见 [Transcriber.ready] 的约定。
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceRecorder : Recorder {

    private var recorder: AVAudioRecorder? = null
    private var fileUrl: NSURL? = null

    override val available: Boolean get() = true

    override suspend fun start(): Boolean {
        if (recorder != null) return false
        if (!IosPermissions.requestMicrophone()) return false

        return withContext(Dispatchers.Default) {
            runCatching {
                // 录音要独占会话：不设 category 的话，正在放音乐或朗读时录进来的会是别的音源
                val session = AVAudioSession.sharedInstance()
                session.setCategory(AVAudioSessionCategoryRecord, null)

                val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + RECORDING_FILE_NAME)
                val settings: Map<Any?, Any?> = mapOf(
                    AVFormatIDKey to kAudioFormatLinearPCM,
                    AVSampleRateKey to Wav.SAMPLE_RATE.toDouble(),
                    AVNumberOfChannelsKey to Wav.CHANNELS,
                    AVLinearPCMBitDepthKey to Wav.BITS_PER_SAMPLE,
                    AVLinearPCMIsFloatKey to false,
                    AVLinearPCMIsBigEndianKey to false,
                )

                val opened = AVAudioRecorder(uRL = url, settings = settings, error = null)
                if (!opened.prepareToRecord()) return@runCatching false
                opened.record()

                recorder = opened
                fileUrl = url
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun stop(): VoiceClip? = withContext(Dispatchers.Default) {
        val opened = recorder ?: return@withContext null
        val url = fileUrl
        recorder = null
        fileUrl = null

        // 先让它落盘，再读文件——AVAudioRecorder 是写文件的，stop 之前内容还没写完
        opened.stop()

        val path = url?.path ?: return@withContext null
        val bytes = readFile(path) ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null

        VoiceClip(bytes, Wav.MIME_TYPE, Wav.durationMillis(bytes))
    }

    override fun cancel() {
        val opened = recorder ?: return
        recorder = null
        fileUrl = null
        runCatching { opened.stop() }
    }

    private fun readFile(path: String): ByteArray? {
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
        val size = data.length.toInt()
        if (size <= 0) return null

        val out = ByteArray(size)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return out
    }

    private companion object {
        const val RECORDING_FILE_NAME = "staragent-input.wav"
    }
}

/**
 * iOS 播放。
 *
 * 合成回来的音频是 WAV，`AVAudioPlayer` 直接用 `NSData` 就能放，不用落临时文件。
 * 这也是整条链路统一用 PCM/WAV 的收益之一——iOS 这边连解码器都不用挑。
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoicePlayer : Player {

    private var playing: AVAudioPlayer? = null

    override val available: Boolean get() = true

    override suspend fun play(clip: VoiceClip): Boolean = withContext(Dispatchers.Default) {
        stop()
        runCatching {
            if (clip.bytes.isEmpty()) return@runCatching false
            val opened = AVAudioPlayer(data = clip.bytes.toNSData(), error = null)
            if (!opened.prepareToPlay()) return@runCatching false
            opened.play()
            playing = opened
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { playing?.stop() }
        playing = null
    }
}

/** 把 Kotlin 字节数组交给需要 `NSData` 的 Apple API。 */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
}
