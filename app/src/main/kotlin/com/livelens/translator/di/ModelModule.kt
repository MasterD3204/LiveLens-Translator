package com.livelens.translator.di

import android.content.Context
import com.livelens.translator.model.GemmaTranslateManager
import com.livelens.translator.model.ModelLoader
import com.livelens.translator.model.SherpaOnnxManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {

    @Provides
    @Singleton
    fun provideModelLoader(@ApplicationContext context: Context): ModelLoader {
        return ModelLoader(context)
    }

    @Provides
    @Singleton
    fun provideSherpaOnnxManager(
        @ApplicationContext context: Context,
        modelLoader: ModelLoader
    ): SherpaOnnxManager {
        return SherpaOnnxManager(context, modelLoader)
    }

    @Provides
    @Singleton
    fun provideGemmaTranslateManager(
        @ApplicationContext context: Context,
        modelLoader: ModelLoader
    ): GemmaTranslateManager {
        return GemmaTranslateManager(context, modelLoader)
    }
}
