package com.livelens.translator.di

import com.livelens.translator.data.db.TranslationDao
import com.livelens.translator.data.repository.TranslationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTranslationRepository(dao: TranslationDao): TranslationRepository {
        return TranslationRepository(dao)
    }
}
