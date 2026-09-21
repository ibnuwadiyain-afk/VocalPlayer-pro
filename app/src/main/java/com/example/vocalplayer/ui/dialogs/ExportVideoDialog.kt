package com.example.vocalplayer.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalGreen
import com.example.ui.theme.VocalOrange
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple
import com.example.vocalplayer.export.ExportResult
import com.example.vocalplayer.i18n.AppLanguage
import com.example.vocalplayer.i18n.AppStrings

@Composable
fun ExportVideoDialog(
    isExporting: Boolean,
    progress: Float,
    stage: String?,
    exportResult: ExportResult?,
    errorMessage: String?,
    deleteOriginal: Boolean = false,
    onDeleteOriginalChange: (Boolean) -> Unit = {},
    isPipelinedReady: Boolean = false,
    currentLanguage: AppLanguage = AppLanguage.ENGLISH,
    onShareVideo: () -> Unit,
    onPlayExportedVideo: () -> Unit,
    onCancelExport: () -> Unit,
    onStartExport: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = {
        if (!isExporting) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
            shadowElevation = 16.dp,
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("export_video_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Elegant Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        exportResult != null -> VocalGreen.copy(alpha = 0.15f)
                                        errorMessage != null -> VocalPink.copy(alpha = 0.15f)
                                        else -> VocalCyan.copy(alpha = 0.15f)
                                    }
                                )
                                .border(
                                    1.dp,
                                    when {
                                        exportResult != null -> VocalGreen.copy(alpha = 0.4f)
                                        errorMessage != null -> VocalPink.copy(alpha = 0.4f)
                                        else -> VocalCyan.copy(alpha = 0.4f)
                                    },
                                    RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    exportResult != null -> Icons.Default.CheckCircle
                                    errorMessage != null -> Icons.Default.ErrorOutline
                                    else -> Icons.Default.Movie
                                },
                                contentDescription = null,
                                tint = when {
                                    exportResult != null -> VocalGreen
                                    errorMessage != null -> VocalPink
                                    else -> VocalCyan
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = when {
                                    exportResult != null -> "Export Ready"
                                    errorMessage != null -> "Export Failed"
                                    isExporting -> "Exporting Video"
                                    else -> "Export Muted Video"
                                },
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            )
                            Text(
                                text = "Muted Instruments • Lead Vocals Only",
                                color = VocalCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    if (!isExporting) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(StudioSurfaceVariant.copy(alpha = 0.6f))
                                .testTag("close_export_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // STATE 1: EXPORTING IN PROGRESS
                if (isExporting) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Status badge / stage text
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioDarkBg)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = VocalCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stage ?: "Simultaneous pipelined muxing with video stream...",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = VocalCyan,
                            trackColor = StudioSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pipelined 0-wait concurrent cache",
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                color = VocalCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedButton(
                            onClick = onCancelExport,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("cancel_export_button")
                        ) {
                            Text("Cancel", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }

                // STATE 2: EXPORT COMPLETED
                if (exportResult != null && !isExporting) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Summary Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(StudioDarkBg)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = exportResult.title,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(VocalGreen.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "MP4 Ready",
                                            color = VocalGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                val sizeMb = exportResult.fileSizeBytes.toFloat() / (1024f * 1024f)
                                val durationSec = (exportResult.durationMs / 1000L).toInt()
                                val minutes = durationSec / 60
                                val seconds = durationSec % 60
                                val timeText = "%d:%02d".format(minutes, seconds)

                                Text(
                                    text = "Duration: $timeText  •  Size: ${"%.1f".format(sizeMb)} MB  •  AAC 192k",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )

                                Text(
                                    text = "Saved to Movies/VocalPlayer",
                                    color = VocalCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                if (exportResult.originalDeleted) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(VocalOrange.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                tint = VocalOrange,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Original video was successfully removed to free device storage.",
                                                color = VocalOrange,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Background instruments were muted with studio precision. Only isolated vocal audio is preserved.",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onShareVideo,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VocalCyan,
                                    contentColor = StudioDarkBg
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("share_exported_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Share Video",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = onPlayExportedVideo,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioSurfaceElevated,
                                    contentColor = TextPrimary
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("play_exported_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Play Video",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .testTag("done_export_button")
                        ) {
                            Text("Done", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }

                // STATE 3: PRE-EXPORT SETTINGS / CHECKBOX (When not exporting and no result yet)
                if (!isExporting && exportResult == null && errorMessage == null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Export your video with instrumental audio replaced by zero-latency isolated vocals.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        // Option card: Delete Original Video checkbox
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(StudioDarkBg)
                                .border(
                                    1.dp,
                                    if (deleteOriginal) VocalOrange.copy(alpha = 0.5f) else StudioCardBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onDeleteOriginalChange(!deleteOriginal) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("delete_original_video_checkbox_card")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = deleteOriginal,
                                    onCheckedChange = onDeleteOriginalChange,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = VocalOrange,
                                        uncheckedColor = TextTertiary,
                                        checkmarkColor = StudioDarkBg
                                    ),
                                    modifier = Modifier.testTag("delete_original_video_checkbox")
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Delete original video after export",
                                        color = if (deleteOriginal) TextPrimary else TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Automatically frees up internal storage once vocal export completes successfully.",
                                        color = TextTertiary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        // Simultaneous pipelined acceleration info
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioSurfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = VocalCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Accelerated by simultaneous neural chunk merging — instant finalization with zero export lag.",
                                    color = VocalCyan.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        // Start Export Button
                        if (onStartExport != null) {
                            Button(
                                onClick = onStartExport,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VocalCyan,
                                    contentColor = StudioDarkBg
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("confirm_export_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = AppStrings.get("start_export", currentLanguage),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // STATE 4: ERROR STATE
                if (errorMessage != null && !isExporting) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(VocalPink.copy(alpha = 0.1f))
                                .border(1.dp, VocalPink.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = errorMessage,
                                color = VocalPink,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = StudioSurfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text("Close", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

