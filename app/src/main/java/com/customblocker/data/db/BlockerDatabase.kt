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
        NotifFilterEntity::class,
        CapturedNotificationEntity::class,
        AppMetaEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun blockerDao(): BlockerDao
}
