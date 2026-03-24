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
import androidx.core.content.ContextCompat
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
        Timber.d("AudioCaptureService.onCreate() — PID=${android.os.Process.myPid()}")
        try {
            val filter = IntentFilter(ACTION_STOP)
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
            Timber.d("AudioCaptureService BroadcastReceiver đã đăng ký cho ACTION_STOP")
        } catch (e: Exception) {
            Timber.e(e, "Lỗi khi đăng ký BroadcastReceiver trong onCreate")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.i("AudioCaptureService.onStartCommand() — action=${intent?.action}, flags=$flags, startId=$startId")

        if (intent == null) {
            Timber.w("onStartCommand nhận intent=null (hệ thống restart service?) — gọi stopSelf()")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID_SERVICE, buildNotification())
            Timber.d("startForeground() thành công, notificationId=$NOTIFICATION_ID_SERVICE")
        } catch (e: Exception) {
            Timber.e(e, "startForeground() thất bại!")
        }

        when (intent.action) {
            ACTION_START_MIC -> {
                Timber.i("→ ACTION_START_MIC nhận được, launching startMicCapture()")
                lifecycleScope.launch { startMicCapture() }
            }
            ACTION_START_MEDIA -> {
                val projectionData = intent.getParcelableExtra<Intent>(EXTRA_MEDIA_PROJECTION_DATA)
                Timber.i("→ ACTION_START_MEDIA nhận được, projectionData=${if (projectionData != null) "CÓ" else "NULL"}")
                if (projectionData != null) {
                    lifecycleScope.launch { startMediaCapture(projectionData) }
                } else {
                    Timber.e("EXTRA_MEDIA_PROJECTION_DATA bị thiếu trong ACTION_START_MEDIA — stopSelf()")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Timber.i("→ ACTION_STOP nhận được qua onStartCommand")
                stopCapture()
            }
            null -> {
                Timber.w("intent.action = null — không làm gì")
            }
            else -> {
                Timber.w("action không nhận dạng được: '${intent.action}'")
            }
        }

        return START_NOT_STICKY
    }

    // ─── Microphone capture (Mode 1) ──────────────────────────────────────────

    private suspend fun startMicCapture() = withContext(Dispatchers.IO) {
        Timber.i("━━━ startMicCapture() BẮT ĐẦU ━━━")
        currentMode = CaptureMode.MIC
        translationManager.setMode(TranslationMode.CONVERSATION)
        Timber.d("Đã set mode = CONVERSATION")

        // Kiểm tra permission RECORD_AUDIO
        val hasMicPermission = android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(
                this@AudioCaptureService, android.Manifest.permission.RECORD_AUDIO
            )
        Timber.d("RECORD_AUDIO permission: ${if (hasMicPermission) "GRANTED ✓" else "DENIED ✗"}")
        if (!hasMicPermission) {
            Timber.e("Không có quyền RECORD_AUDIO — không thể ghi âm!")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        // Initialize sherpa-onnx if needed
        Timber.d("Khởi tạo SherpaOnnxManager...")
        try {
            sherpaOnnxManager.initialize()
            Timber.i("SherpaOnnxManager khởi tạo thành công ✓")
        } catch (e: Exception) {
            Timber.e(e, "SherpaOnnxManager khởi tạo THẤT BẠI ✗")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * BUFFER_SIZE_FACTOR, SAMPLE_RATE * 2 * 4)
        Timber.d("AudioRecord: sampleRate=$SAMPLE_RATE, minBuf=$minBuf, bufSize=$bufSize")

        val record = try {
            AudioRecord.Builder()
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
        } catch (e: Exception) {
            Timber.e(e, "Tạo AudioRecord THẤT BẠI ✗")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        Timber.d("AudioRecord state = ${record.state} (1=INITIALIZED, 0=UNINITIALIZED)")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord KHÔNG khởi tạo được (state=${record.state}) — kiểm tra permission hoặc hardware")
            record.release()
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        audioRecord = record
        try {
            record.startRecording()
            Timber.i("AudioRecord.startRecording() thành công ✓ recordingState=${record.recordingState}")
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord.startRecording() THẤT BẠI ✗")
            record.release()
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val stream = sherpaOnnxManager.createStream()
        Timber.d("SherpaOnnx stream tạo: ${if (stream != null) "OK ✓" else "NULL ✗"}")

        Timber.i("━━━ Bắt đầu vòng lặp xử lý audio (MIC mode) ━━━")
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            processAudioLoop(record)
        }
    }

    // ─── Media playback capture (Mode 2) ──────────────────────────────────────

    private suspend fun startMediaCapture(projectionData: Intent) = withContext(Dispatchers.IO) {
        Timber.i("━━━ startMediaCapture() BẮT ĐẦU ━━━")
        currentMode = CaptureMode.MEDIA
        translationManager.setMode(TranslationMode.MEDIA)
        Timber.d("Đã set mode = MEDIA")

        Timber.d("Khởi tạo SherpaOnnxManager cho MEDIA mode...")
        try {
            sherpaOnnxManager.initialize()
            Timber.i("SherpaOnnxManager khởi tạo thành công ✓")
        } catch (e: Exception) {
            Timber.e(e, "SherpaOnnxManager khởi tạo THẤT BẠI ✗")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Timber.d("Đang lấy MediaProjection từ projectionData...")
        val projection = try {
            projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, projectionData)
        } catch (e: Exception) {
            Timber.e(e, "getMediaProjection() ném exception ✗")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        if (projection == null) {
            Timber.e("getMediaProjection() trả về NULL ✗ — token hết hạn hoặc bị từ chối?")
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }
        Timber.i("MediaProjection lấy thành công ✓")
        mediaProjection = projection

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        Timber.d("AudioPlaybackCaptureConfiguration tạo xong")

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * BUFFER_SIZE_FACTOR, SAMPLE_RATE * 2 * 4)
        Timber.d("AudioRecord: minBuf=$minBuf, bufSize=$bufSize")

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
            Timber.e(e, "AudioPlaybackCapture KHÔNG được hỗ trợ ✗ — app đích có thể chặn capture (FLAG_SECURE)")
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
        } catch (e: Exception) {
            Timber.e(e, "Tạo AudioRecord cho media capture THẤT BẠI ✗")
            projection.stop()
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        Timber.d("AudioRecord (media) state=${record.state} (1=OK)")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord (media) KHÔNG khởi tạo được (state=${record.state})")
            record.release()
            projection.stop()
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        audioRecord = record
        try {
            record.startRecording()
            Timber.i("AudioRecord (media) startRecording() ✓ recordingState=${record.recordingState}")
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord (media) startRecording() THẤT BẠI ✗")
            record.release()
            projection.stop()
            withContext(Dispatchers.Main) { stopSelf() }
            return@withContext
        }

        val stream = sherpaOnnxManager.createStream()
        Timber.d("SherpaOnnx stream: ${if (stream != null) "OK ✓" else "NULL ✗"}")

        Timber.i("━━━ Bắt đầu vòng lặp xử lý audio (MEDIA mode) ━━━")
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
        var chunkCount = 0
        var speechChunkCount = 0
        var endpointCount = 0

        Timber.d("processAudioLoop() khởi động: chunkSamples=$chunkSamples, recordingState=${record.recordingState}")

        while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = record.read(buffer, 0, chunkSamples, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                Timber.w("AudioRecord.read() trả về $read — bỏ qua chunk này")
                continue
            }

            chunkCount++
            val chunk = buffer.copyOf(read)

            // Log định kỳ mỗi 50 chunks (~5 giây) để tránh spam
            if (chunkCount % 50 == 0) {
                Timber.d("Audio loop alive: chunk #$chunkCount, speechChunks=$speechChunkCount, endpoints=$endpointCount, mode=$currentMode")
            }

            // Feed to VAD
            val hasSpeech = sherpaOnnxManager.feedVad(chunk)
            if (hasSpeech) speechChunkCount++

            // Log khi phát hiện speech lần đầu trong mỗi nhóm 50
            if (hasSpeech && chunkCount % 50 == 1) {
                Timber.d("VAD: phát hiện giọng nói tại chunk #$chunkCount")
            }

            // Accumulate for diarization buffer (Mode 1 only)
            if (currentMode == CaptureMode.MIC) {
                speakerBuffer.addAll(chunk.toList())
                if (speakerBuffer.size >= DIARIZATION_BUFFER_SIZE) {
                    Timber.d("Diarization buffer đầy (${speakerBuffer.size} samples) — chạy diarization")
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
                endpointCount++
                val text = sherpaOnnxManager.getCurrentResult().trim()
                sherpaOnnxManager.resetStream()
                Timber.d("STT endpoint #$endpointCount: text='$text' (length=${text.length})")

                if (text.isNotEmpty()) {
                    val label = if (currentMode == CaptureMode.MIC) {
                        speakerIdToLabel[currentSpeakerId] ?: "A"
                    } else null
                    Timber.i("✔ STT kết quả: '$text', speaker=$label — gửi đến TranslationManager")
                    translationManager.translateText(
                        text = text,
                        speakerLabel = label
                    )
                } else {
                    Timber.d("STT endpoint nhưng text rỗng — bỏ qua")
                }
            }
        }
        Timber.i("━━━ processAudioLoop() KẾT THÚC: tổng chunk=$chunkCount, speechChunks=$speechChunkCount, endpoints=$endpointCount, recordingState=${record.recordingState} ━━━")
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
        Timber.i("stopCapture() gọi — currentMode=$currentMode, captureJob=${if (captureJob != null) "active" else "null"}")
        captureJob?.cancel()
        captureJob = null
        try {
            val state = audioRecord?.recordingState
            Timber.d("Dừng AudioRecord (recordingState=$state)...")
            audioRecord?.stop()
            audioRecord?.release()
            Timber.d("AudioRecord dừng và giải phóng ✓")
        } catch (e: Exception) {
            Timber.w(e, "Lỗi khi dừng AudioRecord")
        }
        audioRecord = null
        if (mediaProjection != null) {
            Timber.d("Dừng MediaProjection...")
            mediaProjection?.stop()
            mediaProjection = null
        }
        currentMode = CaptureMode.IDLE
        speakerBuffer.clear()
        speakerIdToLabel.clear()
        Timber.i("stopCapture() hoàn tất — gọi stopSelf()")
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
        Timber.i("AudioCaptureService.onDestroy() — service đã bị hủy")
    }
}
