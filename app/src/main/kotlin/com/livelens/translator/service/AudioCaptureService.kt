package com.livelens.translator.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.livelens.translator.MainActivity
import com.livelens.translator.MainApplication.Companion.CHANNEL_SERVICE
import com.livelens.translator.MainApplication.Companion.NOTIFICATION_ID_SERVICE
import com.livelens.translator.R
import com.livelens.translator.model.SherpaOnnxManager
import com.livelens.translator.model.TranslationMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that handles audio capture for Mode 1 (mic) and Mode 2 (media playback).
 *
 * Intent Actions:
 * - ACTION_START_MIC:   Start microphone capture
 * - ACTION_START_MEDIA: Start AudioPlaybackCapture (requires MediaProjection data in extras)
 * - ACTION_STOP:        Stop capture and the service
 */
@AndroidEntryPoint
class AudioCaptureService : LifecycleService() {

    @Inject lateinit var sherpaOnnxManager: SherpaOnnxManager
    @Inject lateinit var translationManager: TranslationManager

    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null
    private var currentMode: CaptureMode = CaptureMode.IDLE

    // Speaker diarization state (Mode 1 only)
    private val speakerBuffer = mutableListOf<Float>()
    private var currentSpeakerId = 0
    private val speakerIdToLabel = mutableMapOf<Int, String>()

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopCapture()
            }
        }
    }

    companion object {
        const val ACTION_START_MIC    = "com.livelens.ACTION_START_MIC"
        const val ACTION_START_MEDIA  = "com.livelens.ACTION_START_MEDIA"
        const val ACTION_STOP         = "com.livelens.ACTION_STOP"

        const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
        private const val BUFFER_SIZE_FACTOR = 4

        // Collect ~2 seconds of audio before running diarization
        private const val DIARIZATION_BUFFER_SECONDS = 2.0
        private const val DIARIZATION_BUFFER_SIZE = (SAMPLE_RATE * DIARIZATION_BUFFER_SECONDS).toInt()
    }

    enum class CaptureMode { IDLE, MIC, MEDIA }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_STOP)
        registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        Timber.d("AudioCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIFICATION_ID_SERVICE, buildNotification())

        when (intent?.action) {
            ACTION_START_MIC -> {
                lifecycleScope.launch { startMicCapture() }
            }
            ACTION_START_MEDIA -> {
                val projectionData = intent.getParcelableExtra<Intent>(EXTRA_MEDIA_PROJECTION_DATA)
                if (projectionData != null) {
                    lifecycleScope.launch { startMediaCapture(projectionData) }
                } else {
                    Timber.e("Media projection data missing")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopCapture()
        }

        return START_NOT_STICKY
    }

    // ─── Microphone capture (Mode 1) ──────────────────────────────────────────

    private suspend fun startMicCapture() = withContext(Dispatchers.IO) {
        Timber.i("Starting mic capture")
        currentMode = CaptureMode.MIC
        translationManager.setMode(TranslationMode.CONVERSATION)

        // Initialize sherpa-onnx if needed
        try { sherpaOnnxManager.initialize() } catch (e: Exception) {
            Timber.e(e, "Failed to init sherpa-onnx")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * BUFFER_SIZE_FACTOR, SAMPLE_RATE * 2 * 4)

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()

        audioRecord = record
        record.startRecording()
        sherpaOnnxManager.createStream()
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            processAudioLoop(record)
        }
    }

    // ─── Media playback capture (Mode 2) ──────────────────────────────────────

    private suspend fun startMediaCapture(projectionData: Intent) = withContext(Dispatchers.IO) {
        Timber.i("Starting media playback capture")
        currentMode = CaptureMode.MEDIA
        translationManager.setMode(TranslationMode.MEDIA)

        try { sherpaOnnxManager.initialize() } catch (e: Exception) {
            Timber.e(e, "Failed to init sherpa-onnx")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(
            android.app.Activity.RESULT_OK,
            projectionData
        )

        if (projection == null) {
            Timber.e("Failed to get MediaProjection")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }
        mediaProjection = projection

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * BUFFER_SIZE_FACTOR, SAMPLE_RATE * 2 * 4)

        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (e: UnsupportedOperationException) {
            Timber.e(e, "AudioPlaybackCapture not supported — app may block capture")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.blocked_audio_message),
                    Toast.LENGTH_LONG
                ).show()
            }
            projection.stop()
            stopSelf()
            return@withContext
        }

        audioRecord = record
        record.startRecording()
        sherpaOnnxManager.createStream()

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            processAudioLoop(record)
        }
    }

    // ─── Shared audio processing loop ─────────────────────────────────────────

    /**
     * Main audio processing loop.
     * Reads PCM float samples from AudioRecord in chunks.
     * Feeds samples to Silero VAD, then triggers Zipformer STT on detected speech.
     */
    private suspend fun processAudioLoop(record: AudioRecord) = withContext(Dispatchers.IO) {
        val chunkSamples = SAMPLE_RATE / 10   // 100ms chunks
        val buffer = FloatArray(chunkSamples)
        var utteranceBuffer = FloatArray(0)

        while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = record.read(buffer, 0, chunkSamples, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue

            val chunk = buffer.copyOf(read)

            // Feed to VAD
            val hasSpeech = sherpaOnnxManager.feedVad(chunk)

            // Accumulate for diarization buffer (Mode 1 only)
            if (currentMode == CaptureMode.MIC) {
                speakerBuffer.addAll(chunk.toList())
                if (speakerBuffer.size >= DIARIZATION_BUFFER_SIZE) {
                    val diaBuffer = speakerBuffer.toFloatArray()
                    speakerBuffer.clear()
                    runDiarization(diaBuffer)
                }
            }

            // Feed to STT stream
            sherpaOnnxManager.feedSttSamples(chunk)
            sherpaOnnxManager.decodeStream()

            // Check for STT endpoint
            if (sherpaOnnxManager.isEndpointDetected()) {
                val text = sherpaOnnxManager.getCurrentResult().trim()
                sherpaOnnxManager.resetStream()

                if (text.isNotEmpty()) {
                    val label = if (currentMode == CaptureMode.MIC) {
                        speakerIdToLabel[currentSpeakerId] ?: "A"
                    } else null
                    Timber.d("STT endpoint: text='$text', speaker=$label")
                    translationManager.translateText(
                        text = text,
                        speakerLabel = label
                    )
                }
            }
        }
        Timber.d("Audio processing loop ended")
    }

    private suspend fun runDiarization(samples: FloatArray) {
        val segments = sherpaOnnxManager.diarize(samples)
        if (segments.isEmpty()) return

        // Map speaker IDs to consistent A/B labels for this session
        for (seg in segments) {
            if (!speakerIdToLabel.containsKey(seg.speakerId)) {
                val label = if (speakerIdToLabel.isEmpty()) "A" else "B"
                speakerIdToLabel[seg.speakerId] = label
            }
        }

        // Update current speaker to the one speaking most recently
        segments.maxByOrNull { it.endSec }?.let { latestSeg ->
            currentSpeakerId = latestSeg.speakerId
        }
    }

    // ─── Stop ─────────────────────────────────────────────────────────────────

    private fun stopCapture() {
        Timber.i("Stopping audio capture")
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping AudioRecord")
        }
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        currentMode = CaptureMode.IDLE
        speakerBuffer.clear()
        speakerIdToLabel.clear()
        stopSelf()
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(ACTION_STOP)
        val stopPending = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.stop_service), stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        stopCapture()
        Timber.d("AudioCaptureService destroyed")
    }
}
