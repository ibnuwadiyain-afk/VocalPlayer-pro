package com.example

import com.example.vocalplayer.dsp.AudioResampler
import com.example.vocalplayer.dsp.AudioRingBuffer
import com.example.vocalplayer.dsp.Complex
import com.example.vocalplayer.dsp.FFT
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class ExampleUnitTest {

  @Test
  fun testFFTForwardAndInverse() {
    val n = 64
    val input = Array(n) { i -> Complex(sin(2.0 * Math.PI * 4.0 * i / n).toFloat(), 0f) }

    val forward = FFT.forward(input)
    val reconstructed = FFT.inverse(forward)

    for (i in 0 until n) {
      assertEquals(input[i].real, reconstructed[i].real, 1e-4f)
      assertEquals(0f, reconstructed[i].imag, 1e-4f)
    }
  }

  @Test
  fun testAudioRingBuffer() {
    val buffer = AudioRingBuffer(capacity = 100)
    assertEquals(0, buffer.available())
    assertEquals(100, buffer.freeSpace())

    val input = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
    val written = buffer.write(input)
    assertEquals(5, written)
    assertEquals(5, buffer.available())

    val output = FloatArray(5)
    val read = buffer.read(output)
    assertEquals(5, read)
    for (i in 0 until 5) {
      assertEquals(input[i], output[i], 1e-6f)
    }
    assertEquals(0, buffer.available())
  }

  @Test
  fun testAudioResampler() {
    val input = FloatArray(100) { 1.0f }
    val resampled = AudioResampler.resample(input, inRate = 48000, outRate = 24000)
    assertEquals(50, resampled.size)
    assertEquals(1.0f, resampled[0], 1e-3f)
  }
}


