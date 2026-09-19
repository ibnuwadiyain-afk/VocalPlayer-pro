package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vocalplayer.export.ExportResult
import com.example.vocalplayer.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportAndPipelinedMuxerTest {

    @Test
    fun testPlayerUiStateDeleteOptionDefaultAndToggle() {
        val defaultState = PlayerUiState()
        assertFalse(defaultState.deleteOriginalAfterExport)
        assertFalse(defaultState.isPipelinedExportReady)

        val updatedState = defaultState.copy(
            deleteOriginalAfterExport = true,
            isPipelinedExportReady = true
        )
        assertTrue(updatedState.deleteOriginalAfterExport)
        assertTrue(updatedState.isPipelinedExportReady)
    }

    @Test
    fun testExportResultContainsDeleteOriginalFlag() {
        val mockFile = File("/mock/path/video.mp4")
        val mockUri = android.net.Uri.parse("content://mock/video.mp4")
        val result = ExportResult(
            file = mockFile,
            mediaStoreUri = mockUri,
            fileProviderUri = mockUri,
            durationMs = 30000L,
            fileSizeBytes = 1048576L,
            title = "Test Track",
            originalDeleted = true
        )

        assertTrue(result.originalDeleted)
        assertEquals("Test Track", result.title)
        assertEquals(30000L, result.durationMs)
    }
}
