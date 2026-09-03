package com.example.vocalplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.vocalplayer.dsp.AudioRingBuffer
import com.example.vocalplayer.neural.NeuralSeparationEngine
import com.example.vocalplayer.neural.SeparationResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Media3 AudioProcessor that applies real-time neural source separation directly in ExoPlayer's pipeline.
 */
@UnstableApi
class NeuralAudioProcessor(
    private val separationEngine: NeuralSeparationEngine
) : BaseAudioProcessor() {

    @Volatile
    var isVocalOnlyEnabled: Boolean = true

    @Volatile
    var vocalMixRatio: Float = 1.0f // 1.0f = pure vocal stem, 0.0f = original audio

    // Callback for UI waveform and status HUD
    var onMetricsUpdated: ((rtf: Float, latencyMs: Long, vocalEnergy: Float, instEnergy: Float, bufferHealth: Float) -> Unit)? = null

    private val ringBuffer = AudioRingBuffer(capacity = 44100 * 2) // ~1 sec stereo float buffer
    private var chunkAccumulator = FloatArray(4096)
    private var accumulatorCount = 0

    // Transition smoothing for toggle
    private var currentMixAlpha = 1.0f
    private var targetMixAlpha = 1.0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // We support PCM 16-bit encoding
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val sampleRate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount
        val sampleCount = remaining / 2 // 16-bit = 2 bytes per sample

        // Target mix alpha based on toggle state
        targetMixAlpha = if (isVocalOnlyEnabled) vocalMixRatio else 0.0f

        // Convert PCM 16-bit to float [-1.0, 1.0]
        val floatSamples = FloatArray(sampleCount)
        val shortBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(sampleCount)
        shortBuffer.get(shorts)
        inputBuffer.position(inputBuffer.position() + remaining)

        for (i in 0 until sampleCount) {
            floatSamples[i] = shorts[i] / 32768.0f
        }

        // Process audio
        val outputFloat = if (targetMixAlpha > 0.01f || currentMixAlpha > 0.01f) {
            processNeuralSeparation(floatSamples, sampleRate, channelCount)
        } else {
            currentMixAlpha = 0.0f
            floatSamples
        }

        // Convert float back to PCM 16-bit ByteBuffer
        val outputBytes = outputFloat.size * 2
        val outputBuffer = replaceOutputBuffer(outputBytes)

        for (i in outputFloat.indices) {
            val clamped = min(1.0f, max(-1.0f, outputFloat[i]))
            val shortVal = (clamped * 32767.0f).toInt().toShort()
            outputBuffer.putShort(shortVal)
        }
        outputBuffer.flip()
    }

    private fun processNeuralSeparation(
        inputPcm: FloatArray,
        sampleRate: Int,
        channelCount: Int
    ): FloatArray {
        val result: SeparationResult = separationEngine.separateChunk(inputPcm, sampleRate, channelCount)

        val output = FloatArray(inputPcm.size)
        val stepAlpha = (targetMixAlpha - currentMixAlpha) / inputPcm.size.toFloat()

        for (i in inputPcm.indices) {
            currentMixAlpha = min(1.0f, max(0.0f, currentMixAlpha + stepAlpha))
            // Blend original audio and vocal stem according to alpha
            output[i] = (1.0f - currentMixAlpha) * result.originalAudio[i] + currentMixAlpha * result.vocalAudio[i]
        }

        onMetricsUpdated?.invoke(
            result.rtf,
            result.processingTimeMs,
            result.vocalEnergy,
            result.instrumentalEnergy,
            ringBuffer.healthRatio()
        )

        return output
    }

    override fun onFlush() {
        ringBuffer.clear()
        accumulatorCount = 0
        currentMixAlpha = if (isVocalOnlyEnabled) vocalMixRatio else 0.0f
        targetMixAlpha = currentMixAlpha
    }

    override fun onReset() {
        onFlush()
        separationEngine.reset()
    }
}
