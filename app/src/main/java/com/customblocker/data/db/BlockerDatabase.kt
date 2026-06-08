package com.customblocker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, ProfileAppEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun blockerDao(): BlockerDao
}
