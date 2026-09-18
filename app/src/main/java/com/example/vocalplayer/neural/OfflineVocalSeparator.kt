package com.example.vocalplayer.neural

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.vocalplayer.audio.DecodedAudioSource
import com.example.vocalplayer.audio.MediaAudioDecoder
import com.example.vocalplayer.cache.VocalCacheManager
import com.example.vocalplayer.dsp.DemucsSpectrogramTransformer
import com.example.vocalplayer.dsp.STFT
import com.example.vocalplayer.dsp.SpleeterSpectrogramTransformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.min

/**
 * High-performance offline vocal extraction engine.
 * Decodes entire media tracks offline and extracts high-fidelity vocal stems using
 * quantized Demucs (or Spleeter) ONNX models, saving results to VocalCacheManager.
 *
 * This satisfies the golden rule of source separation on mobile:
 * "Process offline — extract vocals once, cache for playback"
 * Eliminating all real-time lag, buffer underruns, and CPU bottlenecks during video playback.
 */
class OfflineVocalSeparator(
    private val context: Context,
    private val cacheManager: VocalCacheManager,
    private val modelManager: ModelManager
) {
    private val tag = "OfflineVocalSeparator"
    private val decoder = MediaAudioDecoder(context)
    private val demucsTransformer = DemucsSpectrogramTransformer()
    private val spleeterTransformer = SpleeterSpectrogramTransformer(nFft = 4096, hopLength = 1024, dimF = 1024, dimT = 512)
    private val onnxRunner = OnnxModelRunner(context)

    data class ExtractionProgress(
        val stage: String,
        val progress: Float, // 0.0 to 1.0
        val isCompleted: Boolean = false,
        val errorMessage: String? = null,
        val isChunkReady: Boolean = false,
        val streamedFrames: Long = 0L,
        val streamedDurationMs: Long = 0L,
        val totalDurationMs: Long = 0L
    )

    /**
     * Extracts vocal stem offline for [mediaUri] and saves to cache.
     * Streams separated vocal chunks to disk incrementally so playback can begin immediately.
     * Invokes [onProgress] as work progresses and whenever a new chunk is ready.
     */
    suspend fun extractAndCacheVocals(
        mediaUri: Uri,
        onProgress: (ExtractionProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.Default) {
        if (cacheManager.isVocalCached(mediaUri) && !cacheManager.isVocalStreaming(mediaUri)) {
            Log.i(tag, "Media already cached: $mediaUri")
            onProgress(ExtractionProgress("Vocals already cached", 1.0f, isCompleted = true, isChunkReady = true))
            return@withContext true
        }

        try {
            onProgress(ExtractionProgress("Decoding audio track...", 0.05f))

            // Step 1: Decode media to memory-efficient streaming PCM audio
            val decodedSource = decoder.streamDecodedAudio(mediaUri) { decodeProg ->
                onProgress(ExtractionProgress("Decoding audio (${(decodeProg * 100).toInt()}%)...", 0.05f + decodeProg * 0.10f))
            }

            try {
                currentCoroutineContext().ensureActive()

                val sampleRate = decodedSource.sampleRate
                val channelCount = decodedSource.channelCount
                val totalShorts = decodedSource.totalSamples

                if (totalShorts < 1000) {
                    Log.w(tag, "Audio track too short to process")
                    return@withContext false
                }

                val numFrames = (if (channelCount >= 2) totalShorts / 2 else totalShorts).toInt()
                val totalDurationMs = (numFrames.toLong() * 1000L) / sampleRate.coerceAtLeast(1)

                // Initialize disk streaming session so player can read immediately as chunks land
                cacheManager.startStreamingSession(mediaUri)

                // Step 2: Determine and initialize best available neural / spectral source separation model
                val demucsProfile = modelManager.getAllModels().find { it.id == NeuralModelProfile.DEMUCS_INT8.id }
                val demucsPath = demucsProfile?.modelPath ?: modelManager.downloader.getSecureModelFile("htdemucs_int8").absolutePath

                val demucsLoaded = if (File(demucsPath).exists()) {
                    onProgress(ExtractionProgress("Loading Demucs Hybrid Transformer ONNX model...", 0.18f, totalDurationMs = totalDurationMs))
                    onnxRunner.loadDemucsModel(demucsPath)
                } else false

                val spleeterLoaded = if (!demucsLoaded) {
                    onProgress(ExtractionProgress("Loading Spleeter 2-stem ONNX model...", 0.18f, totalDurationMs = totalDurationMs))
                    try {
                        val downloader = modelManager.downloader
                        val vocalsFile = downloader.getSecureModelFile("spleeter_2stems_vocals").let {
                            if (it.exists() && it.length() > 1024) it
                            else downloader.copyAssetModelIfPresent("models/spleeter_2stems_vocals.onnx", "spleeter_2stems_vocals")
                        }
                        val accFile = downloader.getSecureModelFile("spleeter_2stems_accompaniment").let {
                            if (it.exists() && it.length() > 1024) it
                            else downloader.copyAssetModelIfPresent("models/spleeter_2stems_accompaniment.onnx", "spleeter_2stems_accompaniment")
                        }
                        if (vocalsFile != null && vocalsFile.exists()) {
                            onnxRunner.loadSpleeterModels(
                                vocalsPath = vocalsFile.absolutePath,
                                accompanimentPath = accFile?.absolutePath,
                                threadCount = 4
                            )
                        } else false
                    } catch (e: Exception) {
                        Log.w(tag, "Spleeter asset staging error: ${e.message}")
                        false
                    }
                } else false

                when {
                    demucsLoaded -> {
                        Log.i(tag, "Running Demucs v4 streaming separation on $numFrames frames...")
                        separateWithDemucsStream(mediaUri, decodedSource, numFrames, totalDurationMs, onProgress)
                    }
                    spleeterLoaded -> {
                        Log.i(tag, "Running Deezer Spleeter 2-stem streaming isolation on $numFrames frames...")
                        separateWithSpleeterStream(mediaUri, decodedSource, numFrames, totalDurationMs, onProgress)
                    }
                    else -> {
                        Log.i(tag, "Running high-precision harmonic STFT spectral streaming isolation on $numFrames frames...")
                        separateWithSpectralStream(mediaUri, decodedSource, numFrames, totalDurationMs, onProgress)
                    }
                }

                currentCoroutineContext().ensureActive()
                cacheManager.finishStreamingSession(mediaUri)

                onProgress(
                    ExtractionProgress(
                        stage = "Vocal isolation complete!",
                        progress = 1.0f,
                        isCompleted = true,
                        isChunkReady = true,
                        streamedFrames = numFrames.toLong(),
                        streamedDurationMs = totalDurationMs,
                        totalDurationMs = totalDurationMs
                    )
                )
                Log.i(tag, "Successfully processed and cached vocals for $mediaUri")
                true
            } finally {
                decodedSource.close()
            }
        } catch (e: CancellationException) {
            Log.i(tag, "Vocal extraction cancelled for $mediaUri")
            cacheManager.cancelStreamingSession(mediaUri)
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error during offline vocal extraction: ${e.message}", e)
            cacheManager.cancelStreamingSession(mediaUri)
            onProgress(ExtractionProgress("Extraction error: ${e.message}", 0f, isCompleted = false, errorMessage = e.message))
            false
        }
    }

    /**
     * Slices audio directly from DecodedAudioSource into 7.8-second (343,980 sample) segments,
     * runs Demucs neural inference, performs smooth crossfade synthesis, and writes each chunk
     * directly to disk cache.
     * Prevents giant audio FloatArrays in memory while allowing immediate playback of separated chunks.
     */
    private suspend fun separateWithDemucsStream(
        mediaUri: Uri,
        source: DecodedAudioSource,
        numFrames: Int,
        totalDurationMs: Long,
        onProgress: (ExtractionProgress) -> Unit
    ) {
        val segmentLen = demucsTransformer.segmentSamples // 343,980
        val fadeLen = 44100 // 1-second overlap between consecutive segments
        val hopLen = segmentLen - fadeLen // 299,880
        val totalSegments = ((numFrames - fadeLen).coerceAtLeast(0) / hopLen) + 1

        val segLeft = FloatArray(segmentLen)
        val segRight = FloatArray(segmentLen)

        val prevTailL = FloatArray(fadeLen)
        val prevTailR = FloatArray(fadeLen)
        var hasPrevTail = false

        var startFrame = 0
        var segIndex = 0
        var totalStreamedFrames = 0L

        while (startFrame < numFrames) {
            currentCoroutineContext().ensureActive()
            segLeft.fill(0f)
            segRight.fill(0f)

            val available = min(segmentLen, numFrames - startFrame)
            source.readFramesToFloats(startFrame.toLong(), available, segLeft, segRight)

            val inputs = demucsTransformer.forwardToDemucsTensors(segLeft, segRight)
            val outputWav = onnxRunner.runDemucs(inputs.mixTensor, inputs.magTensor)

            val (rawVocalL, rawVocalR) = if (outputWav != null) {
                demucsTransformer.extractVocals(outputWav)
            } else {
                separateStereoSpectral(segLeft.copyOf(available), segRight.copyOf(available))
            }

            val vocalL = rawVocalL.copyOf(available)
            val vocalR = rawVocalR.copyOf(available)

            // Crossfade overlap with previous segment's tail
            if (hasPrevTail) {
                val overlapCount = min(fadeLen, available)
                for (i in 0 until overlapCount) {
                    val w = (0.5 * (1.0 - cos(Math.PI * i / fadeLen))).toFloat()
                    vocalL[i] = prevTailL[i] * (1.0f - w) + vocalL[i] * w
                    vocalR[i] = prevTailR[i] * (1.0f - w) + vocalR[i] * w
                }
            }

            val isFinalSegment = (startFrame + hopLen >= numFrames) || (available <= hopLen)

            if (!isFinalSegment) {
                // Frames 0 until hopLen are completely finished
                val framesToWrite = min(hopLen, available)
                val chunkShorts = ShortArray(framesToWrite * 2)
                for (i in 0 until framesToWrite) {
                    chunkShorts[i * 2] = (vocalL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                    chunkShorts[i * 2 + 1] = (vocalR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                }

                cacheManager.appendVocalChunk(mediaUri, chunkShorts, 0, chunkShorts.size, isFinal = false)
                totalStreamedFrames += framesToWrite

                // Save next tail
                for (i in 0 until fadeLen) {
                    val srcIdx = hopLen + i
                    prevTailL[i] = if (srcIdx < available) vocalL[srcIdx] else 0f
                    prevTailR[i] = if (srcIdx < available) vocalR[srcIdx] else 0f
                }
                hasPrevTail = true
            } else {
                // Last segment: all remaining frames are finished
                val chunkShorts = ShortArray(available * 2)
                for (i in 0 until available) {
                    chunkShorts[i * 2] = (vocalL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                    chunkShorts[i * 2 + 1] = (vocalR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                }
                cacheManager.appendVocalChunk(mediaUri, chunkShorts, 0, chunkShorts.size, isFinal = true)
                totalStreamedFrames += available
            }

            segIndex++
            val prog = (0.20f + (segIndex.toFloat() / totalSegments.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            val streamedMs = (totalStreamedFrames * 1000L) / 44100L
            onProgress(
                ExtractionProgress(
                    stage = "Demucs Neural Isolation (${segIndex}/$totalSegments)...",
                    progress = prog,
                    isChunkReady = true,
                    streamedFrames = totalStreamedFrames,
                    streamedDurationMs = streamedMs,
                    totalDurationMs = totalDurationMs
                )
            )

            if (isFinalSegment) break
            startFrame += hopLen
        }
    }

    /**
     * Executes Deezer Spleeter 2-stem ONNX neural vocal isolation in sequential segments directly from DecodedAudioSource.
     * Streams separated chunks to disk immediately so playback can begin after chunk 0.
     */
    private suspend fun separateWithSpleeterStream(
        mediaUri: Uri,
        source: DecodedAudioSource,
        numFrames: Int,
        totalDurationMs: Long,
        onProgress: (ExtractionProgress) -> Unit
    ) {
        val chunkSamples = 524288 // 512 frames * 1024 hop = ~11.88 seconds
        val totalChunks = ((numFrames + chunkSamples - 1) / chunkSamples).coerceAtLeast(1)

        val chunkL = FloatArray(chunkSamples)
        val chunkR = FloatArray(chunkSamples)

        var startFrame = 0
        var chunkIndex = 0
        var totalStreamedFrames = 0L

        while (startFrame < numFrames) {
            currentCoroutineContext().ensureActive()
            val chunkLen = min(chunkSamples, numFrames - startFrame)
            val isFinal = (startFrame + chunkLen >= numFrames)

            chunkL.fill(0f)
            chunkR.fill(0f)
            source.readFramesToFloats(startFrame.toLong(), chunkLen, chunkL, chunkR)

            val sliceL = chunkL.copyOf(chunkLen)
            val sliceR = chunkR.copyOf(chunkLen)

            val (recL, recR) = try {
                val stftData = spleeterTransformer.forwardToSpleeterTensor(sliceL, sliceR)
                val vocalsOutput = onnxRunner.runSpleeterVocals(stftData.magTensor, stftData.numChunks)

                if (vocalsOutput != null) {
                    val accOutput = onnxRunner.runSpleeterAccompaniment(stftData.magTensor, stftData.numChunks)
                    spleeterTransformer.inverseFromSpleeterTensor(
                        stftData = stftData,
                        vocalsOutput = vocalsOutput,
                        accompanimentOutput = accOutput,
                        targetLength = chunkLen,
                        vocalMaskStrength = 1.0f
                    )
                } else {
                    Log.w(tag, "Spleeter ONNX returned null on chunk $chunkIndex, falling back to spectral isolation")
                    separateStereoSpectral(sliceL, sliceR)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in Spleeter chunk $chunkIndex: ${e.message}", e)
                separateStereoSpectral(sliceL, sliceR)
            }

            val chunkShorts = ShortArray(chunkLen * 2)
            for (i in 0 until chunkLen) {
                chunkShorts[i * 2] = (recL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                chunkShorts[i * 2 + 1] = (recR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            cacheManager.appendVocalChunk(mediaUri, chunkShorts, 0, chunkShorts.size, isFinal = isFinal)
            totalStreamedFrames += chunkLen

            chunkIndex++
            val prog = (0.20f + (chunkIndex.toFloat() / totalChunks.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            val streamedMs = (totalStreamedFrames * 1000L) / 44100L
            onProgress(
                ExtractionProgress(
                    stage = "Spleeter Neural Isolation (${chunkIndex}/$totalChunks)...",
                    progress = prog,
                    isChunkReady = true,
                    streamedFrames = totalStreamedFrames,
                    streamedDurationMs = streamedMs,
                    totalDurationMs = totalDurationMs
                )
            )

            startFrame += chunkSamples
        }
    }

    /**
     * High-precision harmonic STFT spectral vocal isolation with streamed chunks.
     * Immediately flushes each 1.48s chunk to disk so playback starts in <100ms.
     */
    private suspend fun separateWithSpectralStream(
        mediaUri: Uri,
        source: DecodedAudioSource,
        numFrames: Int,
        totalDurationMs: Long,
        onProgress: (ExtractionProgress) -> Unit
    ) {
        val chunkSize = 65536
        val chunkL = FloatArray(chunkSize)
        val chunkR = FloatArray(chunkSize)
        var start = 0
        var chunkIdx = 0
        var totalStreamedFrames = 0L
        val totalChunks = ((numFrames + chunkSize - 1) / chunkSize).coerceAtLeast(1)

        while (start < numFrames) {
            currentCoroutineContext().ensureActive()
            val len = min(chunkSize, numFrames - start)
            val isFinal = (start + len >= numFrames)

            chunkL.fill(0f)
            chunkR.fill(0f)
            source.readFramesToFloats(start.toLong(), len, chunkL, chunkR)

            val (recL, recR) = separateStereoSpectral(chunkL.copyOf(len), chunkR.copyOf(len))

            val chunkShorts = ShortArray(len * 2)
            for (i in 0 until len) {
                chunkShorts[i * 2] = (recL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                chunkShorts[i * 2 + 1] = (recR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            cacheManager.appendVocalChunk(mediaUri, chunkShorts, 0, chunkShorts.size, isFinal = isFinal)
            totalStreamedFrames += len

            chunkIdx++
            val prog = (0.20f + (chunkIdx.toFloat() / totalChunks.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            val streamedMs = (totalStreamedFrames * 1000L) / 44100L
            onProgress(
                ExtractionProgress(
                    stage = "Spectral Harmonic Vocal Isolation (${(prog * 100).toInt()}%)...",
                    progress = prog,
                    isChunkReady = true,
                    streamedFrames = totalStreamedFrames,
                    streamedDurationMs = streamedMs,
                    totalDurationMs = totalDurationMs
                )
            )

            start += len
        }
    }

    /**
     * Core multi-band STFT spectral filter for stereo audio.
     */
    private fun separateStereoSpectral(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int = 44100
    ): Pair<FloatArray, FloatArray> {
        val stft = STFT(nFft = 2048, hopLength = 512)
        val stftL = stft.forward(left)
        val stftR = stft.forward(right)
        val numBins = stftL.numBins
        val numFrames = stftL.numFrames
        val binFreqHz = sampleRate.toFloat() / (2f * (numBins - 1))

        val vocalMagsL = FloatArray(stftL.magnitudes.size)
        val vocalMagsR = FloatArray(stftR.magnitudes.size)

        for (bin in 0 until numBins) {
            val freq = bin * binFreqHz
            val vocalPrior = when {
                freq < 90f -> 0.01f // Sub-bass, 808s, kick rumble
                freq in 90f..220f -> 0.05f + 0.80f * ((freq - 90f) / 130f)
                freq in 220f..3500f -> 1.0f // Vocal core formant band
                freq in 3500f..5500f -> 1.0f - 0.65f * ((freq - 3500f) / 2000f)
                freq in 5500f..8500f -> 0.35f - 0.33f * ((freq - 5500f) / 3000f)
                else -> 0.01f // Cymbals, hi-hats, high sizzle
            }

            for (frame in 0 until numFrames) {
                val idx = frame * numBins + bin
                val magL = stftL.magnitudes[idx]
                val magR = stftR.magnitudes[idx]

                val midMag = 0.5f * (magL + magR)
                val sideMag = 0.5f * kotlin.math.abs(magL - magR)

                // Phantom-center coherence: lead vocals are centered, instruments are stereo panned
                val centerCoherence = (midMag / (midMag + 2.4f * sideMag + 1e-5f)).coerceIn(0f, 1f)
                val centerWeight = centerCoherence * centerCoherence

                val vocalScore = centerWeight * vocalPrior

                // Steep rejection mask: instruments with low vocal score are squelched
                val mask = if (vocalScore < 0.15f) {
                    0.005f
                } else {
                    val norm = (vocalScore - 0.15f) / 0.85f
                    (norm * norm).coerceIn(0.005f, 1.0f)
                }

                vocalMagsL[idx] = magL * mask
                vocalMagsR[idx] = magR * mask
            }
        }

        val recL = stft.inverse(vocalMagsL, stftL.phases, numFrames).copyOf(left.size)
        val recR = stft.inverse(vocalMagsR, stftR.phases, numFrames).copyOf(right.size)
        return Pair(recL, recR)
    }

    fun release() {
        onnxRunner.release()
    }
}
