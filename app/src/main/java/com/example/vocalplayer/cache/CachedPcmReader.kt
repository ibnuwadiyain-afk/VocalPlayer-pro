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
    val totalBytes: Long = file.length()
    val totalSamples: Long = totalBytes / 2L
    private var raf: RandomAccessFile? = null
    private val lock = Any()

    init {
        if (file.exists() && totalBytes > 0) {
            raf = RandomAccessFile(file, "r")
        }
    }

    /**
     * Reads a slice of samples into [outBuffer] starting at [startSampleIndex].
     * Returns the actual count of samples read.
     */
    fun readSlice(startSampleIndex: Long, sampleCount: Int, outBuffer: ShortArray): Int {
        if (startSampleIndex >= totalSamples || sampleCount <= 0) return 0
        val actualCount = minOf(sampleCount.toLong(), totalSamples - startSampleIndex).toInt()
        val r = raf ?: return 0

        synchronized(lock) {
            return try {
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
                val bb = ByteBuffer.wrap(bytes, 0, readShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().get(outBuffer, 0, readShorts)
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
