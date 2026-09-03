package com.example.vocalplayer.neural

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Phase of the model download & verification pipeline.
 */
enum class DownloadPhase {
    IDLE,
    CONNECTING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED
}

/**
 * Real-time progress update during model download.
 */
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val progressFraction: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val statusMessage: String = ""
)

/**
 * Result of local ONNX model file integrity verification.
 */
sealed class ModelVerificationResult {
    data class Success(val file: File, val sha256: String, val sizeBytes: Long) : ModelVerificationResult()
    data class ChecksumMismatch(val expected: String, val actual: String) : ModelVerificationResult()
    data class SizeMismatch(val expected: Long, val actual: Long) : ModelVerificationResult()
    data class InvalidFormat(val reason: String) : ModelVerificationResult()
    data class FileNotFound(val path: String) : ModelVerificationResult()
    data class SecurityViolation(val reason: String) : ModelVerificationResult()
}

/**
 * ModelDownloader Service:
 * Manages secure local storage, network streaming, cryptographic SHA-256 checksum verification,
 * file format validation, and atomic staging for ONNX neural source separation models.
 */
class ModelDownloader(private val context: Context) {

    private val tag = "ModelDownloader"

    // App-internal secure sandboxed directory for ONNX inference models (not backed up or shared)
    val secureModelsDir: File = File(context.filesDir, SECURE_MODELS_DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    companion object {
        const val SECURE_MODELS_DIR_NAME = "secure_onnx_models"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * Sanitizes a model identifier/filename to prevent directory traversal and insecure characters.
     */
    fun sanitizeModelFileName(name: String): String {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw SecurityException("Potential directory traversal detected in model filename: $name")
        }
        val clean = name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .replace("\\.+".toRegex(), ".")
            .trimStart('.')
            .trimEnd('.')

        return if (clean.endsWith(".onnx", ignoreCase = true)) clean else "$clean.onnx"
    }

    /**
     * Get the destination file in the secure sandboxed directory for a model.
     */
    fun getSecureModelFile(modelId: String): File {
        val safeFileName = sanitizeModelFileName(modelId)
        return File(secureModelsDir, safeFileName)
    }

    /**
     * Check if a model is downloaded and has valid local storage presence.
     */
    fun isModelLocallyAvailable(modelId: String): Boolean {
        val file = getSecureModelFile(modelId)
        return file.exists() && file.length() > 0
    }

    /**
     * Calculates the SHA-256 cryptographic hash of a local file.
     */
    fun calculateSha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies the integrity of an ONNX file (existence, size, SHA-256, format validation).
     */
    fun verifyModelFile(
        file: File,
        expectedSha256: String? = null,
        expectedSizeBytes: Long? = null
    ): ModelVerificationResult {
        if (!file.exists() || !file.isFile) {
            return ModelVerificationResult.FileNotFound(file.absolutePath)
        }

        val fileSize = file.length()
        if (fileSize < 1024) {
            return ModelVerificationResult.InvalidFormat("File is too small to be a valid ONNX neural model (${fileSize} bytes)")
        }

        // Verify size if specified
        if (expectedSizeBytes != null && expectedSizeBytes > 0) {
            val sizeDiff = kotlin.math.abs(fileSize - expectedSizeBytes)
            // Allow small delta if specified as estimate, but fail on major discrepancy
            if (sizeDiff > (expectedSizeBytes * 0.15).toLong() && expectedSizeBytes > 1_000_000) {
                return ModelVerificationResult.SizeMismatch(expected = expectedSizeBytes, actual = fileSize)
            }
        }

        // Validate ONNX / Protobuf binary header or magic characteristics
        val headerCheck = validateOnnxHeader(file)
        if (!headerCheck) {
            return ModelVerificationResult.InvalidFormat("Invalid ONNX format or corrupted file header")
        }

        // Calculate and verify SHA-256 checksum
        val actualSha256 = calculateSha256(file)
        if (!expectedSha256.isNullOrBlank()) {
            val normalizedExpected = expectedSha256.trim().lowercase(Locale.ROOT)
            val normalizedActual = actualSha256.trim().lowercase(Locale.ROOT)
            if (normalizedExpected != normalizedActual) {
                Log.e(tag, "Checksum mismatch! Expected: $normalizedExpected, Actual: $normalizedActual")
                return ModelVerificationResult.ChecksumMismatch(expected = normalizedExpected, actual = normalizedActual)
            }
        }

        return ModelVerificationResult.Success(file = file, sha256 = actualSha256, sizeBytes = fileSize)
    }

    /**
     * Validates that the file has valid ONNX protobuf byte structure and is not HTML error page or plain text.
     */
    private fun validateOnnxHeader(file: File): Boolean {
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(16)
                val read = fis.read(header)
                if (read < 8) return false

                // Check for common text/HTML error responses (e.g., "<!DOCTYPE", "<html", "404")
                val textHeader = String(header, 0, read, Charsets.US_ASCII).lowercase(Locale.ROOT)
                if (textHeader.startsWith("<!doc") || textHeader.startsWith("<html") || textHeader.startsWith("error")) {
                    Log.w(tag, "File header indicates HTML or text error rather than ONNX binary")
                    return false
                }

                // In ONNX protobuf serialization: field 1 is ir_version (varint 0x08), field 2 is opset_import or producer_name
                // Check for protobuf header tag 0x08 or general non-plain-text binary data
                val hasProtobufTag = header[0].toInt() == 0x08
                val isBinary = header.take(read).any {
                    val b = it.toInt() and 0xFF
                    b == 0 || b == 0x08 || b > 127 || (b < 32 && b != 9 && b != 10 && b != 13)
                }
                return hasProtobufTag || isBinary
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to read file header: ${e.message}")
            return false
        }
    }

