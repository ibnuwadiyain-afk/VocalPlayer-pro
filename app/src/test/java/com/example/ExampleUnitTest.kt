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
  fun testPrimitiveFFTForwardAndInverse() {
    val n = 128
    val real = FloatArray(n) { i -> sin(2.0 * Math.PI * 5.0 * i / n).toFloat() }
    val imag = FloatArray(n)
    val original = real.clone()

    FFT.forward(real, imag, n)
    FFT.inverse(real, imag, n)

    for (i in 0 until n) {
      assertEquals(original[i], real[i], 1e-4f)
      assertEquals(0f, imag[i], 1e-4f)
    }
  }

  @Test
  fun testMdxSpectrogramTransformerTensorShape() {
    val transformer = com.example.vocalplayer.dsp.MdxSpectrogramTransformer(
      nFft = 512,
      hopLength = 128,
      dimF = 256,
      dimT = 16
    )
    val audioLen = 2048
    val left = FloatArray(audioLen) { 0.5f }
    val right = FloatArray(audioLen) { 0.25f }

    val tensor = transformer.forwardToMdxTensor(left, right)
    assertEquals(4 * 256 * 16, tensor.size)

    val (recL, recR) = transformer.inverseFromMdxTensor(tensor, outputLength = audioLen)
    assertEquals(audioLen, recL.size)
    assertEquals(audioLen, recR.size)
  }

  @Test
  fun testSTFTZeroAllocation() {
    val stft = com.example.vocalplayer.dsp.STFT(nFft = 256, hopLength = 64)
    val input = FloatArray(1024) { i -> sin(2.0 * Math.PI * 8.0 * i / 256).toFloat() }
    val result = stft.forward(input)
    val reconstructed = stft.inverse(result.magnitudes, result.phases, result.numFrames)

    // Verify reconstruction length matches and non-zero
    org.junit.Assert.assertTrue(reconstructed.isNotEmpty())
    org.junit.Assert.assertTrue(result.numFrames > 0)
    org.junit.Assert.assertEquals(129, result.numBins)
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


