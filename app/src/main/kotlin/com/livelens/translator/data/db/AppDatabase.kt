package com.livelens.translator.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.livelens.translator.model.TranslationMode

@Database(
    entities = [TranslationEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun translationDao(): TranslationDao

    class Converters {
        @TypeConverter
        fun fromMode(mode: TranslationMode): String = mode.name

        @TypeConverter
        fun toMode(value: String): TranslationMode = TranslationMode.valueOf(value)
    }

    companion object {
        const val DATABASE_NAME = "livelens.db"
    }
}
