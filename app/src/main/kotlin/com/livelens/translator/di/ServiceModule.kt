package com.livelens.translator.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.model.GemmaTranslateManager
import com.livelens.translator.model.SherpaOnnxManager
import com.livelens.translator.service.TranslationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "livelens_settings")

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideTranslationManager(
        gemmaTranslateManager: GemmaTranslateManager,
        sherpaOnnxManager: SherpaOnnxManager,
        translationRepository: TranslationRepository
    ): TranslationManager {
        return TranslationManager(gemmaTranslateManager, sherpaOnnxManager, translationRepository)
    }
}
