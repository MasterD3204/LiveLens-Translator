package com.livelens.translator.model

import android.content.Context
import android.graphics.Bitmap
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
 * Wrapper quanh MediaPipe LLM Inference API (Gemma Translate).
 *
 * Dùng reflection để tránh lỗi compile khi dependency chưa được resolve.
 * Khi mediapipe-tasks-genai có trong classpath, mọi thứ hoạt động bình thường.
 */
class GemmaTranslateManager(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: Any? = null
    private var isInitializing = false

    companion object {
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.1f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f

        private const val TEXT_PROMPT_TEMPLATE =
            "Translate the following English text to Vietnamese. Output only the translation, nothing else:\n%s"

        private const val IMAGE_PROMPT =
            "Translate all English text found in this image to Vietnamese. " +
            "Format output as: [Original]: ... → [Dịch]: ..."

        private fun isMediaPipeAvailable(): Boolean = try {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            true
        } catch (_: ClassNotFoundException) { false }
    }

    fun initializeAsync() {
        if (llmInference != null || isInitializing) return
        if (!modelLoader.isGemmaReady()) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { initializeSync() }
    }

    suspend fun initializeSync() = withContext(Dispatchers.IO) {
        if (llmInference != null || isInitializing) return@withContext

        if (!isMediaPipeAvailable()) {
            Timber.w("MediaPipe LLM không tìm thấy trong classpath")
            return@withContext
        }

        val taskFile = modelLoader.resolveGemmaTaskFile()
        if (taskFile == null) {
            Timber.w("Không tìm thấy file Gemma .task")
            return@withContext
        }

        isInitializing = true
        try {
            // Thử GPU trước
            llmInference = buildLlmInference(taskFile.absolutePath, useGpu = true)
            Timber.i("Gemma khởi tạo thành công (GPU): ${taskFile.name}")
        } catch (e: Exception) {
            Timber.w("GPU init thất bại, thử CPU: ${e.message}")
            try {
                llmInference = buildLlmInference(taskFile.absolutePath, useGpu = false)
                Timber.i("Gemma khởi tạo thành công (CPU)")
            } catch (e2: Exception) {
                Timber.e(e2, "Gemma init thất bại hoàn toàn")
            }
        } finally {
            isInitializing = false
        }
    }

    /**
     * Dùng reflection để tạo LlmInference instance.
     * Tránh hard-coded import trực tiếp class MediaPipe.
     */
    private fun buildLlmInference(modelPath: String, useGpu: Boolean): Any {
        val llmClass     = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val backendClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$Backend")

        val gpuBackend  = backendClass.getField("GPU").get(null)
        val cpuBackend  = backendClass.getField("CPU").get(null)
        val backend     = if (useGpu) gpuBackend else cpuBackend

        val builder = optionsClass.getMethod("builder").invoke(null)
        builder.javaClass.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
        builder.javaClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, MAX_TOKENS)
        builder.javaClass.getMethod("setNumDraft",  Int::class.java).invoke(builder, 0)
        builder.javaClass.getMethod("setPreferredBackend", backendClass).invoke(builder, backend)
        val options = builder.javaClass.getMethod("build").invoke(builder)

        return llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
            .invoke(null, context, options)!!
    }

    // ─── Text translation ─────────────────────────────────────────────────────

    fun translateText(text: String): Flow<String> {
        val llm = llmInference ?: return fallbackFlow("LLM chưa sẵn sàng")
        return streamingFlow(llm, TEXT_PROMPT_TEMPLATE.format(text.trim()))
    }

    // ─── Image translation ────────────────────────────────────────────────────

    fun translateImage(bitmap: Bitmap): Flow<String> {
        val llm = llmInference ?: return fallbackFlow("LLM chưa sẵn sàng")
        val resized = resizeBitmap(bitmap)
        return callbackFlow {
            val session = createSession(llm)
            try {
                // addImage
                session.javaClass.getMethod("addImage", Bitmap::class.java).invoke(session, resized)
                // generateAsync
                invokeGenerateAsync(session, IMAGE_PROMPT) { token, done ->
                    if (token != null) trySend(token)
                    if (done == true) close()
                }
            } catch (e: Exception) {
                Timber.e(e, "translateImage thất bại")
                close(e)
            } finally {
                awaitClose {
                    try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) {}
                    if (resized != bitmap) resized.recycle()
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun streamingFlow(llm: Any, prompt: String): Flow<String> = callbackFlow {
        val session = createSession(llm)
        try {
            invokeGenerateAsync(session, prompt) { token, done ->
                if (token != null) trySend(token)
                if (done == true) close()
            }
        } catch (e: Exception) {
            Timber.e(e, "streamingFlow thất bại")
            close(e)
        } finally {
            awaitClose {
                try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) {}
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun createSession(llm: Any): Any {
        val sessionClass  = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession"
        )
        val sessionOptClass = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession\$LlmInferenceSessionOptions"
        )
        val llmClass = Class.forName(
            "com.google.mediapipe.tasks.genai.llminference.LlmInference"
        )

        val builder = sessionOptClass.getMethod("builder").invoke(null)
        builder.javaClass.getMethod("setTemperature", Float::class.java).invoke(builder, TEMPERATURE)
        builder.javaClass.getMethod("setTopK",        Int::class.java  ).invoke(builder, TOP_K)
        builder.javaClass.getMethod("setTopP",        Float::class.java).invoke(builder, TOP_P)
        val options = builder.javaClass.getMethod("build").invoke(builder)

        return sessionClass
            .getMethod("createFromLlmInference", llmClass, sessionOptClass)
            .invoke(null, llm, options)!!
    }

    /**
     * Gọi generateAsync qua reflection.
     * Tạo một lambda-compatible object cho ResultListener interface.
     */
    private fun invokeGenerateAsync(
        session: Any,
        prompt: String,
        onResult: (String?, Boolean?) -> Unit
    ) {
        // Tìm interface ResultListener trong MediaPipe
        val listenerInterface = try {
            Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession\$LlmInferenceSessionOptions"
            )
            // Tìm functional interface listener
            Class.forName("com.google.mediapipe.tasks.core.OutputHandler\$ResultListener")
        } catch (_: Exception) {
            // Fallback: dùng raw method với Kotlin lambda
            null
        }

        // Dùng Kotlin lambda trực tiếp nếu method chấp nhận Function2
        val generateMethod = session.javaClass.methods
            .firstOrNull { it.name == "generateAsync" }

        if (generateMethod == null) {
            Timber.e("Không tìm thấy method generateAsync")
            onResult(null, true)
            return
        }

        generateMethod.invoke(session, prompt) { partialResult: Any?, done: Any? ->
            onResult(partialResult as? String, done as? Boolean)
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), true)
    }

    private fun fallbackFlow(msg: String): Flow<String> {
        Timber.w(msg)
        return flowOf("[Lỗi: $msg]")
    }

    val isReady: Boolean get() = llmInference != null

    fun release() {
        try { llmInference?.javaClass?.getMethod("close")?.invoke(llmInference) }
        catch (e: Exception) { Timber.e(e, "Lỗi khi close LlmInference") }
        llmInference = null
    }
}
