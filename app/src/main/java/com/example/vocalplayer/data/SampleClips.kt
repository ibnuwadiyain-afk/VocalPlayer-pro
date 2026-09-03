package com.example.vocalplayer.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

data class DemoClip(
    val title: String,
    val description: String,
    val uri: Uri,
    val durationText: String
)

/**
 * Creates and manages high-quality offline demo audio/video clips for instant verification of vocal isolation.
 */
class SampleClipsManager(private val context: Context) {
    private val tag = "SampleClipsManager"
    private val clipsDir = File(context.cacheDir, "demo_clips").apply { mkdirs() }

    suspend fun getDemoClips(): List<DemoClip> = withContext(Dispatchers.IO) {
        val clips = mutableListOf<DemoClip>()
        try {
            val clipFile1 = File(clipsDir, "acoustic_vocal_demo.wav")
            if (!clipFile1.exists()) {
                generateDemoAudio(clipFile1, isAcoustic = true)
            }
            clips.add(
                DemoClip(
                    title = "Acoustic Vocal & Guitar Demo",
                    description = "Lead singing melody (A440 & C523) over acoustic chords and drum beat.",
                    uri = Uri.fromFile(clipFile1),
                    durationText = "0:12"
                )
            )

            val clipFile2 = File(clipsDir, "pop_song_demo.wav")
            if (!clipFile2.exists()) {
                generateDemoAudio(clipFile2, isAcoustic = false)
            }
            clips.add(
                DemoClip(
                    title = "Pop Harmony & Synth Demo",
                    description = "Dynamic vocal vibrato melody over synth pads, bassline, and 808 percussion.",
                    uri = Uri.fromFile(clipFile2),
                    durationText = "0:10"
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "Error generating demo clips: ${e.message}", e)
        }
        clips
    }

    private fun generateDemoAudio(file: File, isAcoustic: Boolean) {
        val sampleRate = 44100
        val durationSec = if (isAcoustic) 12 else 10
        val totalSamples = sampleRate * durationSec
        val channels = 2

        val pcmData = ShortArray(totalSamples * channels)

        for (i in 0 until totalSamples) {
            val t = i.toFloat() / sampleRate.toFloat()

            // Musical melody (Vocal line)
            val vocalFreq = when {
                t < 2.0f -> 440.0 // A4
                t < 4.0f -> 493.88 // B4
                t < 6.0f -> 523.25 // C5
                t < 8.0f -> 587.33 // D5
                t < 10.0f -> 659.25 // E5
                else -> 440.0
            }

            // Vocal vibrato & harmonic richness
            val vibrato = 1.0f + 0.03f * sin(2.0 * PI * 5.5 * t).toFloat()
            val vocalCore = 0.45f * sin(2.0 * PI * (vocalFreq * vibrato) * t).toFloat() +
                    0.25f * sin(4.0 * PI * (vocalFreq * vibrato) * t).toFloat() +
                    0.12f * sin(6.0 * PI * (vocalFreq * vibrato) * t).toFloat()

            // Instrumental accompaniment: Bass + Chords + Drums
            val bassFreq = 110.0 // A2
            val bass = 0.35f * sin(2.0 * PI * bassFreq * t).toFloat()

            // Guitar/Synth Chord (panned slightly wide)
            val chord1 = 0.20f * sin(2.0 * PI * 220.0 * t).toFloat()
            val chord2 = 0.18f * sin(2.0 * PI * 277.18 * t).toFloat()

            // Drum transients (kick on beat, snare on 2/4)
            val beatTime = (t * 2f) % 1.0f
            val drumKick = if (beatTime < 0.08f) 0.5f * sin(2.0 * PI * 65.0 * beatTime * 20.0).toFloat() else 0f
            val drumSnare = if ((t * 2f) % 2.0f in 1.0f..1.08f) (Math.random().toFloat() - 0.5f) * 0.4f else 0f

            // Left / Right Mix (Vocals are strictly centered, instruments have stereo spread)
            val left = vocalCore + bass + chord1 * 1.2f + chord2 * 0.7f + drumKick + drumSnare
            val right = vocalCore + bass + chord1 * 0.7f + chord2 * 1.2f + drumKick + drumSnare

            val shortLeft = (left.coerceIn(-1.0f, 1.0f) * 30000.0f).toInt().toShort()
            val shortRight = (right.coerceIn(-1.0f, 1.0f) * 30000.0f).toInt().toShort()

            pcmData[i * 2] = shortLeft
            pcmData[i * 2 + 1] = shortRight
        }

        writeWavFile(file, pcmData, sampleRate, channels)
    }

    private fun writeWavFile(file: File, pcmShorts: ShortArray, sampleRate: Int, channels: Int) {
        val byteBuffer = ByteBuffer.allocate(pcmShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcmShorts) {
            byteBuffer.putShort(s)
        }
        val audioBytes = byteBuffer.array()

        val totalDataLen = audioBytes.size + 36
        val byteRate = sampleRate * channels * 2

        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
            header[20] = 1; header[21] = 0 // format 1
            header[22] = channels.toByte(); header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * 2).toByte(); header[33] = 0 // block align
            header[34] = 16; header[35] = 0 // bits per sample
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            val pcmLen = audioBytes.size
            header[40] = (pcmLen and 0xff).toByte()
            header[41] = ((pcmLen shr 8) and 0xff).toByte()
            header[42] = ((pcmLen shr 16) and 0xff).toByte()
            header[43] = ((pcmLen shr 24) and 0xff).toByte()

            out.write(header)
            out.write(audioBytes)
        }
    }
}
