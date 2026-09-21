package com.example.vocalplayer.mediaimport

import java.io.File

/**
 * Lifecycle status of a background media download task.
 */
enum class DownloadTaskStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Task model for concurrent background multitask downloading.
 */
data class BackgroundDownloadTask(
    val id: String, // Unique task UUID
    val sourceUrl: String,
    val title: String,
    val platformName: String,
    val resolutionLabel: String,
    val format: String,
    val selectedOption: MediaResolutionOption,
    val probedInfo: ProbedMediaInfo,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val progress: Float = 0f,
    val speedKbps: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String = "Queued",
    val completedFile: File? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)
