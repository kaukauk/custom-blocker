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
