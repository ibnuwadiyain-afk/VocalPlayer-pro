package com.example.vocalplayer.mediaimport

import java.io.File

/**
 * Available stream resolution / format option discovered during pre-download probe.
 */
data class MediaResolutionOption(
    val id: String, // e.g. "1080p", "720p", "480p", "360p", "audio"
    val label: String, // e.g. "1080p (Full HD)", "720p (HD - Fast)", "Audio Only (320kbps MP3)"
    val height: Int, // 1080, 720, 480, 360, 0
    val format: String, // "mp4", "m4a", "mp3", "webm"
    val sizeText: String? = null, // e.g. "45.2 MB", "Est. 18 MB"
    val isRecommended: Boolean = false,
    val internalKey: String? = null, // Y2mate format token or internal resolution identifier
    val directUrl: String? = null, // Direct progressive URL if already known
    val isFromSource: Boolean = false
)

/**
 * Result of stream resolution containing the direct streamable URL or downloaded target.
 */
sealed class ResolvedMediaResult {
    data class Success(
        val streamUrl: String,
        val title: String,
        val headers: Map<String, String> = emptyMap(),
        val isHls: Boolean = false,
        val durationMs: Long = 0L,
        val thumbnailUrl: String? = null
    ) : ResolvedMediaResult()

    data class Error(val message: String) : ResolvedMediaResult()
}

/**
 * Information extracted from probing a media URL before download.
 */
data class ProbedMediaInfo(
    val originalUrl: String,
    val title: String,
    val platformName: String,
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val resolutions: List<MediaResolutionOption> = emptyList(),
    val isHls: Boolean = false,
    val defaultDirectUrl: String? = null
)

/**
 * State for downloading streams into local storage.
 */
data class MediaImportProgress(
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val percentage: Float = 0f,
    val speedKbps: Float = 0f,
    val stage: String = "Connecting...",
    val completedFile: File? = null
)
