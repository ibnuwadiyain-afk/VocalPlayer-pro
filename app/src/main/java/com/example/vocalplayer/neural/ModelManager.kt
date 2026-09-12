package com.example.vocalplayer.neural

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin

/**
 * Result of a model benchmark test.
 */
data class BenchmarkResult(
    val modelId: String,
    val modelName: String,
    val rtf: Float,
    val averageChunkMs: Long,
    val isRealTimeCapable: Boolean,
    val recommendedMode: SeparationMode,
    val summary: String
)

/**
 * Manages model profiles, secure local storage, verification, and device benchmarking.
 */
class ModelManager(private val context: Context) {
    private val tag = "ModelManager"
    val downloader = ModelDownloader(context)

    private val userModels = mutableListOf<NeuralModelProfile>()
    private var activeProfile: NeuralModelProfile = NeuralModelProfile.DEFAULT_BUILTIN

    init {
        // Automatically check and stage bundled asset model into secure storage
        try {
            downloader.copyAssetModelIfPresent("models/UVR_MDXNET_9482.onnx", NeuralModelProfile.UVR_MDXNET_9482.id)
        } catch (e: Exception) {
            Log.d(tag, "Asset model copy check: ${e.message}")
        }
        loadInstalledModels()

        // Default active profile is always the mobile-optimized built-in model for zero-lag real-time playback.
        // Heavy models (like UVR MDX-Net) remain installed and selectable via the Model Manager.
        activeProfile = NeuralModelProfile.DEFAULT_BUILTIN
    }

    fun loadInstalledModels() {
        userModels.clear()
        val files = downloader.listSecureModels()
        files.forEach { file ->
            val modelId = "custom_${file.nameWithoutExtension}"
            val matchingPreset = NeuralModelProfile.PRESET_PROFILES.find {
                downloader.sanitizeModelFileName(it.id) == file.name ||
                        it.id == file.nameWithoutExtension ||
                        file.nameWithoutExtension.contains(it.id, ignoreCase = true) ||
                        (it.id == NeuralModelProfile.UVR_MDXNET_9482.id && file.name.contains("9482", ignoreCase = true))
            }

            if (matchingPreset != null) {
                val updatedPreset = matchingPreset.copy(
                    modelPath = file.absolutePath,
                    isDownloaded = true,
                    fileSizeFormatted = "${file.length() / (1024 * 1024)} MB (Secure)"
                )
                userModels.add(updatedPreset)
            } else {
                val profile = NeuralModelProfile(
                    id = modelId,
                    name = file.nameWithoutExtension.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    architecture = ModelArchitecture.MDX_NET,
                    description = "Verified secure local ONNX model (${file.length() / (1024 * 1024)} MB)",
                    sampleRate = 44100,
                    isStereo = true,
                    isBuiltIn = false,
                    modelPath = file.absolutePath,
                    fileSizeFormatted = "${file.length() / (1024 * 1024)} MB (Secure)",
                    latencyEstimateMs = 50,
                    recommendedMode = SeparationMode.BALANCED,
                    isDownloaded = true
                )
                userModels.add(profile)
            }
        }
    }

    fun getAllModels(): List<NeuralModelProfile> {
        val list = mutableListOf<NeuralModelProfile>()

        NeuralModelProfile.PRESET_PROFILES.forEach { preset ->
            val installed = userModels.find { it.id == preset.id || it.name.equals(preset.name, ignoreCase = true) }
            if (installed != null) {
                list.add(installed)
            } else {
                val isPresent = downloader.isModelLocallyAvailable(preset.id)
                if (isPresent) {
                    val file = downloader.getSecureModelFile(preset.id)
                    list.add(
                        preset.copy(
                            modelPath = file.absolutePath,
                            isDownloaded = true,
                            fileSizeFormatted = "${file.length() / (1024 * 1024)} MB (Secure)"
                        )
                    )
                } else {
                    list.add(preset.copy(isDownloaded = false))
                }
            }
        }

        // Add any non-preset custom imported models
        userModels.forEach { userModel ->
            if (list.none { it.id == userModel.id }) {
                list.add(userModel)
            }
        }

        return list
    }

    fun getActiveModel(): NeuralModelProfile = activeProfile

    fun setActiveModel(profile: NeuralModelProfile) {
        activeProfile = profile
    }

