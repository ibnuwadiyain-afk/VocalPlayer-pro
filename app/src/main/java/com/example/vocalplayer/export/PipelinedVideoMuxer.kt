package com.example.vocalplayer.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipelined Concurrent Video Muxer.
 * Merges separated vocal PCM audio chunks concurrently with video stream processing
 * as the neural source separation engine extracts them.
 *
 * When separation completes, the export video is ready immediately with virtually 0 waiting time.
 */
class PipelinedVideoMuxer(
    private val context: Context,
    private val sourceUri: Uri,
    val outputFile: File,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) : AutoCloseable {

    private val tag = "PipelinedVideoMuxer"

    // High throughput, backpressure-safe channel for PCM audio slices
    private val audioChunkChannel = Channel<ShortArray>(Channel.UNLIMITED)

    private val isClosed = AtomicBoolean(false)
    private val isCompletedSuccessfully = AtomicBoolean(false)
    private var workerJob: Job? = null

    @Volatile
    var totalFramesMuxed: Long = 0L
        private set

    @Volatile
    var totalDurationUs: Long = 0L
        private set

    @Volatile
    var isVideoMuxingDone: Boolean = false
        private set

    /**
     * Launches asynchronous background worker that encodes AAC audio chunks and muxes with video.
     */
    fun start(scope: CoroutineScope, onProgress: (Float, String) -> Unit = { _, _ -> }) {
        workerJob = scope.launch(Dispatchers.IO) {
            runPipeline(onProgress)
        }
    }

    /**
     * Appends a newly isolated PCM vocal chunk from the neural separation engine.
     */
    fun appendVocalChunk(chunk: ShortArray, isFinal: Boolean = false) {
        if (isClosed.get()) return
        if (chunk.isNotEmpty()) {
            audioChunkChannel.trySend(chunk.copyOf())
        }
        if (isFinal) {
            audioChunkChannel.close()
        }
    }

    /**
     * Signals that vocal separation has completed.
     */
    fun finishInput() {
        audioChunkChannel.close()
    }

    /**
     * Awaits pipeline completion.
     */
    suspend fun awaitCompletion(): Boolean {
        return try {
            workerJob?.join()
            isCompletedSuccessfully.get()
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error waiting for pipelined muxer: ${e.message}", e)
            false
        }
    }

    private suspend fun runPipeline(onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor = MediaExtractor()
            setExtractorDataSource(extractor, sourceUri)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            val hasVideo = videoTrackIndex >= 0 && videoFormat != null
            if (hasVideo) {
                extractor.selectTrack(videoTrackIndex)
            }

            val videoDurationUs = if (hasVideo && videoFormat!!.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            totalDurationUs = videoDurationUs

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerVideoTrack = -1
            if (hasVideo) {
                if (videoFormat!!.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                } else if (videoFormat.containsKey("rotation-degrees")) {
                    muxer.setOrientationHint(videoFormat.getInteger("rotation-degrees"))
                }
                muxerVideoTrack = muxer.addTrack(videoFormat)
            }

            val audioCodecFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioCodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            var muxerAudioTrack = -1
            val audioBufferInfo = MediaCodec.BufferInfo()

            class PendingFrame(val data: ByteArray, val info: MediaCodec.BufferInfo)
            val pendingAudioFrames = mutableListOf<PendingFrame>()

            val maxVideoBufSize = if (hasVideo && videoFormat!!.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(65536)
            } else 1024 * 1024
            val videoBuffer = ByteBuffer.allocateDirect(maxVideoBufSize)
            val videoBufferInfo = MediaCodec.BufferInfo()

            var pcmSampleOffset = 0L
            var audioInputDone = false
            var audioEncoderDone = false
            var videoDone = !hasVideo
            var lastVideoPtsUs = 0L
            var lastAudioPtsUs = 0L

            var currentShortChunk: ShortArray? = null
            var currentChunkOffset = 0

            while (isActive && (!audioEncoderDone || !videoDone)) {
                ensureActive()

                // 1. Supply Audio Input Buffer
                if (!audioInputDone) {
                    val inIdx = audioEncoder.dequeueInputBuffer(1000L)
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIdx)!!
                        inBuf.clear()

                        if (currentShortChunk == null || currentChunkOffset >= currentShortChunk!!.size) {
                            val pollResult = audioChunkChannel.tryReceive()
                            if (pollResult.isSuccess) {
                                currentShortChunk = pollResult.getOrNull()
                                currentChunkOffset = 0
                            } else if (pollResult.isClosed) {
                                currentShortChunk = null
                            }
                        }

                        if (currentShortChunk != null && currentChunkOffset < currentShortChunk!!.size) {
                            val remainingInChunk = currentShortChunk!!.size - currentChunkOffset
                            val maxShorts = inBuf.remaining() / 2
                            val toWrite = minOf(maxShorts, remainingInChunk)

                            for (i in 0 until toWrite) {
                                inBuf.putShort(currentShortChunk!![currentChunkOffset + i])
                            }
                            currentChunkOffset += toWrite

                            val ptsUs = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            pcmSampleOffset += toWrite
                            audioEncoder.queueInputBuffer(inIdx, 0, toWrite * 2, ptsUs, 0)
                        } else if (audioChunkChannel.isClosedForReceive && currentShortChunk == null) {
                            val ptsUs = (pcmSampleOffset * 1_000_000L) / (sampleRate * channels)
                            audioEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        } else {
                            // Waiting for next chunk from neural engine: release input buffer and delay lightly to prevent busy spin
                            audioEncoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            kotlinx.coroutines.delay(10L)
                        }
                    }
                }

                // 2. Drain Encoded Audio
                val outIdx = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 1000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        muxerAudioTrack = muxer.addTrack(audioEncoder.outputFormat)
                        muxer.start()
                        muxerStarted = true

                        for (pf in pendingAudioFrames) {
                            val buf = ByteBuffer.wrap(pf.data)
                            muxer.writeSampleData(muxerAudioTrack, buf, pf.info)
                        }
                        pendingAudioFrames.clear()
                    }
                } else if (outIdx >= 0) {
                    val outBuf = audioEncoder.getOutputBuffer(outIdx)!!
                    val isConfig = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (!isConfig && audioBufferInfo.size > 0) {
                        if (muxerStarted) {
                            muxer.writeSampleData(muxerAudioTrack, outBuf, audioBufferInfo)
                            lastAudioPtsUs = audioBufferInfo.presentationTimeUs
                        } else {
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
                    audioEncoder.releaseOutputBuffer(outIdx, false)
                }

                // 3. Concurrently Remux Video Frames up to current audio PTS
                if (hasVideo && muxerStarted && !videoDone && (audioEncoderDone || lastVideoPtsUs <= lastAudioPtsUs + 200_000L)) {
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
                        totalFramesMuxed++
                    }
                }

                if (totalDurationUs > 0) {
                    val maxPts = maxOf(lastVideoPtsUs, lastAudioPtsUs)
                    val prog = (maxPts.toFloat() / totalDurationUs.toFloat()).coerceIn(0f, 1f)
                    onProgress(prog, "Simultaneous video muxing (${(prog * 100).toInt()}% ready)...")
                }
            }

            isVideoMuxingDone = true
            isCompletedSuccessfully.set(true)
            Log.i(tag, "Simultaneous pipelined video muxing completed: $totalFramesMuxed video frames, ${outputFile.length() / 1024} KB")
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(tag, "Simultaneous video muxing error: ${e.message}", e)
            }
            isCompletedSuccessfully.set(false)
        } finally {
            try { audioEncoder?.stop() } catch (_: Exception) {}
            try { audioEncoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
        }
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

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            audioChunkChannel.close()
            workerJob?.cancel()
        }
    }
}
