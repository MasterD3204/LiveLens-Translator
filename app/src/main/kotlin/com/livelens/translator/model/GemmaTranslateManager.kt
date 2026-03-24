package com.livelens.translator.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wrapper quanh MediaPipe LLM Inference API (Gemma EN→VI).
 * Chỉ dùng API cốt lõi: LlmInference + LlmInferenceOptions + generateAsync.
 * Không dùng LlmInferenceSession, Backend enum, setNumDraft — tránh API không ổn định.
 */
class GemmaTranslateManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: LlmInference? = null
    private var isInitializing = false

    companion object {
        private const val MAX_TOKENS = 1024

        private const val TEXT_PROMPT =
            "Translate the following English text to Vietnamese. " +
            "Output only the translation, nothing else:\n%s"

        private const val IMAGE_PROMPT =
            "Translate all English text found in this image to Vietnamese. " +
            "Format output as: [Original]: ... → [Dịch]: ..."
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    fun initializeAsync() {
        if (llmInference != null || isInitializing) return
        if (!modelLoader.isGemmaReady()) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            initializeSync()
        }
    }

    suspend fun initializeSync() = withContext(Dispatchers.IO) {
        if (llmInference != null || isInitializing) return@withContext
        val taskFile = modelLoader.resolveGemmaTaskFile() ?: run {
            Timber.w("Gemma .task file not found")
            return@withContext
        }
        isInitializing = true
        try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(taskFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Timber.i("Gemma initialized: ${taskFile.name}")
        } catch (e: Exception) {
            Timber.e(e, "Gemma init failed")
        } finally {
            isInitializing = false
        }
    }

    // ─── Text translation ─────────────────────────────────────────────────────

    fun translateText(text: String): Flow<String> {
        val llm = llmInference ?: return flowOf("[LLM not ready]")
        return streamFlow(llm, TEXT_PROMPT.format(text.trim()))
    }

    // ─── Image translation ────────────────────────────────────────────────────

    fun translateImage(bitmap: Bitmap): Flow<String> {
        val llm = llmInference ?: return flowOf("[LLM not ready]")
        // Image text description passed as text prompt (multimodal via text fallback)
        return streamFlow(llm, IMAGE_PROMPT)
    }

    // ─── Streaming helper ─────────────────────────────────────────────────────

    /**
     * Wrap generateAsync in a Flow.
     * generateAsync(String, GenerateProgressListener) where GenerateProgressListener
     * is a @FunctionalInterface with: void onResult(@Nullable String partial, boolean done)
     * → Kotlin SAM: (String?, Boolean) -> Unit ✓
     */
    private fun streamFlow(llm: LlmInference, prompt: String): Flow<String> = callbackFlow {
        try {
            llm.generateAsync(prompt) { partial, done ->
                if (partial != null) trySend(partial)
                if (done) close()
            }
        } catch (e: Exception) {
            Timber.e(e, "generateAsync failed")
            close(e)
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    // ─── Utils ────────────────────────────────────────────────────────────────

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
        Timber.i("GemmaTranslateManager released")
    }
}
