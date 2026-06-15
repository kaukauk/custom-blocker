package com.customblocker.domain.engine

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Which part(s) of a notification a filter's regex is tested against. */
enum class NotifMatchTarget { TITLE, CONTENT, EITHER, BOTH }

/** What a matched filter does with the notification. */
enum class NotifActionType { DISMISS, CATCH, BATCH, DELAY }

/** Outcome of evaluating one posted notification against the filter set. */
sealed interface NotifFilterOutcome {
    /** No filter matched — leave the notification alone. */
    data object None : NotifFilterOutcome
    /** Cancel it outright (it's gone). */
    data class Dismiss(val filterId: Long) : NotifFilterOutcome
    /** Cancel it but keep it in the in-app inbox; nothing is re-delivered. */
    data class Catch(val filterId: Long) : NotifFilterOutcome
    /** Snooze the real notification until [untilMillis]; the OS re-delivers the original then. */
    data class Snooze(val filterId: Long, val untilMillis: Long, val action: NotifActionType) : NotifFilterOutcome
}

/** Engine-side view of a notification filter (built from the DB row by the repository). */
data class NotifFilterRule(
    val id: Long,
    val title: String,
    val enabled: Boolean,
    /** Empty = any app. */
    val packages: Set<String>,
    /** Blank = match every notification from the apps. */
    val regexPattern: String,
    val target: NotifMatchTarget,
    val secondaryPattern: String,
    val action: NotifActionType,
    val batchIntervalMinutes: Int,
    /** Minute-of-day release points for DELAY. */
    val releaseTimes: List<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val dayFlags: Int,
    val priority: Int,
) {
    private val primary: Regex? by lazy { compile(regexPattern) }
    private val secondary: Regex? by lazy { compile(secondaryPattern) }

    fun matchesApp(packageName: String): Boolean = packages.isEmpty() || packageName in packages

    /** Whether the capture window is open now (handles windows that wrap past midnight). */
    fun scheduleActive(minuteOfDay: Int, dayBit: Int): Boolean {
        if (dayFlags and dayBit == 0) return false
        return if (startMinute <= endMinute) minuteOfDay in startMinute..endMinute
        else minuteOfDay >= startMinute || minuteOfDay <= endMinute
    }

    fun matchesText(title: String, content: String): Boolean {
        if (regexPattern.isBlank()) return true // no pattern → all notifications from the apps
        val p = primary ?: return false
        return when (target) {
            NotifMatchTarget.TITLE -> p.containsMatchIn(title)
            NotifMatchTarget.CONTENT -> p.containsMatchIn(content)
            NotifMatchTarget.EITHER -> p.containsMatchIn(title) || p.containsMatchIn(content)
            NotifMatchTarget.BOTH -> {
                val s = secondary ?: return false
                p.containsMatchIn(title) && s.containsMatchIn(content)
            }
        }
    }

    private fun compile(pattern: String): Regex? =
        if (pattern.isBlank()) null
        else runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
}

/** The fields a filter needs to decide what to do with one posted notification. */
data class NotifContext(
    val packageName: String,
    val title: String,
    val content: String,
    val minuteOfDay: Int,
    val dayBit: Int,
    val nowMillis: Long,
)

/**
 * Pure decision core for notification filtering (PLAN.md §4b). Independent of the app/website
 * [BlockingEngine]: it only inspects a posted notification's app + text and the current time.
 * On a match it returns the lowest-priority filter's action. Re-delivery for BATCH/DELAY is done
 * by the OS snooze mechanism (the caller snoozes until [NotifFilterOutcome.Snooze.untilMillis]).
 */
@Singleton
class NotificationFilterEngine @Inject constructor() {

    @Volatile
    var filters: List<NotifFilterRule> = emptyList()
        private set

    fun update(newFilters: List<NotifFilterRule>) { filters = newFilters }

    fun evaluate(ctx: NotifContext): NotifFilterOutcome {
        val match = filters
            .filter {
                it.enabled &&
                    it.matchesApp(ctx.packageName) &&
                    it.scheduleActive(ctx.minuteOfDay, ctx.dayBit) &&
                    it.matchesText(ctx.title, ctx.content)
            }
            .minByOrNull { it.priority } ?: return NotifFilterOutcome.None

        return when (match.action) {
            NotifActionType.DISMISS -> NotifFilterOutcome.Dismiss(match.id)
            NotifActionType.CATCH -> NotifFilterOutcome.Catch(match.id)
            NotifActionType.BATCH ->
                NotifFilterOutcome.Snooze(match.id, nextBatchBoundary(match, ctx.nowMillis), NotifActionType.BATCH)
            NotifActionType.DELAY -> {
                val until = nextReleaseTime(match, ctx.nowMillis)
                // No release times configured → behave like Catch (hold in the inbox, no re-delivery).
                if (until == null) NotifFilterOutcome.Catch(match.id)
                else NotifFilterOutcome.Snooze(match.id, until, NotifActionType.DELAY)
            }
        }
    }

    /** Next multiple of the batch interval counting from midnight (e.g. every 2h → 2:00, 4:00…). */
    private fun nextBatchBoundary(rule: NotifFilterRule, nowMillis: Long): Long {
        val zone = ZoneId.systemDefault()
        val startOfDay = Instant.ofEpochMilli(nowMillis).atZone(zone)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val intervalMs = rule.batchIntervalMinutes.coerceAtLeast(1) * 60_000L
        val sinceMidnight = nowMillis - startOfDay
        val untilNext = intervalMs - (sinceMidnight % intervalMs)
        return nowMillis + untilNext
    }

    /** Soonest configured release time strictly after now; rolls to the first one tomorrow. */
    private fun nextReleaseTime(rule: NotifFilterRule, nowMillis: Long): Long? {
        if (rule.releaseTimes.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nowMin = now.hour * 60 + now.minute
        val sorted = rule.releaseTimes.sorted()
        val todayNext = sorted.firstOrNull { it > nowMin }
        val date = if (todayNext != null) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val minute = todayNext ?: sorted.first()
        return date.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    }
}
