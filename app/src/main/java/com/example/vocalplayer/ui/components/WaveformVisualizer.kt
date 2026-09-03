package com.example.vocalplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple
import kotlin.math.sin

/**
 * Animated real-time neural vocal spectrum waveform visualizer.
 */
@Composable
fun WaveformVisualizer(
    vocalEnergy: Float,
    instrumentalEnergy: Float,
    isVocalOnly: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        val width = size.width
        val height = size.height
        val barCount = 36
        val barWidth = width / (barCount * 1.6f)
        val gap = (width - barCount * barWidth) / (barCount - 1)
        val centerY = height / 2f

        val effectiveVocal = if (isPlaying) vocalEnergy.coerceIn(0.12f, 1.0f) else 0.05f
        val effectiveInst = if (isPlaying) instrumentalEnergy.coerceIn(0.08f, 1.0f) else 0.04f

        for (i in 0 until barCount) {
            val normX = i.toFloat() / barCount.toFloat()
            val wave = sin(normX * 12.0f + phase).toFloat()

            // Frequency envelope: mid frequencies (vocals) peak around center
            val formantWeight = 1.0f - (2.0f * (normX - 0.5f) * 2.0f * (normX - 0.5f)).coerceIn(0f, 0.7f)

            val vHeight = (height * 0.85f * effectiveVocal * formantWeight * (0.6f + 0.4f * wave)).coerceAtLeast(4.dp.toPx())
            val iHeight = (height * 0.65f * effectiveInst * (0.5f + 0.5f * sin(normX * 8.0f - phase).toFloat())).coerceAtLeast(3.dp.toPx())

            val x = i * (barWidth + gap)

            if (isVocalOnly) {
                // Vocal Only Mode: Glowing Cyan / Purple bars
                val barTop = centerY - vHeight / 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(VocalCyan, VocalPurple),
                        startY = barTop,
                        endY = barTop + vHeight
                    ),
                    topLeft = Offset(x, barTop),
                    size = Size(barWidth, vHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            } else {
                // Original Audio Mode: Dual Vocal (Cyan) + Instrumental (Pink) layers
                val totalH = (vHeight + iHeight).coerceAtMost(height * 0.95f)
                val barTop = centerY - totalH / 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(VocalCyan, VocalPink),
                        startY = barTop,
                        endY = barTop + totalH
                    ),
                    topLeft = Offset(x, barTop),
                    size = Size(barWidth, totalH),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
