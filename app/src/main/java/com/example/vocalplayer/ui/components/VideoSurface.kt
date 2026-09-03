package com.example.vocalplayer.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPurple

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    exoPlayer: ExoPlayer,
    hasMediaLoaded: Boolean,
    isBuffering: Boolean,
    isVocalOnly: Boolean,
    onOpenFilePicker: () -> Unit,
    onOpenDemos: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .testTag("video_surface_container"),
        contentAlignment = Alignment.Center
    ) {
        if (hasMediaLoaded) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    if (view.player != exoPlayer) {
                        view.player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Buffering Indicator
            if (isBuffering) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = VocalCyan,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            // Small Floating "Vocal Only" or "Original" badge in top-left of video
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isVocalOnly) VocalCyan.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isVocalOnly) "VOCAL STEM ACTIVE" else "ORIGINAL AUDIO",
                    color = if (isVocalOnly) StudioDarkBg else TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Empty State: Hero Banner & Prompts
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(StudioSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "No video loaded",
                        tint = VocalCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Offline Neural Vocal Player",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Select any local video or audio file to extract synchronized vocals in real time.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenFilePicker,
                        colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("empty_open_video_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = StudioDarkBg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Open Video", color = StudioDarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onOpenDemos,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("empty_demo_clips_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = VocalPurple,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Demo Clips", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
