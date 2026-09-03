package com.example.vocalplayer.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPurple

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    volume: Float,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    val activePos = if (isDraggingSlider) dragPositionMs else currentPositionMs
    val validDuration = durationMs.coerceAtLeast(1L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("player_controls_column")
    ) {
        // Scrubber / Seek Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDuration(activePos),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Slider(
                value = (activePos.toFloat() / validDuration.toFloat()).coerceIn(0f, 1f),
                onValueChange = { ratio ->
                    isDraggingSlider = true
                    dragPositionMs = (ratio * validDuration).toLong()
                },
                onValueChangeFinished = {
                    onSeek(dragPositionMs)
                    isDraggingSlider = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = VocalCyan,
                    activeTrackColor = VocalCyan,
                    inactiveTrackColor = StudioSurfaceVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .testTag("playback_seekbar")
            )

            Text(
                text = formatDuration(durationMs),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Action Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed Menu
            Box {
                IconButton(
                    onClick = { showSpeedMenu = true },
                    modifier = Modifier.testTag("speed_menu_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${playbackSpeed}x",
                            color = VocalCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false }
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${speed}x",
                                    color = if (playbackSpeed == speed) VocalCyan else TextPrimary,
                                    fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSpeedChange(speed)
                                showSpeedMenu = false
                            }
                        )
                    }
                }
            }

            // Rewind 10s
            IconButton(
                onClick = { onSeek((currentPositionMs - 10000L).coerceAtLeast(0L)) },
                modifier = Modifier.testTag("rewind_10_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "Rewind 10s",
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Play / Pause Main Button
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(VocalCyan)
                    .clickable(onClick = onPlayPause)
                    .testTag("play_pause_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = StudioDarkBg,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Forward 10s
            IconButton(
                onClick = { onSeek((currentPositionMs + 10000L).coerceAtMost(durationMs)) },
                modifier = Modifier.testTag("forward_10_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Forward 10s",
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Fullscreen Button
            IconButton(
                onClick = onFullscreenToggle,
                modifier = Modifier.testTag("fullscreen_toggle_button")
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit Fullscreen" else "Enter Fullscreen",
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