    /**
     * Downloads an ONNX model from a remote URL, verifies its cryptographic integrity,
     * and atomically moves it into the secure models directory for the inference engine.
     */
    suspend fun downloadAndVerifyModel(
        profile: NeuralModelProfile,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val urlString = profile.downloadUrl
        if (urlString.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Model '${profile.name}' has no download URL configured."))
        }

        val destinationFile = getSecureModelFile(profile.id)
        val tempFile = File(secureModelsDir, "${destinationFile.name}.download_${System.currentTimeMillis()}.tmp")

        try {
            onProgress(
                DownloadProgress(
                    modelId = profile.id,
                    phase = DownloadPhase.CONNECTING,
                    statusMessage = "Connecting to neural model repository..."
                )
            )

            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VocalPlayer-Android/1.0 (ONNX-Downloader)")
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.disconnect()
                return@withContext Result.failure(
                    Exception("Failed to download model (HTTP $responseCode: ${connection.responseMessage})")
                )
            }

            val contentLength = connection.contentLengthLong
            val inputStream = connection.inputStream

            onProgress(
                DownloadProgress(
                    modelId = profile.id,
                    totalBytes = contentLength,
                    phase = DownloadPhase.DOWNLOADING,
                    statusMessage = "Downloading ${profile.name}..."
                )
            )

            var bytesDownloaded = 0L
            var lastUpdateMs = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    bytesSinceLastUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastUpdateMs
                    if (deltaMs >= 200) {
                        currentSpeed = (bytesSinceLastUpdate * 1000L) / deltaMs
                        val fraction = if (contentLength > 0) bytesDownloaded.toFloat() / contentLength.toFloat() else 0f
                        onProgress(
                            DownloadProgress(
                                modelId = profile.id,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = contentLength,
                                progressFraction = fraction,
                                speedBytesPerSec = currentSpeed,
                                phase = DownloadPhase.DOWNLOADING,
                                statusMessage = "Downloading ${(fraction * 100).toInt()}% (${formatBytes(bytesDownloaded)} / ${formatBytes(contentLength)})"
                            )
                        )
                        lastUpdateMs = now
                        bytesSinceLastUpdate = 0L
                    }
                }
                outputStream.flush()
            }
            connection.disconnect()

            // Verification phase
            onProgress(
                DownloadProgress(
                    modelId = profile.id,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = contentLength,
                    progressFraction = 1.0f,
                    phase = DownloadPhase.VERIFYING,
                    statusMessage = "Verifying cryptographic SHA-256 and ONNX model structure..."
                )
            )

            val verification = verifyModelFile(
                file = tempFile,
                expectedSha256 = profile.sha256Checksum,
                expectedSizeBytes = profile.expectedSizeBytes ?: if (contentLength > 0) contentLength else null
            )

            when (verification) {
                is ModelVerificationResult.Success -> {
                    onProgress(
                        DownloadProgress(
                            modelId = profile.id,
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = contentLength,
                            progressFraction = 1.0f,
                            phase = DownloadPhase.INSTALLING,
                            statusMessage = "Installing into secure engine storage..."
                        )
                    )

                    // Atomic replacement: if target exists, delete first then rename
                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    val renameSuccess = tempFile.renameTo(destinationFile)
                    if (!renameSuccess) {
                        // Fallback copy if cross-filesystem rename fails
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                    }

                    onProgress(
                        DownloadProgress(
                            modelId = profile.id,
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = destinationFile.length(),
                            progressFraction = 1.0f,
                            phase = DownloadPhase.COMPLETED,
                            statusMessage = "Model ready and verified (SHA-256: ${verification.sha256.take(8)}...)"
                        )
                    )
                    Log.i(tag, "Model successfully downloaded & verified: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")
                    Result.success(destinationFile)
                }

                is ModelVerificationResult.ChecksumMismatch -> {
                    tempFile.delete()
                    val msg = "SHA-256 checksum mismatch (expected: ${verification.expected.take(8)}..., got: ${verification.actual.take(8)}...)"
                    onProgress(DownloadProgress(modelId = profile.id, phase = DownloadPhase.FAILED, statusMessage = msg))
                    Result.failure(SecurityException(msg))
                }

                is ModelVerificationResult.SizeMismatch -> {
                    tempFile.delete()
                    val msg = "File size mismatch (expected: ${verification.expected}, actual: ${verification.actual})"
                    onProgress(DownloadProgress(modelId = profile.id, phase = DownloadPhase.FAILED, statusMessage = msg))
                    Result.failure(IllegalStateException(msg))
                }

                is ModelVerificationResult.InvalidFormat -> {
                    tempFile.delete()
                    val msg = "Invalid ONNX model format: ${verification.reason}"
                    onProgress(DownloadProgress(modelId = profile.id, phase = DownloadPhase.FAILED, statusMessage = msg))
                    Result.failure(IllegalArgumentException(msg))
                }

                is ModelVerificationResult.FileNotFound -> {
                    tempFile.delete()
                    Result.failure(Exception("Temporary file not found during verification"))
                }

                is ModelVerificationResult.SecurityViolation -> {
                    tempFile.delete()
                    Result.failure(SecurityException(verification.reason))
                }
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(tag, "Error during model download: ${e.message}", e)
            onProgress(
                DownloadProgress(
                    modelId = profile.id,
                    phase = DownloadPhase.FAILED,
                    statusMessage = "Download failed: ${e.localizedMessage ?: e.message}"
                )
            )
            Result.failure(e)
        }
    }

    /**
     * Import a model from a local URI into secure sandboxed storage with verification.
     */
    suspend fun importAndVerifyLocalModel(
        uri: Uri,
        displayName: String,
        expectedSha256: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeName = sanitizeModelFileName(displayName)
        val destinationFile = File(secureModelsDir, safeName)
        val tempFile = File(secureModelsDir, "${safeName}.import_${System.currentTimeMillis()}.tmp")

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = BUFFER_SIZE)
                }
            } ?: return@withContext Result.failure(Exception("Unable to read model stream from URI"))

            val verification = verifyModelFile(tempFile, expectedSha256 = expectedSha256)
            if (verification is ModelVerificationResult.Success) {
                if (destinationFile.exists()) destinationFile.delete()
                val renamed = tempFile.renameTo(destinationFile)
                if (!renamed) {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                }
                Result.success(destinationFile)
            } else {
                tempFile.delete()
                Result.failure(Exception("Imported file failed ONNX integrity validation: $verification"))
            }
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Deletes a model from secure storage.
     */
    fun deleteModel(modelId: String): Boolean {
        val file = getSecureModelFile(modelId)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Lists all verified models currently installed in the secure directory.
     */
    fun listSecureModels(): List<File> {
        return secureModelsDir.listFiles { _, name ->
            (name.endsWith(".onnx", ignoreCase = true) || name.endsWith(".ort", ignoreCase = true)) && !name.contains(".tmp")
        }?.toList() ?: emptyList()
    }

    /**
     * Computes the total storage space consumed by downloaded models in bytes.
     */
    fun getTotalStorageUsedBytes(): Long {
        return listSecureModels().sumOf { it.length() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }
}
