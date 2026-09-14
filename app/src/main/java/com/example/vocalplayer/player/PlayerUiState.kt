package com.example.vocalplayer.player

import android.net.Uri
import com.example.vocalplayer.data.DemoClip
import com.example.vocalplayer.neural.BenchmarkResult
import com.example.vocalplayer.neural.DownloadProgress
import com.example.vocalplayer.neural.NeuralModelProfile
import com.example.vocalplayer.neural.SeparationConfig
import com.example.vocalplayer.neural.SeparationMode

/**
 * Complete UI state for VocalPlayer.
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val mediaUri: Uri? = null,
    val mediaTitle: String = "No media selected",
    val isVocalOnly: Boolean = false,
    val vocalIntensity: Float = 1.0f,
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val isFullscreen: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val hasMediaLoaded: Boolean = false,

    // Offline Demucs Vocal Extraction & Caching state
    val isVocalCached: Boolean = false,
    val isExtractingVocals: Boolean = false,
    val extractionStage: String? = null,
    val extractionProgress: Float = 0.0f,
    val cacheSizeMb: Float = 0.0f,

    // Real-time neural processing telemetry
    val rtf: Float = 0.42f,
    val latencyMs: Long = 28L,
    val bufferHealth: Float = 0.85f,
    val vocalEnergy: Float = 0.0f,
    val instrumentalEnergy: Float = 0.0f,

    // Configuration & models
    val activeModel: NeuralModelProfile = NeuralModelProfile.DEFAULT_BUILTIN,
    val separationMode: SeparationMode = SeparationMode.BALANCED,
    val threadCount: Int = 4,
    val availableModels: List<NeuralModelProfile> = emptyList(),
    val demoClips: List<DemoClip> = emptyList(),

    // Model downloader & verification state
    val downloadingModelId: String? = null,
    val downloadProgress: DownloadProgress? = null,

    // Benchmark state
    val benchmarkResult: BenchmarkResult? = null,
    val isBenchmarking: Boolean = false,

    // Dialog sheets
    val showModelManagerDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val showBenchmarkDialog: Boolean = false,
    val showDemoClipsDialog: Boolean = false,
    val statusMessage: String? = null
)
