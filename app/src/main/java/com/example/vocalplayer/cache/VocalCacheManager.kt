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

    // Fast in-memory cache for the actively playing media to avoid disk reads during playback
    private val activeMemoryCache = ConcurrentHashMap<String, ShortArray>()
    private var activeMediaKey: String? = null

    fun getCacheKey(uri: Uri): String {
        val uriStr = uri.toString()
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(uriStr.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            uriStr.hashCode().toString()
        }
    }

    fun isVocalCached(uri: Uri): Boolean {
        val key = getCacheKey(uri)
        if (activeMemoryCache.containsKey(key)) return true
        val pcmFile = File(cacheDir, "${key}_vocals.pcm")
        return pcmFile.exists() && pcmFile.length() > 1024
    }

    fun getCachedPcm(uri: Uri): ShortArray? {
        val key = getCacheKey(uri)
        activeMemoryCache[key]?.let { return it }

        val pcmFile = File(cacheDir, "${key}_vocals.pcm")
        if (!pcmFile.exists() || pcmFile.length() < 1024) return null

        return try {
            val totalBytes = pcmFile.length().toInt()
            val totalShorts = totalBytes / 2
            val shortArray = ShortArray(totalShorts)

            FileInputStream(pcmFile).use { fis ->
                val byteBuffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
                val channel = fis.channel
                channel.read(byteBuffer)
                byteBuffer.flip()
                byteBuffer.asShortBuffer().get(shortArray)
            }

            activeMemoryCache[key] = shortArray
            activeMediaKey = key
            Log.i(tag, "Loaded vocal cache for $key: ${shortArray.size} samples (${shortArray.size / 44100 / 2}s)")
            shortArray
        } catch (e: Exception) {
            Log.e(tag, "Failed to read cached PCM for $key: ${e.message}", e)
            null
        }
    }

    fun saveVocalPcm(uri: Uri, pcmData: ShortArray, sampleRate: Int = 44100, channels: Int = 2): File {
        val key = getCacheKey(uri)
        val pcmFile = File(cacheDir, "${key}_vocals.pcm")

        try {
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(pcmData)

            FileOutputStream(pcmFile).use { fos ->
                fos.channel.write(byteBuffer)
            }

            activeMemoryCache[key] = pcmData
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
     */
    fun readVocalSlice(uri: Uri, startSampleIndex: Long, sampleCount: Int, outBuffer: ShortArray): Int {
        val key = getCacheKey(uri)
        val pcm = activeMemoryCache[key] ?: getCachedPcm(uri) ?: return 0

        val totalSamples = pcm.size
        if (startSampleIndex >= totalSamples) return 0

        val start = startSampleIndex.toInt().coerceAtLeast(0)
        val count = sampleCount.coerceAtMost(totalSamples - start)

        if (count > 0) {
            System.arraycopy(pcm, start, outBuffer, 0, count)
        }
        return count
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
