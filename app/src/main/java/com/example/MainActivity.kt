package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.ui.theme.MyApplicationTheme
import com.example.vocalplayer.player.VocalVideoPlayer
import com.example.vocalplayer.ui.VocalPlayerScreen

class MainActivity : ComponentActivity() {

    private var player: VocalVideoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val videoPlayer = remember {
                VocalVideoPlayer(this).also { player = it }
            }

            DisposableEffect(Unit) {
                onDispose {
                    videoPlayer.release()
                }
            }

            // Handle incoming shared links or media from other apps
            LaunchedEffect(intent) {
                handleIncomingIntent(intent, videoPlayer)
            }

            MyApplicationTheme {
                VocalPlayerScreen(
                    player = videoPlayer,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        player?.let { handleIncomingIntent(intent, it) }
    }

    private fun handleIncomingIntent(intent: Intent?, videoPlayer: VocalVideoPlayer) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return

        val type = intent.type ?: return
        if (type.startsWith("text/")) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Extract URL from shared text (e.g. from YouTube or Twitter share sheet)
                val urlRegex = Regex("""https?://[^\s]+""")
                val foundUrl = urlRegex.find(sharedText)?.value ?: sharedText.trim()
                videoPlayer.showUrlImportDialog(true)
                videoPlayer.probeUrl(foundUrl)
            }
        } else if (type.startsWith("video/") || type.startsWith("audio/")) {
            val mediaUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (mediaUri != null) {
                videoPlayer.loadMedia(mediaUri)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}


