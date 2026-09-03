package com.example.vocalplayer.ui.dialogs

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.theme.VocalOrange
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple
import com.example.vocalplayer.neural.BenchmarkResult
import com.example.vocalplayer.neural.NeuralModelProfile

@Composable
fun BenchmarkDialog(
    activeModel: NeuralModelProfile,
    isBenchmarking: Boolean,
    benchmarkResult: BenchmarkResult?,
    onRunBenchmark: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = VocalPurple,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Device Neural Benchmark",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tests neural inference speed (Real-Time Factor: processing time / audio duration) on your Android device hardware.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                if (isBenchmarking) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioDarkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = VocalCyan, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Running neural separation on test audio...",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else if (benchmarkResult != null) {
                    val rtfColor = if (benchmarkResult.isRealTimeCapable) VocalGreen else VocalOrange

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(StudioDarkBg)
                            .border(1.dp, rtfColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = benchmarkResult.modelName,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(rtfColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (benchmarkResult.isRealTimeCapable) "RTF < 1.0 PASS" else "HEAVY",
                                        color = rtfColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${"%.2f".format(benchmarkResult.rtf)}x",
                                        color = rtfColor,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(text = "Real-Time Factor", color = TextSecondary, fontSize = 10.sp)
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${benchmarkResult.averageChunkMs} ms",
                                        color = TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(text = "Per 1.0s Chunk", color = TextSecondary, fontSize = 10.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Auto Recommended Mode: ${benchmarkResult.recommendedMode.displayName}",
                                color = VocalCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioDarkBg)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Active Model: ${activeModel.name}\nTap 'Run Benchmark' below to measure RTF.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onRunBenchmark,
                    enabled = !isBenchmarking,
                    colors = ButtonDefaults.buttonColors(containerColor = VocalPurple),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_benchmark_action_button")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = StudioDarkBg)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBenchmarking) "Benchmarking..." else "Run Benchmark Now",
                        color = StudioDarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = VocalCyan, fontWeight = FontWeight.Bold)
            }
        }
    )
}
