package com.customblocker.di

import android.content.Context
import androidx.room.Room
import com.customblocker.data.db.BlockerDao
import com.customblocker.data.db.BlockerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlockerDatabase =
        Room.databaseBuilder(context, BlockerDatabase::class.java, "blocker.db")
            // Dev convenience: schema still churning across phases. Replace with real
            // migrations before any release with user data. (Room 2.6.x: no-arg form.)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBlockerDao(database: BlockerDatabase): BlockerDao = database.blockerDao()
}
