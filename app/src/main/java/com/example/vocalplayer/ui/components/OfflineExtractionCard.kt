package com.example.vocalplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.theme.VocalGreen
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple

/**
 * Visual card displaying offline Spleeter vocal extraction progress,
 * cache status, and fast action triggers.
 */
@Composable
fun OfflineExtractionCard(
    isExtracting: Boolean,
    isCached: Boolean,
    extractionStage: String?,
    extractionProgress: Float,
    cacheSizeMb: Float,
    hasMediaLoaded: Boolean,
    isStreaming: Boolean = false,
    streamedDurationMs: Long = 0L,
    elapsedSeconds: Long = 0L,
    onStartExtraction: () -> Unit,
    onCancelExtraction: () -> Unit,
    onClearCache: () -> Unit,
    onExportVideo: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!hasMediaLoaded && !isExtracting) return

    val formattedElapsed = remember(elapsedSeconds) {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    val borderColor = when {
        isCached -> VocalGreen.copy(alpha = 0.5f)
        isStreaming -> VocalCyan.copy(alpha = 0.6f)
        else -> StudioCardBorder
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StudioSurface)
            .border(
                1.dp,
                borderColor,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
            .animateContentSize()
            .testTag("offline_extraction_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                // CASE 1: Actively Extracting with Streaming Playback Support
                isExtracting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isStreaming) VocalGreen.copy(alpha = 0.2f) else VocalCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Extracting",
                                    tint = if (isStreaming) VocalGreen else VocalCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = if (isStreaming) "Playing Separated Chunks Live" else "Neural Vocal Isolation",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isStreaming) {
                                        "${extractionStage ?: "Processing"} • ${streamedDurationMs / 1000}s ready"
                                    } else {
                                        extractionStage ?: "Processing audio track..."
                                    },
                                    color = if (isStreaming) VocalGreen else VocalCyan,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Elapsed Time Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StudioDarkBg)
                                    .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Elapsed time",
                                        tint = if (isStreaming) VocalGreen else VocalCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = formattedElapsed,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = onCancelExtraction,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (isStreaming) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(VocalGreen.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⚡ Isolated vocal playback active • Elapsed: $formattedElapsed • ${streamedDurationMs / 1000}s ready",
                                color = VocalGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { extractionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isStreaming) VocalGreen else VocalCyan,
                        trackColor = StudioSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isStreaming) "Audio plays continuously • $formattedElapsed elapsed" else "Elapsed: $formattedElapsed • Separating chunks",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "${(extractionProgress * 100).toInt()}%",
                            color = if (isStreaming) VocalGreen else VocalCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // CASE 2: Demucs Vocals Successfully Cached
                isCached -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(VocalGreen.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Cached",
                                    tint = VocalGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Spleeter Vocals Cached",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(VocalGreen.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "0ms Lag",
                                            color = VocalGreen,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = if (elapsedSeconds > 0) {
                                        "Extracted in $formattedElapsed • ${"%.1f".format(cacheSizeMb)} MB Cached"
                                    } else {
                                        "Deezer Spleeter 2-Stem ONNX • ${"%.1f".format(cacheSizeMb)} MB Cached"
                                    },
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onStartExtraction,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Re-extract",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = onClearCache,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Clear Cache",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (onExportVideo != null) {
                        Button(
                            onClick = onExportVideo,
                            colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("export_muted_video_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = StudioDarkBg,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Export Video (Instruments Muted)",
                                color = StudioDarkBg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // CASE 3: Media loaded but not yet cached
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(VocalPurple.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Process Offline",
                                    tint = VocalPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = "Spleeter Offline Isolation",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Extract vocals once to cache for 100% lag-free playback",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = onStartExtraction,
                            colors = ButtonDefaults.buttonColors(containerColor = VocalPurple),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("extract_vocals_button")
                        ) {
                            Text("Extract", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
