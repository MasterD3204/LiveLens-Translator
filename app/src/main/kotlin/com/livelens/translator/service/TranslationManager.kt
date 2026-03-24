package com.livelens.translator.service

import android.graphics.Bitmap
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.model.GemmaTranslateManager
import com.livelens.translator.model.SherpaOnnxManager
import com.livelens.translator.model.TranslationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central translation manager.
 *
 * Responsibilities:
 * - Holds the sentence queue with drop logic
 * - Exposes streaming translation flows for text and image modes
 * - Manages active translation sentences shown in the overlay
 * - Persists translations to the Room database
 */
@Singleton
class TranslationManager @Inject constructor(
    private val gemmaTranslateManager: GemmaTranslateManager,
    private val sherpaOnnxManager: SherpaOnnxManager,
    private val translationRepository: TranslationRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queueMutex = Mutex()

    // ─── Settings (injectable at runtime) ────────────────────────────────────
    var dropThreshold: Int = 2          // Drop oldest if queue >= this size
    var maxOverlaySentences: Int = 3    // Max sentences shown in overlay

    // ─── Sentence queue ───────────────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<TranslationSentence>>(emptyList())
    val sentences: StateFlow<List<TranslationSentence>> = _sentences.asStateFlow()

    // ─── Current mode ─────────────────────────────────────────────────────────
    private val _currentMode = MutableStateFlow(TranslationMode.CONVERSATION)
    val currentMode: StateFlow<TranslationMode> = _currentMode.asStateFlow()

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setMode(mode: TranslationMode) {
        _currentMode.value = mode
    }

    /**
     * Translate a transcribed English text sentence.
     * Applies drop logic: if queue is at capacity, clears backlog before adding.
     * Returns a Flow<String> of streaming tokens — callers may observe for real-time UI updates.
     *
     * @param text Transcribed English text
     * @param speakerLabel Optional "A" or "B" for diarization display
     * @param mode TranslationMode for history storage
     */
    fun translateText(
        text: String,
        speakerLabel: String? = null,
        mode: TranslationMode = _currentMode.value
    ): Flow<String> {
        val sentenceId = System.currentTimeMillis()

        // Add a placeholder sentence (will be updated token-by-token)
        val placeholder = TranslationSentence(
            id = sentenceId,
            originalText = text,
            translatedText = "",
            speakerLabel = speakerLabel,
            isStreaming = true,
            timestampMs = sentenceId
        )

        scope.launch {
            queueMutex.withLock {
                val current = _sentences.value
                val newList = if (current.size >= dropThreshold) {
                    // Drop backlog, keep only the newest item
                    Timber.d("Queue overflow (size=${current.size}), dropping backlog")
                    listOf(placeholder)
                } else {
                    current + placeholder
                }
                _sentences.value = newList.takeLast(maxOverlaySentences)
            }
        }

        // Stream translation tokens
        return gemmaTranslateManager.translateText(text)
            .catch { e ->
                Timber.e(e, "Translation stream error")
                updateSentence(sentenceId, "[Translation error]", isStreaming = false)
            }
            .onCompletion { cause ->
                if (cause == null) {
                    // Mark sentence as complete and persist
                    val finalText = _sentences.value.find { it.id == sentenceId }?.translatedText ?: ""
                    updateSentence(sentenceId, finalText, isStreaming = false)
                    persistTranslation(text, finalText, mode, speakerLabel)
                }
            }
            .also { flow ->
                // Accumulate tokens and update the sentence in real-time
                scope.launch {
                    val accumulated = StringBuilder()
                    flow.collect { token ->
                        accumulated.append(token)
                        updateSentence(sentenceId, accumulated.toString(), isStreaming = true)
                    }
                }
            }
    }

    /**
     * Translate an image using Gemma multimodal.
     * Returns a Flow<String> of streaming tokens.
     *
     * @param bitmap Source image (will be resized internally)
     */
    fun translateImage(bitmap: Bitmap): Flow<String> {
        return gemmaTranslateManager.translateImage(bitmap)
    }

    /**
     * Dismiss a sentence from the overlay (e.g., after auto-dismiss timeout).
     */
    fun dismissSentence(id: Long) {
        _sentences.value = _sentences.value.filter { it.id != id }
    }

    /**
     * Clear all sentences from the overlay.
     */
    fun clearAllSentences() {
        _sentences.value = emptyList()
    }

    // ─── Internal helpers ──────────────────────────────────────────────────────

    private fun updateSentence(id: Long, text: String, isStreaming: Boolean) {
        _sentences.value = _sentences.value.map { sentence ->
            if (sentence.id == id) sentence.copy(translatedText = text, isStreaming = isStreaming)
            else sentence
        }
    }

    private fun persistTranslation(
        original: String,
        translated: String,
        mode: TranslationMode,
        speakerLabel: String?
    ) {
        if (translated.isBlank()) return
        scope.launch {
            try {
                translationRepository.insert(
                    originalText = original,
                    translatedText = translated,
                    mode = mode,
                    speakerLabel = speakerLabel
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist translation")
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}

/**
 * Represents a single translation sentence shown in the overlay.
 */
data class TranslationSentence(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val speakerLabel: String? = null,   // "A" or "B"
    val isStreaming: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)
