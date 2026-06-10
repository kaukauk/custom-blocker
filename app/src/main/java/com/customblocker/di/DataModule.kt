package com.customblocker.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /** v7→v8: add profiles.pausedUntilMillis (temporary per-profile pause). Preserves user data. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN pausedUntilMillis INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v8→v9: add profiles.batchNotifications (snooze-and-redeliver vs hide). Preserves user data. */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN batchNotifications INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v9→v10: add profiles.chargerInverted (charging-condition inverse sense). Preserves user data. */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN chargerInverted INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlockerDatabase =
        Room.databaseBuilder(context, BlockerDatabase::class.java, "blocker.db")
            .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            // Dev convenience backstop for older churn; real migrations are added per-version above.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBlockerDao(database: BlockerDatabase): BlockerDao = database.blockerDao()
}
