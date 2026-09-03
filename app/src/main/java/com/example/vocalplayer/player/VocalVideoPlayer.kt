package com.example.vocalplayer.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.vocalplayer.audio.NeuralAudioProcessor
import com.example.vocalplayer.data.SampleClipsManager
import com.example.vocalplayer.neural.ModelManager
import com.example.vocalplayer.neural.NeuralModelProfile
import com.example.vocalplayer.neural.NeuralSeparationEngine
import com.example.vocalplayer.neural.SeparationConfig
import com.example.vocalplayer.neural.SeparationMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class VocalVideoPlayer(private val context: Context) {

    private val modelManager = ModelManager(context)
    private val sampleClipsManager = SampleClipsManager(context)

    private val separationEngine = NeuralSeparationEngine(
        context = context,
        config = SeparationConfig(mode = SeparationMode.BALANCED)
    )

    private val audioProcessor = NeuralAudioProcessor(separationEngine)

    val exoPlayer: ExoPlayer

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressTrackerJob: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    init {
        // Setup Media3 ExoPlayer with our custom neural audio processing sink
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(audioProcessor))
                    .build()
            }
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory).build()

        setupPlayerListeners()
        setupAudioProcessorMetrics()
        refreshModelsList()
        loadDemoClips()
    }

    private fun setupPlayerListeners() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isBuffering = playbackState == Player.STATE_BUFFERING
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
                _uiState.update {
                    it.copy(
                        isBuffering = isBuffering,
                        durationMs = duration,
                        hasMediaLoaded = playbackState != Player.STATE_IDLE
                    )
                }
            }
        })
    }

    private fun setupAudioProcessorMetrics() {
        audioProcessor.onMetricsUpdated = { rtf, latencyMs, vocalEnergy, instEnergy, bufferHealth ->
            _uiState.update {
                it.copy(
                    rtf = rtf,
                    latencyMs = latencyMs,
                    vocalEnergy = vocalEnergy,
                    instrumentalEnergy = instEnergy,
                    bufferHealth = bufferHealth
                )
            }
        }
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch {
            while (isActive) {
                val pos = exoPlayer.currentPosition
                val dur = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackerJob?.cancel()
    }

    fun play() {
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun setSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun setVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
        _uiState.update { it.copy(volume = volume) }
    }

    fun toggleVocalOnly() {
        val newState = !_uiState.value.isVocalOnly
        audioProcessor.isVocalOnlyEnabled = newState
        _uiState.update { it.copy(isVocalOnly = newState) }
    }

    fun setVocalIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        audioProcessor.vocalMixRatio = clamped
        _uiState.update { it.copy(vocalIntensity = clamped) }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun loadMedia(uri: Uri, title: String? = null) {
        val resolvedTitle = title ?: resolveFileName(uri) ?: "Local Media File"
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()

        _uiState.update {
            it.copy(
                mediaUri = uri,
                mediaTitle = resolvedTitle,
                hasMediaLoaded = true,
                currentPositionMs = 0L
            )
        }
    }

    fun setSeparationMode(mode: SeparationMode) {
        val newConfig = separationEngine.config.copy(mode = mode, threadCount = mode.defaultThreads)
        separationEngine.updateConfig(newConfig)
        _uiState.update { it.copy(separationMode = mode, threadCount = mode.defaultThreads) }
    }

    fun setThreadCount(threads: Int) {
        val newConfig = separationEngine.config.copy(threadCount = threads)
        separationEngine.updateConfig(newConfig)
        _uiState.update { it.copy(threadCount = threads) }
    }

    fun selectModel(profile: NeuralModelProfile) {
        val success = separationEngine.setModelProfile(profile)
        modelManager.setActiveModel(profile)
        _uiState.update {
            it.copy(
                activeModel = profile,
                statusMessage = if (success) "Switched to ${profile.name}" else "Reverted to Built-in model"
            )
        }
    }

    fun downloadModel(profile: NeuralModelProfile) {
        scope.launch {
            _uiState.update {
                it.copy(
                    downloadingModelId = profile.id,
                    statusMessage = "Starting download for ${profile.name}..."
                )
            }
            val result = modelManager.downloadModel(profile) { progress ->
                _uiState.update {
                    it.copy(downloadProgress = progress)
                }
            }
            result.onSuccess { updatedProfile ->
                refreshModelsList()
                selectModel(updatedProfile)
                _uiState.update {
                    it.copy(
                        downloadingModelId = null,
                        downloadProgress = null,
                        statusMessage = "Installed & verified: ${updatedProfile.name}"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        downloadingModelId = null,
                        downloadProgress = null,
                        statusMessage = "Download failed: ${error.localizedMessage ?: error.message}"
                    )
                }
            }
        }
    }

    fun deleteModel(profile: NeuralModelProfile) {
        val success = modelManager.deleteModel(profile)
        refreshModelsList()
        if (profile.id == _uiState.value.activeModel.id) {
            selectModel(NeuralModelProfile.DEFAULT_BUILTIN)
        }
        _uiState.update {
            it.copy(
                statusMessage = if (success) "Removed ${profile.name}" else "Failed to delete ${profile.name}"
            )
        }
    }

    fun importModel(uri: Uri, displayName: String) {
        scope.launch {
            _uiState.update { it.copy(statusMessage = "Importing ONNX model...") }
            val result = modelManager.importModelFromUri(uri, displayName)
            result.onSuccess { profile ->
                refreshModelsList()
                selectModel(profile)
                _uiState.update { it.copy(statusMessage = "Successfully imported: ${profile.name}") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Failed to import model: ${err.localizedMessage}") }
            }
        }
    }

    fun runBenchmark(profile: NeuralModelProfile = _uiState.value.activeModel) {
        scope.launch {
            _uiState.update { it.copy(isBenchmarking = true, benchmarkResult = null) }
            val result = modelManager.runBenchmark(profile)
            _uiState.update {
                it.copy(
                    isBenchmarking = false,
                    benchmarkResult = result,
                    statusMessage = "Benchmark complete: RTF ${"%.2f".format(result.rtf)}x"
                )
            }
        }
    }

    private fun refreshModelsList() {
        val models = modelManager.getAllModels()
        _uiState.update { it.copy(availableModels = models) }
    }

    private fun loadDemoClips() {
        scope.launch {
            val clips = sampleClipsManager.getDemoClips()
            _uiState.update { it.copy(demoClips = clips) }
        }
    }

    fun showModelManagerDialog(show: Boolean) {
        _uiState.update { it.copy(showModelManagerDialog = show) }
    }

    fun showSettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSettingsDialog = show) }
    }

    fun showBenchmarkDialog(show: Boolean) {
        _uiState.update { it.copy(showBenchmarkDialog = show) }
    }

    fun showDemoClipsDialog(show: Boolean) {
        _uiState.update { it.copy(showDemoClipsDialog = show) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }

    fun release() {
        stopProgressTracker()
        exoPlayer.release()
        separationEngine.release()
    }
}
