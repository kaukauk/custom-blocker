package com.customblocker.service.conditions

import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.LimitPeriod
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory accumulator for usage-time and launch-count limits, keyed by profile id. Counters
 * reset at the start of each period (hour/day). Reads the current rules from [BlockingEngine] to
 * know which profiles to track and with what period.
 *
 * NOTE (Phase 3b): counters live in memory for the life of the app process. Persisting them across
 * process death / reboot is Phase 5 (reliability) work.
 */
@Singleton
class UsageTracker @Inject constructor(
    private val engine: BlockingEngine,
) {
    private val usedSeconds = ConcurrentHashMap<Long, Int>()
    private val launches = ConcurrentHashMap<Long, Int>()
    private val usagePeriod = ConcurrentHashMap<Long, Long>()
    private val launchPeriod = ConcurrentHashMap<Long, Long>()

    /** Add [seconds] of foreground use of [pkg] to every matching usage-limited profile. */
    fun addUsage(pkg: String, seconds: Int) {
        if (seconds <= 0) return
        engine.profiles.forEach { rule ->
            val spec = rule.usageLimit ?: return@forEach
            if (pkg !in rule.apps) return@forEach
            resetIfNeeded(rule.id, usagePeriod, usedSeconds, periodIndex(spec.period))
            usedSeconds.merge(rule.id, seconds, Int::plus)
        }
    }

    /** Count a launch of [pkg] against every matching launch-limited profile. */
    fun onLaunch(pkg: String) {
        engine.profiles.forEach { rule ->
            val spec = rule.launchLimit ?: return@forEach
            if (pkg !in rule.apps) return@forEach
            resetIfNeeded(rule.id, launchPeriod, launches, periodIndex(spec.period))
            launches.merge(rule.id, 1, Int::plus)
        }
    }

    /** Current (usedSeconds, launchCounts) maps for the engine context, with stale periods reset. */
    fun snapshot(): Pair<Map<Long, Int>, Map<Long, Int>> {
        engine.profiles.forEach { rule ->
            rule.usageLimit?.let { resetIfNeeded(rule.id, usagePeriod, usedSeconds, periodIndex(it.period)) }
            rule.launchLimit?.let { resetIfNeeded(rule.id, launchPeriod, launches, periodIndex(it.period)) }
        }
        return usedSeconds.toMap() to launches.toMap()
    }

    private fun resetIfNeeded(
        id: Long,
        periodMap: ConcurrentHashMap<Long, Long>,
        valueMap: ConcurrentHashMap<Long, Int>,
        currentPeriod: Long,
    ) {
        if (periodMap[id] != currentPeriod) {
            valueMap[id] = 0
            periodMap[id] = currentPeriod
        }
    }

    private fun periodIndex(period: LimitPeriod): Long = when (period) {
        LimitPeriod.DAILY -> LocalDate.now().toEpochDay()
        LimitPeriod.HOURLY -> System.currentTimeMillis() / 3_600_000L
    }
}
