package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
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
                VocalVideoPlayer(this).also {
                    player = it
                    handleIncomingIntent(intent, it)
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    videoPlayer.release()
                }
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

    private fun handleIncomingIntent(incomingIntent: Intent?, targetPlayer: VocalVideoPlayer) {
        if (incomingIntent == null) return
        val action = incomingIntent.action
        val dataUri: Uri? = incomingIntent.data

        if (Intent.ACTION_VIEW == action && dataUri != null) {
            try {
                Log.i("MainActivity", "Handling incoming media URI: $dataUri (type=${incomingIntent.type})")
                targetPlayer.loadMedia(dataUri)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to handle incoming media URI: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

