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
  fun testSpleeterModelLoading() {
    val file = java.io.File("/tmp/spleeter_int8/sherpa-onnx-spleeter-2stems-int8/vocals.int8.onnx")
    if (!file.exists()) return
    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    val session = env.createSession(file.absolutePath)
    println("Spleeter Inputs: " + session.inputNames)
    for (name in session.inputNames) {
      val info = session.inputInfo[name]?.info as? ai.onnxruntime.TensorInfo
      println("Input $name shape: " + info?.shape?.contentToString() + " type: " + info?.type)
    }
    println("Spleeter Outputs: " + session.outputNames)
    for (name in session.outputNames) {
      val info = session.outputInfo[name]?.info as? ai.onnxruntime.TensorInfo
      println("Output $name shape: " + info?.shape?.contentToString() + " type: " + info?.type)
    }

    // Benchmark 1 chunk: shape [2, 1, 512, 1024] = 11.88 seconds of audio!
    val dummyInput = FloatArray(2 * 1 * 512 * 1024) { 0.1f }
    val buffer = java.nio.FloatBuffer.wrap(dummyInput)
    val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, buffer, longArrayOf(2, 1, 512, 1024))
    val startTime = System.currentTimeMillis()
    val results = session.run(mapOf("x" to tensor))
    val elapsed = System.currentTimeMillis() - startTime
    val outTensor = results.get(0) as ai.onnxruntime.OnnxTensor
    println("Spleeter inference time for 11.88s of audio: ${elapsed}ms (RTF = ${elapsed / 11880.0})")
    tensor.close()
    results.close()
    session.close()
  }

  @Test
  fun testSpleeterTransformerRoundTrip() {
    val transformer = com.example.vocalplayer.dsp.SpleeterSpectrogramTransformer()
    val length = 44100 // 1 second of audio
    val left = FloatArray(length) { i -> sin(2.0 * Math.PI * 440.0 * i / 44100.0).toFloat() * 0.8f }
    val right = FloatArray(length) { i -> sin(2.0 * Math.PI * 880.0 * i / 44100.0).toFloat() * 0.8f }

    val stftData = transformer.forwardToSpleeterTensor(left, right)
    assertEquals(1, stftData.numChunks)

    // With unit mask (vocalsOutput = mixMag, accOutput = 0), reconstruction should be faithful
    val (recL, recR) = transformer.inverseFromSpleeterTensor(
      stftData = stftData,
      vocalsOutput = stftData.magTensor,
      accompanimentOutput = FloatArray(stftData.magTensor.size),
      targetLength = length,
      vocalMaskStrength = 1.0f
    )

    assertEquals(length, recL.size)
    assertEquals(length, recR.size)

    // Verify correlation / signal energy preserved
    var energyL = 0.0
    for (i in 4096 until length - 4096) {
      val diff = left[i] - recL[i]
      energyL += diff * diff
    }
    val rmse = Math.sqrt(energyL / (length - 8192))
    println("Spleeter roundtrip RMSE: $rmse")
    org.junit.Assert.assertTrue("RMSE should be low: $rmse", rmse < 0.1)
  }

  @Test
  fun testSpleeterModelInferenceFullAudio() {
    val modelFile = java.io.File("src/main/assets/models/spleeter_2stems_vocals.onnx")
    org.junit.Assume.assumeTrue(modelFile.exists())

    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    val session = env.createSession(modelFile.absolutePath)
    val transformer = com.example.vocalplayer.dsp.SpleeterSpectrogramTransformer()

    val length = 44100 * 2 // 2 seconds of stereo audio
    val left = FloatArray(length) { i -> sin(2.0 * Math.PI * 440.0 * i / 44100.0).toFloat() * 0.7f }
    val right = FloatArray(length) { i -> sin(2.0 * Math.PI * 880.0 * i / 44100.0).toFloat() * 0.7f }

    val stftData = transformer.forwardToSpleeterTensor(left, right)
    val buffer = java.nio.FloatBuffer.wrap(stftData.magTensor)
    val shape = longArrayOf(2, stftData.numChunks.toLong(), 512, 1024)
    val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, buffer, shape)

    val start = System.currentTimeMillis()
    val results = session.run(mapOf("x" to tensor))
    val elapsed = System.currentTimeMillis() - start

    val outTensor = results.get(0) as ai.onnxruntime.OnnxTensor
    val outBuffer = outTensor.floatBuffer
    val vocalsOutput = FloatArray(outBuffer.remaining())
    outBuffer.get(vocalsOutput)

    println("Inference for 2s audio took ${elapsed}ms")

    val (recL, recR) = transformer.inverseFromSpleeterTensor(
      stftData = stftData,
      vocalsOutput = vocalsOutput,
      accompanimentOutput = null,
      targetLength = length,
      vocalMaskStrength = 1.0f
    )

    assertEquals(length, recL.size)
    assertEquals(length, recR.size)

    tensor.close()
    results.close()
    session.close()
    env.close()
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


