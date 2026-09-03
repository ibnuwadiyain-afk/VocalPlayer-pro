package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vocalplayer.neural.ModelDownloader
import com.example.vocalplayer.neural.ModelVerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloaderTest {

    private lateinit var context: Context
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        downloader = ModelDownloader(context)
    }

    @Test
    fun testSecureDirectoryExists() {
        val dir = downloader.secureModelsDir
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
        assertTrue(dir.absolutePath.contains("secure_onnx_models"))
    }

    @Test
    fun testFileNameSanitization() {
        val safe1 = downloader.sanitizeModelFileName("my_model.onnx")
        assertEquals("my_model.onnx", safe1)

        val safe2 = downloader.sanitizeModelFileName("model with spaces & symbols!")
        assertEquals("model_with_spaces___symbols_.onnx", safe2)
    }

    @Test(expected = SecurityException::class)
    fun testDirectoryTraversalPrevented() {
        downloader.sanitizeModelFileName("../../../etc/passwd")
    }

    @Test
    fun testSha256CalculationAndVerification() {
        // Create a test file with known content
        val testFile = File(downloader.secureModelsDir, "test_model.onnx")
        val content = ByteArray(4096) { (it % 256).toByte() }
        FileOutputStream(testFile).use { it.write(content) }

        // Expected SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(content).joinToString("") { "%02x".format(it) }

        val calculatedSha = downloader.calculateSha256(testFile)
        assertEquals(expectedSha, calculatedSha)

        val result = downloader.verifyModelFile(
            file = testFile,
            expectedSha256 = expectedSha,
            expectedSizeBytes = 4096L
        )

        assertTrue("Expected verification success, got: $result", result is ModelVerificationResult.Success)

        testFile.delete()
    }

    @Test
    fun testChecksumMismatchDetection() {
        val testFile = File(downloader.secureModelsDir, "corrupted_model.onnx")
        val content = ByteArray(2048) { 0x08.toByte() }
        FileOutputStream(testFile).use { it.write(content) }

        val fakeSha = "0000000000000000000000000000000000000000000000000000000000000000"
        val result = downloader.verifyModelFile(testFile, expectedSha256 = fakeSha)

        assertTrue(result is ModelVerificationResult.ChecksumMismatch)

        testFile.delete()
    }

    @Test
    fun testDeleteModel() {
        val testFile = downloader.getSecureModelFile("to_delete")
        val content = ByteArray(2048) { 0x08.toByte() }
        FileOutputStream(testFile).use { it.write(content) }

        assertTrue(downloader.isModelLocallyAvailable("to_delete"))
        val deleted = downloader.deleteModel("to_delete")
        assertTrue(deleted)
        assertFalse(downloader.isModelLocallyAvailable("to_delete"))
    }
}
