package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.PcmAudioChunk
import com.starringbox.staragent.core.speech.PcmPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import kotlinx.cinterop.set
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSRecursiveLock

/** Streams mono PCM16 chunks through AVAudioEngine, keeping at most eight buffers in flight. */
@OptIn(ExperimentalForeignApi::class)
public class IosPcmPlayer : PcmPlayer {
    private data class Session(
        val generation: Long,
        val sampleRate: Int,
        val engine: AVAudioEngine,
        val player: AVAudioPlayerNode,
        val format: AVAudioFormat,
        val pending: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
    )

    private sealed interface Reservation {
        data class Ready(val completion: CompletableDeferred<Unit>) : Reservation
        data class Wait(val completion: Deferred<Unit>) : Reservation
        data object Stale : Reservation
    }

    private val lock = NSRecursiveLock()
    private val transitionLock = NSRecursiveLock()
    private val enqueueLock = Mutex()
    private var generation = 0L
    private var session: Session? = null
    private var closed = false

    override val available: Boolean get() = true

    override suspend fun enqueue(chunk: PcmAudioChunk) {
        if (chunk.pcm16.isEmpty()) return
        val expectedGeneration = lock.withNativeLock {
            check(!closed) { "PCM 播放器已关闭" }
            generation
        }
        val audio = chunk.pcm16.copyOf()
        enqueueLock.withLock {
            val active = getOrOpenSession(chunk.sampleRate, expectedGeneration) ?: return
            val completion = reserve(active) ?: return
            try {
                val buffer = toBuffer(active.format, audio)
                active.player.scheduleBuffer(
                    buffer,
                    completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
                    completionHandler = { _ -> complete(active, completion) },
                )
            } catch (failure: Throwable) {
                complete(active, completion)
                throw failure
            }
        }
    }

    override suspend fun drain() {
        enqueueLock.withLock {
            val pending = lock.withNativeLock { session?.pending?.toList().orEmpty() }
            pending.forEach { it.await() }
        }
    }

    override fun stop() = endSession(closePermanently = false)

    override fun close() = endSession(closePermanently = true)

    private suspend fun reserve(active: Session): CompletableDeferred<Unit>? {
        while (true) {
            val reservation = lock.withNativeLock {
                if (closed || generation != active.generation || session !== active) {
                    Reservation.Stale
                } else if (active.pending.size < MAX_PENDING_BUFFERS) {
                    CompletableDeferred<Unit>().also {
                        active.pending += it
                    }.let(Reservation::Ready)
                } else {
                    Reservation.Wait(active.pending.first())
                }
            }
            when (reservation) {
                is Reservation.Ready -> return reservation.completion
                is Reservation.Wait -> reservation.completion.await()
                Reservation.Stale -> return null
            }
        }
    }

    private fun complete(active: Session, completion: CompletableDeferred<Unit>) {
        val removed = lock.withNativeLock { active.pending.remove(completion) }
        if (removed) completion.complete(Unit)
    }

    private fun getOrOpenSession(sampleRate: Int, expectedGeneration: Long): Session? = transitionLock.withNativeLock outer@{
        val existing = lock.withNativeLock {
            check(!closed) { "PCM 播放器已关闭" }
            if (generation != expectedGeneration) return@outer null
            session
        }
        existing?.let {
            check(it.sampleRate == sampleRate) { "PCM 采样率已变化，请先停止当前播放会话" }
            return@outer it
        }

        val format = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
            ?: error("iOS 不支持 ${sampleRate}Hz PCM 音频")
        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val opened = Session(expectedGeneration, sampleRate, engine, player, format)
        try {
            val audioSession = AVAudioSession.sharedInstance()
            check(audioSession.setCategory(AVAudioSessionCategoryPlayback, null)) { "无法设置 iOS 音频输出" }
            check(audioSession.setActive(true, null)) { "无法启用 iOS 音频输出" }
            engine.attachNode(player)
            engine.connect(player, to = engine.mainMixerNode, format = format)
            engine.prepare()
            check(engine.startAndReturnError(null)) { "无法启动 iOS 音频引擎" }
            player.play()
        } catch (failure: Throwable) {
            runCatching { player.stop() }
            runCatching { engine.stop() }
            runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
            throw failure
        }

        lock.withNativeLock commit@{
            if (closed || generation != expectedGeneration) {
                player.stop()
                engine.stop()
                return@commit null
            }
            session = opened
            opened
        }
    }

    private fun endSession(closePermanently: Boolean) {
        transitionLock.withNativeLock outer@{
            val state = lock.withNativeLock inner@{
                if (closed) return@inner null
                if (closePermanently) closed = true
                generation++
                val old = session
                session = null
                val pending = old?.pending?.toList().orEmpty()
                old?.pending?.clear()
                old to pending
            } ?: return@outer
            val (old, pending) = state
            pending.forEach { it.complete(Unit) }
            old?.let {
                runCatching { it.player.stop() }
                runCatching { it.engine.stop() }
            }
            runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
        }
    }

    private fun toBuffer(format: AVAudioFormat, pcm16: ByteArray): AVAudioPCMBuffer {
        val frames = pcm16.size / 2
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt())
            ?: error("无法创建 iOS PCM 音频缓冲区")
        buffer.frameLength = frames.toUInt()
        val samples = checkNotNull(buffer.floatChannelData?.pointed?.value) { "iOS 音频缓冲区不支持 Float32" }
        for (index in 0 until frames) {
            val raw = (pcm16[index * 2].toInt() and 0xff) or
                ((pcm16[index * 2 + 1].toInt() and 0xff) shl 8)
            samples[index] = raw.toShort().toFloat() / 32768f
        }
        return buffer
    }

    private companion object {
        const val MAX_PENDING_BUFFERS = 8
    }
}

private inline fun <T> NSRecursiveLock.withNativeLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
