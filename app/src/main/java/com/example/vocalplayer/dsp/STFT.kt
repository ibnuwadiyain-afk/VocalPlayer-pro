package com.example.vocalplayer.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Short-Time Fourier Transform (STFT) and Inverse STFT (iSTFT) with Overlap-Add
 * for neural audio source separation.
 */
class STFT(
    val nFft: Int = 1024,
    val hopLength: Int = 256
) {
    init {
        require((nFft and (nFft - 1)) == 0) { "nFft must be a power of 2, got $nFft" }
        require(hopLength <= nFft) { "hopLength must be <= nFft" }
    }

    val numBins: Int = nFft / 2 + 1

    // Pre-computed Hann window
    val window: FloatArray = FloatArray(nFft) { i ->
        (0.5f * (1f - cos(2.0 * PI * i / nFft))).toFloat()
    }

    // Scratch buffers to eliminate object allocations during real-time streaming
    private val realScratch = FloatArray(nFft)
    private val imagScratch = FloatArray(nFft)

    /**
     * Compute STFT spectrogram from audio samples using zero-allocation primitive FFT.
     * Returns:
     * - magnitudes: FloatArray of shape [numBins * numFrames]
     * - phases: FloatArray of shape [numBins * numFrames]
     * - numFrames: number of time frames
     */
    fun forward(audio: FloatArray): STFTResult {
        val audioLen = audio.size
        val numFrames = if (audioLen < nFft) 1 else (audioLen - nFft) / hopLength + 1

        val magnitudes = FloatArray(numBins * numFrames)
        val phases = FloatArray(numBins * numFrames)

        for (frame in 0 until numFrames) {
            val startSample = frame * hopLength
            for (i in 0 until nFft) {
                val idx = startSample + i
                realScratch[i] = if (idx < audioLen) audio[idx] * window[i] else 0f
                imagScratch[i] = 0f
            }

            FFT.forward(realScratch, imagScratch, nFft)

            val frameOffset = frame * numBins
            for (bin in 0 until numBins) {
                val r = realScratch[bin]
                val im = imagScratch[bin]
                val outIdx = frameOffset + bin
                magnitudes[outIdx] = kotlin.math.sqrt(r * r + im * im)
                phases[outIdx] = kotlin.math.atan2(im, r)
            }
        }

        return STFTResult(magnitudes, phases, numBins, numFrames)
    }

    /**
     * Reconstruct audio waveform from magnitude spectrogram and phase using Overlap-Add
     * with zero object allocations.
     */
    fun inverse(magnitudes: FloatArray, phases: FloatArray, numFrames: Int): FloatArray {
        val outputLen = (numFrames - 1) * hopLength + nFft
        val reconstructed = FloatArray(outputLen)
        val windowSum = FloatArray(outputLen)

        for (frame in 0 until numFrames) {
            val startSample = frame * hopLength
            val frameOffset = frame * numBins

            // Reconstruct full spectrum with Hermitian symmetry directly in primitive arrays
            for (bin in 0 until numBins) {
                val idx = frameOffset + bin
                val mag = magnitudes[idx]
                val ph = phases[idx]
                realScratch[bin] = mag * kotlin.math.cos(ph)
                imagScratch[bin] = mag * kotlin.math.sin(ph)
            }
            // Conjugate symmetry for negative frequencies
            for (bin in numBins until nFft) {
                val mirrorBin = nFft - bin
                realScratch[bin] = realScratch[mirrorBin]
                imagScratch[bin] = -imagScratch[mirrorBin]
            }

            FFT.inverse(realScratch, imagScratch, nFft)

            for (i in 0 until nFft) {
                val outIdx = startSample + i
                if (outIdx < outputLen) {
                    val w = window[i]
                    reconstructed[outIdx] += realScratch[i] * w
                    windowSum[outIdx] += w * w
                }
            }
        }

        // Window normalization to avoid overlap artifacts
        for (i in 0 until outputLen) {
            if (windowSum[i] > 1e-4f) {
                reconstructed[i] /= windowSum[i]
            }
        }

        return reconstructed
    }
}

data class STFTResult(
    val magnitudes: FloatArray,
    val phases: FloatArray,
    val numBins: Int,
    val numFrames: Int
)

/**
 * High-performance 4-channel Complex Spectrogram Transformer for UVR MDX-Net models.
 * UVR MDX-Net architecture requires:
 * - n_fft = 4096
 * - hop_length = 1024
 * - dim_f = 2048 (frequency bins)
 * - dim_t = 256 (time frames per chunk)
 * - 4 channels: [0]=Left Real, [1]=Left Imag, [2]=Right Real, [3]=Right Imag
 * - Tensor shape: [1, 4, 2048, 256]
 */
