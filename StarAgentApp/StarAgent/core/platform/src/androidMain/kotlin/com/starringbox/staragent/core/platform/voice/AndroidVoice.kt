package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.Player
import com.starringbox.staragent.core.speech.Recorder
import com.starringbox.staragent.core.speech.SpeechActivity
import com.starringbox.staragent.core.speech.VoiceClip
import com.starringbox.staragent.core.speech.Wav
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android 录音。
 *
 * 和选图一样要挂在 Activity 上：麦克风是危险权限，必须运行时申请，
 * 而 `registerForActivityResult` **只能在 Activity 初始化阶段调用**，
 * 所以这个类由 [DeviceVoice.install] 在 `Activity.onCreate` 里构造并注入，
 * 不能等用到时再 new。
 */
class AndroidVoiceRecorder(
    private val context: Context,
    caller: ActivityResultCaller,
) : Recorder {

    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val permissionLauncher =
        caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingPermission
            pendingPermission = null
            callback?.invoke(granted)
        }

    private var record: AudioRecord? = null
    private var sink: ByteArrayOutputStream? = null
    private var pump: Thread? = null
    private val activity = SpeechActivity()
    override val inputLevel: Float get() = activity.level
    override val speechEnded: Boolean get() = activity.ended

    /**
     * 能力本身是有的——**没权限不算不可用**，点一下就会去申请。
     * 手机没有麦克风是极少数情况，那种交给 start() 返回 false。
     */
    override val available: Boolean get() = true

    private fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun start(): Boolean {
        if (record != null) return false
        if (!granted() && !requestPermission()) return false

        return withContext(Dispatchers.IO) {
            runCatching {
                val minBytes = AudioRecord.getMinBufferSize(
                    Wav.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                // 取大一点：缓冲区太小会在读得慢的时候丢帧
                val bufferBytes = maxOf(minBytes, Wav.SAMPLE_RATE * 2)

                val opened = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    Wav.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                if (opened.state != AudioRecord.STATE_INITIALIZED) {
                    opened.release()
                    return@runCatching false
                }
                opened.startRecording()

                val collected = ByteArrayOutputStream()
                activity.reset()
                val worker = Thread {
                    val chunk = ByteArray(3_200)
                    while (true) {
                        val read = runCatching { opened.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                        if (read <= 0) break
                        activity.acceptPcm(chunk, read)
                        collected.write(chunk, 0, read)
                    }
                }
                worker.isDaemon = true
                worker.start()

                record = opened
                sink = collected
                pump = worker
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun stop(): VoiceClip? = withContext(Dispatchers.IO) {
        val opened = record ?: return@withContext null
        val collected = sink
        record = null
        sink = null

        runCatching {
            opened.stop()
            opened.release()
        }
        pump?.join(500)
        pump = null

        val pcm = collected?.toByteArray() ?: ByteArray(0)
        if (pcm.isEmpty()) return@withContext null

        val wav = Wav.wrap(pcm)
        VoiceClip(wav, Wav.MIME_TYPE, Wav.durationMillis(wav))
    }

    override fun cancel() {
        val opened = record ?: return
        record = null
        sink = null
        runCatching {
            opened.stop()
            opened.release()
        }
        runCatching { pump?.join(500) }
        pump = null
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        pendingPermission = { granted -> if (continuation.isActive) continuation.resume(granted) }
        continuation.invokeOnCancellation { pendingPermission = null }
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/**
 * Android 播放。
 *
 * 走 `MediaPlayer` 播临时文件而不是 `AudioTrack`：后者要自己按 PCM 喂数据、
 * 还要管采样率，而我们已经把音频统一成 WAV 了，交给系统解码更省事。
 */
class AndroidVoicePlayer(private val context: Context) : Player {

    private var playing: MediaPlayer? = null

    override val available: Boolean get() = true

    override suspend fun play(clip: VoiceClip): Boolean = withContext(Dispatchers.IO) {
        stop()
        runCatching {
            val file = File(context.cacheDir, "staragent-reply.wav")
            file.writeBytes(clip.bytes)

            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            playing = player
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        runCatching {
            playing?.stop()
            playing?.release()
        }
        playing = null
    }
}
