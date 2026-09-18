package com.example.vocalplayer.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Memory-efficient container for decoded audio.
 * For audio shorter than threshold, keeps in memory.
 * For long audio files, streams to a temporary disk-backed PCM file
 * to prevent OutOfMemoryError crashes on mobile devices.
 */
class DecodedAudioSource(
    val sampleRate: Int,
    val channelCount: Int,
    val durationMs: Long,
    val totalSamples: Long,
    val tempPcmFile: File? = null,
    val memoryPcm: ShortArray? = null
) : AutoCloseable {

    private var raf: RandomAccessFile? = null
    private val bufferLock = Any()

    init {
        if (tempPcmFile != null && tempPcmFile.exists()) {
            raf = RandomAccessFile(tempPcmFile, "r")
        }
    }

    /**
     * Reads a slice of samples into [outBuffer].
     * Returns the actual count of samples read.
     */
    fun readSamples(startSampleIndex: Long, count: Int, outBuffer: ShortArray): Int {
        if (startSampleIndex >= totalSamples || count <= 0) return 0
        val actualCount = minOf(count.toLong(), totalSamples - startSampleIndex).toInt()

        val mem = memoryPcm
        if (mem != null) {
            System.arraycopy(mem, startSampleIndex.toInt(), outBuffer, 0, actualCount)
            return actualCount
        }

        val fileRaf = raf ?: return 0
        synchronized(bufferLock) {
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
