package com.starringbox.staragent.core.platform.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Streaming PCM output backed by AudioTrack. stop() ends only the active playback session. */
public class AndroidPcmPlayer : PcmPlayer {
    private data class Session(
        val generation: Long,
        val sampleRate: Int,
        val track: AudioTrack,
        val writtenFrames: AtomicLong = AtomicLong(0),
    )

    private val lock = Any()
    private val transitionLock = Any()
    private val enqueueLock = Mutex()
    private var generation = 0L
    private var session: Session? = null
    private var closed = false

    override val available: Boolean get() = true

    override suspend fun enqueue(chunk: PcmAudioChunk) {
        val enqueueGeneration = synchronized(lock) {
            check(!closed) { "PCM 播放器已关闭" }
            generation
        }
        if (chunk.pcm16.isEmpty()) return
        val audio = chunk.pcm16.copyOf()
        enqueueLock.withLock {
            withContext(Dispatchers.IO) {
                val active = getOrOpenSession(chunk.sampleRate, enqueueGeneration) ?: return@withContext
                var offset = 0
                while (offset < audio.size) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(active)) return@withContext
                    val count = try {
                        active.track.write(
                            audio,
                            offset,
                            minOf((chunk.sampleRate / 20).coerceAtLeast(1) * 2, audio.size - offset),
                            AudioTrack.WRITE_BLOCKING,
                        )
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(active)) return@withContext
                        throw failure
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(active)) return@withContext
                    check(count > 0 && count % 2 == 0) { "音频写入失败：$count" }
                    active.writtenFrames.addAndGet((count / 2).toLong())
                    offset += count
                }
            }
        }
    }

    override suspend fun drain() {
        val active = synchronized(lock) {
            check(!closed) { "PCM 播放器已关闭" }
            session
        } ?: return
        withContext(Dispatchers.IO) {
            while (isCurrent(active)) {
                currentCoroutineContext().ensureActive()
                val playedFrames = try {
                    active.track.playbackHeadPosition.toLong() and 0xffff_ffffL
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(active)) return@withContext
                    throw failure
                }
                if (!isCurrent(active) || playedFrames >= active.writtenFrames.get()) return@withContext
                delay(10)
            }
        }
    }

    override fun stop() {
        synchronized(transitionLock) {
            val old = synchronized(lock) {
                if (closed) return
                generation++
                session.also { session = null }
            }
            old?.let(::releaseImmediately)
        }
    }

    override fun close() {
        synchronized(transitionLock) {
            val old = synchronized(lock) {
                if (closed) return
                closed = true
                generation++
                session.also { session = null }
            }
            old?.let(::releaseImmediately)
        }
    }

    private fun getOrOpenSession(sampleRate: Int, expectedGeneration: Long): Session? = synchronized(transitionLock) {
        synchronized(lock) { getOrOpenSessionLocked(sampleRate, expectedGeneration) }
    }

    private fun getOrOpenSessionLocked(sampleRate: Int, expectedGeneration: Long): Session? {
        check(!closed) { "PCM 播放器已关闭" }
        if (generation != expectedGeneration) return null
        session?.let { current ->
            check(current.sampleRate == sampleRate) { "PCM 采样率已变化，请先停止当前播放会话" }
            return current
        }

        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "扬声器不支持 ${sampleRate}Hz PCM 音频" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((maxOf(minimum, (sampleRate / 10).coerceAtLeast(1) * 2) + 1) and -2)
            .build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "扬声器初始化失败" }
            track.play()
        } catch (failure: Throwable) {
            runCatching { track.release() }
            throw failure
        }
        return Session(generation, sampleRate, track).also { session = it }
    }

    private fun isCurrent(candidate: Session): Boolean = synchronized(lock) {
        !closed && generation == candidate.generation && session === candidate
    }

    private fun releaseImmediately(active: Session) {
        runCatching { active.track.pause() }
        runCatching { active.track.flush() }
        runCatching { active.track.release() }
    }
}
