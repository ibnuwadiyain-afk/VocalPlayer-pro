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
    private val separationEngine: NeuralSeparationEngine,
    private val cacheManager: com.example.vocalplayer.cache.VocalCacheManager? = null
) : BaseAudioProcessor() {

    @Volatile
    var isVocalOnlyEnabled: Boolean = false

    @Volatile
    var vocalMixRatio: Float = 1.0f // 1.0f = pure vocal stem, 0.0f = original audio

    @Volatile
    var activeMediaUri: android.net.Uri? = null

    var positionMsProvider: (() -> Long)? = null

    // Callback for UI waveform and status HUD
    var onMetricsUpdated: ((rtf: Float, latencyMs: Long, vocalEnergy: Float, instEnergy: Float, bufferHealth: Float) -> Unit)? = null

    private val ringBuffer = AudioRingBuffer(capacity = 44100 * 2) // ~1 sec stereo float buffer

    // Preallocated scratch buffers to eliminate GC allocations during playback
    private var floatSamples = FloatArray(16384)
    private var shorts = ShortArray(16384)
    private var cachedVocalShorts = ShortArray(16384)
    private var blendedOutput = FloatArray(16384)

    // Current playback sample index for stream alignment
    private var playbackSampleIndex: Long = 0L
    private val atomicPositionMs = java.util.concurrent.atomic.AtomicLong(0L)

    // Transition smoothing for toggle
    private var currentMixAlpha = 0.0f
    private var targetMixAlpha = 0.0f

    // UI telemetry rate limiting to avoid flooding Compose recomposition
    private var lastMetricsUpdateTime = 0L

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

        try {
            // Target mix alpha based on toggle state
            targetMixAlpha = if (isVocalOnlyEnabled) vocalMixRatio else 0.0f

            // FAST PATH: When vocal isolation is disabled and transition complete, direct zero-copy pass-through
            if (targetMixAlpha <= 0.001f && currentMixAlpha <= 0.001f) {
                currentMixAlpha = 0.0f
                val outputBuffer = replaceOutputBuffer(remaining)
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                playbackSampleIndex += (remaining / 2)
                return
            }

            val sampleRate = inputAudioFormat.sampleRate
            val channelCount = inputAudioFormat.channelCount
            val sampleCount = remaining / 2 // 16-bit = 2 bytes per sample

            // Ensure scratch buffers are large enough
            if (sampleCount > floatSamples.size) {
                floatSamples = FloatArray(sampleCount * 2)
                shorts = ShortArray(sampleCount * 2)
                cachedVocalShorts = ShortArray(sampleCount * 2)
                blendedOutput = FloatArray(sampleCount * 2)
            }

            // Convert PCM 16-bit to short scratch arrays
            val shortBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val availableShorts = kotlin.math.min(sampleCount, shortBuffer.remaining())
            shortBuffer.get(shorts, 0, availableShorts)
            inputBuffer.position(inputBuffer.limit())

            val uri = activeMediaUri
            val isCached = uri != null && cacheManager != null && cacheManager.isVocalCached(uri)
            val inv32768 = 1.0f / 32768.0f

            if (isCached && uri != null && cacheManager != null) {
                // ZERO-LAG CACHED PLAYBACK: Read pre-extracted Demucs vocal stem directly
                val readCount = cacheManager.readVocalSlice(uri, playbackSampleIndex, availableShorts, cachedVocalShorts)
                val stepAlpha = (targetMixAlpha - currentMixAlpha) / availableShorts.toFloat().coerceAtLeast(1f)

                var vocalEnergySum = 0f
                var instEnergySum = 0f

                for (i in 0 until availableShorts) {
                    currentMixAlpha = min(1.0f, max(0.0f, currentMixAlpha + stepAlpha))
                    val orig = shorts[i] * inv32768
                    val vocal = if (i < readCount) cachedVocalShorts[i] * inv32768 else orig
                    val blended = (1.0f - currentMixAlpha) * orig + currentMixAlpha * vocal
                    blendedOutput[i] = blended

                    vocalEnergySum += vocal * vocal
                    val inst = orig - vocal
                    instEnergySum += inst * inst
                }

                playbackSampleIndex += availableShorts

                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMetricsUpdateTime >= 120L) {
                    lastMetricsUpdateTime = now
                    val vEnergy = kotlin.math.sqrt(vocalEnergySum / availableShorts.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val iEnergy = kotlin.math.sqrt(instEnergySum / availableShorts.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onMetricsUpdated?.invoke(0.001f, 0L, vEnergy, iEnergy, 1.0f)
                }
            } else {
                // Live processing path for un-cached content
                for (i in 0 until availableShorts) {
                    floatSamples[i] = shorts[i] * inv32768
                }

                val inputSlice = if (floatSamples.size == availableShorts) floatSamples else floatSamples.copyOf(availableShorts)
                val result: SeparationResult = separationEngine.separateChunk(inputSlice, sampleRate, channelCount)

                val stepAlpha = (targetMixAlpha - currentMixAlpha) / availableShorts.toFloat().coerceAtLeast(1f)
                for (i in 0 until availableShorts) {
                    currentMixAlpha = min(1.0f, max(0.0f, currentMixAlpha + stepAlpha))
                    val orig = if (i < result.originalAudio.size) result.originalAudio[i] else floatSamples[i]
                    val vocal = if (i < result.vocalAudio.size) result.vocalAudio[i] else orig
                    blendedOutput[i] = (1.0f - currentMixAlpha) * orig + currentMixAlpha * vocal
                }

                playbackSampleIndex += availableShorts

                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMetricsUpdateTime >= 120L) {
                    lastMetricsUpdateTime = now
                    onMetricsUpdated?.invoke(
                        result.rtf,
                        result.processingTimeMs,
                        result.vocalEnergy,
                        result.instrumentalEnergy,
                        0.95f
                    )
                }
            }

            // Convert float back to PCM 16-bit ByteBuffer
            val outputBytes = availableShorts * 2
            val outputBuffer = replaceOutputBuffer(outputBytes)

            for (i in 0 until availableShorts) {
                val clamped = min(1.0f, max(-1.0f, blendedOutput[i]))
                val shortVal = (clamped * 32767.0f).toInt().toShort()
                outputBuffer.putShort(shortVal)
            }
            outputBuffer.flip()
        } catch (e: Throwable) {
            android.util.Log.e("NeuralAudioProcessor", "Error processing audio: ${e.message}", e)
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
        }
    }

    override fun onFlush() {
        ringBuffer.clear()
        currentMixAlpha = if (isVocalOnlyEnabled) vocalMixRatio else 0.0f
        targetMixAlpha = currentMixAlpha

        val currentMs = try {
            positionMsProvider?.invoke() ?: atomicPositionMs.get()
        } catch (e: Throwable) {
            atomicPositionMs.get()
        }
        val sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 44100
        val channelCount = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        playbackSampleIndex = ((currentMs * sampleRate.toLong()) / 1000L) * channelCount.toLong()
    }

    override fun onReset() {
        onFlush()
        separationEngine.reset()
    }

    fun resetStreamPosition(positionMs: Long = 0L) {
        atomicPositionMs.set(positionMs)
        val sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 44100
        val channelCount = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        playbackSampleIndex = ((positionMs * sampleRate.toLong()) / 1000L) * channelCount.toLong()
        ringBuffer.clear()
    }
}
