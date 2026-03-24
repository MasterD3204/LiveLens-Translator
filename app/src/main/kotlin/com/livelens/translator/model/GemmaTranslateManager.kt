package com.livelens.translator.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wrapper quanh MediaPipe LLM Inference API (tasks-genai 0.10.27).
 *
 * Text streaming: setResultListener trong options + generateResponseAsync(prompt)
 * Image translation: LlmInferenceSession + BitmapImageBuilder + generateResponse()
 */
class GemmaTranslateManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: LlmInference? = null
    private var isInitializing = false

    // Kênh hiện tại để nhận partial results khi streaming
    @Volatile private var activeChannel: SendChannel<String>? = null

    // Mutex đảm bảo chỉ có 1 request tại một thời điểm
    private val mutex = Mutex()

    companion object {
        private const val MAX_TOP_K = 64
        private const val MAX_NUM_IMAGES = 5

        // Prompt chuẩn theo hướng dẫn từ nhà phát triển
        private const val TEXT_PROMPT =
            "You are a professional English (en) to Vietnamese (vie) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original English text " +
            "while adhering to Vietnamese grammar, vocabulary, and cultural sensitivities.\n\n" +
            "Produce only the Vietnamese translation, without any additional explanations or commentary. " +
            "Please translate the following English text into Vietnamese: %s"

        private const val IMAGE_PROMPT =
            "Translate all English text found in this image to Vietnamese. " +
            "Format output as: [Original]: ... → [Dịch]: ..."
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    fun initializeAsync() {
        if (llmInference != null || isInitializing) return
        if (!modelLoader.isGemmaReady()) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { initializeSync() }
    }

    suspend fun initializeSync() = withContext(Dispatchers.IO) {
        if (llmInference != null || isInitializing) return@withContext
        val taskFile = modelLoader.resolveGemmaTaskFile() ?: run {
            Timber.w("Gemma .task file not found")
            return@withContext
        }
        isInitializing = true
        try {
            // ✅ API mới: KHÔNG dùng setResultListener ở đây nữa
            val options = LlmInferenceOptions.builder()
                .setModelPath(taskFile.absolutePath)
                .setMaxTopK(MAX_TOP_K)
                .setMaxNumImages(MAX_NUM_IMAGES)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Timber.i("Gemma initialized: ${taskFile.name}")
        } catch (e: Exception) {
            Timber.e(e, "Gemma init failed")
        } finally {
            isInitializing = false
        }
    }

    // ─── Text translation (streaming) ────────────────────────────────────────

    /**
     * Dịch text tiếng Anh → tiếng Việt với streaming (token by token).
     * Dùng generateResponseAsync() + setResultListener.
     */
    fun translateText(text: String): Flow<String> = callbackFlow {
        val llm = llmInference ?: run {
            close(IllegalStateException("LLM chưa khởi tạo"))
            return@callbackFlow
        }

        mutex.withLock {
            activeChannel = channel
            try {
                withContext(Dispatchers.IO) {
                    llm.generateResponseAsync(TEXT_PROMPT.format(text.trim()))
                }
            } catch (e: Exception) {
                Timber.e(e, "generateResponseAsync thất bại")
                activeChannel = null
                close(e)
                return@withLock
            }
            // Chờ cho đến khi listener gọi channel.close() (done=true)
            awaitClose { activeChannel = null }
        }
    }.flowOn(Dispatchers.IO)

    // ─── Image translation (blocking session) ────────────────────────────────

    /**
     * Dịch text trong ảnh sang tiếng Việt.
     * Dùng LlmInferenceSession với enableVisionModality + MPImage.
     */
    fun translateImage(bitmap: Bitmap): Flow<String> = flow {
        val llm = llmInference ?: run {
            emit("[LLM chưa sẵn sàng]")
            return@flow
        }

        mutex.withLock {
            try {
                val mpImage = BitmapImageBuilder(resizeBitmap(bitmap)).build()

                val sessionOptions = LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.1f)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .build()

                val result = withContext(Dispatchers.IO) {
                    LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                        session.addQueryChunk(IMAGE_PROMPT)
                        session.addImage(mpImage)
                        session.generateResponse()
                    }
                }

                if (!result.isNullOrBlank()) emit(result)
            } catch (e: Exception) {
                Timber.e(e, "translateImage thất bại")
                emit("[Lỗi dịch ảnh: ${e.message}]")
            }
        }
    }.flowOn(Dispatchers.IO)

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun resizeBitmap(bitmap: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    val isReady: Boolean get() = llmInference != null

    fun release() {
        try { llmInference?.close() } catch (e: Exception) { Timber.e(e) }
        llmInference = null
    }
}
