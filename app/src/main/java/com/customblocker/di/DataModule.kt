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

    /** v10→v11: add the notification-filter + captured-notification tables. Preserves user data. */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS notif_filters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    packagesCsv TEXT NOT NULL DEFAULT '',
                    regexPattern TEXT NOT NULL DEFAULT '',
                    regexTarget INTEGER NOT NULL DEFAULT 2,
                    secondaryPattern TEXT NOT NULL DEFAULT '',
                    action INTEGER NOT NULL DEFAULT 0,
                    batchIntervalMinutes INTEGER NOT NULL DEFAULT 60,
                    releaseTimesCsv TEXT NOT NULL DEFAULT '',
                    startMinute INTEGER NOT NULL DEFAULT 0,
                    endMinute INTEGER NOT NULL DEFAULT 1439,
                    dayFlags INTEGER NOT NULL DEFAULT 127,
                    priority INTEGER NOT NULL DEFAULT 0,
                    hits INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS captured_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    packageName TEXT NOT NULL,
                    title TEXT NOT NULL,
                    text TEXT NOT NULL,
                    postedAtMillis INTEGER NOT NULL,
                    filterId INTEGER,
                    actionApplied INTEGER NOT NULL DEFAULT -1
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_captured_notifications_postedAtMillis " +
                    "ON captured_notifications (postedAtMillis)",
            )
        }
    }

    /**
     * v11→v12: drop the now-removed profiles.blockLaunch column (app blocking always ejects, so the
     * flag was dead config). SQLite can't drop a column in place pre-3.35 reliably across devices, so
     * recreate the table without it and copy data. FK children reference "profiles" by name, so the
     * drop-and-rename keeps them valid.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE profiles_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    enabled INTEGER NOT NULL,
                    blockingMode INTEGER NOT NULL,
                    conditionsMask INTEGER NOT NULL,
                    dayFlags INTEGER NOT NULL,
                    blockUnsupportedBrowsers INTEGER NOT NULL,
                    chargerInverted INTEGER NOT NULL,
                    blockNotifications INTEGER NOT NULL,
                    batchNotifications INTEGER NOT NULL,
                    addNewApps INTEGER NOT NULL,
                    pausedUntilMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO profiles_new (
                    id, title, enabled, blockingMode, conditionsMask, dayFlags,
                    blockUnsupportedBrowsers, chargerInverted, blockNotifications,
                    batchNotifications, addNewApps, pausedUntilMillis
                )
                SELECT
                    id, title, enabled, blockingMode, conditionsMask, dayFlags,
                    blockUnsupportedBrowsers, chargerInverted, blockNotifications,
                    batchNotifications, addNewApps, pausedUntilMillis
                FROM profiles
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE profiles")
            db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
        }
    }

    /** v12→v13: add the app_meta cache (label + icon snapshot) so uninstalled-but-targeted apps keep
     *  their real name/icon. Additive, preserves user data. */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS app_meta (
                    packageName TEXT PRIMARY KEY NOT NULL,
                    label TEXT NOT NULL,
                    iconPng BLOB
                )
                """.trimIndent(),
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlockerDatabase =
        Room.databaseBuilder(context, BlockerDatabase::class.java, "blocker.db")
            .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            // Dev convenience backstop for older churn; real migrations are added per-version above.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBlockerDao(database: BlockerDatabase): BlockerDao = database.blockerDao()
}
