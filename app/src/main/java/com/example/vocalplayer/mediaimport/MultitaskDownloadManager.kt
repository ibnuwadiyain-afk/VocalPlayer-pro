package com.example.vocalplayer.mediaimport

import android.content.Context
import android.util.Log
import com.example.vocalplayer.export.ExportNameNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Multitask Background Media Download Manager.
 * Handles multiple concurrent web downloads in background coroutines,
 * tracks individual download progress, speed, and state,
 * and allows pausing/cancelling/removing tasks without freezing the player or UI.
 */
class MultitaskDownloadManager(
    private val context: Context,
    private val processor: SocialMediaVideoProcessor
) {
    private val tag = "MultitaskDownloadMgr"
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tasks = MutableStateFlow<List<BackgroundDownloadTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundDownloadTask>> = _tasks.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Enqueues a new media download into the multitask queue.
     * Starts execution immediately in the background on IO dispatcher.
     */
    fun enqueueDownload(
        probedInfo: ProbedMediaInfo,
        selectedOption: MediaResolutionOption
    ): String {
        val taskId = UUID.randomUUID().toString()
        val newTask = BackgroundDownloadTask(
            id = taskId,
            sourceUrl = probedInfo.originalUrl,
            title = probedInfo.title,
            platformName = probedInfo.platformName,
            resolutionLabel = selectedOption.label,
            format = selectedOption.format,
            selectedOption = selectedOption,
            probedInfo = probedInfo,
            status = DownloadTaskStatus.QUEUED,
            statusMessage = "Starting download..."
        )

        _tasks.update { listOf(newTask) + it }

        val job = managerScope.launch {
            executeDownloadTask(taskId, probedInfo, selectedOption)
        }
        activeJobs[taskId] = job

        return taskId
    }

    private suspend fun executeDownloadTask(
        taskId: String,
        probedInfo: ProbedMediaInfo,
        selectedOption: MediaResolutionOption
    ) {
        updateTask(taskId) {
            it.copy(
                status = DownloadTaskStatus.RESOLVING,
                statusMessage = "Resolving stream link..."
            )
        }

        try {
            val resolved = processor.resolveMediaStream(probedInfo, selectedOption)
            when (resolved) {
                is ResolvedMediaResult.Error -> {
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadTaskStatus.FAILED,
                            errorMessage = resolved.message,
                            statusMessage = "Resolution failed: ${resolved.message}"
                        )
                    }
                }
                is ResolvedMediaResult.Success -> {
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadTaskStatus.DOWNLOADING,
                            statusMessage = "Downloading stream..."
                        )
                    }

                    // Preserve Arabic characters in output filename
                    val safeTitle = ExportNameNormalizer.sanitizeFileName(probedInfo.title)
                    val outputFileName = "${safeTitle}_${selectedOption.id}"

                    val downloadedFile = processor.downloadMedia(
                        resolved = resolved,
                        outputFileName = outputFileName,
                        onProgress = { progress ->
                            updateTask(taskId) { task ->
                                task.copy(
                                    status = DownloadTaskStatus.DOWNLOADING,
                                    progress = progress.percentage,
                                    bytesDownloaded = progress.bytesDownloaded,
                                    totalBytes = progress.totalBytes,
                                    speedKbps = progress.speedKbps,
                                    statusMessage = progress.stage
                                )
                            }
                        }
                    )

                    updateTask(taskId) {
                        it.copy(
                            status = DownloadTaskStatus.COMPLETED,
                            progress = 1.0f,
                            completedFile = downloadedFile,
                            statusMessage = "Download completed!"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                updateTask(taskId) {
                    it.copy(
                        status = DownloadTaskStatus.CANCELLED,
                        statusMessage = "Cancelled"
                    )
                }
            } else {
                Log.e(tag, "Download task $taskId failed: ${e.message}", e)
                updateTask(taskId) {
                    it.copy(
                        status = DownloadTaskStatus.FAILED,
                        errorMessage = e.message ?: "Network or host error",
                        statusMessage = "Failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        } finally {
            activeJobs.remove(taskId)
        }
    }

    /**
     * Cancels an active or queued background download.
     */
    fun cancelTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        job?.cancel()
        updateTask(taskId) {
            it.copy(
                status = DownloadTaskStatus.CANCELLED,
                statusMessage = "Cancelled by user"
            )
        }
    }

    /**
     * Removes a completed or failed task from the list.
     */
    fun removeTask(taskId: String) {
        cancelTask(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
    }

    /**
     * Clears all completed, cancelled, or failed tasks.
     */
    fun clearFinishedTasks() {
        _tasks.update { list ->
            list.filter { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.QUEUED || it.status == DownloadTaskStatus.RESOLVING }
        }
    }

    private fun updateTask(taskId: String, block: (BackgroundDownloadTask) -> BackgroundDownloadTask) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) block(task) else task
            }
        }
    }
}
