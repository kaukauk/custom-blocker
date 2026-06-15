package com.customblocker.domain.engine

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of evaluating a foreground app against the active rules. */
sealed interface BlockDecision {
    data class Block(val packageName: String, val reason: String) : BlockDecision
    data object Allow : BlockDecision
}

/** What to do with a notification from a targeted app right now. */
sealed interface NotificationAction {
    /** Let it through. */
    data object Allow : NotificationAction
    /** Drop it silently. */
    data object Hide : NotificationAction
    /** Snooze it until [untilMillis] (when the blocking profile next stops restricting). */
    data class Batch(val untilMillis: Long) : NotificationAction
}

enum class BlockingMode { BLOCKLIST, ALLOWLIST }

/**
 * Pure decision core (PLAN.md §7). Holds the current set of profile rules; on each foreground
 * event the accessibility service supplies a [BlockContext] and asks for a [decide]. A package is
 * blocked if ANY currently-active profile blocks it.
 */
@Singleton
class BlockingEngine @Inject constructor() {

    /** Volatile: written from the DB-sync coroutine, read on the accessibility thread. */
    @Volatile
    var profiles: List<ProfileRule> = emptyList()
        private set

    /** Packages we must never block (would soft-brick the device / lock the user out). */
    private val neverBlock: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        // Runtime permission dialogs — not a real app; an allow-list must never eject these or the
        // user can't grant/deny permissions (and they pop up over other apps during normal use).
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /**
     * Our own package — set once at startup. Never blocked in any mode (incl. allow-list), so the
     * user can always reach the app to change or disable a profile and can't lock themselves out.
     */
    @Volatile
    var selfPackage: String? = null

    /**
     * System packages resolved at runtime that must never be blocked, even by an allow-list:
     * the active IME(s) (keyboard) and the current home launcher. Without this an allow-list that
     * omits the keyboard would block it, leaving the user unable to type. Set from the Android side.
     */
    @Volatile
    var systemPackages: Set<String> = emptySet()

    private fun isNeverBlocked(packageName: String): Boolean =
        packageName in neverBlock || packageName == selfPackage || packageName in systemPackages

    fun update(newProfiles: List<ProfileRule>) {
        profiles = newProfiles
    }

    fun decide(packageName: String?, ctx: BlockContext): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (isNeverBlocked(packageName)) return BlockDecision.Allow

