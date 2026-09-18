package com.example.vocalplayer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-speed offline audio decoder using Android's native MediaExtractor and MediaCodec.
 * Decodes video files (MP4, MKV, WebM) or audio files (MP3, WAV, AAC, M4A, FLAC) into
 * standard 16-bit PCM stereo audio samples for offline neural source separation.
 * Supports streaming to disk for long videos to eliminate OutOfMemoryError crashes.
 */
class MediaAudioDecoder(private val context: Context) {
    private val tag = "MediaAudioDecoder"

    data class DecodedAudio(
        val pcmShorts: ShortArray,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long
    )

    /**
     * Memory-safe streaming decoder: for long files, streams decoded PCM frames
     * directly into a temporary file on disk, avoiding keeping hundreds of megabytes
     * in the JVM heap simultaneously.
     */
    suspend fun streamDecodedAudio(
        uri: Uri,
        onProgress: ((progress: Float) -> Unit)? = null
    ): DecodedAudioSource = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pfd: android.os.ParcelFileDescriptor? = null

        val tempPcmFile = File(context.cacheDir, "dec_stream_${System.currentTimeMillis()}_${(1000..9999).random()}.pcm")
        var fos: FileOutputStream? = null

        try {
            if (uri.scheme == "content") {
                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        extractor.setDataSource(pfd.fileDescriptor)
                    } else {
                        extractor.setDataSource(context, uri, null)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to open content FD, falling back to context URI: ${e.message}")
                    extractor.setDataSource(context, uri, null)
                }
            } else {
                extractor.setDataSource(context, uri, null)
            }
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                throw IllegalStateException("No audio track found in media file: $uri")
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val durationMs = durationUs / 1000L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            fos = FileOutputStream(tempPcmFile)
            val channel = fos.channel

            var totalShortsWritten = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val kTimeOutUs = 10000L

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(kTimeOutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()

                            if (durationUs > 0) {
                                val progress = (sampleTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, kTimeOutUs)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        channel.write(outputBuffer)
                        totalShortsWritten += (bufferInfo.size / 2)
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }

            channel.force(true)
            fos.close()
            fos = null

            Log.i(tag, "Stream-decoded $uri to disk: $totalShortsWritten shorts ($sampleRate Hz, $channelCount ch, $durationMs ms, ${tempPcmFile.length() / 1024} KB)")

            DecodedAudioSource(
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationMs = durationMs,
                totalSamples = totalShortsWritten,
                tempPcmFile = tempPcmFile
            )
        } catch (e: Throwable) {
            fos?.close()
            tempPcmFile.delete()
            throw e
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                Log.w(tag, "Error closing codec: ${e.message}")
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing extractor: ${e.message}")
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing PFD: ${e.message}")
            }
        }
    }

    suspend fun decodeToPcm(
        uri: Uri,
        onProgress: ((progress: Float) -> Unit)? = null
    ): DecodedAudio = withContext(Dispatchers.IO) {
        val streamSource = streamDecodedAudio(uri, onProgress)
        val totalShorts = streamSource.totalSamples
        val sampleRate = streamSource.sampleRate
        val channelCount = streamSource.channelCount
        val durationMs = streamSource.durationMs

        try {
            if (totalShorts > Int.MAX_VALUE / 2) {
                throw OutOfMemoryError("Audio file too large for in-memory ShortArray ($totalShorts shorts)")
            }
            val pcm = ShortArray(totalShorts.toInt())
            streamSource.readSamples(0, totalShorts.toInt(), pcm)
            DecodedAudio(
                pcmShorts = pcm,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationMs = durationMs
            )
        } finally {
            streamSource.close()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
}
