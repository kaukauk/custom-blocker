package com.customblocker.domain.engine

/**
 * Pure condition model + evaluator (PLAN.md §7-8). No Android imports → JVM-unit-testable.
 *
 * A profile is "schedule-active" when ALL of its enabled conditions are currently satisfied
 * (logical AND). A package is blocked if ANY active profile blocks it (logical OR across profiles).
 * Conditions enabled for a profile are encoded as a bitmask; an empty mask = always-active.
 */
object ConditionTypes {
    const val TIME = 1
    const val WIFI = 2
    const val CHARGER = 4
    const val LOCATION = 8
    const val USAGE_LIMIT = 16
    const val LAUNCH_COUNT = 32
}

/** Reset cadence for usage/launch counters. */
enum class LimitPeriod { HOURLY, DAILY }

/** "Block this profile's apps once they've been used [limitSeconds] this [period]." */
data class UsageLimitSpec(val limitSeconds: Int, val period: LimitPeriod)

/** "Block this profile's apps once they've been opened [limit] times this [period]." */
data class LaunchLimitSpec(val limit: Int, val period: LimitPeriod)

/** Day-of-week bitmask (matches storage). */
object DayFlags {
    const val MON = 1
    const val TUE = 2
    const val WED = 4
    const val THU = 8
    const val FRI = 16
    const val SAT = 32
    const val SUN = 64
    const val ALL = MON or TUE or WED or THU or FRI or SAT or SUN
}

/** A daily time range in minutes-from-midnight; handles windows that wrap past midnight. */
data class TimeWindow(val fromMinuteOfDay: Int, val toMinuteOfDay: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (fromMinuteOfDay <= toMinuteOfDay) {
            minuteOfDay in fromMinuteOfDay until toMinuteOfDay
        } else {
            // wraps midnight, e.g. 22:00 -> 06:00
            minuteOfDay >= fromMinuteOfDay || minuteOfDay < toMinuteOfDay
        }
}

/** A geofence reference; [inverted] = active when OUTSIDE the fence. */
data class GeofenceSpec(val id: String, val inverted: Boolean = false)

/** A profile flattened into everything the engine needs to evaluate it. */
data class ProfileRule(
    val id: Long,
    val title: String,
    val enabled: Boolean,
    val mode: BlockingMode,
    val apps: Set<String>,
    val conditionsMask: Int = 0,
    val dayFlags: Int = DayFlags.ALL,
    val timeWindows: List<TimeWindow> = emptyList(),
    val wifiSsids: Set<String> = emptySet(),
    val geofences: List<GeofenceSpec> = emptyList(),
    val usageLimit: UsageLimitSpec? = null,
    val launchLimit: LaunchLimitSpec? = null,
)

/** The live signals the engine evaluates a profile against at a moment in time. */
data class BlockContext(
    val dayBit: Int,
    val minuteOfDay: Int,
    val wifiSsid: String? = null,
    val charging: Boolean = false,
    val insideGeofenceIds: Set<String> = emptySet(),
    /** Foreground seconds used this period, keyed by profile id. */
    val usageUsedSeconds: Map<Long, Int> = emptyMap(),
    /** Launch count this period, keyed by profile id. */
    val launchCounts: Map<Long, Int> = emptyMap(),
)

object ConditionEvaluator {

    /** True if every enabled condition of [rule] is satisfied by [ctx]. */
    fun isActive(rule: ProfileRule, ctx: BlockContext): Boolean {
        if (!rule.enabled) return false
        val m = rule.conditionsMask

        if (m and ConditionTypes.TIME != 0) {
            if (rule.dayFlags and ctx.dayBit == 0) return false
            if (rule.timeWindows.none { it.contains(ctx.minuteOfDay) }) return false
        }
        if (m and ConditionTypes.WIFI != 0) {
            val ssid = ctx.wifiSsid ?: return false
            if (ssid !in rule.wifiSsids) return false
        }
        if (m and ConditionTypes.CHARGER != 0) {
            if (!ctx.charging) return false
        }
        if (m and ConditionTypes.LOCATION != 0) {
            val satisfied = rule.geofences.any { fence ->
                val inside = fence.id in ctx.insideGeofenceIds
                if (fence.inverted) !inside else inside
            }
            if (!satisfied) return false
        }
        if (m and ConditionTypes.USAGE_LIMIT != 0) {
            val spec = rule.usageLimit ?: return false
            if ((ctx.usageUsedSeconds[rule.id] ?: 0) < spec.limitSeconds) return false
        }
        if (m and ConditionTypes.LAUNCH_COUNT != 0) {
            val spec = rule.launchLimit ?: return false
            if ((ctx.launchCounts[rule.id] ?: 0) < spec.limit) return false
        }
        return true
    }
}
