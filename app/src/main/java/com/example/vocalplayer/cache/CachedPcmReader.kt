package com.example.vocalplayer.cache

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, memory-safe reader for cached vocal PCM files.
 * Streams samples directly from disk using preallocated small buffers,
 * allowing even 2+ hour videos to play back without loading hundreds
 * of megabytes into JVM heap.
 */
class CachedPcmReader(val file: File) : AutoCloseable {
    private var raf: RandomAccessFile? = null
    private val lock = Any()

    init {
        synchronized(lock) {
            ensureOpenLocked()
        }
    }

    private fun ensureOpenLocked(): RandomAccessFile? {
        if (raf == null && file.exists()) {
            try {
                raf = RandomAccessFile(file, "r")
            } catch (_: Exception) {}
        }
        return raf
    }

    val totalBytes: Long
        get() = synchronized(lock) {
            val r = ensureOpenLocked()
            try {
                r?.length() ?: if (file.exists()) file.length() else 0L
            } catch (_: Exception) {
                if (file.exists()) file.length() else 0L
            }
        }

    val totalSamples: Long
        get() = (totalBytes / 2L).coerceAtLeast(0L)

    /**
     * Reads a slice of samples into [outBuffer] starting at [startSampleIndex].
     * Safely bounds the read to [maxAvailableSamples] (if provided) or the current file length.
     * Returns the actual count of samples read.
     */
    fun readSlice(
        startSampleIndex: Long,
        sampleCount: Int,
        outBuffer: ShortArray,
        maxAvailableSamples: Long = Long.MAX_VALUE
    ): Int {
        if (startSampleIndex < 0 || sampleCount <= 0) return 0

        synchronized(lock) {
            val r = ensureOpenLocked() ?: return 0

            return try {
                val currentFileSamples = r.length() / 2L
                val boundary = minOf(currentFileSamples, maxAvailableSamples)
                if (startSampleIndex >= boundary) return 0

                val actualCount = minOf(sampleCount.toLong(), boundary - startSampleIndex).toInt()
                if (actualCount <= 0) return 0

                r.seek(startSampleIndex * 2L)
                val bytesToRead = actualCount * 2
                val bytes = ByteArray(bytesToRead)
                var bytesRead = 0
                while (bytesRead < bytesToRead) {
                    val n = r.read(bytes, bytesRead, bytesToRead - bytesRead)
                    if (n < 0) break
                    bytesRead += n
                }
                val readShorts = bytesRead / 2
                if (readShorts > 0) {
                    val bb = ByteBuffer.wrap(bytes, 0, readShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().get(outBuffer, 0, readShorts)
                }
                readShorts
            } catch (e: Exception) {
                0
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                raf?.close()
                raf = null
            } catch (_: Exception) {}
        }
    }
}
