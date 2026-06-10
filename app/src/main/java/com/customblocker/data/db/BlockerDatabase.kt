package com.customblocker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        ProfileAppEntity::class,
        TimeIntervalEntity::class,
        WifiNetworkEntity::class,
        GeoAddressEntity::class,
        UsageLimitEntity::class,
        WebsiteEntity::class,
        UsageCounterEntity::class,
        DailyStatEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun blockerDao(): BlockerDao
}
