package com.livelens.translator.data.repository

import com.livelens.translator.data.db.TranslationDao
import com.livelens.translator.data.db.TranslationEntity
import com.livelens.translator.model.TranslationMode
import kotlinx.coroutines.flow.Flow

class TranslationRepository(
    private val dao: TranslationDao
) {
    companion object {
        private const val MAX_HISTORY_ENTRIES = 500
    }

    /** All translations ordered by timestamp descending, observed as Flow */
    fun observeAll(): Flow<List<TranslationEntity>> = dao.observeRecent(MAX_HISTORY_ENTRIES)

    /** Most recent 5 translations (for Home screen quick history) */
    suspend fun getRecentFive(): List<TranslationEntity> = dao.getRecentFive()

    /**
     * Insert a new translation and enforce max 500 entry cap.
     * If over the cap, oldest entries are pruned.
     */
    suspend fun insert(
        originalText: String,
        translatedText: String,
        mode: TranslationMode,
        speakerLabel: String? = null,
        hasImage: Boolean = false,
        imageUri: String? = null
    ): Long {
        val entity = TranslationEntity(
            originalText = originalText,
            translatedText = translatedText,
            mode = mode,
            speakerLabel = speakerLabel,
            hasImage = hasImage,
            imageUri = imageUri
        )
        val insertedId = dao.insert(entity)

        // Enforce the 500-entry cap
        val count = dao.getCount()
        if (count > MAX_HISTORY_ENTRIES) {
            dao.deleteOldest(count - MAX_HISTORY_ENTRIES)
        }

        return insertedId
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
