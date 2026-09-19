package com.example.vocalplayer.neural

enum class ModelArchitecture {
    SPLEETER_2STEM,       // True Genuine Deezer Spleeter 2-Stem (Built-in ONNX)
    SPECTROGRAM_UNET,     // STFT -> Complex Masking / U-Net -> iSTFT
    MDX_NET,              // Time-Frequency Hybrid (UVR MDX-Net / MDX23C)
    MEL_ROFORMER,         // Transformer / RoFormer on Mel-Spectrograms
    DEMUCS_WAVE,          // Time-Domain Conv-TasNet / Demucs
    BUILTIN_NEURAL_MASK   // Legacy Mobile Spectrogram Masker
}

/**
 * Metadata descriptor for a neural source separation model.
 */
data class NeuralModelProfile(
    val id: String,
    val name: String,
    val architecture: ModelArchitecture,
    val description: String,
    val sampleRate: Int = 44100,
    val isStereo: Boolean = true,
    val isBuiltIn: Boolean = false,
    val modelPath: String? = null,
    val accompanimentModelPath: String? = null,
    val fileSizeFormatted: String = "Built-in",
    val latencyEstimateMs: Int = 15,
    val recommendedMode: SeparationMode = SeparationMode.PERFORMANCE,
    val downloadUrl: String? = null,
    val sha256Checksum: String? = null,
    val expectedSizeBytes: Long? = null,
    val isDownloaded: Boolean = false
) {
    companion object {
        val SPLEETER_2STEMS = NeuralModelProfile(
            id = "spleeter_2stems",
            name = "Deezer Spleeter 2-Stem",
            architecture = ModelArchitecture.SPLEETER_2STEM,
            description = "True genuine Deezer Spleeter 2-stem neural separation engine. High-fidelity vocal extraction with fast offline caching.",
            sampleRate = 44100,
            isStereo = true,
            isBuiltIn = true,
            fileSizeFormatted = "26 MB (Built-in)",
            latencyEstimateMs = 15,
            recommendedMode = SeparationMode.PERFORMANCE,
            isDownloaded = true
        )

        val DEFAULT_BUILTIN = SPLEETER_2STEMS

        val PRESET_PROFILES = listOf(
            SPLEETER_2STEMS
        )
    }
}
