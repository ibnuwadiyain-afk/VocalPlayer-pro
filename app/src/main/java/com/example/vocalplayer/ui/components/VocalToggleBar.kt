package com.example.vocalplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple

/**
 * Prominent Vocal Only vs Original Audio selector and isolation level controller.
 */
@Composable
fun VocalToggleBar(
    isVocalOnly: Boolean,
    vocalIntensity: Float,
    canToggleVocalOnly: Boolean = true,
    onToggle: () -> Unit,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val vocalBgGlow by animateColorAsState(
        targetValue = if (isVocalOnly) VocalCyan.copy(alpha = 0.18f) else Color.Transparent,
        label = "vocal_glow"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(StudioSurface)
            .border(1.dp, StudioCardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("vocal_toggle_container")
    ) {
        // Toggle Switch Segmented Control
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioDarkBg)
                .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Original Audio Segment
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (!isVocalOnly) StudioSurfaceVariant else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (isVocalOnly) onToggle() }
                        )
                        .testTag("toggle_original_audio"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Original Audio",
                            tint = if (!isVocalOnly) VocalPink else TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ORIGINAL AUDIO",
                            color = if (!isVocalOnly) TextPrimary else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (!isVocalOnly) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Vocal Only Segment
                val vocalOnlyAlpha = if (canToggleVocalOnly || isVocalOnly) 1f else 0.45f

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isVocalOnly) {
                                Modifier.background(
                                    Brush.horizontalGradient(
                                        colors = listOf(VocalCyan.copy(alpha = 0.85f), VocalPurple.copy(alpha = 0.85f))
                                    )
                                )
                            } else {
                                Modifier.background(Color.Transparent)
                            }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (canToggleVocalOnly || isVocalOnly) {
                                    onToggle()
                                } else {
                                    // Trigger status feedback explaining chunks are generating
                                    onToggle()
                                }
                            }
                        )
                        .testTag("toggle_vocal_only"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (canToggleVocalOnly || isVocalOnly) Icons.Default.Mic else Icons.Default.HourglassEmpty,
                            contentDescription = "Vocal Only",
                            tint = if (isVocalOnly) StudioDarkBg else TextTertiary.copy(alpha = vocalOnlyAlpha),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isVocalOnly -> "VOCAL ONLY"
                                !canToggleVocalOnly -> "GENERATING..."
                                else -> "VOCAL ONLY"
                            },
                            color = if (isVocalOnly) StudioDarkBg else TextSecondary.copy(alpha = vocalOnlyAlpha),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        // Info message when vocal toggle is waiting for first chunk
        if (!canToggleVocalOnly && !isVocalOnly) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⏳ Vocal Only unlocks automatically once first separated chunk is ready",
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }
        }

        // Intensity slider when Vocal Only is active
        if (isVocalOnly) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Vocal Isolation Strength",
                        tint = VocalCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Vocal Isolation Strength",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "${(vocalIntensity * 100).toInt()}%",
                    color = VocalCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = vocalIntensity,
                onValueChange = onIntensityChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = VocalCyan,
                    activeTrackColor = VocalCyan,
                    inactiveTrackColor = StudioSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vocal_intensity_slider")
            )
        }
    }
}
