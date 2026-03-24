package com.livelens.translator.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around MediaPipe LLM Inference API for Gemma Translate model.
 *
 * Responsibilities:
 * - Lazy-initialize LlmInference on a background thread
 * - Provide streaming text translation via Flow<String>
 * - Provide multimodal image translation via Flow<String>
 * - Manage session lifecycle to avoid OOM
 */
@Singleton
class GemmaTranslateManager @Inject constructor(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: LlmInference? = null
    private var isInitializing = false
    private var initializationError: Exception? = null

    companion object {
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.1f      // Low temp for deterministic translation
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
        private const val NUM_THREADS = 4

        private const val TEXT_TRANSLATE_PROMPT_TEMPLATE =
            "Translate the following English text to Vietnamese. Output only the translation, nothing else:\n%s"

        private const val IMAGE_TRANSLATE_PROMPT_TEMPLATE =
            "Translate all English text found in this image to Vietnamese. Format output as: [Original]: ... → [Dịch]: ..."
    }

    /**
     * Called from Application.onCreate() to kick off async initialization.
     * Uses a background coroutine to avoid blocking the main thread.
     */
    fun initializeAsync() {
        if (llmInference != null || isInitializing) return
        if (!modelLoader.isGemmaReady()) {
            Timber.w("Gemma model not ready — skipping async init")
            return
        }
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            initializeSync()
        }
    }

    /**
     * Synchronously initialize the LLM inference engine.
     * Safe to call multiple times — subsequent calls are no-ops.
     * Dùng resolveGemmaTaskFile() để hỗ trợ cả file trong Download lẫn internal.
     */
    suspend fun initializeSync() = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext
        if (isInitializing) return@withContext

        // Tìm file .task — ưu tiên internal, fallback ra Download
        val taskFile = modelLoader.resolveGemmaTaskFile()
        if (taskFile == null) {
            Timber.w("Gemma model không tìm thấy ở bất kỳ vị trí nào")
            return@withContext
        }

        isInitializing = true
        try {
            Timber.i("Khởi tạo Gemma Translate từ: ${taskFile.absolutePath}")
            val options = LlmInferenceOptions.builder()
                .setModelPath(taskFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setNumDraft(0)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            initializationError = null
            Timber.i("Gemma Translate khởi tạo thành công (GPU)")
        } catch (e: Exception) {
            Timber.e(e, "GPU init thất bại — thử lại với CPU")
            // Retry with CPU backend
            try {
                val taskFile2 = modelLoader.resolveGemmaTaskFile() ?: return@withContext
                val options = LlmInferenceOptions.builder()
                    .setModelPath(taskFile2.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setNumDraft(0)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                initializationError = null
                Timber.i("Gemma Translate initialized on CPU")
            } catch (e2: Exception) {
                initializationError = e2
                Timber.e(e2, "Gemma Translate CPU fallback also failed")
            }
        } finally {
            isInitializing = false
        }
    }

    /**
     * Translate English text to Vietnamese.
     * Returns a Flow<String> that emits tokens as they are generated (streaming).
     * The flow completes when generation finishes.
     *
     * @param text English text to translate
     * @return Flow emitting cumulative token output
     */
    fun translateText(text: String): Flow<String> = callbackFlow {
        val llm = ensureLlm() ?: run {
            close(IllegalStateException("LLM not initialized"))
            return@callbackFlow
        }

        val prompt = TEXT_TRANSLATE_PROMPT_TEMPLATE.format(text.trim())
        val session = createSession(llm)

        try {
            session.generateAsync(prompt) { partialResult, done ->
                if (partialResult != null) {
                    trySend(partialResult)
                }
                if (done == true) {
                    close()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "translateText failed")
            close(e)
        } finally {
            awaitClose {
                try { session.close() } catch (_: Exception) {}
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Translate text found in an image from English to Vietnamese.
     * Returns a Flow<String> that emits tokens as they are generated (streaming).
     *
     * @param bitmap Image bitmap (will be resized to max 1024px before inference)
     * @return Flow emitting cumulative token output
     */
    fun translateImage(bitmap: Bitmap): Flow<String> = callbackFlow {
        val llm = ensureLlm() ?: run {
            close(IllegalStateException("LLM not initialized"))
            return@callbackFlow
        }

        val resizedBitmap = resizeBitmapForInference(bitmap)
        val session = createSession(llm)

        try {
            // For multimodal: add the image to the session context, then prompt
            session.addImage(resizedBitmap)
            session.generateAsync(IMAGE_TRANSLATE_PROMPT_TEMPLATE) { partialResult, done ->
                if (partialResult != null) {
                    trySend(partialResult)
                }
                if (done == true) {
                    close()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "translateImage failed")
            close(e)
        } finally {
            awaitClose {
                try { session.close() } catch (_: Exception) {}
                if (resizedBitmap != bitmap) resizedBitmap.recycle()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Ensure the LLM is initialized, blocking if necessary.
     * Returns null if model is not available.
     */
    private suspend fun ensureLlm(): LlmInference? {
        if (llmInference != null) return llmInference
        if (!modelLoader.isGemmaReady()) return null
        initializeSync()
        return llmInference
    }

    /**
     * Create a new LlmInferenceSession with configured parameters.
     */
    private fun createSession(llm: LlmInference): LlmInferenceSession {
        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .build()
        return LlmInferenceSession.createFromLlmInference(llm, sessionOptions)
    }

    /**
     * Resize a bitmap so the longest side is ≤ 1024px to reduce TTFT latency.
     * Returns the original bitmap unchanged if it already fits within the limit.
     */
    private fun resizeBitmapForInference(bitmap: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /** True if the LLM engine is ready to accept requests. */
    val isReady: Boolean get() = llmInference != null

    /** Release resources. Call this only on OOM or when the app is fully destroyed. */
    fun release() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to close LlmInference")
        }
        llmInference = null
        Timber.i("GemmaTranslateManager released")
    }
}
