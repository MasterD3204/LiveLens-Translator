package com.livelens.translator.model

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegmentResult
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParallelDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.VoiceActivityDetector
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPiperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around sherpa-onnx Android SDK.
 * Manages:
 *  - Silero VAD (Voice Activity Detection)
 *  - Zipformer STT (Speech-to-Text, online streaming recognizer)
 *  - Piper TTS (Text-to-Speech, Vietnamese)
 *  - Speaker Diarization (offline, for Mode 1)
 *
 * Thread-safety: All heavy init is done on Dispatchers.IO.
 * STT streaming is designed to be called from a single producer thread.
 */
@Singleton
class SherpaOnnxManager @Inject constructor(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    // Core sherpa-onnx objects
    private var vad: VoiceActivityDetector? = null
    private var recognizer: OnlineRecognizer? = null
    private var tts: OfflineTts? = null
    private var diarization: OfflineSpeakerDiarization? = null

    // Active STT stream
    private var activeStream: OnlineStream? = null

    private var isInitialized = false

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW_SIZE_SAMPLES = 512      // ~32ms at 16kHz
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SILENCE_DURATION_SEC = 0.5f
        private const val VAD_MIN_SPEECH_DURATION_SEC = 0.25f
        private const val VAD_MAX_SPEECH_DURATION_SEC = 30f
        private const val CHUNK_SIZE_SAMPLES = 1600           // 100ms at 16kHz
    }

    /**
     * Initialize all sherpa-onnx components.
     * Must be called on a background thread before using STT/VAD/TTS.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        try {
            if (modelLoader.isVadReady()) initVad()
            if (modelLoader.isSttReady()) initStt()
            if (modelLoader.isTtsReady()) initTts()
            if (modelLoader.isDiarizationReady()) initDiarization()
            isInitialized = true
            Timber.i("SherpaOnnxManager initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize SherpaOnnxManager")
            throw e
        }
    }

    private fun initVad() {
        val vadConfig = VadModelConfig(
            sileroVad = SileroVadModelConfig(
                model = modelLoader.vadModelFile.absolutePath,
                threshold = VAD_THRESHOLD,
                minSilenceDuration = VAD_MIN_SILENCE_DURATION_SEC,
                minSpeechDuration = VAD_MIN_SPEECH_DURATION_SEC,
                maxSpeechDuration = VAD_MAX_SPEECH_DURATION_SEC,
                windowSize = VAD_WINDOW_SIZE_SAMPLES
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 2,
            provider = "cpu",
            debug = false
        )
        vad = VoiceActivityDetector(
            ttsModelConfig = vadConfig,
            bufferSizeInSeconds = 60f
        )
        Timber.d("VAD initialized")
    }

    private fun initStt() {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = modelLoader.sttEncoderFile.absolutePath,
                decoder = modelLoader.sttDecoderFile.absolutePath,
                joiner = modelLoader.sttJoinerFile.absolutePath
            ),
            tokens = modelLoader.sttTokensFile.absolutePath,
            numThreads = 4,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2"
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(
                mustContainNonSilence = false,
                minTrailingSilence = 2.4f,
                minUtteranceLength = 0f
            ),
            rule2 = EndpointRule(
                mustContainNonSilence = true,
                minTrailingSilence = 1.2f,
                minUtteranceLength = 0f
            ),
            rule3 = EndpointRule(
                mustContainNonSilence = false,
                minTrailingSilence = 0f,
                minUtteranceLength = 20f
            )
        )

        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            maxActivePaths = 4
        )
        recognizer = OnlineRecognizer(ttsConfig = recognizerConfig)
        Timber.d("STT recognizer initialized")
    }

    private fun initTts() {
        val ttsConfig = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                piper = OfflineTtsPiperModelConfig(
                    model = modelLoader.ttsModelFile.absolutePath,
                    dataDir = modelLoader.ttsModelFile.parent ?: "",
                    dictDir = modelLoader.ttsModelFile.parent ?: ""
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            ruleFsts = "",
            maxNumSentences = 1
        )
        tts = OfflineTts(ttsConfig = ttsConfig)
        Timber.d("TTS initialized")
    }

    private fun initDiarization() {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = modelLoader.diarizationSegmentFile.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = modelLoader.diarizationEmbeddingFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            clustering = com.k2fsa.sherpa.onnx.FastClusteringConfig(
                numClusters = -1,
                threshold = 0.5f
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f
        )
        diarization = OfflineSpeakerDiarization(config = config)
        Timber.d("Speaker diarization initialized")
    }

    // ─── VAD API ─────────────────────────────────────────────────────────────

    /**
     * Feed audio samples to VAD.
     * @param samples Float array of audio samples (normalized -1..1)
     * @return true if speech was detected in this window
     */
    fun feedVad(samples: FloatArray): Boolean {
        val v = vad ?: return false
        v.acceptWaveform(samples)
        return !v.empty()
    }

    /**
     * Check if VAD has detected a complete speech segment (silence after speech).
     */
    fun isVadEndpointDetected(): Boolean = vad?.let { !it.empty() } ?: false

    /**
     * Pop the front speech segment from VAD buffer.
     * Returns the speech samples, or null if buffer is empty.
     */
    fun popVadSpeechSegment(): FloatArray? {
        val v = vad ?: return null
        return if (!v.empty()) {
            val front = v.front()
            v.pop()
            front.samples
        } else null
    }

    // ─── STT API ─────────────────────────────────────────────────────────────

    /**
     * Create a new STT stream for a new utterance.
     * Call this before feeding audio to STT.
     */
    fun createStream(): OnlineStream? {
        activeStream?.let { recognizer?.decode(it) }
        activeStream = recognizer?.createStream()
        return activeStream
    }

    /**
     * Feed audio samples to the active STT stream.
     * @param samples Float array of PCM samples at 16kHz
     */
    fun feedSttSamples(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        activeStream?.acceptWaveform(samples, sampleRate)
    }

    /**
     * Decode all pending audio in the active stream.
     * Call this repeatedly while audio is being fed.
     */
    fun decodeStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        while (r.isReady(s)) {
            r.decode(s)
        }
    }

    /**
     * Get the current recognition result from the active stream.
     * @return the recognized text, or empty string if nothing yet
     */
    fun getCurrentResult(): String {
        val r = recognizer ?: return ""
        val s = activeStream ?: return ""
        return r.getResult(s).text
    }

    /**
     * Check if the recognizer has detected an endpoint (end of utterance).
     */
    fun isEndpointDetected(): Boolean {
        val r = recognizer ?: return false
        val s = activeStream ?: return false
        return r.isEndpoint(s)
    }

    /**
     * Reset the active stream after endpoint detection.
     * Starts a fresh recognition context.
     */
    fun resetStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        r.reset(s)
    }

    // ─── TTS API ─────────────────────────────────────────────────────────────

    /**
     * Generate speech audio for the given Vietnamese text.
     * @param text Vietnamese text to synthesize
     * @param speed Playback speed (0.75f, 1.0f, 1.25f)
     * @return FloatArray of PCM audio samples, or null if TTS not available
     */
    suspend fun synthesizeSpeech(text: String, speed: Float = 1.0f): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                tts?.generate(text = text, sid = 0, speed = speed)?.samples
            } catch (e: Exception) {
                Timber.e(e, "TTS synthesis failed")
                null
            }
        }

    val ttsSampleRate: Int get() = tts?.sampleRate ?: 22050

    // ─── Speaker Diarization API ──────────────────────────────────────────────

    /**
     * Perform speaker diarization on a complete audio segment.
     * Returns segments with speaker labels.
     * @param samples Float array of complete audio (usually a longer chunk)
     * @param sampleRate Audio sample rate (should be 16kHz)
     */
    suspend fun diarize(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): List<DiarizationSegment> =
        withContext(Dispatchers.IO) {
            try {
                val d = diarization ?: return@withContext emptyList()
                val results: Array<OfflineSpeakerDiarizationSegmentResult> =
                    d.process(samples = samples, sampleRate = sampleRate).sortedBy { it.start }.toTypedArray()
                results.map { seg ->
                    DiarizationSegment(
                        startSec = seg.start,
                        endSec = seg.end,
                        speakerId = seg.speaker
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Diarization failed")
                emptyList()
            }
        }

    // ─── Resource cleanup ─────────────────────────────────────────────────────

    fun release() {
        activeStream = null
        recognizer = null
        vad = null
        tts = null
        diarization = null
        isInitialized = false
        Timber.i("SherpaOnnxManager released")
    }
}

data class DiarizationSegment(
    val startSec: Float,
    val endSec: Float,
    val speakerId: Int    // 0-based; map to A/B labels in the UI layer
)
