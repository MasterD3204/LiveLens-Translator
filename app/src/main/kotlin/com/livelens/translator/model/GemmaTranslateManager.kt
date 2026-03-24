package com.livelens.translator.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
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
 * Wrapper quanh MediaPipe LLM Inference API (Gemma Translate EN→VI).
 * Được tạo thủ công qua ModelModule.provideGemmaTranslateManager().
 */
class GemmaTranslateManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: LlmInference? = null
    private var isInitializing = false

    companion object {
        private const val MAX_TOKENS  = 1024
        private const val TEMPERATURE = 0.1f
        private const val TOP_K       = 40
        private const val TOP_P       = 0.95f

        private const val TEXT_PROMPT =
            "Translate the following English text to Vietnamese. Output only the translation, nothing else:\n%s"

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
            llmInference = buildInference(taskFile.absolutePath, useGpu = true)
            Timber.i("Gemma initialized (GPU): ${taskFile.name}")
        } catch (e: Exception) {
            Timber.w("GPU init failed, retrying on CPU: ${e.message}")
            try {
                llmInference = buildInference(taskFile.absolutePath, useGpu = false)
                Timber.i("Gemma initialized (CPU)")
            } catch (e2: Exception) {
                Timber.e(e2, "Gemma init failed")
            }
        } finally {
            isInitializing = false
        }
    }

    private fun buildInference(modelPath: String, useGpu: Boolean): LlmInference {
        val backend = if (useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setNumDraft(0)
            .setPreferredBackend(backend)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    // ─── Text translation ─────────────────────────────────────────────────────

    fun translateText(text: String): Flow<String> {
        val llm = llmInference ?: return flowOf("[LLM not ready]")
        return streamFlow(llm, TEXT_PROMPT.format(text.trim()))
    }

    // ─── Image translation ────────────────────────────────────────────────────

    fun translateImage(bitmap: Bitmap): Flow<String> {
        val llm = llmInference ?: return flowOf("[LLM not ready]")
        val resized = resizeBitmap(bitmap)
        return callbackFlow {
            val session = newSession(llm)
            try {
                session.addImage(resized)
                session.generateAsync(IMAGE_PROMPT) { partial, done ->
                    if (partial != null) trySend(partial)
                    if (done == true) close()
                }
            } catch (e: Exception) {
                Timber.e(e, "translateImage failed")
                close(e)
            } finally {
                awaitClose {
                    try { session.close() } catch (_: Exception) {}
                    if (resized != bitmap) resized.recycle()
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun streamFlow(llm: LlmInference, prompt: String): Flow<String> = callbackFlow {
        val session = newSession(llm)
        try {
            session.generateAsync(prompt) { partial, done ->
                if (partial != null) trySend(partial)
                if (done == true) close()
            }
        } catch (e: Exception) {
            Timber.e(e, "streamFlow failed")
            close(e)
        } finally {
            awaitClose { try { session.close() } catch (_: Exception) {} }
        }
    }.flowOn(Dispatchers.IO)

    private fun newSession(llm: LlmInference): LlmInferenceSession {
        val opts = LlmInferenceSessionOptions.builder()
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .build()
        return LlmInferenceSession.createFromLlmInference(llm, opts)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), true)
    }

    val isReady: Boolean get() = llmInference != null

    fun release() {
        try { llmInference?.close() } catch (e: Exception) { Timber.e(e) }
        llmInference = null
    }
}
