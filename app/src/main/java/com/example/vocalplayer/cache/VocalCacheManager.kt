package com.example.vocalplayer.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance offline cache for extracted vocal stems.
 * Stores PCM 16-bit audio stems keyed by media URI / content hash.
 * Enables zero-latency, lag-free playback without running heavy real-time neural inference.
 */
class VocalCacheManager(private val context: Context) {
    private val tag = "VocalCacheManager"

    private val cacheDir = File(context.cacheDir, "vocal_stems_cache").apply { mkdirs() }

    // Fast in-memory cache for short media; bounded to 15MB to prevent OOM
    private val activeMemoryCache = ConcurrentHashMap<String, ShortArray>()
    private val activeReaders = ConcurrentHashMap<String, CachedPcmReader>()
    private var activeMediaKey: String? = null

    // Threshold for storing in memory: ~3 minutes stereo at 44.1kHz (~30MB)
    private val maxMemoryShorts = 44100 * 2 * 180

    fun getCacheKey(uri: Uri): String {
        val uriStr = uri.toString()
        val baseHash = try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(uriStr.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            uriStr.hashCode().toString()
        }
        return "v2_$baseHash"
    }

    fun isVocalCached(uri: Uri): Boolean {
        val key = getCacheKey(uri)
        if (activeMemoryCache.containsKey(key)) return true
        val pcmFile = File(cacheDir, "${key}_vocals.pcm")
        return pcmFile.exists() && pcmFile.length() > 1024
    }

    fun getCachedPcmFile(uri: Uri): File? {
        val key = getCacheKey(uri)
        val pcmFile = File(cacheDir, "${key}_vocals.pcm")
        return if (pcmFile.exists() && pcmFile.length() > 1024) pcmFile else null
    }

    fun getCachedReader(uri: Uri): CachedPcmReader? {
        val key = getCacheKey(uri)
        activeReaders[key]?.let { return it }
        val pcmFile = getCachedPcmFile(uri) ?: return null
        val reader = CachedPcmReader(pcmFile)
        activeReaders[key] = reader
        return reader
    }

    fun getCachedPcm(uri: Uri): ShortArray? {
        val key = getCacheKey(uri)
        activeMemoryCache[key]?.let { return it }

        val pcmFile = File(cacheDir, "${key}_vocals.pcm")
        if (!pcmFile.exists() || pcmFile.length() < 1024) return null

        val totalBytes = pcmFile.length()
        val totalShorts = (totalBytes / 2).toInt()

        // If file is very large (> 25MB), don't load entire ShortArray into heap
        if (totalShorts > maxMemoryShorts) {
            Log.i(tag, "Cached vocal file for $key is large (${totalBytes / 1024 / 1024} MB); using direct disk streaming")
            return null
        }

        return try {
            val shortArray = ShortArray(totalShorts)

            FileInputStream(pcmFile).use { fis ->
                val byteBuffer = ByteBuffer.allocate(totalBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                val channel = fis.channel
                channel.read(byteBuffer)
                byteBuffer.flip()
                byteBuffer.asShortBuffer().get(shortArray)
            }

            activeMemoryCache[key] = shortArray
            activeMediaKey = key
            Log.i(tag, "Loaded vocal cache for $key: ${shortArray.size} samples (${shortArray.size / 44100 / 2}s)")
            shortArray
        } catch (e: OutOfMemoryError) {
            Log.w(tag, "OutOfMemory loading vocal cache for $key, falling back to disk streaming: ${e.message}")
            activeMemoryCache.remove(key)
            null
        } catch (e: Exception) {
            Log.e(tag, "Failed to read cached PCM for $key: ${e.message}", e)
            null
        }
    }

    fun saveVocalPcm(uri: Uri, pcmData: ShortArray, sampleRate: Int = 44100, channels: Int = 2): File {
        val key = getCacheKey(uri)
        val pcmFile = File(cacheDir, "${key}_vocals.pcm")

        try {
            FileOutputStream(pcmFile).use { fos ->
                val channel = fos.channel
                val chunkSize = 32768 // 64 KB per chunk
                val byteBuffer = ByteBuffer.allocateDirect(chunkSize * 2).order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = byteBuffer.asShortBuffer()
                var offset = 0

                while (offset < pcmData.size) {
                    val count = minOf(chunkSize, pcmData.size - offset)
                    byteBuffer.clear()
                    shortBuffer.clear()
                    shortBuffer.put(pcmData, offset, count)
                    byteBuffer.position(0).limit(count * 2)
                    channel.write(byteBuffer)
                    offset += count
                }
            }

            if (pcmData.size <= maxMemoryShorts) {
                activeMemoryCache[key] = pcmData
            } else {
                activeMemoryCache.remove(key)
            }
            activeMediaKey = key
            Log.i(tag, "Saved vocal cache for $key (${pcmData.size} samples, ${pcmFile.length() / 1024} KB)")
        } catch (e: Exception) {
            Log.e(tag, "Error saving vocal cache for $key: ${e.message}", e)
        }

        return pcmFile
    }

    /**
     * Retrieves a slice of cached vocal PCM samples corresponding to current playback position.
     * Returns the actual number of samples written to [outBuffer].
     * Reads from fast memory cache if available, or streams directly from disk reader.
     */
    fun readVocalSlice(uri: Uri, startSampleIndex: Long, sampleCount: Int, outBuffer: ShortArray): Int {
        val key = getCacheKey(uri)

        // 1. Check in-memory cache
        val pcm = activeMemoryCache[key]
        if (pcm != null) {
            val totalSamples = pcm.size
            if (startSampleIndex >= totalSamples) return 0
            val start = startSampleIndex.toInt().coerceAtLeast(0)
            val count = sampleCount.coerceAtMost(totalSamples - start)
            if (count > 0) {
                System.arraycopy(pcm, start, outBuffer, 0, count)
            }
            return count
        }

        // 2. Stream directly from disk reader without heap allocation
        val reader = getCachedReader(uri) ?: return 0
        return reader.readSlice(startSampleIndex, sampleCount, outBuffer)
    }

    fun setActiveMedia(uri: Uri) {
        val key = getCacheKey(uri)
        activeMediaKey = key
        if (!activeMemoryCache.containsKey(key)) {
            getCachedPcm(uri)
        }
    }

    fun clearCache(): Long {
        activeMemoryCache.clear()
        activeReaders.values.forEach { it.close() }
        activeReaders.clear()
        activeMediaKey = null
        var freedBytes = 0L
        cacheDir.listFiles()?.forEach { file ->
            freedBytes += file.length()
            file.delete()
        }
        return freedBytes
    }

    fun getCacheSizeMb(): Float {
        var bytes = 0L
        cacheDir.listFiles()?.forEach { bytes += it.length() }
        return bytes.toFloat() / (1024f * 1024f)
    }
}