    /**
     * Download and verify an ONNX model into secure app-internal storage.
     */
    suspend fun downloadModel(
        profile: NeuralModelProfile,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<NeuralModelProfile> = withContext(Dispatchers.IO) {
        val downloadResult = downloader.downloadAndVerifyModel(profile, onProgress)
        downloadResult.map { targetFile ->
            val updated = profile.copy(
                modelPath = targetFile.absolutePath,
                isDownloaded = true,
                fileSizeFormatted = "${targetFile.length() / (1024 * 1024)} MB (Secure)"
            )
            loadInstalledModels()
            updated
        }
    }

    /**
     * Import a custom .onnx model with secure storage copy and integrity verification.
     */
    suspend fun importModelFromUri(uri: Uri, displayName: String): Result<NeuralModelProfile> = withContext(Dispatchers.IO) {
        val importResult = downloader.importAndVerifyLocalModel(uri, displayName)
        importResult.map { targetFile ->
            val profile = NeuralModelProfile(
                id = "custom_${targetFile.nameWithoutExtension}",
                name = displayName,
                architecture = ModelArchitecture.MDX_NET,
                description = "Locally verified ONNX model (${targetFile.length() / (1024 * 1024)} MB)",
                sampleRate = 44100,
                isStereo = true,
                isBuiltIn = false,
                modelPath = targetFile.absolutePath,
                fileSizeFormatted = "${targetFile.length() / (1024 * 1024)} MB (Secure)",
                latencyEstimateMs = 50,
                recommendedMode = SeparationMode.BALANCED,
                isDownloaded = true
            )
            loadInstalledModels()
            activeProfile = profile
            profile
        }
    }

    /**
     * Delete a downloaded model from secure storage.
     */
    fun deleteModel(profile: NeuralModelProfile): Boolean {
        if (profile.isBuiltIn) return false
        val deleted = downloader.deleteModel(profile.id)
        loadInstalledModels()
        if (activeProfile.id == profile.id) {
            activeProfile = NeuralModelProfile.DEFAULT_BUILTIN
        }
        return deleted
    }

    /**
     * Run a synthetic vocal/music separation benchmark on the device to measure RTF.
     */
    suspend fun runBenchmark(profile: NeuralModelProfile): BenchmarkResult = withContext(Dispatchers.Default) {
        val sampleRate = 44100
        val durationSec = 3.0f
        val numSamples = (sampleRate * durationSec).toInt() * 2 // Stereo

        val testAudio = FloatArray(numSamples) { i ->
            val t = (i / 2).toFloat() / sampleRate.toFloat()
            val vocal = 0.4f * sin(2.0 * Math.PI * 440.0 * t).toFloat() + 0.2f * sin(2.0 * Math.PI * 880.0 * t).toFloat()
            val bass = 0.3f * sin(2.0 * Math.PI * 90.0 * t).toFloat()
            val kick = if (i % (sampleRate / 2) < 200) 0.5f else 0f
            (vocal + bass + kick)
        }

        val engine = NeuralSeparationEngine(context, SeparationConfig(mode = profile.recommendedMode))
        engine.setModelProfile(profile)

        val chunkSize = sampleRate
        val totalChunks = numSamples / (chunkSize * 2)
        var totalInferenceMs = 0L
        var rtfSum = 0f

        for (c in 0 until totalChunks) {
            val chunk = testAudio.copyOfRange(c * chunkSize * 2, (c + 1) * chunkSize * 2)
            val res = engine.separateChunk(chunk, sampleRate, channels = 2)
            totalInferenceMs += res.processingTimeMs
            rtfSum += res.rtf
        }

        engine.release()

        val avgChunkMs = if (totalChunks > 0) totalInferenceMs / totalChunks else 0L
        val measuredRtf = if (totalChunks > 0) rtfSum / totalChunks else 0.5f
        val isRealTime = measuredRtf < 1.0f

        val recommended = when {
            measuredRtf < 0.35f -> SeparationMode.QUALITY
            measuredRtf < 0.75f -> SeparationMode.BALANCED
            else -> SeparationMode.PERFORMANCE
        }

        val summary = if (isRealTime) {
            "Real-Time capable (RTF: ${"%.2f".format(measuredRtf)}x). Processing is faster than playback."
        } else {
            "High computational load (RTF: ${"%.2f".format(measuredRtf)}x). Recommend Performance mode."
        }

        BenchmarkResult(
            modelId = profile.id,
            modelName = profile.name,
            rtf = measuredRtf,
            averageChunkMs = avgChunkMs,
            isRealTimeCapable = isRealTime,
            recommendedMode = recommended,
            summary = summary
        )
    }
}
