package com.example.vocalplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Complex number for frequency-domain representation.
 */
data class Complex(val real: Float, val imag: Float) {
    operator fun plus(b: Complex) = Complex(real + b.real, imag + b.imag)
    operator fun minus(b: Complex) = Complex(real - b.real, imag - b.imag)
    operator fun times(b: Complex) = Complex(
        real * b.real - imag * b.imag,
        real * b.imag + imag * b.real
    )
    operator fun times(scalar: Float) = Complex(real * scalar, imag * scalar)
    operator fun div(scalar: Float) = Complex(real / scalar, imag / scalar)

    fun magnitude(): Float = sqrt(real * real + imag * imag)
    fun phase(): Float = kotlin.math.atan2(imag, real)

    companion object {
        val ZERO = Complex(0f, 0f)
        fun fromPolar(magnitude: Float, phase: Float) = Complex(
            magnitude * cos(phase),
            magnitude * sin(phase)
        )
    }
}

/**
 * High-performance Radix-2 Cooley-Tukey Fast Fourier Transform.
 * Supports both object-based Complex arrays and zero-allocation primitive FloatArrays.
 */
object FFT {

    /**
     * In-place Radix-2 Cooley-Tukey forward FFT for separate real and imag FloatArrays.
     * n must be a power of 2 (e.g. 512, 1024, 2048, 4096).
     */
    fun forward(real: FloatArray, imag: FloatArray, n: Int = real.size) {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of 2, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * In-place Radix-2 Cooley-Tukey inverse FFT for separate real and imag FloatArrays.
     * Normalized by 1/n.
     */
    fun inverse(real: FloatArray, imag: FloatArray, n: Int = real.size) {
        require(n > 0 && (n and (n - 1)) == 0) { "iFFT length must be a power of 2, got $n" }

        // Conjugate input: imag = -imag
        for (i in 0 until n) {
            imag[i] = -imag[i]
        }

        forward(real, imag, n)

        // Conjugate output and scale by 1/n
        val invN = 1.0f / n
        for (i in 0 until n) {
            real[i] *= invN
            imag[i] = -imag[i] * invN
        }
    }

    /**
     * Compute forward FFT in-place for power-of-2 size array.
     */
    fun forward(input: Array<Complex>): Array<Complex> {
        val n = input.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of 2, got $n" }

        val output = input.copyOf()
        bitReverse(output)

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wStep = Complex(cos(angle).toFloat(), sin(angle).toFloat())

            var i = 0
            while (i < n) {
                var w = Complex(1f, 0f)
                for (j in 0 until halfLen) {
                    val u = output[i + j]
                    val v = output[i + j + halfLen] * w
                    output[i + j] = u + v
                    output[i + j + halfLen] = u - v
                    w = w * wStep
                }
                i += len
            }
            len *= 2
        }
        return output
    }

    /**
     * Compute inverse FFT (iFFT) in-place for power-of-2 size array.
     */
    fun inverse(input: Array<Complex>): Array<Complex> {
        val n = input.size
        require(n > 0 && (n and (n - 1)) == 0) { "iFFT length must be a power of 2, got $n" }

        // Conjugate input
        val conjugated = Array(n) { i -> Complex(input[i].real, -input[i].imag) }
        val forwardResult = forward(conjugated)

        // Conjugate output and scale by 1/n
        val invN = 1f / n
        return Array(n) { i ->
            Complex(forwardResult[i].real * invN, -forwardResult[i].imag * invN)
        }
    }

    private fun bitReverse(a: Array<Complex>) {
        val n = a.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val temp = a[i]
                a[i] = a[j]
                a[j] = temp
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
    }
}
