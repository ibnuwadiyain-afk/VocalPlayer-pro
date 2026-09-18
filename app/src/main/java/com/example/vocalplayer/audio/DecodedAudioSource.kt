package com.example.vocalplayer.audio

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Memory-efficient container for decoded audio.
 * Supports both static pre-decoded audio and real-time pipelined streaming,
 * where neural source separation runs concurrently as MediaCodec decodes
 * chunks to disk.
 */
class DecodedAudioSource(
    val sampleRate: Int,
    val channelCount: Int,
    val durationMs: Long,
    val totalSamples: Long,
    val tempPcmFile: File? = null,
    val memoryPcm: ShortArray? = null,
    val isPipelined: Boolean = false
) : AutoCloseable {

    private var raf: RandomAccessFile? = null
    private val bufferLock = Any()

    @Volatile
    var decodedSamples: Long = if (isPipelined) 0L else totalSamples
        private set

    val decodedFrames: Long
        get() = decodedSamples / channelCount.coerceAtLeast(1)

    @Volatile
    var isDecodingFinished: Boolean = !isPipelined
        private set

    @Volatile
    var decodingError: Throwable? = null
        private set

    var decodingJob: Job? = null

    init {
        synchronized(bufferLock) {
            ensureOpenLocked()
        }
    }

    private fun ensureOpenLocked(): RandomAccessFile? {
        if (raf == null && tempPcmFile != null && tempPcmFile.exists()) {
            try {
                raf = RandomAccessFile(tempPcmFile, "r")
            } catch (_: Exception) {}
        }
        return raf
    }

    /**
     * Called by the background MediaCodec worker when new samples are written to [tempPcmFile].
     */
    fun notifyDecodedSamples(newTotalSamples: Long) {
        decodedSamples = newTotalSamples
    }

    /**
     * Called by the background MediaCodec worker when EOS is reached.
     */
    fun notifyDecodingFinished(finalTotalSamples: Long) {
        decodedSamples = finalTotalSamples
        isDecodingFinished = true
    }

    /**
     * Called if an unrecoverable decoding error occurs.
     */
    fun notifyDecodingError(throwable: Throwable) {
        decodingError = throwable
        isDecodingFinished = true
    }

    /**
     * Suspends until at least [targetFrames] have been decoded to disk,
     * or until decoding has completely finished.
     * Returns the current count of available decoded frames.
     */
    suspend fun waitForFrames(targetFrames: Long): Long {
        while (true) {
            currentCoroutineContext().ensureActive()
            decodingError?.let { throw it }

            val currentFrames = decodedFrames
            if (currentFrames >= targetFrames || isDecodingFinished) {
                return currentFrames
            }
            delay(15)
        }
    }

    /**
     * Reads a slice of samples into [outBuffer].
     * Returns the actual count of samples read.
     */
    fun readSamples(startSampleIndex: Long, count: Int, outBuffer: ShortArray): Int {
        val availableLimit = if (isPipelined) decodedSamples else totalSamples
        if (startSampleIndex >= availableLimit || count <= 0) return 0
        val actualCount = minOf(count.toLong(), availableLimit - startSampleIndex).toInt()
        if (actualCount <= 0) return 0

        val mem = memoryPcm
        if (mem != null) {
            System.arraycopy(mem, startSampleIndex.toInt(), outBuffer, 0, actualCount)
            return actualCount
        }

        synchronized(bufferLock) {
            val fileRaf = ensureOpenLocked() ?: return 0
            try {
                fileRaf.seek(startSampleIndex * 2L)
                val bytesToRead = actualCount * 2
                val byteBuf = ByteArray(bytesToRead)
                var bytesReadTotal = 0
                while (bytesReadTotal < bytesToRead) {
                    val read = fileRaf.read(byteBuf, bytesReadTotal, bytesToRead - bytesReadTotal)
                    if (read < 0) break
                    bytesReadTotal += read
                }
                val readShorts = bytesReadTotal / 2
                val bb = ByteBuffer.wrap(byteBuf, 0, readShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().get(outBuffer, 0, readShorts)
                return readShorts
            } catch (e: Exception) {
                return 0
            }
        }
    }

    /**
     * Reads a slice of audio channels into separate float arrays in range [-1.0f, 1.0f].
     * [outLeft] and [outRight] will be filled with [frameCount] audio frames.
     */
    fun readFramesToFloats(startFrame: Long, frameCount: Int, outLeft: FloatArray, outRight: FloatArray): Int {
        val channels = channelCount.coerceAtLeast(1)
        val samplesToRead = frameCount * channels
        val scratchShorts = ShortArray(samplesToRead)
        val readSamples = readSamples(startFrame * channels, samplesToRead, scratchShorts)
        val readFrames = readSamples / channels
        val inv = 1.0f / 32768.0f

        if (channels >= 2) {
            for (i in 0 until readFrames) {
                outLeft[i] = scratchShorts[i * 2] * inv
                outRight[i] = scratchShorts[i * 2 + 1] * inv
            }
        } else {
            for (i in 0 until readFrames) {
                val s = scratchShorts[i] * inv
                outLeft[i] = s
                outRight[i] = s
            }
        }
        return readFrames
    }

    override fun close() {
        try {
            decodingJob?.cancel()
        } catch (_: Exception) {}
        synchronized(bufferLock) {
            try {
                raf?.close()
                raf = null
            } catch (_: Exception) {}
            try {
                tempPcmFile?.delete()
            } catch (_: Exception) {}
        }
    }
}