        for (rule in profiles) {
            // Launch-blocking (ejecting from a targeted app) is core behaviour — always applied for
            // an active profile, not an opt-in.
            if (!ConditionEvaluator.isActive(rule, ctx)) continue
            if (rule.targets(packageName)) {
                val how = if (rule.mode == BlockingMode.BLOCKLIST) "block list" else "allow list"
                return BlockDecision.Block(packageName, "by \"${rule.title}\" ($how)")
            }
        }
        return BlockDecision.Allow
    }

    /**
     * Whether notifications from [packageName] should be suppressed right now: any active profile
     * that targets the package AND has notification-blocking enabled (PLAN.md §4). Independent of
     * [decide]'s launch blocking, so a profile can suppress notifications without ejecting.
     */
    fun shouldBlockNotification(packageName: String?, ctx: BlockContext): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (isNeverBlocked(packageName)) return false
        return profiles.any { rule ->
            rule.blockNotifications && rule.targets(packageName) && ConditionEvaluator.isActive(rule, ctx)
        }
    }

    /**
     * Richer notification decision: which [NotificationAction] applies to [packageName] now.
     * Hide wins if any blocking profile suppresses; otherwise (all batch) we snooze until the
     * soonest one stops restricting (e.g. the top of the hour for a 5-min/hour usage limit).
     * [dayStartHour] sets when "per day" limits roll over.
     */
    fun notificationAction(packageName: String?, ctx: BlockContext, dayStartHour: Int): NotificationAction {
        if (packageName.isNullOrBlank()) return NotificationAction.Allow
        if (isNeverBlocked(packageName)) return NotificationAction.Allow
        val matching = profiles.filter {
            it.blockNotifications && it.targets(packageName) && ConditionEvaluator.isActive(it, ctx)
        }
        if (matching.isEmpty()) return NotificationAction.Allow
        if (matching.any { !it.batchNotifications }) return NotificationAction.Hide
        // All matching profiles batch: hold until the soonest moment one could stop restricting, then
        // let the OS re-deliver. If it's still blocked then it's simply re-held — self-correcting.
        val timeRelease = matching.mapNotNull { deactivationMillis(it, ctx, dayStartHour) }.minOrNull()
        // A profile gated by charger / location / Wi-Fi stops at an unpredictable moment (you unplug,
        // you leave the area) — there's no fixed time to release at. Rather than drop the notification
        // (the old behaviour), re-check on a fixed cadence so it surfaces shortly after the profile
        // goes inactive. Held notifications then come back instead of being lost.
        val eventGated = matching.any {
            it.conditionsMask and (ConditionTypes.CHARGER or ConditionTypes.LOCATION or ConditionTypes.WIFI) != 0
        }
        val until = when {
            eventGated -> minOf(timeRelease ?: Long.MAX_VALUE, ctx.nowMillis + NOTIFICATION_RECHECK_MS)
            // Purely time-bounded (schedule / usage / launch limit): release exactly at the boundary.
            timeRelease != null -> timeRelease
            // No condition can ever stop restricting (e.g. an always-on block list) → nothing to
            // release at, so holding forever is pointless: hide.
            else -> return NotificationAction.Hide
        }
        return NotificationAction.Batch(until)
    }

    private companion object {
        /**
         * Re-check cadence for a held notification whose profile stops at an unpredictable time
         * (charger / location / Wi-Fi). The OS re-delivers it this often; once the profile is inactive
         * it's released, otherwise it's re-held. Trades a little release latency for not dropping it.
         */
        const val NOTIFICATION_RECHECK_MS = 15 * 60_000L
    }

    /** Soonest wall-clock instant [rule] (currently active) stops restricting, from time conditions. */
    private fun deactivationMillis(rule: ProfileRule, ctx: BlockContext, dayStartHour: Int): Long? {
        val candidates = mutableListOf<Long>()
        if (rule.conditionsMask and ConditionTypes.USAGE_LIMIT != 0 && rule.usageLimit != null) {
            candidates += nextPeriodReset(rule.usageLimit.period, ctx.nowMillis, dayStartHour)
        }
        if (rule.conditionsMask and ConditionTypes.LAUNCH_COUNT != 0 && rule.launchLimit != null) {
            candidates += nextPeriodReset(rule.launchLimit.period, ctx.nowMillis, dayStartHour)
        }
        if (rule.conditionsMask and ConditionTypes.TIME != 0) {
            windowEndMillis(rule, ctx)?.let { candidates += it }
        }
        return candidates.minOrNull()
    }

    private fun nextPeriodReset(period: LimitPeriod, nowMillis: Long, dayStartHour: Int): Long = when (period) {
        LimitPeriod.HOURLY -> (nowMillis / 3_600_000L + 1) * 3_600_000L
        LimitPeriod.DAILY -> {
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            var start = now.toLocalDate().atTime(dayStartHour, 0).atZone(zone)
            if (!start.isAfter(now)) start = start.plusDays(1)
            start.toInstant().toEpochMilli()
        }
    }

    /** End of the currently-active time window (handles wrap past midnight), as wall-clock millis. */
    private fun windowEndMillis(rule: ProfileRule, ctx: BlockContext): Long? {
        val window = rule.timeWindows.firstOrNull { it.contains(ctx.minuteOfDay) } ?: return null
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(ctx.nowMillis).atZone(zone)
        val end = window.toMinuteOfDay
        var endTime = now.toLocalDate().atTime(end / 60, end % 60).atZone(zone)
        if (!endTime.isAfter(now)) endTime = endTime.plusDays(1)
        return endTime.toInstant().toEpochMilli()
    }

    /**
     * Website decision (PLAN.md §10). Called when [packageName] is a browser. [url] is the raw
     * URL-bar text (null/blank when it couldn't be read). A site is blocked if ANY active profile
     * has a matching website rule; if the URL is unreadable, an active profile with
     * [ProfileRule.blockUnsupportedBrowsers] blocks the whole browser.
     */
    fun decideUrl(packageName: String?, url: String?, ctx: BlockContext): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (isNeverBlocked(packageName)) return BlockDecision.Allow

        val readable = !url.isNullOrBlank()
        for (rule in profiles) {
            if (rule.websites.isEmpty() && !rule.blockUnsupportedBrowsers) continue
            if (!ConditionEvaluator.isActive(rule, ctx)) continue
            if (readable) {
                val hit = rule.websites.firstOrNull { it.matches(url!!) }
                if (hit != null) {
                    return BlockDecision.Block(packageName, "site \"${hit.value}\" by \"${rule.title}\"")
                }
            } else if (rule.blockUnsupportedBrowsers) {
                return BlockDecision.Block(packageName, "unreadable URL by \"${rule.title}\"")
            }
        }
        return BlockDecision.Allow
    }
}
