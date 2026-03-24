package com.livelens.translator.service

import android.content.Context
import com.livelens.translator.data.repository.SettingsRepository
import com.livelens.translator.model.SherpaOnnxManager
import com.livelens.translator.util.AudioUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TTS (Text-to-Speech) playback of Vietnamese translations.
 * Reads settings to determine if TTS is enabled and at what speed.
 * Uses the Piper model via SherpaOnnxManager.
 *
 * Provided by ServiceModule via @Provides — no duplicate @Inject constructor.
 */
@Singleton
class TtsPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sherpaOnnxManager: SherpaOnnxManager,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Speak the given Vietnamese text if TTS is enabled in settings.
     */
    fun speakIfEnabled(vietnameseText: String) {
        scope.launch {
            try {
                val settings = settingsRepository.appSettings.first()
                if (!settings.ttsEnabled) return@launch
                if (vietnameseText.isBlank()) return@launch

                val samples = sherpaOnnxManager.synthesizeSpeech(vietnameseText, settings.ttsSpeed)
                if (samples != null && samples.isNotEmpty()) {
                    AudioUtils.playPcmFloat(samples, sherpaOnnxManager.ttsSampleRate)
                }
            } catch (e: Exception) {
                Timber.e(e, "TTS playback failed")
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
