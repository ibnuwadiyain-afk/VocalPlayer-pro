package com.example.vocalplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Handles feature extraction and tensor transformations for Demucs (Hybrid Transformer HTDemucs).
 * Matches the exact specification of Demucs ONNX:
 * - segment_samples: 343,980 (~7.8 sec at 44.1 kHz)
 * - nfft: 4096
 * - hop: 1024
 * - freq_bins: 2048
 * - frames: 336
 * - mix input: [1, 2, 343980]
 * - mag input: [1, 4, 2048, 336] (Left Real, Left Imag, Right Real, Right Imag)
 * - wav output: [1, 4, 2, 343980] (Sources: 0=Drums, 1=Bass, 2=Other, 3=Vocals)
 */
class DemucsSpectrogramTransformer(
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val dimF: Int = 2048,
    val dimT: Int = 336,
    val segmentSamples: Int = 343980
) {
    private val window = FloatArray(nFft) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    data class DemucsInputs(
        val mixTensor: FloatArray, // [1, 2, segmentSamples]
        val magTensor: FloatArray  // [1, 4, dimF, dimT]
    )

    /**
     * Prepares Demucs model inputs from stereo audio channels of length [segmentSamples].
     */
    fun forwardToDemucsTensors(leftAudio: FloatArray, rightAudio: FloatArray): DemucsInputs {
        val mix = FloatArray(2 * segmentSamples)
        val copyLenL = min(segmentSamples, leftAudio.size)
        val copyLenR = min(segmentSamples, rightAudio.size)

        System.arraycopy(leftAudio, 0, mix, 0, copyLenL)
        System.arraycopy(rightAudio, 0, mix, segmentSamples, copyLenR)

        // mag tensor: [1, 4, dimF, dimT]
        // Flattened order: ch * (dimF * dimT) + f * dimT + t
        // Channel 0: Left Real, 1: Left Imag, 2: Right Real, 3: Right Imag
        val mag = FloatArray(4 * dimF * dimT)
        val frameReal = FloatArray(nFft)
        val frameImag = FloatArray(nFft)

        for (ch in 0 until 2) {
            val audio = if (ch == 0) leftAudio else rightAudio
            val realChOffset = (ch * 2) * (dimF * dimT)
            val imagChOffset = (ch * 2 + 1) * (dimF * dimT)

            for (frameIdx in 0 until dimT) {
                val start = frameIdx * hopLength
                for (i in 0 until nFft) {
                    val sampleIdx = start + i
                    val sampleVal = if (sampleIdx in audio.indices) audio[sampleIdx] else 0f
                    frameReal[i] = sampleVal * window[i]
                    frameImag[i] = 0f
                }

                FFT.forward(frameReal, frameImag, nFft)

                // First dimF (2048) frequency bins
                for (f in 0 until dimF) {
                    val magIndex = f * dimT + frameIdx
                    mag[realChOffset + magIndex] = frameReal[f]
                    mag[imagChOffset + magIndex] = frameImag[f]
                }
            }
        }

        return DemucsInputs(mixTensor = mix, magTensor = mag)
    }

    /**
     * Extracts vocal stem from Demucs output wav tensor: [1, 4, 2, segmentSamples].
     * Source index 3 is Vocals.
     * Returns Pair(vocalLeft, vocalRight).
     */
    fun extractVocals(wavOutput: FloatArray): Pair<FloatArray, FloatArray> {
        val vocalLeft = FloatArray(segmentSamples)
        val vocalRight = FloatArray(segmentSamples)

        // wav has shape [1, 4, 2, segmentSamples]
        // Vocals is source 3 (index 3 out of 0..3)
        // Offset for source 3 = 3 * (2 * segmentSamples)
        val vocalSourceOffset = 3 * (2 * segmentSamples)
        val vocalLeftOffset = vocalSourceOffset
        val vocalRightOffset = vocalSourceOffset + segmentSamples

        if (wavOutput.size >= vocalRightOffset + segmentSamples) {
            System.arraycopy(wavOutput, vocalLeftOffset, vocalLeft, 0, segmentSamples)
            System.arraycopy(wavOutput, vocalRightOffset, vocalRight, 0, segmentSamples)
        }

        return Pair(vocalLeft, vocalRight)
    }
}
