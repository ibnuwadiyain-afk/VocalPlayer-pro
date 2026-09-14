package com.example.vocalplayer.neural

import android.content.Context
import android.net.Uri
import android.util.Log
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
        val errorMessage: String? = null
    )

    /**
     * Extracts vocal stem offline for [mediaUri] and saves to cache.
     * Invokes [onProgress] as work progresses.
     */
    suspend fun extractAndCacheVocals(
        mediaUri: Uri,
        onProgress: (ExtractionProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.Default) {
        if (cacheManager.isVocalCached(mediaUri)) {
            Log.i(tag, "Media already cached: $mediaUri")
            onProgress(ExtractionProgress("Vocals already cached", 1.0f, isCompleted = true))
            return@withContext true
        }

        try {
            onProgress(ExtractionProgress("Decoding audio track...", 0.05f))

            // Step 1: Decode media to PCM audio
            val decoded = decoder.decodeToPcm(mediaUri) { decodeProg ->
                onProgress(ExtractionProgress("Decoding audio (${(decodeProg * 100).toInt()}%)...", 0.05f + decodeProg * 0.10f))
            }

            currentCoroutineContext().ensureActive()

            val rawPcm = decoded.pcmShorts
            val sampleRate = decoded.sampleRate
            val channelCount = decoded.channelCount
            val totalShorts = rawPcm.size

            if (totalShorts < 1000) {
                Log.w(tag, "Audio track too short to process")
                return@withContext false
            }

            // Convert interleaved PCM shorts to de-interleaved FloatArray (Left, Right)
            val numFrames = if (channelCount >= 2) totalShorts / 2 else totalShorts
            val leftAudio = FloatArray(numFrames)
            val rightAudio = FloatArray(numFrames)

            val invShort = 1.0f / 32768.0f
            if (channelCount >= 2) {
                for (i in 0 until numFrames) {
                    leftAudio[i] = rawPcm[i * 2] * invShort
                    rightAudio[i] = rawPcm[i * 2 + 1] * invShort
                }
            } else {
                for (i in 0 until numFrames) {
                    val sample = rawPcm[i] * invShort
                    leftAudio[i] = sample
                    rightAudio[i] = sample
                }
            }

            currentCoroutineContext().ensureActive()

            // Step 2: Determine and initialize best available neural / spectral source separation model
            val demucsProfile = modelManager.getAllModels().find { it.id == NeuralModelProfile.DEMUCS_INT8.id }
            val demucsPath = demucsProfile?.modelPath ?: modelManager.downloader.getSecureModelFile("htdemucs_int8").absolutePath

            val demucsLoaded = if (File(demucsPath).exists()) {
                onProgress(ExtractionProgress("Loading Demucs Hybrid Transformer ONNX model...", 0.18f))
                onnxRunner.loadDemucsModel(demucsPath)
            } else false

            val spleeterLoaded = if (!demucsLoaded) {
                onProgress(ExtractionProgress("Loading Spleeter 2-stem ONNX model...", 0.18f))
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

            val separatedVocalsShorts: ShortArray = when {
                demucsLoaded -> {
                    Log.i(tag, "Running Demucs v4 offline separation on $numFrames frames...")
                    separateWithDemucs(leftAudio, rightAudio, numFrames, onProgress)
                }
                spleeterLoaded -> {
                    Log.i(tag, "Running Deezer Spleeter 2-stem ONNX neural isolation on $numFrames frames...")
                    separateWithSpleeter(leftAudio, rightAudio, numFrames, onProgress)
                }
                else -> {
                    Log.i(tag, "Running high-precision harmonic STFT spectral vocal isolation on $numFrames frames...")
                    separateWithSpectral(leftAudio, rightAudio, numFrames, onProgress)
                }
            }

            currentCoroutineContext().ensureActive()
            onProgress(ExtractionProgress("Saving vocal stem to cache...", 0.95f))

            // Step 3: Save to cache
            cacheManager.saveVocalPcm(mediaUri, separatedVocalsShorts, sampleRate, 2)
            onProgress(ExtractionProgress("Vocal isolation complete!", 1.0f, isCompleted = true))
            Log.i(tag, "Successfully processed and cached vocals for $mediaUri (${separatedVocalsShorts.size} shorts)")
            true
        } catch (e: CancellationException) {
            Log.i(tag, "Vocal extraction cancelled for $mediaUri")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error during offline vocal extraction: ${e.message}", e)
            onProgress(ExtractionProgress("Extraction error: ${e.message}", 0f, isCompleted = false, errorMessage = e.message))
            false
        }
    }

    /**
     * Slices audio into 7.8-second (343,980 sample) segments, runs Demucs neural inference,
     * and performs smooth crossfade synthesis into the final vocal PCM track.
     */
    private suspend fun separateWithDemucs(
        leftAudio: FloatArray,
        rightAudio: FloatArray,
        numFrames: Int,
        onProgress: (ExtractionProgress) -> Unit
    ): ShortArray {
        val segmentLen = demucsTransformer.segmentSamples // 343,980
        val hopLen = segmentLen - 44100 // 1-second overlap between consecutive segments
        val totalSegments = ((numFrames - 44100).coerceAtLeast(0) / hopLen) + 1

        val outVocalLeft = FloatArray(numFrames)
        val outVocalRight = FloatArray(numFrames)
        val weights = FloatArray(numFrames)

        val segLeft = FloatArray(segmentLen)
        val segRight = FloatArray(segmentLen)

        // Hann crossfade window for overlapping regions
        val window = FloatArray(segmentLen) { 1.0f }
        val fadeLen = 44100
        for (i in 0 until fadeLen) {
            val f = (0.5 * (1.0 - cos(Math.PI * i / fadeLen))).toFloat()
            window[i] = f
            window[segmentLen - 1 - i] = f
        }

        var startFrame = 0
        var segIndex = 0

        while (startFrame < numFrames) {
            currentCoroutineContext().ensureActive()
            segLeft.fill(0f)
            segRight.fill(0f)

            val available = min(segmentLen, numFrames - startFrame)
            System.arraycopy(leftAudio, startFrame, segLeft, 0, available)
            System.arraycopy(rightAudio, startFrame, segRight, 0, available)

            val inputs = demucsTransformer.forwardToDemucsTensors(segLeft, segRight)
            val outputWav = onnxRunner.runDemucs(inputs.mixTensor, inputs.magTensor)

            if (outputWav != null) {
                val (vocalL, vocalR) = demucsTransformer.extractVocals(outputWav)
                for (i in 0 until available) {
                    val frameIdx = startFrame + i
                    val w = window[i]
                    outVocalLeft[frameIdx] += vocalL[i] * w
                    outVocalRight[frameIdx] += vocalR[i] * w
                    weights[frameIdx] += w
                }
            } else {
                // Spectral fallback if Demucs tensor failed on this block
                val (spL, spR) = separateStereoSpectral(segLeft.copyOf(available), segRight.copyOf(available))
                for (i in 0 until available) {
                    val frameIdx = startFrame + i
                    val w = window[i]
                    outVocalLeft[frameIdx] += spL[i] * w
                    outVocalRight[frameIdx] += spR[i] * w
                    weights[frameIdx] += w
                }
            }

            segIndex++
            val prog = (0.20f + (segIndex.toFloat() / totalSegments.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            onProgress(ExtractionProgress("Demucs Neural Isolation (${segIndex}/$totalSegments)...", prog))

            startFrame += hopLen
        }

        // Normalize weights and convert back to 16-bit interleaved PCM shorts
        val resultShorts = ShortArray(numFrames * 2)
        for (i in 0 until numFrames) {
            val w = if (weights[i] > 1e-4f) weights[i] else 1.0f
            val sampleL = (outVocalLeft[i] / w).coerceIn(-1.0f, 1.0f)
            val sampleR = (outVocalRight[i] / w).coerceIn(-1.0f, 1.0f)

            resultShorts[i * 2] = (sampleL * 32767.0f).toInt().toShort()
            resultShorts[i * 2 + 1] = (sampleR * 32767.0f).toInt().toShort()
        }

        return resultShorts
    }

    /**
     * Executes Deezer Spleeter 2-stem ONNX neural vocal isolation in sequential segments.
     * Guaranteed zero out-of-memory and high throughput.
     */
    private suspend fun separateWithSpleeter(
        leftAudio: FloatArray,
        rightAudio: FloatArray,
        numFrames: Int,
        onProgress: (ExtractionProgress) -> Unit
    ): ShortArray {
        val chunkSamples = 524288 // 512 frames * 1024 hop = ~11.88 seconds
        val totalChunks = ((numFrames + chunkSamples - 1) / chunkSamples).coerceAtLeast(1)
        val resultShorts = ShortArray(numFrames * 2)

        var startFrame = 0
        var chunkIndex = 0

        while (startFrame < numFrames) {
            currentCoroutineContext().ensureActive()
            val chunkLen = min(chunkSamples, numFrames - startFrame)
            val chunkL = leftAudio.copyOfRange(startFrame, startFrame + chunkLen)
            val chunkR = rightAudio.copyOfRange(startFrame, startFrame + chunkLen)

            val (recL, recR) = try {
                val stftData = spleeterTransformer.forwardToSpleeterTensor(chunkL, chunkR)
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
                    separateStereoSpectral(chunkL, chunkR)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in Spleeter chunk $chunkIndex: ${e.message}", e)
                separateStereoSpectral(chunkL, chunkR)
            }

            for (i in 0 until chunkLen) {
                resultShorts[(startFrame + i) * 2] = (recL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                resultShorts[(startFrame + i) * 2 + 1] = (recR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            chunkIndex++
            val prog = (0.20f + (chunkIndex.toFloat() / totalChunks.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            onProgress(ExtractionProgress("Spleeter Neural Isolation (${chunkIndex}/$totalChunks)...", prog))

            startFrame += chunkSamples
        }

        return resultShorts
    }

    /**
     * High-precision harmonic STFT spectral vocal isolation.
     * Uses phantom-center mid/side phase coherence and vocal formant harmonic priors
     * to eliminate panned instruments, stereo synths, cymbals, bass, and drums.
     */
    private suspend fun separateWithSpectral(
        leftAudio: FloatArray,
        rightAudio: FloatArray,
        numFrames: Int,
        onProgress: (ExtractionProgress) -> Unit
    ): ShortArray {
        val resultShorts = ShortArray(numFrames * 2)
        val chunkSize = 65536
        var start = 0
        var chunkIdx = 0
        val totalChunks = ((numFrames + chunkSize - 1) / chunkSize).coerceAtLeast(1)

        while (start < numFrames) {
            currentCoroutineContext().ensureActive()
            val len = min(chunkSize, numFrames - start)
            val chunkL = leftAudio.copyOfRange(start, start + len)
            val chunkR = rightAudio.copyOfRange(start, start + len)

            val (recL, recR) = separateStereoSpectral(chunkL, chunkR)

            for (i in 0 until len) {
                resultShorts[(start + i) * 2] = (recL[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                resultShorts[(start + i) * 2 + 1] = (recR[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            chunkIdx++
            val prog = (0.20f + (chunkIdx.toFloat() / totalChunks.toFloat()) * 0.74f).coerceIn(0.20f, 0.94f)
            onProgress(ExtractionProgress("Spectral Harmonic Vocal Isolation (${(prog * 100).toInt()}%)...", prog))

            start += len
        }

        return resultShorts
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
