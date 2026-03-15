package io.github.ian_miller.wuziqi.di

import android.content.Context
import androidx.room.Room
import io.github.ian_miller.wuziqi.data.local.GameDatabase
import io.github.ian_miller.wuziqi.domain.repository.GameRepository
import io.github.ian_miller.wuziqi.data.repository.GameRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideGameDatabase(@ApplicationContext context: Context): GameDatabase {
        return Room.databaseBuilder(
            context,
            GameDatabase::class.java,
            "gomoku_database"
        ).allowMainThreadQueries()
         .fallbackToDestructiveMigration(true)
         .build()
    }

    @Provides
    @Singleton
    fun provideGameRepository(database: GameDatabase): GameRepository {
        return GameRepositoryImpl(database)
    }
}