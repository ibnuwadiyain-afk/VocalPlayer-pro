package com.example.vocalplayer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple
import com.example.vocalplayer.player.VocalVideoPlayer
import com.example.vocalplayer.ui.components.HudStatusBadge
import com.example.vocalplayer.ui.components.OfflineExtractionCard
import com.example.vocalplayer.ui.components.PlayerControls
import com.example.vocalplayer.ui.components.VideoSurface
import com.example.vocalplayer.ui.components.VocalToggleBar
import com.example.vocalplayer.ui.components.WaveformVisualizer
import com.example.vocalplayer.ui.dialogs.BenchmarkDialog
import com.example.vocalplayer.ui.dialogs.ExportVideoDialog
import com.example.vocalplayer.ui.dialogs.ModelManagerDialog
import com.example.vocalplayer.ui.dialogs.SettingsDialog
import com.example.vocalplayer.ui.dialogs.UrlImportDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocalPlayerScreen(
    player: VocalVideoPlayer,
    modifier: Modifier = Modifier
) {
    val uiState by player.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Video media picker using standard zero-permission Photo & Video picker contract
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            player.loadMedia(uri)
        }
    }

    // Document picker fallback for audio/video files
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            player.loadMedia(uri)
        }
    }

    // Custom ONNX Model file importer
    val onnxFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            player.importModel(uri, "Custom ONNX Model")
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            player.clearStatusMessage()
        }
    }

    if (uiState.isFullscreen && uiState.hasMediaLoaded) {
        // Fullscreen Mode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VideoSurface(
                exoPlayer = player.exoPlayer,
                hasMediaLoaded = uiState.hasMediaLoaded,
                isBuffering = uiState.isBuffering,
                isVocalOnly = uiState.isVocalOnly,
                isPlaying = uiState.isPlaying,
                isEnded = uiState.isEnded,
                isExtractingVocals = uiState.isExtractingVocals,
                isStreamingVocal = uiState.isStreamingVocal,
                extractionProgress = uiState.extractionProgress,
                extractionElapsedSec = uiState.extractionElapsedSec,
                onTogglePlayPause = { player.togglePlayPause() },
                onOpenFilePicker = {
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay Controls in Fullscreen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(16.dp)
            ) {
                PlayerControls(
                    isPlaying = uiState.isPlaying,
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs,
                    playbackSpeed = uiState.playbackSpeed,
                    volume = uiState.volume,
                    isFullscreen = uiState.isFullscreen,
                    onPlayPause = { player.togglePlayPause() },
                    onSeek = { pos -> player.seekTo(pos) },
                    onSpeedChange = { speed -> player.setSpeed(speed) },
                    onVolumeChange = { vol -> player.setVolume(vol) },
                    onFullscreenToggle = { player.toggleFullscreen() }
                )
            }
        }
    } else {
        // Standard Portrait / Studio Layout
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(listOf(VocalCyan, VocalPurple))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = StudioDarkBg,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "VocalPlayer",
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Offline Neural Source Separation",
                                    color = VocalCyan,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { player.showUrlImportDialog(true) },
                            modifier = Modifier.testTag("action_import_url")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Import Web Media",
                                tint = VocalCyan
                            )
                        }

                        if (uiState.isVocalCached) {
                            IconButton(
                                onClick = { player.showExportVideoDialog(true) },
                                modifier = Modifier.testTag("action_export_video")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "Export Video (Instruments Muted)",
                                    tint = VocalCyan
                                )
                            }
                        }

                        IconButton(
                            onClick = { player.showModelManagerDialog(true) },
                            modifier = Modifier.testTag("action_models")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "Models",
                                tint = VocalPurple
                            )
                        }

                        IconButton(
                            onClick = { player.showSettingsDialog(true) },
                            modifier = Modifier.testTag("action_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = StudioDarkBg
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = StudioDarkBg,
            modifier = modifier.fillMaxSize()
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Video Screen Area
                VideoSurface(
                    exoPlayer = player.exoPlayer,
                    hasMediaLoaded = uiState.hasMediaLoaded,
                    isBuffering = uiState.isBuffering,
                    isVocalOnly = uiState.isVocalOnly,
                    isPlaying = uiState.isPlaying,
                    isEnded = uiState.isEnded,
                    isExtractingVocals = uiState.isExtractingVocals,
                    isStreamingVocal = uiState.isStreamingVocal,
                    extractionProgress = uiState.extractionProgress,
                    extractionElapsedSec = uiState.extractionElapsedSec,
                    onTogglePlayPause = { player.togglePlayPause() },
                    onOpenFilePicker = {
                        videoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onOpenUrlImport = { player.showUrlImportDialog(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )

                // Currently Playing Title (if loaded)
                if (uiState.hasMediaLoaded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.mediaTitle,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Offline Spleeter Extraction & Cache Progress Card
                OfflineExtractionCard(
                    isExtracting = uiState.isExtractingVocals,
                    isCached = uiState.isVocalCached,
                    isStreaming = uiState.isStreamingVocal,
                    streamedDurationMs = uiState.streamedDurationMs,
                    elapsedSeconds = uiState.extractionElapsedSec,
                    extractionStage = uiState.extractionStage,
                    extractionProgress = uiState.extractionProgress,
                    cacheSizeMb = uiState.cacheSizeMb,
                    hasMediaLoaded = uiState.hasMediaLoaded,
                    onStartExtraction = { player.extractVocalsOffline() },
                    onCancelExtraction = { player.cancelVocalExtraction() },
                    onClearCache = { player.clearVocalCache() },
                    onExportVideo = { player.showExportVideoDialog(true) }
                )

                // Waveform Spectrum Visualizer
                WaveformVisualizer(
                    vocalEnergy = uiState.vocalEnergy,
                    instrumentalEnergy = uiState.instrumentalEnergy,
                    isVocalOnly = uiState.isVocalOnly,
                    isPlaying = uiState.isPlaying
                )

                // Prominent VOCAL ONLY <---> ORIGINAL AUDIO Toggle
                VocalToggleBar(
                    isVocalOnly = uiState.isVocalOnly,
                    vocalIntensity = uiState.vocalIntensity,
                    canToggleVocalOnly = uiState.canToggleVocalOnly,
                    onToggle = { player.toggleVocalOnly() },
                    onIntensityChange = { ratio -> player.setVocalIntensity(ratio) }
                )

                // Real-time Neural HUD Telemetry
                HudStatusBadge(
                    rtf = uiState.rtf,
                    latencyMs = uiState.latencyMs,
                    bufferHealth = uiState.bufferHealth,
                    activeModel = uiState.activeModel,
                    mode = uiState.separationMode,
                    isVocalOnly = uiState.isVocalOnly
                )

                // Playback Controls (Play, Seek, Speed, Fullscreen)
                PlayerControls(
                    isPlaying = uiState.isPlaying,
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs,
                    playbackSpeed = uiState.playbackSpeed,
                    volume = uiState.volume,
                    isFullscreen = uiState.isFullscreen,
                    onPlayPause = { player.togglePlayPause() },
                    onSeek = { pos -> player.seekTo(pos) },
                    onSpeedChange = { speed -> player.setSpeed(speed) },
                    onVolumeChange = { vol -> player.setVolume(vol) },
                    onFullscreenToggle = { player.toggleFullscreen() }
                )

                // Bottom Quick Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_video_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = StudioDarkBg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Video", color = StudioDarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            documentPickerLauncher.launch(arrayOf("video/*", "audio/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioSurfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_audio_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("All Media", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Dialog Sheets
    if (uiState.showModelManagerDialog) {
        ModelManagerDialog(
            models = uiState.availableModels,
            activeModel = uiState.activeModel,
            downloadingModelId = uiState.downloadingModelId,
            downloadProgress = uiState.downloadProgress,
            onSelectModel = { model -> player.selectModel(model) },
            onDownloadModel = { model -> player.downloadModel(model) },
            onDeleteModel = { model -> player.deleteModel(model) },
            onImportOnnxClicked = {
                onnxFilePickerLauncher.launch(arrayOf("*/*"))
            },
            onRunBenchmarkClicked = {
                player.showModelManagerDialog(false)
                player.showBenchmarkDialog(true)
                player.runBenchmark(uiState.activeModel)
            },
            onDismiss = { player.showModelManagerDialog(false) }
        )
    }

    if (uiState.showSettingsDialog) {
        SettingsDialog(
            currentMode = uiState.separationMode,
            currentThreadCount = uiState.threadCount,
            currentLanguage = uiState.appLanguage,
            onModeSelected = { mode -> player.setSeparationMode(mode) },
            onThreadCountSelected = { count -> player.setThreadCount(count) },
            onLanguageSelected = { lang -> player.setAppLanguage(lang) },
            onDismiss = { player.showSettingsDialog(false) }
        )
    }

    if (uiState.showBenchmarkDialog) {
        BenchmarkDialog(
            activeModel = uiState.activeModel,
            isBenchmarking = uiState.isBenchmarking,
            benchmarkResult = uiState.benchmarkResult,
            onRunBenchmark = { player.runBenchmark(uiState.activeModel) },
            onDismiss = { player.showBenchmarkDialog(false) }
        )
    }

    if (uiState.showExportDialog) {
        ExportVideoDialog(
            isExporting = uiState.isExportingVideo,
            progress = uiState.exportProgress,
            stage = uiState.exportStage,
            exportResult = uiState.exportResult,
            errorMessage = uiState.exportErrorMessage,
            deleteOriginal = uiState.deleteOriginalAfterExport,
            onDeleteOriginalChange = { player.setDeleteOriginalAfterExport(it) },
            isPipelinedReady = uiState.isPipelinedExportReady,
            currentLanguage = uiState.appLanguage,
            onShareVideo = { player.shareExportedVideo() },
            onPlayExportedVideo = { player.playExportedVideo() },
            onCancelExport = { player.cancelExport() },
            onStartExport = { player.exportMutedInstrumentsVideo() },
            onDismiss = { player.dismissExportDialog() }
        )
    }

    if (uiState.showUrlImportDialog) {
        UrlImportDialog(
            isProbing = uiState.isProbingUrl,
            isDownloading = uiState.isDownloadingMedia,
            probedInfo = uiState.probedMediaInfo,
            selectedOption = uiState.selectedResolutionOption,
            importProgress = uiState.importProgress,
            errorMessage = uiState.importErrorMessage,
            backgroundDownloads = uiState.backgroundDownloads,
            currentLanguage = uiState.appLanguage,
            onProbeUrl = { url -> player.probeUrl(url) },
            onSelectOption = { option -> player.selectImportResolutionOption(option) },
            onStartDownload = { player.downloadAndLoadImportedMedia() },
            onCancelDownload = { player.cancelDirectDownload() },
            onEnqueueBackgroundDownload = { player.enqueueBackgroundDownload() },
            onCancelBackgroundTask = { taskId -> player.cancelBackgroundDownload(taskId) },
            onRemoveBackgroundTask = { taskId -> player.removeBackgroundDownload(taskId) },
            onClearFinishedTasks = { player.clearCompletedBackgroundDownloads() },
            onLoadCompletedMedia = { file, title -> player.loadMediaFromFile(file, title) },
            onDismiss = { player.showUrlImportDialog(false) }
        )
    }
}
