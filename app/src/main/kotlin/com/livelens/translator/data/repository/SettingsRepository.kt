package com.livelens.translator.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val MAX_SENTENCES = intPreferencesKey("max_sentences")
        val DROP_THRESHOLD = intPreferencesKey("drop_threshold")
    }

    val appSettings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: false,
            ttsSpeed = prefs[Keys.TTS_SPEED] ?: 1.0f,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: 0.75f,
            subtitlePosition = SubtitlePosition.valueOf(
                prefs[Keys.SUBTITLE_POSITION] ?: SubtitlePosition.BOTTOM.name
            ),
            fontSize = FontSizePref.valueOf(
                prefs[Keys.FONT_SIZE] ?: FontSizePref.MEDIUM.name
            ),
            maxSentences = prefs[Keys.MAX_SENTENCES] ?: 3,
            dropThreshold = prefs[Keys.DROP_THRESHOLD] ?: 2
        )
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    suspend fun setTtsSpeed(speed: Float) {
        dataStore.edit { it[Keys.TTS_SPEED] = speed }
    }

    suspend fun setOverlayOpacity(opacity: Float) {
        dataStore.edit { it[Keys.OVERLAY_OPACITY] = opacity.coerceIn(0.50f, 0.90f) }
    }

    suspend fun setSubtitlePosition(position: SubtitlePosition) {
        dataStore.edit { it[Keys.SUBTITLE_POSITION] = position.name }
    }

    suspend fun setFontSize(size: FontSizePref) {
        dataStore.edit { it[Keys.FONT_SIZE] = size.name }
    }

    suspend fun setMaxSentences(max: Int) {
        dataStore.edit { it[Keys.MAX_SENTENCES] = max.coerceIn(1, 3) }
    }

    suspend fun setDropThreshold(threshold: Int) {
        dataStore.edit { it[Keys.DROP_THRESHOLD] = threshold.coerceIn(1, 3) }
    }
}

data class AppSettings(
    val ttsEnabled: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val overlayOpacity: Float = 0.75f,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,
    val fontSize: FontSizePref = FontSizePref.MEDIUM,
    val maxSentences: Int = 3,
    val dropThreshold: Int = 2
)

enum class SubtitlePosition { TOP, BOTTOM }

enum class FontSizePref { SMALL, MEDIUM, LARGE }
