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
    /** CHARGER condition sense: false = active while charging, true = active while NOT charging. */
    val chargerInverted: Boolean = false,
    /** Suppress notifications from targeted apps while this profile is active. */
    val blockNotifications: Boolean = false,
    /** When blocking notifications: true = batch (snooze, re-deliver at next reset), false = hide. */
    val batchNotifications: Boolean = true,
    /** Automatically add newly-installed launchable apps to this profile's list. */
    val addNewApps: Boolean = false,
    /** Temporarily inactive until this wall-clock instant (0 = not paused). Auto-resumes after. */
    val pausedUntilMillis: Long = 0,
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

/**
 * Cached display metadata for a package (its real label + downscaled icon PNG), snapshotted while
 * the app is installed. Lets the UI keep showing a targeted app's real name/icon after it's
 * uninstalled, instead of falling back to the raw package name. Keyed by package, shared across
 * profiles; a harmless cache (safe to lose/rebuild).
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val iconPng: ByteArray? = null,
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

/**
 * Per-app, per-day statistics (PLAN.md §13). One row per (package, day) accumulates foreground
 * seconds, block count, and suppressed-notification count via UPSERT-increment. Dashboards
 * aggregate over day/week ranges. dateEpochDay = LocalDate.toEpochDay().
 */
/** Aggregated per-app totals over a date range (Room query projection). */
data class AppStat(
    val packageName: String,
    val usageSeconds: Int,
    val blockCount: Int,
    val notificationBlockCount: Int,
)

@Entity(
    tableName = "daily_stats",
    indices = [Index(value = ["packageName", "dateEpochDay"], unique = true)],
)
data class DailyStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val dateEpochDay: Long,
    val usageSeconds: Int = 0,
    val blockCount: Int = 0,
    val notificationBlockCount: Int = 0,
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

/**
 * A notification filter (PLAN.md §4b): independent of blocking profiles. Matches posted
 * notifications by app + an optional regex over the title/text, and applies an action while a
 * daily schedule is active. [packagesCsv] is a comma-separated package list (empty = any app);
 * [releaseTimesCsv] is a comma-separated list of minute-of-day release points used by DELAY.
 */
@Entity(tableName = "notif_filters")
data class NotifFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val enabled: Boolean = true,
    /** Comma-separated package names to match; empty = any app. */
    val packagesCsv: String = "",
    /** Regex over the chosen target; blank = match every notification from the apps. */
    val regexPattern: String = "",
    /** 0 = TITLE, 1 = CONTENT, 2 = EITHER, 3 = BOTH (NotifMatchTarget.ordinal). */
    val regexTarget: Int = 2,
    /** Second regex (matched against the content) when [regexTarget] is BOTH. */
    val secondaryPattern: String = "",
    /** 0 = DISMISS, 1 = CATCH, 2 = BATCH, 3 = DELAY (NotifActionType.ordinal). */
    val action: Int = 0,
    /** BATCH: release cadence in minutes, aligned to midnight. */
    val batchIntervalMinutes: Int = 60,
    /** DELAY: comma-separated minute-of-day release points. */
    val releaseTimesCsv: String = "",
    /** Capture-window start (minute of day, inclusive). 0 = midnight. */
    val startMinute: Int = 0,
    /** Capture-window end (minute of day, inclusive). 1439 = 23:59. Wraps if end < start. */
    val endMinute: Int = 1439,
    /** Day-of-week bitmask (DayFlags.*) the schedule applies on. */
    val dayFlags: Int = 127,
    /** Lower wins when several filters match the same notification. */
    val priority: Int = 0,
    /** How many notifications this filter has acted on (display only). */
    val hits: Int = 0,
)

/**
 * A captured notification for the in-app history/inbox. Every non-ongoing posted notification is
 * logged here (capped, oldest-trimmed) so the user can review what came in and build filters from
 * real examples. [filterId]/[actionApplied] record which filter (if any) acted and how.
 */
@Entity(
    tableName = "captured_notifications",
    indices = [Index("postedAtMillis")],
)
data class CapturedNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    /** The filter that acted on it, or null if it was only observed. */
    val filterId: Long? = null,
    /** Action applied: -1 = none/observed, else NotifActionType.ordinal. */
    val actionApplied: Int = -1,
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
