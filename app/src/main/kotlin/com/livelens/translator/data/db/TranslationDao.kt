package com.livelens.translator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(translation: TranslationEntity): Long

    @Delete
    suspend fun delete(translation: TranslationEntity)

    @Query("DELETE FROM translations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM translations ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<TranslationEntity>>

    @Query("SELECT * FROM translations ORDER BY timestamp DESC LIMIT 5")
    suspend fun getRecentFive(): List<TranslationEntity>

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun getCount(): Int

    @Query("""
        DELETE FROM translations
        WHERE id IN (
            SELECT id FROM translations ORDER BY timestamp ASC LIMIT :count
        )
    """)
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM translations")
    suspend fun deleteAll()
}
