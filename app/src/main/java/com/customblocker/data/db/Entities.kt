package com.customblocker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema (PLAN.md §6). A Profile holds apps + a blocking mode + conditions. A condition "type"
 * is active for a profile when its bit is set in [ProfileEntity.conditionsMask]; an empty mask
 * means the profile is always-active (Phase 2 behaviour). Condition specifics live in the
 * per-type child tables below. Websites + usage/launch-limit tables arrive in later phases.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val enabled: Boolean = true,
    /** 0 = BLOCKLIST (block these), 1 = ALLOWLIST (allow only these). */
    val blockingMode: Int = 0,
    /** Bitmask of active condition types (ConditionTypes.*); 0 = always active. */
    val conditionsMask: Int = 0,
    /** Day-of-week bitmask for the TIME condition (DayFlags.*). */
    val dayFlags: Int = 127,
    /** Block the whole browser when its URL can't be read (PLAN.md §10). */
    val blockUnsupportedBrowsers: Boolean = false,
    /** Eject the user when a targeted app is launched/foregrounded. */
    val blockLaunch: Boolean = true,
    /** Suppress notifications from targeted apps while this profile is active. */
    val blockNotifications: Boolean = false,
    /** Automatically add newly-installed launchable apps to this profile's list. */
    val addNewApps: Boolean = false,
)

@Entity(
    tableName = "profile_apps",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId"), Index(value = ["profileId", "packageName"], unique = true)],
)
data class ProfileAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val packageName: String,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "time_intervals",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId")],
)
data class TimeIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val fromMinuteOfDay: Int,
    val toMinuteOfDay: Int,
)

@Entity(
    tableName = "wifi_networks",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId")],
)
data class WifiNetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val ssid: String,
)

@Entity(
    tableName = "usage_limits",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["profileId", "limitType"], unique = true)],
)
data class UsageLimitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** 0 = usage time (amount in minutes), 1 = launch count (amount = launches). */
    val limitType: Int,
    val amount: Int,
    /** true = resets daily, false = resets hourly. */
    val periodDaily: Boolean = true,
)

/**
 * Runtime usage/launch counters, persisted so a daily/hourly limit survives process death and
 * reboot (PLAN.md §12). [usagePeriodKey]/[launchPeriodKey] are the epoch-day (daily) or epoch-hour
 * (hourly) the counter belongs to; on load, a stale key means the period rolled over → reset to 0.
 */
@Entity(
    tableName = "usage_counters",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["profileId"], unique = true)],
)
data class UsageCounterEntity(
    @PrimaryKey val profileId: Long,
    val usedSeconds: Int = 0,
    val launches: Int = 0,
    val usagePeriodKey: Long = 0,
    val launchPeriodKey: Long = 0,
)

@Entity(
    tableName = "profile_websites",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId"), Index(value = ["profileId", "value", "matchType"], unique = true)],
)
data class WebsiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Already normalized for its match type (lowercased; host for DOMAIN, scheme-stripped for EXACT). */
    val value: String,
    /** 0 = DOMAIN, 1 = KEYWORD, 2 = EXACT (UrlMatchType.ordinal). */
    val matchType: Int,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "geo_addresses",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId")],
)
data class GeoAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val geofenceId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val inverted: Boolean = false,
    val label: String? = null,
)
