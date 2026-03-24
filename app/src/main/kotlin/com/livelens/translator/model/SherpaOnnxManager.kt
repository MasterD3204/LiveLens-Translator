package com.livelens.translator.model

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wrapper quản lý sherpa-onnx: VAD, STT (Zipformer), TTS (VITS/Piper), Speaker Diarization.
 * Được tạo thủ công qua ModelModule.provideSherpaOnnxManager().
 */
class SherpaOnnxManager(
    private val modelLoader: ModelLoader
) {
    private var vad: Vad? = null
    private var recognizer: OnlineRecognizer? = null
    private var tts: OfflineTts? = null
    private var diarization: OfflineSpeakerDiarization? = null
    private var activeStream: OnlineStream? = null
    private var isInitialized = false

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Timber.d("SherpaOnnxManager.initialize() — đã khởi tạo rồi, bỏ qua")
            return@withContext
        }
        Timber.i("━━━ SherpaOnnxManager.initialize() BẮT ĐẦU ━━━")
        try {
            val vadReady = modelLoader.isVadReady()
            val sttReady = modelLoader.isSttReady()
            val ttsReady = modelLoader.isTtsReady()
            val diaReady = modelLoader.isDiarizationReady()
            Timber.d("Model status: VAD=$vadReady, STT=$sttReady, TTS=$ttsReady, Diarization=$diaReady")
            Timber.d("VAD path: ${modelLoader.vadModelFile.absolutePath} (exists=${modelLoader.vadModelFile.exists()}, size=${modelLoader.vadModelFile.length()}B)")
            Timber.d("STT encoder: ${modelLoader.sttEncoderFile.absolutePath} (exists=${modelLoader.sttEncoderFile.exists()}, size=${modelLoader.sttEncoderFile.length()/1024}KB)")
            Timber.d("STT decoder: ${modelLoader.sttDecoderFile.absolutePath} (exists=${modelLoader.sttDecoderFile.exists()}, size=${modelLoader.sttDecoderFile.length()/1024}KB)")
            Timber.d("STT joiner: ${modelLoader.sttJoinerFile.absolutePath} (exists=${modelLoader.sttJoinerFile.exists()}, size=${modelLoader.sttJoinerFile.length()/1024}KB)")
            Timber.d("STT tokens: ${modelLoader.sttTokensFile.absolutePath} (exists=${modelLoader.sttTokensFile.exists()}, size=${modelLoader.sttTokensFile.length()}B)")

            if (vadReady) {
                Timber.d("Đang khởi tạo VAD...")
                initVad()
                Timber.i("VAD khởi tạo thành công ✓")
            } else {
                Timber.w("VAD model chưa sẵn sàng — bỏ qua initVad()")
            }

            if (sttReady) {
                Timber.d("Đang khởi tạo STT (Zipformer)...")
                initStt()
                Timber.i("STT khởi tạo thành công ✓")
            } else {
                Timber.w("STT model chưa sẵn sàng — bỏ qua initStt()")
            }

            if (ttsReady) {
                Timber.d("Đang khởi tạo TTS (VITS)...")
                initTts()
                Timber.i("TTS khởi tạo thành công ✓")
            } else {
                Timber.d("TTS model không có — bỏ qua (optional)")
            }

            if (diaReady) {
                Timber.d("Đang khởi tạo Diarization...")
                initDiarization()
                Timber.i("Diarization khởi tạo thành công ✓")
            } else {
                Timber.d("Diarization model không có — bỏ qua (optional)")
            }

            isInitialized = true
            Timber.i("━━━ SherpaOnnxManager khởi tạo HOÀN TẤT: VAD=${vad != null}, STT=${recognizer != null}, TTS=${tts != null}, Diarization=${diarization != null} ━━━")
        } catch (e: Exception) {
            Timber.e(e, "━━━ SherpaOnnxManager.initialize() THẤT BẠI ✗ ━━━")
            throw e
        }
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    private fun initVad() {
        Timber.d("initVad(): model=${modelLoader.vadModelFile.absolutePath}")
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model             = modelLoader.vadModelFile.absolutePath,
                    threshold         = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration  = 0.25f,
                    maxSpeechDuration  = 30f,
                    windowSize        = 512
                ),
                sampleRate  = SAMPLE_RATE,
                numThreads  = 2,
                provider    = "cpu",
                debug       = false
            )
        )
        Timber.d("VAD object tạo: ${if (vad != null) "OK ✓" else "NULL ✗"}")
    }

    private fun initStt() {
        Timber.d("initStt(): encoder=${modelLoader.sttEncoderFile.absolutePath}")
        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
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
        Timber.d("OnlineRecognizer tạo: ${if (recognizer != null) "OK ✓" else "NULL ✗"}")
    }

    private fun initTts() {
        // Piper Vietnamese models dùng VITS format
        val parent = modelLoader.ttsModelFile.parent ?: ""
        tts = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
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
                    pyannote   = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = modelLoader.diarizationSegmentFile.absolutePath
                    ),
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
                clustering     = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn  = 0.3f,
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

    // sampleRate() là function trong sherpa-onnx, không phải property
    val ttsSampleRate: Int get() = tts?.sampleRate() ?: 22050

    // ─── Diarization ─────────────────────────────────────────────────────────

    // process() chỉ nhận samples, không có sampleRate
    suspend fun diarize(samples: FloatArray): List<DiarizationSegment> =
        withContext(Dispatchers.IO) {
            try {
                diarization?.process(samples)
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
        recognizer?.release()
        vad?.release()
        tts?.release()
        diarization?.release()
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
