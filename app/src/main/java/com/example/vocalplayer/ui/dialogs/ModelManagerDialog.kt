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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.theme.VocalCyan
import com.example.ui.theme.VocalPink
import com.example.ui.theme.VocalPurple
import com.example.vocalplayer.neural.DownloadPhase
import com.example.vocalplayer.neural.DownloadProgress
import com.example.vocalplayer.neural.NeuralModelProfile

@Composable
fun ModelManagerDialog(
    models: List<NeuralModelProfile>,
    activeModel: NeuralModelProfile,
    downloadingModelId: String? = null,
    downloadProgress: DownloadProgress? = null,
    onSelectModel: (NeuralModelProfile) -> Unit,
    onDownloadModel: (NeuralModelProfile) -> Unit,
    onDeleteModel: (NeuralModelProfile) -> Unit,
    onImportOnnxClicked: () -> Unit,
    onRunBenchmarkClicked: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = VocalCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Neural Models & Storage",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = VocalCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "SHA-256 Verified App-Private Storage",
                            color = VocalCyan,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Manage neural separation models. ONNX models are verified and executed securely on-device.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { profile ->
                        val isSelected = profile.id == activeModel.id
                        val isDownloadingThis = downloadingModelId == profile.id
                        val isAvailable = profile.isBuiltIn || profile.isDownloaded

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) StudioSurfaceVariant else StudioDarkBg)
                                .border(
                                    1.dp,
                                    if (isSelected) VocalCyan else StudioCardBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = isAvailable && !isDownloadingThis) {
                                    onSelectModel(profile)
                                }
                                .padding(12.dp)
                                .testTag("model_item_${profile.id}")
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.name,
                                                color = if (isSelected) VocalCyan else TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(StudioSurface)
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = profile.fileSizeFormatted,
                                                    color = TextSecondary,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = profile.description,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Action Area: Selected check, Download button, or Delete button
                                    if (isDownloadingThis) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = VocalCyan,
                                            strokeWidth = 2.5.dp
                                        )
                                    } else if (!isAvailable && profile.downloadUrl != null) {
                                        Button(
                                            onClick = { onDownloadModel(profile) },
                                            colors = ButtonDefaults.buttonColors(containerColor = VocalCyan),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.testTag("download_model_${profile.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudDownload,
                                                contentDescription = "Download",
                                                tint = StudioDarkBg,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Get", color = StudioDarkBg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(VocalCyan),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = StudioDarkBg,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else if (!profile.isBuiltIn && profile.isDownloaded) {
                                        IconButton(
                                            onClick = { onDeleteModel(profile) },
                                            modifier = Modifier.size(28.dp).testTag("delete_model_${profile.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Model",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                // Download & Verification Progress
                                if (isDownloadingThis && downloadProgress != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress.progressFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = VocalCyan,
                                            trackColor = StudioSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = downloadProgress.statusMessage,
                                            color = VocalCyan,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onImportOnnxClicked,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_onnx_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = VocalCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import .ONNX", color = VocalCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onRunBenchmarkClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = VocalPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("benchmark_model_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = StudioDarkBg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Benchmark", color = StudioDarkBg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
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

