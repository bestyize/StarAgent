package com.starringbox.staragent.core.platform.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Streaming PCM output backed by SourceDataLine. stop() ends only the active playback session. */
public class DesktopPcmPlayer : PcmPlayer {
    private data class Session(
        val generation: Long,
        val sampleRate: Int,
        val line: SourceDataLine,
    )

    private val lock = Any()
    private val transitionLock = Any()
    private val enqueueLock = Mutex()
    private val hasOutput by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(22_050, 24_000, 44_100, 48_000).any { rate ->
            runCatching {
                val format = pcmFormat(rate)
                AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
            }.getOrDefault(false)
        }
    }

    private var generation = 0L
    private var session: Session? = null
    private var closed = false

    override val available: Boolean get() = hasOutput

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
                        active.line.write(audio, offset, minOf((chunk.sampleRate / 20).coerceAtLeast(1) * 2, audio.size - offset))
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(active)) return@withContext
                        throw failure
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(active)) return@withContext
                    check(count > 0 && count % 2 == 0) { "扬声器播放失败：$count" }
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
            currentCoroutineContext().ensureActive()
            if (!isCurrent(active)) return@withContext
            try {
                active.line.drain()
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(active)) return@withContext
                throw failure
            }
            if (!isCurrent(active)) return@withContext
        }
    }

    override fun stop() {
        synchronized(transitionLock) {
            val old = synchronized(lock) {
                if (closed) return
                generation++
                session.also { session = null }
            }
            old?.let(::closeImmediately)
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
            old?.let(::closeImmediately)
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

        val format = pcmFormat(sampleRate)
        val line = AudioSystem.getSourceDataLine(format)
        try {
            line.open(format, maxOf(2_048, (sampleRate / 10).coerceAtLeast(1) * 2))
            line.start()
        } catch (failure: Throwable) {
            runCatching { line.close() }
            throw failure
        }
        return Session(generation, sampleRate, line).also { session = it }
    }

    private fun isCurrent(candidate: Session): Boolean = synchronized(lock) {
        !closed && generation == candidate.generation && session === candidate
    }

    private fun closeImmediately(active: Session) {
        runCatching { active.line.stop() }
        runCatching { active.line.flush() }
        runCatching { active.line.close() }
    }

    private fun pcmFormat(sampleRate: Int) = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
}
