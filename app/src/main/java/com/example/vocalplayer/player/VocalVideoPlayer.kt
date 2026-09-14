package com.example.vocalplayer.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class VocalVideoPlayer(private val context: Context) {
    private val tag = "VocalVideoPlayer"

    val cacheManager = com.example.vocalplayer.cache.VocalCacheManager(context)
    private val modelManager = ModelManager(context)
    private val sampleClipsManager = SampleClipsManager(context, cacheManager)
    val offlineVocalSeparator = com.example.vocalplayer.neural.OfflineVocalSeparator(context, cacheManager, modelManager)

    private val separationEngine = NeuralSeparationEngine(
        context = context,
        config = SeparationConfig(mode = SeparationMode.PERFORMANCE)
    )

    private val audioProcessor = NeuralAudioProcessor(separationEngine, cacheManager)

    val exoPlayer: ExoPlayer

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressTrackerJob: Job? = null
    private var extractionJob: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    @Volatile private var latestPlaybackPositionMs: Long = 0L

    init {
        // Setup Media3 ExoPlayer with our custom neural audio processing sink and optimized LoadControl
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf<AudioProcessor>(audioProcessor))
                    .build()
            }
        }

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 30000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        audioProcessor.positionMsProvider = { latestPlaybackPositionMs }
        audioProcessor.isVocalOnlyEnabled = false

        setupPlayerListeners()
        setupAudioProcessorMetrics()
        refreshModelsList()
        val initialModel = modelManager.getActiveModel()
        selectModel(initialModel)
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
                val isEnded = playbackState == Player.STATE_ENDED
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
                _uiState.update {
                    it.copy(
                        isBuffering = isBuffering,
                        isEnded = isEnded,
                        durationMs = duration,
                        hasMediaLoaded = it.mediaUri != null
                    )
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val rootCause = error.cause?.localizedMessage ?: error.cause?.message ?: error.localizedMessage ?: error.message
                Log.e(tag, "ExoPlayer playback error: $rootCause", error)
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        statusMessage = "Playback error: $rootCause"
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
                latestPlaybackPositionMs = pos
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
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            _uiState.value.mediaUri?.let { uri ->
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
            }
        }
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            latestPlaybackPositionMs = 0L
            exoPlayer.seekTo(0L)
            audioProcessor.resetStreamPosition(0L)
        }
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
        latestPlaybackPositionMs = positionMs
        exoPlayer.seekTo(positionMs)
        audioProcessor.resetStreamPosition(positionMs)
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
        audioProcessor.activeMediaUri = uri
        val isCached = cacheManager.isVocalCached(uri)

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        _uiState.update {
            it.copy(
                mediaUri = uri,
                mediaTitle = resolvedTitle,
                hasMediaLoaded = true,
                currentPositionMs = 0L,
                isVocalCached = isCached,
                cacheSizeMb = cacheManager.getCacheSizeMb(),
                statusMessage = if (isCached) "Demucs Vocals Loaded (0ms Lag Cached Playback)" else null
            )
        }

        if (isCached) {
            cacheManager.setActiveMedia(uri)
            audioProcessor.resetStreamPosition(0L)
            exoPlayer.seekTo(0L)
            exoPlayer.play()
        } else {
            // Keep at start while Demucs extraction isolates vocals in background
            exoPlayer.pause()
            exoPlayer.seekTo(0L)
            extractVocalsOffline(uri)
        }
    }

    fun extractVocalsOffline(targetUri: Uri? = null) {
        val uri = targetUri ?: _uiState.value.mediaUri ?: return
        if (cacheManager.isVocalCached(uri)) {
            cacheManager.setActiveMedia(uri)
            audioProcessor.activeMediaUri = uri
            audioProcessor.resetStreamPosition(exoPlayer.currentPosition)
            _uiState.update {
                it.copy(
                    isVocalCached = true,
                    statusMessage = "Demucs Vocals already cached (0ms Lag)"
                )
            }
            return
        }

        extractionJob?.cancel()
        extractionJob = scope.launch {
            _uiState.update {
                it.copy(
                    isExtractingVocals = true,
                    extractionStage = "Starting Demucs offline isolation...",
                    extractionProgress = 0.0f
                )
            }

            val success = offlineVocalSeparator.extractAndCacheVocals(uri) { prog ->
                _uiState.update {
                    it.copy(
                        extractionStage = prog.stage,
                        extractionProgress = prog.progress
                    )
                }
            }

            if (success) {
                withContext(Dispatchers.Main) {
                    cacheManager.setActiveMedia(uri)
                    audioProcessor.activeMediaUri = uri
                    latestPlaybackPositionMs = 0L
                    audioProcessor.resetStreamPosition(0L)
                    exoPlayer.seekTo(0L)
                    exoPlayer.play()
                }
            }

            _uiState.update {
                it.copy(
                    isExtractingVocals = false,
                    isVocalCached = success,
                    cacheSizeMb = cacheManager.getCacheSizeMb(),
                    statusMessage = if (success) "Demucs Vocals Cached! 0ms Lag-Free Playback Ready" else "Demucs extraction error"
                )
            }
        }
    }

    fun cancelVocalExtraction() {
        extractionJob?.cancel()
        extractionJob = null
        _uiState.update {
            it.copy(
                isExtractingVocals = false,
                extractionStage = null,
                statusMessage = "Vocal extraction cancelled"
            )
        }
    }

    fun clearVocalCache() {
        val freed = cacheManager.clearCache()
        val currentUri = _uiState.value.mediaUri
        _uiState.update {
            it.copy(
                isVocalCached = if (currentUri != null) cacheManager.isVocalCached(currentUri) else false,
                cacheSizeMb = 0.0f,
                statusMessage = "Cleared ${freed / (1024 * 1024)} MB vocal cache"
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
        cancelVocalExtraction()
        exoPlayer.release()
        audioProcessor.activeMediaUri = null
        separationEngine.release()
        offlineVocalSeparator.release()
    }
}
