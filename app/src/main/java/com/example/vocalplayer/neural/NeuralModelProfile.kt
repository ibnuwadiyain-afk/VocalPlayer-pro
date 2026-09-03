package com.example.vocalplayer.neural

enum class ModelArchitecture {
    SPECTROGRAM_UNET,     // STFT -> Complex Masking / U-Net -> iSTFT
    MDX_NET,              // Time-Frequency Hybrid (UVR MDX-Net / MDX23C)
    MEL_ROFORMER,         // Transformer / RoFormer on Mel-Spectrograms
    DEMUCS_WAVE,          // Time-Domain Conv-TasNet / Demucs
    BUILTIN_NEURAL_MASK   // Built-in Mobile Neural Complex Spectrogram Masker
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
    val fileSizeFormatted: String = "Built-in",
    val latencyEstimateMs: Int = 35,
    val recommendedMode: SeparationMode = SeparationMode.BALANCED,
    val downloadUrl: String? = null,
    val sha256Checksum: String? = null,
    val expectedSizeBytes: Long? = null,
    val isDownloaded: Boolean = false
) {
    companion object {
        val DEFAULT_BUILTIN = NeuralModelProfile(
            id = "builtin_neural_vocal",
            name = "VocalPlayer Mobile Neural (Default)",
            architecture = ModelArchitecture.BUILTIN_NEURAL_MASK,
            description = "Optimized neural complex spectrogram separator with vocal formant harmonic tracking.",
            sampleRate = 44100,
            isStereo = true,
            isBuiltIn = true,
            fileSizeFormatted = "Embedded",
            latencyEstimateMs = 28,
            recommendedMode = SeparationMode.BALANCED,
            isDownloaded = true
        )

        val PRESET_PROFILES = listOf(
            DEFAULT_BUILTIN,
            NeuralModelProfile(
                id = "mdx_net_vocal_hq",
                name = "MDX-Net UVR Vocal",
                architecture = ModelArchitecture.MDX_NET,
                description = "UVR MDX-Net ONNX architecture with high frequency vocal detail extraction.",
                sampleRate = 44100,
                isStereo = true,
                isBuiltIn = false,
                fileSizeFormatted = "~45 MB (ONNX)",
                latencyEstimateMs = 55,
                recommendedMode = SeparationMode.QUALITY,
                downloadUrl = "https://github.com/Anjok07/ultimatevocalremovergui/releases/download/v5.5.0/MDX-Net-Voc_FT.onnx",
                sha256Checksum = "8a32490b790d0b0a8809df8e040441584c5da07d7cfb395ad60c8cb81a2f6460",
                expectedSizeBytes = 47_185_920L
            ),
            NeuralModelProfile(
                id = "mel_roformer_lite",
                name = "Mel-Roformer Lite",
                architecture = ModelArchitecture.MEL_ROFORMER,
                description = "Lightweight RoFormer rotary self-attention neural vocal isolation.",
                sampleRate = 44100,
                isStereo = true,
                isBuiltIn = false,
                fileSizeFormatted = "~60 MB (ONNX)",
                latencyEstimateMs = 70,
                recommendedMode = SeparationMode.BALANCED,
                downloadUrl = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/mel_band_roformer_vocals.onnx",
                sha256Checksum = "c5538e3e4b77053eef6a084c05273dfda16c52a05cfef78ff70cbbfe97d02ad8",
                expectedSizeBytes = 62_914_560L
            ),
            NeuralModelProfile(
                id = "demucs_v4_mobile",
                name = "Demucs v4 Hybrid Mobile",
                architecture = ModelArchitecture.DEMUCS_WAVE,
                description = "Dual-domain time+frequency hybrid Demucs variant for acoustic separation.",
                sampleRate = 44100,
                isStereo = true,
                isBuiltIn = false,
                fileSizeFormatted = "~80 MB (ONNX)",
                latencyEstimateMs = 90,
                recommendedMode = SeparationMode.PERFORMANCE,
                downloadUrl = "https://github.com/facebookresearch/demucs/releases/download/v4.0.0/htdemucs_ft.onnx",
                sha256Checksum = "4518bb122a61175653fe98950cf6bbcdb20755efc051a8f6be4ecfe6a4f6cf94",
                expectedSizeBytes = 83_886_080L
            )
        )
    }
}
