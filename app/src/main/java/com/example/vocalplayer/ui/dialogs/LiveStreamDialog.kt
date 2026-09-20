package com.example.vocalplayer.ui.dialogs

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
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.example.ui.theme.VocalPurple

data class StreamPreset(
    val title: String,
    val description: String,
    val url: String,
    val type: String
)

val DEFAULT_STREAM_PRESETS = listOf(
    StreamPreset(
        title = "Akamai Big Buck Bunny (HLS)",
        description = "Apple HLS Adaptive Live Stream (.m3u8)",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        type = "HLS"
    ),
    StreamPreset(
        title = "Tears of Steel (DASH)",
        description = "MPEG-DASH Multi-bitrate Stream (.mpd)",
        url = "https://dash.akamaized.net/akamai/test/caption_test/ElephantsDream/elephants_dream_480p_heaac5_1.mpd",
        type = "DASH"
    ),
    StreamPreset(
        title = "Sintel HD Video (MP4 HTTP)",
        description = "High Definition Stereo Video Stream",
        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
        type = "MP4"
    )
)

@Composable
fun LiveStreamDialog(
    initialUrl: String? = null,
    onPlayStream: (url: String, title: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var streamUrl by remember { mutableStateOf(initialUrl ?: "") }
    var streamTitle by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(VocalCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = VocalCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Play Live Stream",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "HLS (.m3u8), DASH (.mpd), RTSP & HTTP Video",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Stream URL Input Field
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("Stream URL (e.g. https://.../stream.m3u8)") },
                    placeholder = { Text("https://example.com/live.m3u8", color = TextSecondary.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = VocalCyan,
                        unfocusedBorderColor = StudioCardBorder,
                        focusedLabelColor = VocalCyan,
                        unfocusedLabelColor = TextSecondary,
                        focusedContainerColor = StudioDarkBg,
                        unfocusedContainerColor = StudioDarkBg
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    streamUrl = clip.trim()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste from clipboard",
                                tint = VocalCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_url_input")
                )

                // Optional Custom Stream Name
                OutlinedTextField(
                    value = streamTitle,
                    onValueChange = { streamTitle = it },
                    label = { Text("Stream Label / Name (Optional)") },
                    placeholder = { Text("My Live Channel", color = TextSecondary.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = VocalCyan,
                        unfocusedBorderColor = StudioCardBorder,
                        focusedLabelColor = VocalCyan,
                        unfocusedLabelColor = TextSecondary,
                        focusedContainerColor = StudioDarkBg,
                        unfocusedContainerColor = StudioDarkBg
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_title_input")
                )

                // Real-time Neural Separation Notice
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioDarkBg)
                        .border(1.dp, VocalGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null,
                            tint = VocalGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Real-time Neural Engine: Live stream audio is processed with on-the-fly vocal extraction without buffering the full stream.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // Sample Presets Section
                Text(
                    text = "QUICK TEST PRESETS",
                    color = VocalCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DEFAULT_STREAM_PRESETS.forEach { preset ->
                        val isSelected = streamUrl == preset.url
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioSurfaceVariant else StudioDarkBg)
                                .border(
                                    1.dp,
                                    if (isSelected) VocalCyan else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    streamUrl = preset.url
                                    if (streamTitle.isBlank()) streamTitle = preset.title
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = preset.title,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(VocalCyan.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = preset.type,
                                                color = VocalCyan,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = preset.description,
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = VocalCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (streamUrl.isNotBlank()) {
                        onPlayStream(streamUrl.trim(), streamTitle.ifBlank { null })
                        onDismiss()
                    }
                },
                enabled = streamUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VocalCyan,
                    disabledContainerColor = StudioSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("play_stream_confirm_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = StudioDarkBg,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play Stream", color = StudioDarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary, fontSize = 12.sp)
            }
        }
    )
}
