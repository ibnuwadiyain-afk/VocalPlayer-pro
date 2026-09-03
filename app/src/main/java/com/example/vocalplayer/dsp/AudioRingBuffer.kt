package com.example.vocalplayer.dsp

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe Circular Audio Ring Buffer for streaming chunked audio.
 * Allows lock-free or minimal-lock read and write between audio decoder thread and neural inference thread.
 */
class AudioRingBuffer(val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var availableSamples = 0
    private val lock = ReentrantLock()

    fun write(data: FloatArray, offset: Int = 0, count: Int = data.size): Int {
        lock.withLock {
            val toWrite = min(count, capacity - availableSamples)
            if (toWrite <= 0) return 0

            for (i in 0 until toWrite) {
                buffer[writePos] = data[offset + i]
                writePos = (writePos + 1) % capacity
            }
            availableSamples += toWrite
            return toWrite
        }
    }

    fun read(destination: FloatArray, offset: Int = 0, count: Int = destination.size): Int {
        lock.withLock {
            val toRead = min(count, availableSamples)
            if (toRead <= 0) return 0

            for (i in 0 until toRead) {
                destination[offset + i] = buffer[readPos]
                readPos = (readPos + 1) % capacity
            }
            availableSamples -= toRead
            return toRead
        }
    }

    fun peek(destination: FloatArray, offset: Int = 0, count: Int = destination.size): Int {
        lock.withLock {
            val toPeek = min(count, availableSamples)
            if (toPeek <= 0) return 0

            var peekPos = readPos
            for (i in 0 until toPeek) {
                destination[offset + i] = buffer[peekPos]
                peekPos = (peekPos + 1) % capacity
            }
            return toPeek
        }
    }

    fun available(): Int = lock.withLock { availableSamples }

    fun freeSpace(): Int = lock.withLock { capacity - availableSamples }

    fun healthRatio(): Float = lock.withLock {
        if (capacity > 0) availableSamples.toFloat() / capacity.toFloat() else 0f
    }

    fun clear() {
        lock.withLock {
            writePos = 0
            readPos = 0
            availableSamples = 0
        }
    }
}
