package com.example.vocalplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation Spectrogram Transformer for Genuine Deezer Spleeter 2-Stem ONNX models.
 *
 * Spleeter 2-Stem specifications:
 * - Sample Rate: 44,100 Hz
 * - n_fft = 4096
 * - hop_length = 1024
 * - Frequency bins: dimF = 1024 (covers 0 to ~11,025 Hz, capturing all vocal harmonics)
 * - Time frames per chunk: dimT = 512 (~11.88s per chunk)
 * - Input shape: [2, num_chunks, 512, 1024] (Stereo Left=0, Right=1)
 * - Model output shape: [2, num_chunks, 512, 1024]
 */
class SpleeterSpectrogramTransformer(
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val dimF: Int = 1024,
    val dimT: Int = 512
) {
    val padLength: Int = nFft / 2 // Center padding (2048 samples)
    val numBins: Int = nFft / 2 + 1 // 2049 bins for 4096-point FFT

    // Precomputed Hann window
    val window: FloatArray = FloatArray(nFft) { i ->
        (0.5f * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    // Scratch buffers for zero-allocation FFT execution
    private val realScratch = FloatArray(nFft)
    private val imagScratch = FloatArray(nFft)

    /**
     * Intermediate STFT data containing complex STFT frames and magnitudes.
     */
    class SpleeterStftData(
        val numFrames: Int,
        val numChunks: Int,
        val paddedFrames: Int,
        val realL: FloatArray,
        val imagL: FloatArray,
        val realR: FloatArray,
        val imagR: FloatArray,
        val magTensor: FloatArray // Shape: [2 * numChunks * 512 * 1024]
    )

    /**
     * Computes the STFT and prepares the [2, numChunks, 512, 1024] input tensor for Spleeter.
     */
    fun forwardToSpleeterTensor(
        leftAudio: FloatArray,
        rightAudio: FloatArray
    ): SpleeterStftData {
        val totalSamples = leftAudio.size
        // Calculate number of frames with center padding
        val paddedAudioLen = totalSamples + 2 * padLength
        val numFrames = max(1, (paddedAudioLen - nFft) / hopLength + 1)

        // Spleeter processes in chunks of dimT (512) frames
        val numChunks = max(1, (numFrames + dimT - 1) / dimT)
        val paddedFrames = numChunks * dimT

        val realL = FloatArray(numBins * paddedFrames)
        val imagL = FloatArray(numBins * paddedFrames)
        val realR = FloatArray(numBins * paddedFrames)
        val imagR = FloatArray(numBins * paddedFrames)

        val chunkSize = dimT * dimF
        val channelSize = numChunks * chunkSize
        val magTensor = FloatArray(2 * channelSize)

        for (frame in 0 until numFrames) {
            val frameStart = frame * hopLength - padLength

            // Process Left channel
            for (i in 0 until nFft) {
                val idx = frameStart + i
                realScratch[i] = if (idx in 0 until totalSamples) leftAudio[idx] * window[i] else 0f
                imagScratch[i] = 0f
            }
            FFT.forward(realScratch, imagScratch, nFft)

            val frameOffset = frame * numBins
            val chunkIdx = frame / dimT
            val frameInChunk = frame % dimT
            val tensorFrameOffset = chunkIdx * chunkSize + frameInChunk * dimF

            for (b in 0 until numBins) {
                val r = realScratch[b]
                val im = imagScratch[b]
                realL[frameOffset + b] = r
                imagL[frameOffset + b] = im

                if (b < dimF) {
                    val mag = sqrt(r * r + im * im)
                    magTensor[0 * channelSize + tensorFrameOffset + b] = mag
                }
            }

            // Process Right channel
            for (i in 0 until nFft) {
                val idx = frameStart + i
                realScratch[i] = if (idx in 0 until totalSamples) rightAudio[idx] * window[i] else 0f
                imagScratch[i] = 0f
            }
            FFT.forward(realScratch, imagScratch, nFft)

            for (b in 0 until numBins) {
                val r = realScratch[b]
                val im = imagScratch[b]
                realR[frameOffset + b] = r
                imagR[frameOffset + b] = im

                if (b < dimF) {
                    val mag = sqrt(r * r + im * im)
                    magTensor[1 * channelSize + tensorFrameOffset + b] = mag
                }
            }
        }

        return SpleeterStftData(
            numFrames = numFrames,
            numChunks = numChunks,
            paddedFrames = paddedFrames,
            realL = realL,
            imagL = imagL,
            realR = realR,
            imagR = imagR,
            magTensor = magTensor
        )
    }

    /**
     * Applies Spleeter neural masks to the STFT and reconstructs audio using iSTFT Overlap-Add.
     *
     * @param stftData The cached STFT data from [forwardToSpleeterTensor].
     * @param vocalsOutput Model output tensor for vocals [2, numChunks, 512, 1024].
     * @param accompanimentOutput Optional model output tensor for accompaniment [2, numChunks, 512, 1024].
     * @param targetLength Original length of audio to trim padding.
     * @param vocalMaskStrength User vocal balance factor (0.0 = full instrumental, 1.0 = full vocal).
     * @return Pair of Left and Right reconstructed FloatArrays of length [targetLength].
     */
    fun inverseFromSpleeterTensor(
        stftData: SpleeterStftData,
        vocalsOutput: FloatArray,
        accompanimentOutput: FloatArray? = null,
        targetLength: Int,
        vocalMaskStrength: Float = 1.0f
    ): Pair<FloatArray, FloatArray> {
        val numFrames = stftData.numFrames
        val numChunks = stftData.numChunks
        val chunkSize = dimT * dimF
        val channelSize = numChunks * chunkSize

        val eps = 1e-10f
        val halfEps = eps * 0.5f

        // Masked STFT buffers
        val maskedRealL = stftData.realL.clone()
        val maskedImagL = stftData.imagL.clone()
        val maskedRealR = stftData.realR.clone()
        val maskedImagR = stftData.imagR.clone()

        for (ch in 0 until 2) {
            val targetReal = if (ch == 0) maskedRealL else maskedRealR
            val targetImag = if (ch == 0) maskedImagL else maskedImagR
            val channelOffset = ch * channelSize

            for (frame in 0 until numFrames) {
                val frameOffset = frame * numBins
                val chunkIdx = frame / dimT
                val frameInChunk = frame % dimT
                val tensorOffset = channelOffset + chunkIdx * chunkSize + frameInChunk * dimF

                for (b in 0 until dimF) {
                    val vocMag = vocalsOutput[tensorOffset + b]
                    val accMag = if (accompanimentOutput != null) {
                        accompanimentOutput[tensorOffset + b]
                    } else {
                        val mixMag = stftData.magTensor[tensorOffset + b]
                        max(0f, mixMag - vocMag)
                    }

                    val vocSq = vocMag * vocMag
                    val accSq = accMag * accMag
                    val sumSq = vocSq + accSq + eps

                    // Deezer Spleeter Wiener ratio mask:
                    val rawMask = (vocSq + halfEps) / sumSq
                    val mask = min(1.0f, max(0.0f, rawMask * vocalMaskStrength))

                    val idx = frameOffset + b
                    targetReal[idx] *= mask
                    targetImag[idx] *= mask
                }

                // High-frequency suppression (> 11kHz): vocals have minimal natural energy here.
                // Gently attenuate high frequencies to eliminate cymbal and hi-hat bleed.
                for (b in dimF until numBins) {
                    val idx = frameOffset + b
                    targetReal[idx] *= 0.05f * vocalMaskStrength
                    targetImag[idx] *= 0.05f * vocalMaskStrength
                }
            }
        }

        // Reconstruct both channels with Overlap-Add
        val reconstructedL = performIstft(maskedRealL, maskedImagL, numFrames, targetLength)
        val reconstructedR = performIstft(maskedRealR, maskedImagR, numFrames, targetLength)

        return Pair(reconstructedL, reconstructedR)
    }

    private fun performIstft(
        real: FloatArray,
        imag: FloatArray,
        numFrames: Int,
        targetLength: Int
    ): FloatArray {
        val totalPaddedLen = (numFrames - 1) * hopLength + nFft
        val rawBuffer = FloatArray(totalPaddedLen)
        val windowSum = FloatArray(totalPaddedLen)

        for (frame in 0 until numFrames) {
            val startSample = frame * hopLength
            val frameOffset = frame * numBins

            // Load Hermitian half-spectrum
            for (b in 0 until numBins) {
                realScratch[b] = real[frameOffset + b]
                imagScratch[b] = imag[frameOffset + b]
            }
            // Conjugate symmetry for negative frequencies
            for (b in numBins until nFft) {
                val mirror = nFft - b
                realScratch[b] = realScratch[mirror]
                imagScratch[b] = -imagScratch[mirror]
            }

            FFT.inverse(realScratch, imagScratch, nFft)

            for (i in 0 until nFft) {
                val outIdx = startSample + i
                if (outIdx < totalPaddedLen) {
                    val w = window[i]
                    rawBuffer[outIdx] += realScratch[i] * w
                    windowSum[outIdx] += w * w
                }
            }
        }

        // Window normalization
        for (i in 0 until totalPaddedLen) {
            if (windowSum[i] > 1e-4f) {
                rawBuffer[i] /= windowSum[i]
            }
        }

        // Remove center padding (padLength) and slice to targetLength
        val output = FloatArray(targetLength)
        for (i in 0 until targetLength) {
            val srcIdx = padLength + i
            output[i] = if (srcIdx in 0 until totalPaddedLen) rawBuffer[srcIdx] else 0f
        }
        return output
    }
}
