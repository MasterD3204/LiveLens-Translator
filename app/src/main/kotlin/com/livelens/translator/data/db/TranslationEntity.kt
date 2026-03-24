package com.livelens.translator.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.livelens.translator.model.TranslationMode

@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val mode: TranslationMode,
    val speakerLabel: String? = null,    // "A" or "B" for diarization mode
    val timestamp: Long = System.currentTimeMillis(),
    val hasImage: Boolean = false,
    val imageUri: String? = null
)
