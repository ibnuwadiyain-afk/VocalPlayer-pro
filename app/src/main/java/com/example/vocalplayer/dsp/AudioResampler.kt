package com.example.vocalplayer.dsp

import kotlin.math.floor

/**
 * High-quality linear / polynomial audio resampler.
 */
object AudioResampler {

    /**
     * Resample single channel float audio from inRate to outRate.
     */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input.copyOf()

        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)

        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val srcIndex = floor(srcPos).toInt()
            val frac = (srcPos - srcIndex).toFloat()

            if (srcIndex + 1 < input.size) {
                val s0 = input[srcIndex]
                val s1 = input[srcIndex + 1]
                output[i] = s0 + frac * (s1 - s0)
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        return output
    }

    /**
     * Resample interleaved stereo audio.
     */
    fun resampleStereo(interleaved: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || interleaved.isEmpty()) return interleaved.copyOf()

        val frames = interleaved.size / 2
        val leftIn = FloatArray(frames)
        val rightIn = FloatArray(frames)

        for (i in 0 until frames) {
            leftIn[i] = interleaved[i * 2]
            rightIn[i] = interleaved[i * 2 + 1]
        }

        val leftOut = resample(leftIn, inRate, outRate)
        val rightOut = resample(rightIn, inRate, outRate)

        val outFrames = leftOut.size
        val outInterleaved = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) {
            outInterleaved[i * 2] = leftOut[i]
            outInterleaved[i * 2 + 1] = rightOut[i]
        }
        return outInterleaved
    }
}