class MdxSpectrogramTransformer(
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val dimF: Int = 2048,
    val dimT: Int = 256
) {
    val padLength: Int = nFft / 2 // Center padding (2048 samples)

    // Precomputed Hann window
    val window: FloatArray = FloatArray(nFft) { i ->
        (0.5f * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    // Reusable buffers to eliminate garbage collection during real-time inference
    private val realL = FloatArray(nFft)
    private val imagL = FloatArray(nFft)
    private val realR = FloatArray(nFft)
    private val imagR = FloatArray(nFft)

    /**
     * Converts stereo audio PCM into 4-channel complex tensor of shape [1, 4, dimF, dimT].
     * Row-major index formula: c * (dimF * dimT) + f * dimT + t
     */
    fun forwardToMdxTensor(
        leftAudio: FloatArray,
        rightAudio: FloatArray,
        startSampleOffset: Int = 0
    ): FloatArray {
        val totalTensorSize = 4 * dimF * dimT
        val tensor = FloatArray(totalTensorSize)
        val channelStride = dimF * dimT

        val leftLen = leftAudio.size
        val rightLen = rightAudio.size

        for (t in 0 until dimT) {
            val frameStart = startSampleOffset + (t * hopLength) - padLength

            // Prepare left & right windowed buffers
            for (i in 0 until nFft) {
                val sampleIdx = frameStart + i
                val w = window[i]

                val sL = if (sampleIdx in 0 until leftLen) leftAudio[sampleIdx] else 0f
                realL[i] = sL * w
                imagL[i] = 0f

                val sR = if (sampleIdx in 0 until rightLen) rightAudio[sampleIdx] else 0f
                realR[i] = sR * w
                imagR[i] = 0f
            }

            // In-place FFT on primitive arrays
            FFT.forward(realL, imagL, nFft)
            FFT.forward(realR, imagR, nFft)

            // Fill 4-channel tensor for first dimF (2048) bins
            val frameOffset = t
            for (f in 0 until dimF) {
                val fStride = f * dimT
                val idx0 = 0 * channelStride + fStride + frameOffset // Left Real
                val idx1 = 1 * channelStride + fStride + frameOffset // Left Imag
                val idx2 = 2 * channelStride + fStride + frameOffset // Right Real
                val idx3 = 3 * channelStride + fStride + frameOffset // Right Imag

                tensor[idx0] = realL[f]
                tensor[idx1] = imagL[f]
                tensor[idx2] = realR[f]
                tensor[idx3] = imagR[f]
            }
        }

        return tensor
    }

    /**
     * Reconstructs stereo audio waveforms from 4-channel MDX-Net complex output tensor.
     * Applies Hermitian conjugate expansion, inverse FFT, synthesis windowing, and Overlap-Add.
     */
    fun inverseFromMdxTensor(
        tensor: FloatArray,
        outputLength: Int,
        startSampleOffset: Int = 0
    ): Pair<FloatArray, FloatArray> {
        val channelStride = dimF * dimT
        val reconstructedL = FloatArray(outputLength)
        val reconstructedR = FloatArray(outputLength)
        val windowSum = FloatArray(outputLength)

        for (t in 0 until dimT) {
            val frameStart = startSampleOffset + (t * hopLength) - padLength
            val frameOffset = t

            // Load 2048 bins from tensor
            for (f in 0 until dimF) {
                val fStride = f * dimT
                val idx0 = 0 * channelStride + fStride + frameOffset // Left Real
                val idx1 = 1 * channelStride + fStride + frameOffset // Left Imag
                val idx2 = 2 * channelStride + fStride + frameOffset // Right Real
                val idx3 = 3 * channelStride + fStride + frameOffset // Right Imag

                realL[f] = tensor[idx0]
                imagL[f] = tensor[idx1]
                realR[f] = tensor[idx2]
                imagR[f] = tensor[idx3]
            }

            // Nyquist bin
            if (dimF < nFft) {
                realL[dimF] = 0f
                imagL[dimF] = 0f
                realR[dimF] = 0f
                imagR[dimF] = 0f
            }

            // Hermitian conjugate symmetry for bins from dimF + 1 until nFft
            for (f in 1 until (nFft - dimF)) {
                val mirror = nFft - f
                if (mirror < nFft && f < dimF) {
                    realL[mirror] = realL[f]
                    imagL[mirror] = -imagL[f]
                    realR[mirror] = realR[f]
                    imagR[mirror] = -imagR[f]
                }
            }

            // In-place inverse FFT
            FFT.inverse(realL, imagL, nFft)
            FFT.inverse(realR, imagR, nFft)

            // Overlap-Add with synthesis window
            for (i in 0 until nFft) {
                val sampleIdx = frameStart + i
                if (sampleIdx in 0 until outputLength) {
                    val w = window[i]
                    reconstructedL[sampleIdx] += realL[i] * w
                    reconstructedR[sampleIdx] += realR[i] * w
                    windowSum[sampleIdx] += w * w
                }
            }
        }

        // Window normalization to eliminate overlap distortion
        for (i in 0 until outputLength) {
            val sum = windowSum[i]
            if (sum > 1e-4f) {
                reconstructedL[i] /= sum
                reconstructedR[i] /= sum
            }
        }

        return Pair(reconstructedL, reconstructedR)
    }
}

