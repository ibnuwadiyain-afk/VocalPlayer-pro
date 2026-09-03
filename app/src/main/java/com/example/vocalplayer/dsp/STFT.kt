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

    /**
     * Compute STFT spectrogram from audio samples.
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
        val frameBuffer = Array(nFft) { Complex.ZERO }

        for (frame in 0 until numFrames) {
            val startSample = frame * hopLength
            for (i in 0 until nFft) {
                val idx = startSample + i
                val sample = if (idx < audioLen) audio[idx] else 0f
                frameBuffer[i] = Complex(sample * window[i], 0f)
            }

            val fftOut = FFT.forward(frameBuffer)

            for (bin in 0 until numBins) {
                val c = fftOut[bin]
                val outIdx = frame * numBins + bin
                magnitudes[outIdx] = c.magnitude()
                phases[outIdx] = c.phase()
            }
        }

        return STFTResult(magnitudes, phases, numBins, numFrames)
    }

    /**
     * Reconstruct audio waveform from magnitude spectrogram and phase using Overlap-Add.
     */
    fun inverse(magnitudes: FloatArray, phases: FloatArray, numFrames: Int): FloatArray {
        val outputLen = (numFrames - 1) * hopLength + nFft
        val reconstructed = FloatArray(outputLen)
        val windowSum = FloatArray(outputLen)

        val fftBuffer = Array(nFft) { Complex.ZERO }

        for (frame in 0 until numFrames) {
            val startSample = frame * hopLength

            // Reconstruct full spectrum (Hermitian symmetry)
            for (bin in 0 until numBins) {
                val idx = frame * numBins + bin
                val mag = magnitudes[idx]
                val ph = phases[idx]
                fftBuffer[bin] = Complex.fromPolar(mag, ph)
            }
            // Conjugate symmetry for negative frequencies
            for (bin in numBins until nFft) {
                val mirrorBin = nFft - bin
                val c = fftBuffer[mirrorBin]
                fftBuffer[bin] = Complex(c.real, -c.imag)
            }

            val timeFrame = FFT.inverse(fftBuffer)

            for (i in 0 until nFft) {
                val outIdx = startSample + i
                if (outIdx < outputLen) {
                    val w = window[i]
                    reconstructed[outIdx] += timeFrame[i].real * w
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
