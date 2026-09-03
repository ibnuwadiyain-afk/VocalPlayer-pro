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
 */
object FFT {

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
