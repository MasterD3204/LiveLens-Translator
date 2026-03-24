package com.livelens.translator.model

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPiperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.VoiceActivityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wrapper quản lý sherpa-onnx: VAD, STT (Zipformer), TTS (Piper), Speaker Diarization.
 * Được tạo thủ công qua ModelModule.provideSherpaOnnxManager().
 */
class SherpaOnnxManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var vad: VoiceActivityDetector? = null
    private var recognizer: OnlineRecognizer? = null
    private var tts: OfflineTts? = null
    private var diarization: OfflineSpeakerDiarization? = null
    private var activeStream: OnlineStream? = null
    private var isInitialized = false

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SILENCE_SEC = 0.5f
        private const val VAD_MIN_SPEECH_SEC = 0.25f
        private const val VAD_MAX_SPEECH_SEC = 30f
        private const val VAD_WINDOW_SIZE = 512
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        try {
            if (modelLoader.isVadReady()) initVad()
            if (modelLoader.isSttReady()) initStt()
            if (modelLoader.isTtsReady()) initTts()
            if (modelLoader.isDiarizationReady()) initDiarization()
            isInitialized = true
            Timber.i("SherpaOnnxManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "SherpaOnnxManager init failed")
            throw e
        }
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    private fun initVad() {
        vad = VoiceActivityDetector(
            ttsModelConfig = VadModelConfig(
                sileroVad = SileroVadModelConfig(
                    model = modelLoader.vadModelFile.absolutePath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE_SEC,
                    minSpeechDuration = VAD_MIN_SPEECH_SEC,
                    maxSpeechDuration = VAD_MAX_SPEECH_SEC,
                    windowSize = VAD_WINDOW_SIZE
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu",
                debug = false
            ),
            bufferSizeInSeconds = 60f
        )
        Timber.d("VAD initialized")
    }

    private fun initStt() {
        recognizer = OnlineRecognizer(
            ttsConfig = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = modelLoader.sttEncoderFile.absolutePath,
                        decoder = modelLoader.sttDecoderFile.absolutePath,
                        joiner  = modelLoader.sttJoinerFile.absolutePath
                    ),
                    tokens     = modelLoader.sttTokensFile.absolutePath,
                    numThreads = 4,
                    debug      = false,
                    provider   = "cpu",
                    modelType  = "zipformer2"
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                    rule2 = EndpointRule(mustContainNonSilence = true,  minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                    rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f,   minUtteranceLength = 20f)
                ),
                enableEndpoint = true,
                maxActivePaths = 4
            )
        )
        Timber.d("STT initialized")
    }

    private fun initTts() {
        val parent = modelLoader.ttsModelFile.parent ?: ""
        tts = OfflineTts(
            ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    piper = OfflineTtsPiperModelConfig(
                        model   = modelLoader.ttsModelFile.absolutePath,
                        dataDir = parent,
                        dictDir = parent
                    ),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu"
                ),
                ruleFsts        = "",
                maxNumSentences = 1
            )
        )
        Timber.d("TTS initialized")
    }

    private fun initDiarization() {
        diarization = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote   = OfflineSpeakerSegmentationPyannoteModelConfig(model = modelLoader.diarizationSegmentFile.absolutePath),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu"
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model      = modelLoader.diarizationEmbeddingFile.absolutePath,
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu"
                ),
                clustering    = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f
            )
        )
        Timber.d("Diarization initialized")
    }

    // ─── VAD ─────────────────────────────────────────────────────────────────

    fun feedVad(samples: FloatArray): Boolean {
        val v = vad ?: return false
        v.acceptWaveform(samples)
        return !v.empty()
    }

    fun popVadSpeechSegment(): FloatArray? {
        val v = vad ?: return null
        if (v.empty()) return null
        val front = v.front()
        v.pop()
        return front.samples
    }

    // ─── STT ─────────────────────────────────────────────────────────────────

    fun createStream(): OnlineStream? {
        val r = recognizer ?: return null
        activeStream?.let { r.decode(it) }
        activeStream = r.createStream()
        return activeStream
    }

    fun feedSttSamples(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        activeStream?.acceptWaveform(samples, sampleRate)
    }

    fun decodeStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        while (r.isReady(s)) r.decode(s)
    }

    fun getCurrentResult(): String {
        val r = recognizer ?: return ""
        val s = activeStream ?: return ""
        return r.getResult(s).text
    }

    fun isEndpointDetected(): Boolean {
        val r = recognizer ?: return false
        val s = activeStream ?: return false
        return r.isEndpoint(s)
    }

    fun resetStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        r.reset(s)
    }

    // ─── TTS ─────────────────────────────────────────────────────────────────

    suspend fun synthesizeSpeech(text: String, speed: Float = 1.0f): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                tts?.generate(text = text, sid = 0, speed = speed)?.samples
            } catch (e: Exception) {
                Timber.e(e, "TTS failed")
                null
            }
        }

    val ttsSampleRate: Int get() = tts?.sampleRate ?: 22050

    // ─── Diarization ─────────────────────────────────────────────────────────

    suspend fun diarize(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): List<DiarizationSegment> =
        withContext(Dispatchers.IO) {
            try {
                diarization?.process(samples = samples, sampleRate = sampleRate)
                    ?.sortedBy { it.start }
                    ?.map { DiarizationSegment(it.start, it.end, it.speaker) }
                    ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Diarization failed")
                emptyList()
            }
        }

    // ─── Release ─────────────────────────────────────────────────────────────

    fun release() {
        activeStream = null
        recognizer   = null
        vad          = null
        tts          = null
        diarization  = null
        isInitialized = false
        Timber.i("SherpaOnnxManager released")
    }
}

data class DiarizationSegment(
    val startSec: Float,
    val endSec: Float,
    val speakerId: Int
)
