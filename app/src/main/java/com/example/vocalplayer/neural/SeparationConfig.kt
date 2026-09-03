package com.example.vocalplayer.neural

/**
 * Processing mode for balancing latency, computational load, and vocal extraction quality.
 */
enum class SeparationMode(
    val displayName: String,
    val description: String,
    val defaultThreads: Int,
    val fftSize: Int,
    val hopSize: Int,
    val overlapRatio: Float
) {
    PERFORMANCE(
        displayName = "Performance",
        description = "Lowest CPU and battery load. Optimized for real-time responsiveness.",
        defaultThreads = 2,
        fftSize = 512,
        hopSize = 256,
        overlapRatio = 0.25f
    ),
    BALANCED(
        displayName = "Balanced",
        description = "Recommended default. High vocal clarity with smooth real-time playback.",
        defaultThreads = 4,
        fftSize = 1024,
        hopSize = 256,
        overlapRatio = 0.50f
    ),
    QUALITY(
        displayName = "Quality",
        description = "Highest vocal isolation fidelity with deep harmonic resolution.",
        defaultThreads = 4,
        fftSize = 2048,
        hopSize = 512,
        overlapRatio = 0.50f
    )
}

/**
 * Configuration options for neural source separation.
 */
data class SeparationConfig(
    val mode: SeparationMode = SeparationMode.BALANCED,
    val threadCount: Int = 4,
    val chunkDurationMs: Int = 1000,
    val targetSampleRate: Int = 44100,
    val vocalGain: Float = 1.0f,
    val instrumentalSuppression: Float = 0.95f
) {
    val fftSize: Int get() = mode.fftSize
    val hopSize: Int get() = mode.hopSize
    val overlapRatio: Float get() = mode.overlapRatio
}
