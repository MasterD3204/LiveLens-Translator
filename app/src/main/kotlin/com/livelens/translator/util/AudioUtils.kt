package com.livelens.translator.util

import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioManager
import timber.log.Timber

/**
 * Audio utilities for PCM playback and format conversion.
 */
object AudioUtils {

    /**
     * Play PCM float audio samples using AudioTrack.
     * Used for TTS playback.
     *
     * @param samples Float PCM audio samples (-1.0 to 1.0)
     * @param sampleRate Sample rate (typically 22050 for Piper TTS)
     */
    fun playPcmFloat(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        try {
            audioTrack.play()
            audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.stop()
        } catch (e: Exception) {
            Timber.e(e, "AudioTrack playback error")
        } finally {
            audioTrack.release()
        }
    }

    /**
     * Convert 16-bit PCM short array to float array (normalized to -1.0..1.0).
     */
    fun shortToFloat(shorts: ShortArray): FloatArray {
        return FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
    }

    /**
     * Convert float array (normalized -1.0..1.0) to 16-bit PCM short array.
     */
    fun floatToShort(floats: FloatArray): ShortArray {
        return ShortArray(floats.size) { i ->
            (floats[i].coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
        }
    }

    /**
     * Compute root mean square (RMS) power of an audio buffer.
     * Useful for audio level visualization.
     */
    fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        val sumOfSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return Math.sqrt(sumOfSquares / samples.size).toFloat()
    }
}
