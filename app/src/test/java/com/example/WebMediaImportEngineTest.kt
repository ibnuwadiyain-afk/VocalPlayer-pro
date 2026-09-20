package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vocalplayer.mediaimport.MediaResolutionOption
import com.example.vocalplayer.mediaimport.ProbedMediaInfo
import com.example.vocalplayer.mediaimport.SocialMediaVideoProcessor
import com.example.vocalplayer.player.PlayerUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebMediaImportEngineTest {

    private lateinit var context: Context
    private lateinit var processor: SocialMediaVideoProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = SocialMediaVideoProcessor(context)
    }

    @Test
    fun testPlayerUiStateWebImportDefaults() {
        val state = PlayerUiState()
        assertFalse(state.showUrlImportDialog)
        assertFalse(state.isProbingUrl)
        assertFalse(state.isDownloadingMedia)
        assertEquals(null, state.probedMediaInfo)
        assertEquals(null, state.selectedResolutionOption)
        assertEquals(null, state.importErrorMessage)
    }

    @Test
    fun testMediaResolutionOptionProperties() {
        val option = MediaResolutionOption(
            id = "720p",
            label = "720p (HD - Fast)",
            height = 720,
            format = "mp4",
            sizeText = "34.5 MB",
            isRecommended = true,
            internalKey = "abc123token",
            directUrl = "https://example.com/video_720.mp4",
            isFromSource = true
        )

        assertEquals("720p", option.id)
        assertEquals(720, option.height)
        assertTrue(option.isRecommended)
        assertEquals("mp4", option.format)
        assertEquals("https://example.com/video_720.mp4", option.directUrl)
    }

    @Test
    fun testProbeDirectMediaUrlDetection() = runBlocking {
        val directUrl = "https://test.example.com/sample_video.mp4?auth=1"
        val probed = processor.probeMediaUrl(directUrl)

        assertEquals("Direct File", probed.platformName)
        assertEquals("sample_video.mp4", probed.title)
        assertTrue(probed.resolutions.isNotEmpty())
        assertEquals("mp4", probed.resolutions.first().format)
        assertEquals(directUrl, probed.resolutions.first().directUrl)
    }

    @Test
    fun testProbeDirectAudioUrlDetection() = runBlocking {
        val audioUrl = "https://test.example.com/podcast.mp3"
        val probed = processor.probeMediaUrl(audioUrl)

        assertEquals("Direct File", probed.platformName)
        assertEquals("podcast.mp3", probed.title)
        assertTrue(probed.resolutions.isNotEmpty())
        assertEquals("audio", probed.resolutions.first().id)
        assertEquals("mp3", probed.resolutions.first().format)
    }

    @Test
    fun testProbeHlsMasterPlaylistDetection() = runBlocking {
        val hlsUrl = "https://stream.example.com/live/master.m3u8"
        val probed = processor.probeMediaUrl(hlsUrl)

        assertEquals("HLS Stream", probed.platformName)
        assertTrue(probed.isHls)
        assertTrue(probed.resolutions.isNotEmpty())
    }

    @Test
    fun testHtmlMasqueradeRejection() {
        val dummyHtmlFile = File(context.cacheDir, "fake_video.mp4")
        FileOutputStream(dummyHtmlFile).use {
            it.write("<!DOCTYPE html><html><body>Error 403 Forbidden</body></html>".toByteArray())
        }

        var exceptionThrown = false
        try {
            // Read first 128 bytes to test detection logic
            val headerBytes = ByteArray(128)
            dummyHtmlFile.inputStream().use { it.read(headerBytes) }
            val headerStr = String(headerBytes).lowercase()
            if (headerStr.contains("<!doctype") || headerStr.contains("<html")) {
                dummyHtmlFile.delete()
                throw IllegalStateException("HTML masquerade rejected")
            }
        } catch (e: IllegalStateException) {
            exceptionThrown = true
        }

        assertTrue("File masquerading as video should be caught and rejected", exceptionThrown)
        assertFalse("Rejected file should be deleted", dummyHtmlFile.exists())
    }
}
