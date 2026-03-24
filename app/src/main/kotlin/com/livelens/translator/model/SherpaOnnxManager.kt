package com.livelens.translator.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wrapper quản lý sherpa-onnx: VAD, STT (Zipformer), TTS (Piper), Speaker Diarization.
 *
 * Tất cả class sherpa-onnx được load qua reflection để tránh lỗi compile
 * khi AAR chưa được đặt vào app/libs/. Khi AAR có mặt, mọi thứ hoạt động bình thường.
 *
 * Để thêm AAR: chạy scripts/download-sherpa-onnx.sh trước khi build.
 */
class SherpaOnnxManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    // Dùng Any? để tránh import trực tiếp class sherpa-onnx tại compile time
    private var vad: Any? = null
    private var recognizer: Any? = null
    private var tts: Any? = null
    private var diarization: Any? = null
    private var activeStream: Any? = null

    private var isInitialized = false
    private var sherpaAvailable = false

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SILENCE_DURATION_SEC = 0.5f
        private const val VAD_MIN_SPEECH_DURATION_SEC = 0.25f
        private const val VAD_MAX_SPEECH_DURATION_SEC = 30f
        private const val VAD_WINDOW_SIZE_SAMPLES = 512

        // Kiểm tra AAR có trong classpath không
        private fun isSherpaAvailable(): Boolean = try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * Khởi tạo tất cả component sherpa-onnx.
     * Nếu AAR không có, ghi log cảnh báo và trả về mà không crash.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        sherpaAvailable = isSherpaAvailable()
        if (!sherpaAvailable) {
            Timber.w("sherpa-onnx AAR không tìm thấy trong classpath. " +
                     "Chạy scripts/download-sherpa-onnx.sh và rebuild.")
            return@withContext
        }

        try {
            if (modelLoader.isVadReady()) initVad()
            if (modelLoader.isSttReady()) initStt()
            if (modelLoader.isTtsReady()) initTts()
            if (modelLoader.isDiarizationReady()) initDiarization()
            isInitialized = true
            Timber.i("SherpaOnnxManager khởi tạo thành công")
        } catch (e: Exception) {
            Timber.e(e, "Lỗi khởi tạo SherpaOnnxManager")
            throw e
        }
    }

    // ─── Init từng component (dùng reflection) ───────────────────────────────

    private fun initVad() {
        val vadModelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.VadModelConfig")
        val sileroClass         = Class.forName("com.k2fsa.sherpa.onnx.SileroVadModelConfig")

        val sileroConfig = sileroClass.constructors.first().newInstance(
            modelLoader.vadModelFile.absolutePath,
            VAD_THRESHOLD,
            VAD_MIN_SILENCE_DURATION_SEC,
            VAD_MIN_SPEECH_DURATION_SEC,
            VAD_MAX_SPEECH_DURATION_SEC,
            VAD_WINDOW_SIZE_SAMPLES
        )

        val vadConfig = vadModelConfigClass.constructors.first().newInstance(
            sileroConfig,
            SAMPLE_RATE,
            2,      // numThreads
            "cpu",  // provider
            false   // debug
        )

        val vadClass = Class.forName("com.k2fsa.sherpa.onnx.VoiceActivityDetector")
        vad = vadClass.constructors.first().newInstance(vadConfig, 60f)
        Timber.d("VAD khởi tạo xong")
    }

    private fun initStt() {
        // OnlineTransducerModelConfig
        val transducerClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
        val transducerConfig = transducerClass.constructors.first().newInstance(
            modelLoader.sttEncoderFile.absolutePath,
            modelLoader.sttDecoderFile.absolutePath,
            modelLoader.sttJoinerFile.absolutePath
        )

        // OnlineModelConfig
        val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
        val modelConfig = modelConfigClass.constructors.first().newInstance(
            transducerConfig,
            modelLoader.sttTokensFile.absolutePath,
            4,       // numThreads
            false,   // debug
            "cpu",   // provider
            "zipformer2"
        )

        // EndpointRule x3
        val ruleClass = Class.forName("com.k2fsa.sherpa.onnx.EndpointRule")
        val rule1 = ruleClass.constructors.first().newInstance(false, 2.4f, 0f)
        val rule2 = ruleClass.constructors.first().newInstance(true,  1.2f, 0f)
        val rule3 = ruleClass.constructors.first().newInstance(false, 0f,  20f)

        // EndpointConfig
        val endpointClass = Class.forName("com.k2fsa.sherpa.onnx.EndpointConfig")
        val endpointConfig = endpointClass.constructors.first().newInstance(rule1, rule2, rule3)

        // FeatureConfig
        val featClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
        val featConfig = featClass.constructors.first().newInstance(SAMPLE_RATE, 80)

        // OnlineRecognizerConfig
        val recConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
        val recConfig = recConfigClass.constructors.first().newInstance(
            featConfig, modelConfig, endpointConfig,
            true,  // enableEndpoint
            4      // maxActivePaths
        )

        // OnlineRecognizer
        val recClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        recognizer = recClass.constructors.first().newInstance(recConfig)
        Timber.d("STT (Zipformer) khởi tạo xong")
    }

    private fun initTts() {
        val piperClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsPiperModelConfig")
        val parentDir  = modelLoader.ttsModelFile.parent ?: ""
        val piperConfig = piperClass.constructors.first().newInstance(
            modelLoader.ttsModelFile.absolutePath,
            parentDir,
            parentDir
        )

        val ttsModelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
        val ttsModelConfig = ttsModelConfigClass.constructors.first().newInstance(
            piperConfig, 2, false, "cpu"
        )

        val ttsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
        val ttsConfig = ttsConfigClass.constructors.first().newInstance(
            ttsModelConfig, "", 1
        )

        val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
        tts = ttsClass.constructors.first().newInstance(ttsConfig)
        Timber.d("TTS (Piper) khởi tạo xong")
    }

    private fun initDiarization() {
        val pyannoteClass = Class.forName(
            "com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig"
        )
        val pyannoteConfig = pyannoteClass.constructors.first().newInstance(
            modelLoader.diarizationSegmentFile.absolutePath
        )

        val segClass = Class.forName(
            "com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig"
        )
        val segConfig = segClass.constructors.first().newInstance(
            pyannoteConfig, 2, false, "cpu"
        )

        val embClass = Class.forName("com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig")
        val embConfig = embClass.constructors.first().newInstance(
            modelLoader.diarizationEmbeddingFile.absolutePath, 2, false, "cpu"
        )

        val clusterClass = Class.forName("com.k2fsa.sherpa.onnx.FastClusteringConfig")
        val clusterConfig = clusterClass.constructors.first().newInstance(-1, 0.5f)

        val diarConfigClass = Class.forName(
            "com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig"
        )
        val diarConfig = diarConfigClass.constructors.first().newInstance(
            segConfig, embConfig, clusterConfig, 0.3f, 0.5f
        )

        val diarClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization")
        diarization = diarClass.constructors.first().newInstance(diarConfig)
        Timber.d("Speaker Diarization khởi tạo xong")
    }

    // ─── VAD API ─────────────────────────────────────────────────────────────

    fun feedVad(samples: FloatArray): Boolean {
        val v = vad ?: return false
        v.javaClass.getMethod("acceptWaveform", FloatArray::class.java).invoke(v, samples)
        return !(v.javaClass.getMethod("empty").invoke(v) as Boolean)
    }

    fun popVadSpeechSegment(): FloatArray? {
        val v = vad ?: return null
        val empty = v.javaClass.getMethod("empty").invoke(v) as Boolean
        if (empty) return null
        val front = v.javaClass.getMethod("front").invoke(v)
        v.javaClass.getMethod("pop").invoke(v)
        return front?.javaClass?.getField("samples")?.get(front) as? FloatArray
    }

    // ─── STT API ─────────────────────────────────────────────────────────────

    fun createStream(): Any? {
        val r = recognizer ?: return null
        activeStream?.let {
            r.javaClass.getMethod("decode", it.javaClass).invoke(r, it)
        }
        activeStream = r.javaClass.getMethod("createStream").invoke(r)
        return activeStream
    }

    fun feedSttSamples(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val s = activeStream ?: return
        s.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.java)
            .invoke(s, samples, sampleRate)
    }

    fun decodeStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        val isReadyMethod = r.javaClass.getMethod("isReady", s.javaClass)
        val decodeMethod  = r.javaClass.getMethod("decode",  s.javaClass)
        while (isReadyMethod.invoke(r, s) as Boolean) {
            decodeMethod.invoke(r, s)
        }
    }

    fun getCurrentResult(): String {
        val r = recognizer ?: return ""
        val s = activeStream ?: return ""
        val result = r.javaClass.getMethod("getResult", s.javaClass).invoke(r, s)
        return result?.javaClass?.getField("text")?.get(result) as? String ?: ""
    }

    fun isEndpointDetected(): Boolean {
        val r = recognizer ?: return false
        val s = activeStream ?: return false
        return r.javaClass.getMethod("isEndpoint", s.javaClass).invoke(r, s) as Boolean
    }

    fun resetStream() {
        val r = recognizer ?: return
        val s = activeStream ?: return
        r.javaClass.getMethod("reset", s.javaClass).invoke(r, s)
    }

    // ─── TTS API ─────────────────────────────────────────────────────────────

    suspend fun synthesizeSpeech(text: String, speed: Float = 1.0f): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                val t = tts ?: return@withContext null
                val result = t.javaClass
                    .getMethod("generate", String::class.java, Int::class.java, Float::class.java)
                    .invoke(t, text, 0, speed)
                result?.javaClass?.getField("samples")?.get(result) as? FloatArray
            } catch (e: Exception) {
                Timber.e(e, "TTS synthesis thất bại")
                null
            }
        }

    val ttsSampleRate: Int
        get() = try {
            tts?.javaClass?.getMethod("getSampleRate")?.invoke(tts) as? Int ?: 22050
        } catch (_: Exception) { 22050 }

    // ─── Diarization API ─────────────────────────────────────────────────────

    suspend fun diarize(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): List<DiarizationSegment> =
        withContext(Dispatchers.IO) {
            try {
                val d = diarization ?: return@withContext emptyList()
                @Suppress("UNCHECKED_CAST")
                val results = d.javaClass
                    .getMethod("process", FloatArray::class.java, Int::class.java)
                    .invoke(d, samples, sampleRate) as? Array<Any>
                    ?: return@withContext emptyList()

                results.map { seg ->
                    val start   = seg.javaClass.getField("start").getFloat(seg)
                    val end     = seg.javaClass.getField("end").getFloat(seg)
                    val speaker = seg.javaClass.getField("speaker").getInt(seg)
                    DiarizationSegment(start, end, speaker)
                }.sortedBy { it.startSec }
            } catch (e: Exception) {
                Timber.e(e, "Diarization thất bại")
                emptyList()
            }
        }

    // ─── Release ─────────────────────────────────────────────────────────────

    fun release() {
        listOf(activeStream, recognizer, vad, tts, diarization).forEach { obj ->
            obj ?: return@forEach
            try { obj.javaClass.getMethod("release").invoke(obj) } catch (_: Exception) {}
        }
        activeStream = null; recognizer = null; vad = null; tts = null; diarization = null
        isInitialized = false
        Timber.i("SherpaOnnxManager released")
    }
}

data class DiarizationSegment(
    val startSec: Float,
    val endSec: Float,
    val speakerId: Int
)
