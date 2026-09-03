package com.example

import android.os.Bundle
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
                VocalVideoPlayer(this).also { player = it }
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

