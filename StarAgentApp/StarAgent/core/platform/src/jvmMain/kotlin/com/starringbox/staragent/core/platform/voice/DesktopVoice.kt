package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.Player
import com.starringbox.staragent.core.speech.Recorder
import com.starringbox.staragent.core.speech.SpeechActivity
import com.starringbox.staragent.core.speech.VoiceClip
import com.starringbox.staragent.core.speech.Wav
import javazoom.jl.player.Player as JLayerPlayer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 录音规格和 Wav.wrap 保持一致：16kHz 单声道 16 位。 */
private fun captureFormat() = AudioFormat(
    Wav.SAMPLE_RATE.toFloat(),
    16,
    Wav.CHANNELS,
    true, // signed
    false, // little-endian
)

/**
 * 桌面录音。
 *
 * **这台机器没有麦克风是常态，不是异常**：`available` 如实返回 false，
 * 界面据此把语音入口藏起来，而不是留一个点了报错的按钮。
 */
class DesktopVoiceRecorder : Recorder {

    private val lineInfo = DataLine.Info(TargetDataLine::class.java, captureFormat())
    private val hasInput: Boolean by lazy {
        runCatching { AudioSystem.isLineSupported(lineInfo) }.getOrDefault(false)
    }

    private var line: TargetDataLine? = null
    private var sink: ByteArrayOutputStream? = null
    private var pump: Thread? = null
    private val activity = SpeechActivity()
    override val inputLevel: Float get() = activity.level
    override val speechEnded: Boolean get() = activity.ended

    override val available: Boolean get() = hasInput

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (line != null) return@withContext false

        runCatching {
            val opened = AudioSystem.getLine(lineInfo) as TargetDataLine
            opened.open(captureFormat())
            opened.start()

            val collected = ByteArrayOutputStream()
            activity.reset()
            // 单独一条线程持续把音频读进内存：开始/结束由用户点两下控制，
            // 不能阻塞在调用方那边。
            val worker = Thread {
                val chunk = ByteArray(4096)
                while (true) {
                    val read = runCatching { opened.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                    if (read <= 0) break
                    activity.acceptPcm(chunk, read)
                    collected.write(chunk, 0, read)
                }
            }
            worker.isDaemon = true
            worker.start()

            line = opened
            sink = collected
            pump = worker
            true
        }.getOrDefault(false)
    }

    override suspend fun stop(): VoiceClip? = withContext(Dispatchers.IO) {
        val opened = line ?: return@withContext null
        val collected = sink
        line = null
        sink = null

        // 先关线：读取线程会从 read() 上退出来
        runCatching {
            opened.stop()
            opened.close()
        }
        pump?.join(500)
        pump = null

        val pcm = collected?.toByteArray() ?: ByteArray(0)
        if (pcm.isEmpty()) return@withContext null

        val wav = Wav.wrap(pcm)
        VoiceClip(wav, Wav.MIME_TYPE, Wav.durationMillis(wav))
    }

    override fun cancel() {
        val opened = line ?: return
        line = null
        sink = null
        runCatching {
            opened.stop()
            opened.close()
        }
        runCatching { pump?.join(500) }
        pump = null
    }
}

/**
 * 桌面播放。
 *
 * WAV 直接交给 `Clip`。Edge TTS 公开端点只返回 CBR MP3，而 JDK 不带 MP3 解码，
 * 所以这一条用 JLayer 解出音频设备能直接接受的采样流。
 */
class DesktopVoicePlayer : Player {

    private val hasOutput: Boolean by lazy {
        runCatching { AudioSystem.getMixerInfo().isNotEmpty() }.getOrDefault(false)
    }

    private var playing: Clip? = null
    private var mp3Player: JLayerPlayer? = null
    private var mp3Thread: Thread? = null

    override val available: Boolean get() = hasOutput

    override suspend fun play(clip: VoiceClip): Boolean = withContext(Dispatchers.IO) {
        stop()
        if (clip.mimeType == "audio/mpeg") {
            return@withContext runCatching {
                val opened = JLayerPlayer(ByteArrayInputStream(clip.bytes))
                val worker = Thread {
                    try {
                        opened.play()
                    } finally {
                        synchronized(this@DesktopVoicePlayer) {
                            if (mp3Player === opened) {
                                mp3Player = null
                                mp3Thread = null
                            }
                        }
                    }
                }
                worker.isDaemon = true
                synchronized(this@DesktopVoicePlayer) {
                    mp3Player = opened
                    mp3Thread = worker
                }
                worker.start()
                true
            }.getOrDefault(false)
        }
        runCatching {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(clip.bytes))
            val opened = AudioSystem.getClip()
            opened.open(stream)
            opened.start()
            playing = opened
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        val activeMp3: JLayerPlayer?
        val activeThread: Thread?
        synchronized(this) {
            activeMp3 = mp3Player
            activeThread = mp3Thread
            mp3Player = null
            mp3Thread = null
        }
        runCatching { activeMp3?.close() }
        activeThread?.interrupt()
        runCatching {
            playing?.stop()
            playing?.close()
        }
        playing = null
    }
}
