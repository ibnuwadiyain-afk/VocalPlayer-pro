package com.example.vocalplayer.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.vocalplayer.cache.CachedPcmReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ExportResult(
    val file: File,
    val mediaStoreUri: Uri?,
    val fileProviderUri: Uri,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val title: String,
    val originalDeleted: Boolean = false
)

/**
 * High-performance exporter for videos with muted instruments (isolated lead vocals).
 *
 * Capabilities:
 * 1. Takes the source video track from [sourceUri] via MediaExtractor and remuxes it
 *    directly with zero quality loss and near-instant speed.
 * 2. Encodes the cached vocal PCM (instruments muted) into high-fidelity stereo AAC (192 kbps).
 * 3. Interleaves video frames and audio frames into an MP4 container with MediaMuxer.
 * 4. Fallback for audio-only sources: generates an elegant H.264 video track with studio artwork.
 * 5. Saves the exported video to the device's Movies/VocalPlayer folder via MediaStore
 *    and provides a FileProvider content URI for instant sharing via Android Share Sheet.
 */
class VocalVideoExporter(private val context: Context) {
    private val tag = "VocalVideoExporter"

    private val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Creates a new destination File for pipelined background video export.
     */
    fun createExportOutputFile(title: String): File {
        val cleanTitle = title.ifBlank { "vocal_isolated" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(exportDir, "${cleanTitle}_muted_instruments_${System.currentTimeMillis()}.mp4")
    }

    /**
     * Finalizes a completed pipelined video file into MediaStore, optionally deleting the original video.
     */
    suspend fun finalizePipelinedVideoExport(
        sourceUri: Uri,
        outputFile: File,
        title: String,
        durationMs: Long,
        deleteOriginal: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ExportResult = withContext(Dispatchers.IO) {
        val cleanTitle = title.ifBlank { "vocal_isolated" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")

        onProgress(0.95f, "Finalizing video in media gallery...")
        val mediaStoreUri = saveToMediaStore(outputFile, "${cleanTitle}_muted_instruments.mp4")
        val fileProviderUri = getFileProviderUri(outputFile)

        var originalDeleted = false
        if (deleteOriginal) {
            onProgress(0.98f, "Removing original video file as requested...")
            originalDeleted = deleteOriginalMedia(sourceUri)
            Log.i(tag, "Delete original video ($sourceUri) result: $originalDeleted")
        }

        onProgress(1.0f, if (originalDeleted) "Export Complete! (Original video deleted)" else "Export Complete!")

        ExportResult(
            file = outputFile,
            mediaStoreUri = mediaStoreUri,
            fileProviderUri = fileProviderUri,
            durationMs = durationMs,
            fileSizeBytes = outputFile.length(),
            title = "$cleanTitle (Instruments Muted)",
            originalDeleted = originalDeleted
        )
    }

    /**
     * Deletes the original media file if user opted in.
     */
    fun deleteOriginalMedia(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                val f = File(path)
                f.delete()
            } else if (uri.scheme == "content") {
                val deletedRows = context.contentResolver.delete(uri, null, null)
                deletedRows > 0
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not delete original media at $uri: ${e.message}")
            false
        }
    }

    suspend fun exportMutedInstrumentsVideo(
        sourceUri: Uri,
        cachedVocalPcm: ShortArray,
        title: String,
        sampleRate: Int = 44100,
        channels: Int = 2,
        deleteOriginal: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ExportResult = exportMutedInstrumentsVideoInternal(
        sourceUri = sourceUri,
        cachedVocalPcm = cachedVocalPcm,
        reader = null,
        title = title,
        sampleRate = sampleRate,
        channels = channels,
        deleteOriginal = deleteOriginal,
        onProgress = onProgress
    )

    suspend fun exportMutedInstrumentsVideo(
        sourceUri: Uri,
        reader: CachedPcmReader,
        title: String,
        sampleRate: Int = 44100,
        channels: Int = 2,
        deleteOriginal: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ExportResult = exportMutedInstrumentsVideoInternal(
        sourceUri = sourceUri,
        cachedVocalPcm = null,
        reader = reader,
        title = title,
        sampleRate = sampleRate,
        channels = channels,
        deleteOriginal = deleteOriginal,
        onProgress = onProgress
    )

    private suspend fun exportMutedInstrumentsVideoInternal(
        sourceUri: Uri,
        cachedVocalPcm: ShortArray?,
        reader: CachedPcmReader?,
        title: String,
        sampleRate: Int = 44100,
        channels: Int = 2,
        deleteOriginal: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ExportResult = withContext(Dispatchers.IO) {
        val cleanTitle = title.ifBlank { "vocal_isolated" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outputFile = File(exportDir, "${cleanTitle}_muted_instruments_${System.currentTimeMillis()}.mp4")

        onProgress(0.05f, "Inspecting video source tracks...")

        val extractor = MediaExtractor()
        var hasVideoTrack = false
        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null

        try {
            setExtractorDataSource(extractor, sourceUri)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    hasVideoTrack = true
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not extract video track from $sourceUri: ${e.message}. Will generate studio video frame.")
            hasVideoTrack = false
        }

        val totalPcmSamples = reader?.totalSamples ?: cachedVocalPcm?.size?.toLong() ?: 0L
        val totalAudioDurationUs = (totalPcmSamples * 1_000_000L) / (sampleRate * channels)
        val durationMs = totalAudioDurationUs / 1000L

        if (hasVideoTrack && videoFormat != null) {
            muxExistingVideoWithVocalAudio(
                extractor = extractor,
                videoTrackIndex = videoTrackIndex,
                videoFormat = videoFormat,
                cachedVocalPcm = cachedVocalPcm,
                reader = reader,
                sampleRate = sampleRate,
                channels = channels,
                totalAudioDurationUs = totalAudioDurationUs,
                outputFile = outputFile,
                onProgress = onProgress
            )
        } else {
            extractor.release()
            generateStudioVideoWithVocalAudio(
                cachedVocalPcm = cachedVocalPcm,
                reader = reader,
                sampleRate = sampleRate,
                channels = channels,
                totalAudioDurationUs = totalAudioDurationUs,
                outputFile = outputFile,
                onProgress = onProgress
            )
        }

        onProgress(0.95f, "Saving to media gallery...")

        val mediaStoreUri = saveToMediaStore(outputFile, "${cleanTitle}_muted_instruments.mp4")
        val fileProviderUri = getFileProviderUri(outputFile)

        var originalDeleted = false
        if (deleteOriginal) {
            onProgress(0.98f, "Removing original video file as requested...")
            originalDeleted = deleteOriginalMedia(sourceUri)
            Log.i(tag, "Delete original video ($sourceUri) result: $originalDeleted")
        }

        onProgress(1.0f, if (originalDeleted) "Export Complete! (Original video deleted)" else "Export Complete!")

        ExportResult(
            file = outputFile,
            mediaStoreUri = mediaStoreUri,
            fileProviderUri = fileProviderUri,
            durationMs = durationMs,
            fileSizeBytes = outputFile.length(),
            title = "$cleanTitle (Instruments Muted)",
            originalDeleted = originalDeleted
        )
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, uri: Uri) {
        if (uri.scheme == "content" || uri.scheme == "android.resource") {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                return
            }
        } else if (uri.scheme == "file") {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            extractor.setDataSource(path)
            return
        }
        extractor.setDataSource(context, uri, null)
    }

    /**
     * Seamlessly remuxes the original video track with the cached AAC vocal track.
     */
    private suspend fun muxExistingVideoWithVocalAudio(
        extractor: MediaExtractor,
        videoTrackIndex: Int,
        videoFormat: MediaFormat,
        cachedVocalPcm: ShortArray?,
        reader: CachedPcmReader?,
        sampleRate: Int,
        channels: Int,
        totalAudioDurationUs: Long,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        extractor.selectTrack(videoTrackIndex)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
        } else if (videoFormat.containsKey("rotation-degrees")) {
            muxer.setOrientationHint(videoFormat.getInteger("rotation-degrees"))
        }

        val muxerVideoTrack = muxer.addTrack(videoFormat)

        val audioCodecFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(audioCodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        var muxerAudioTrack = -1
        var muxerStarted = false
        val audioBufferInfo = MediaCodec.BufferInfo()

        val maxVideoBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(65536)
        } else {
            1024 * 1024
        }
        val videoBuffer = ByteBuffer.allocateDirect(maxVideoBufSize)
        val videoBufferInfo = MediaCodec.BufferInfo()

        var pcmSampleOffset = 0L
        val totalPcmSamples = reader?.totalSamples ?: cachedVocalPcm?.size?.toLong() ?: 0L
        val tempShortChunk = ShortArray(8192)
        var audioInputDone = false
        var audioEncoderDone = false
        var videoDone = false
        var lastVideoPtsUs = 0L
        var lastAudioPtsUs = 0L

        val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            totalAudioDurationUs
        }
        val totalDurationUs = maxOf(videoDurationUs, totalAudioDurationUs)

        // Pending encoded audio frames received before format change
        class PendingFrame(val data: ByteArray, val info: MediaCodec.BufferInfo)
        val pendingAudioFrames = mutableListOf<PendingFrame>()

        try {
            while (currentCoroutineContext().isActive && (!videoDone || !audioEncoderDone)) {
                // 1. Feed audio encoder input
                if (!audioInputDone) {
                    val inIndex = audioEncoder.dequeueInputBuffer(1000L)
                    if (inIndex >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val remainingSamples = (totalPcmSamples - pcmSampleOffset).coerceAtLeast(0L)
                        val shortsToRead = minOf(inBuf.remaining() / 2, remainingSamples.toInt())
                        if (shortsToRead > 0) {
                            if (reader != null) {
                                val bufferToUse = if (shortsToRead <= tempShortChunk.size) tempShortChunk else ShortArray(shortsToRead)
                                val read = reader.readSlice(pcmSampleOffset, shortsToRead, bufferToUse)
                                for (i in 0 until read) {
                                    inBuf.putShort(bufferToUse[i])
                                }
                            } else if (cachedVocalPcm != null) {
                                val off = pcmSampleOffset.toInt()
                                for (i in 0 until shortsToRead) {
                                    inBuf.putShort(cachedVocalPcm[off + i])
                                }
                            }
                            val ptsUs = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            pcmSampleOffset += shortsToRead
                            audioEncoder.queueInputBuffer(inIndex, 0, shortsToRead * 2, ptsUs, 0)
                        } else {
                            val ptsUs = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            audioEncoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        }
                    }
                }

                // 2. Drain audio encoder output
                val outIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 1000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        muxerAudioTrack = muxer.addTrack(audioEncoder.outputFormat)
                        muxer.start()
                        muxerStarted = true

                        // Flush pending audio frames
                        for (pf in pendingAudioFrames) {
                            val buf = ByteBuffer.wrap(pf.data)
                            muxer.writeSampleData(muxerAudioTrack, buf, pf.info)
                        }
                        pendingAudioFrames.clear()
                    }
                } else if (outIndex >= 0) {
                    val outBuf = audioEncoder.getOutputBuffer(outIndex)!!
                    val isConfig = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (!isConfig && audioBufferInfo.size > 0) {
                        if (muxerStarted) {
                            muxer.writeSampleData(muxerAudioTrack, outBuf, audioBufferInfo)
                            lastAudioPtsUs = audioBufferInfo.presentationTimeUs
                        } else {
                            // Queue until muxer started
                            val bytes = ByteArray(audioBufferInfo.size)
                            outBuf.position(audioBufferInfo.offset)
                            outBuf.get(bytes)
                            val infoCopy = MediaCodec.BufferInfo().apply {
                                set(0, bytes.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                            }
                            pendingAudioFrames.add(PendingFrame(bytes, infoCopy))
                        }
                    }

                    if (isEos) {
                        audioEncoderDone = true
                    }
                    audioEncoder.releaseOutputBuffer(outIndex, false)
                }

                // 3. Write video frames if muxer is started
                if (muxerStarted && !videoDone && (audioEncoderDone || lastVideoPtsUs <= lastAudioPtsUs + 100_000L)) {
                    val sampleSize = extractor.readSampleData(videoBuffer, 0)
                    if (sampleSize < 0) {
                        videoDone = true
                    } else {
                        videoBufferInfo.offset = 0
                        videoBufferInfo.size = sampleSize
                        videoBufferInfo.presentationTimeUs = extractor.sampleTime
                        videoBufferInfo.flags = extractor.sampleFlags
                        lastVideoPtsUs = videoBufferInfo.presentationTimeUs

                        muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoBufferInfo)
                        extractor.advance()
                    }
                }

                // Progress update
                if (totalDurationUs > 0) {
                    val maxPts = maxOf(lastVideoPtsUs, lastAudioPtsUs)
                    val progress = (0.10f + 0.82f * (maxPts.toFloat() / totalDurationUs.toFloat())).coerceIn(0.10f, 0.92f)
                    onProgress(progress, "Muxing video with muted instruments (${(progress * 100).toInt()}%)...")
                }
            }
        } finally {
            try { audioEncoder.stop() } catch (_: Exception) {}
            try { audioEncoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Fallback for audio-only media: generates a 16:9 H.264 video with an ambient studio backdrop.
     */
    private suspend fun generateStudioVideoWithVocalAudio(
        cachedVocalPcm: ShortArray?,
        reader: CachedPcmReader?,
        sampleRate: Int,
        channels: Int,
        totalAudioDurationUs: Long,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val width = 640
        val height = 360
        val frameRate = 24
        val videoCodecFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(videoCodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder.start()

        val audioCodecFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(audioCodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val videoBufferInfo = MediaCodec.BufferInfo()
        val audioBufferInfo = MediaCodec.BufferInfo()

        // Generate static studio YUV frame
        val yuvFrame = ByteArray(width * height * 3 / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val grad = (24 + (x.toFloat() / width * 22f)).toInt().toByte()
                yuvFrame[y * width + x] = grad
            }
        }
        val uvOffset = width * height
        for (i in uvOffset until yuvFrame.size step 2) {
            yuvFrame[i] = 138.toByte()     // U (cyan tint)
            yuvFrame[i + 1] = 132.toByte() // V (purple tint)
        }

        var pcmSampleOffset = 0L
        val totalPcmSamples = reader?.totalSamples ?: cachedVocalPcm?.size?.toLong() ?: 0L
        val tempShortChunk = ShortArray(8192)
        var audioInputDone = false
        var audioEncoderDone = false
        var videoFrameIndex = 0
        val totalVideoFrames = ((totalAudioDurationUs * frameRate) / 1_000_000L).toInt().coerceAtLeast(frameRate)
        var videoInputDone = false
        var videoEncoderDone = false

        try {
            while (currentCoroutineContext().isActive && (!audioEncoderDone || !videoEncoderDone)) {
                // Audio input
                if (!audioInputDone) {
                    val inIdx = audioEncoder.dequeueInputBuffer(1000L)
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remainingSamples = (totalPcmSamples - pcmSampleOffset).coerceAtLeast(0L)
                        val shortsToRead = minOf(inBuf.remaining() / 2, remainingSamples.toInt())
                        if (shortsToRead > 0) {
                            if (reader != null) {
                                val bufferToUse = if (shortsToRead <= tempShortChunk.size) tempShortChunk else ShortArray(shortsToRead)
                                val read = reader.readSlice(pcmSampleOffset, shortsToRead, bufferToUse)
                                for (i in 0 until read) {
                                    inBuf.putShort(bufferToUse[i])
                                }
                            } else if (cachedVocalPcm != null) {
                                val off = pcmSampleOffset.toInt()
                                for (i in 0 until shortsToRead) {
                                    inBuf.putShort(cachedVocalPcm[off + i])
                                }
                            }
                            val pts = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            pcmSampleOffset += shortsToRead
                            audioEncoder.queueInputBuffer(inIdx, 0, shortsToRead * 2, pts, 0)
                        } else {
                            val pts = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            audioEncoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        }
                    }
                }

                // Video input
                if (!videoInputDone) {
                    val inIdx = videoEncoder.dequeueInputBuffer(1000L)
                    if (inIdx >= 0) {
                        if (videoFrameIndex < totalVideoFrames) {
                            val inBuf = videoEncoder.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            inBuf.put(yuvFrame)
                            val pts = (videoFrameIndex.toLong() * 1_000_000L) / frameRate
                            videoEncoder.queueInputBuffer(inIdx, 0, yuvFrame.size, pts, 0)
                            videoFrameIndex++
                        } else {
                            val pts = (videoFrameIndex.toLong() * 1_000_000L) / frameRate
                            videoEncoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            videoInputDone = true
                        }
                    }
                }

                // Format changes & draining
                val aOut = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 1000L)
                if (aOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerAudioTrack = muxer.addTrack(audioEncoder.outputFormat)
                    if (muxerVideoTrack >= 0 && !muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (aOut >= 0) {
                    if (muxerStarted && (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && audioBufferInfo.size > 0) {
                        muxer.writeSampleData(muxerAudioTrack, audioEncoder.getOutputBuffer(aOut)!!, audioBufferInfo)
                    }
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioEncoderDone = true
                    audioEncoder.releaseOutputBuffer(aOut, false)
                }

                val vOut = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 1000L)
                if (vOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerVideoTrack = muxer.addTrack(videoEncoder.outputFormat)
                    if (muxerAudioTrack >= 0 && !muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (vOut >= 0) {
                    if (muxerStarted && (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoBufferInfo.size > 0) {
                        muxer.writeSampleData(muxerVideoTrack, videoEncoder.getOutputBuffer(vOut)!!, videoBufferInfo)
                    }
                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoEncoderDone = true
                    videoEncoder.releaseOutputBuffer(vOut, false)
                }

                val prog = (videoFrameIndex.toFloat() / totalVideoFrames.toFloat() * 0.85f + 0.10f).coerceIn(0.10f, 0.95f)
                onProgress(prog, "Encoding video with muted instruments (${(prog * 100).toInt()}%)...")
            }
        } finally {
            try { videoEncoder.stop() } catch (_: Exception) {}
            try { videoEncoder.release() } catch (_: Exception) {}
            try { audioEncoder.stop() } catch (_: Exception) {}
            try { audioEncoder.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun saveToMediaStore(videoFile: File, displayName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VocalPlayer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = try {
            resolver.insert(collection, contentValues)
        } catch (e: Exception) {
            Log.w(tag, "Failed to insert video into MediaStore: ${e.message}")
            null
        } ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(videoFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            Log.i(tag, "Successfully exported video to MediaStore: $itemUri")
            itemUri
        } catch (e: Exception) {
            Log.e(tag, "Failed to write exported video to MediaStore: ${e.message}", e)
            null
        }
    }

    fun getFileProviderUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
