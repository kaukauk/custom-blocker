package com.customblocker.service.conditions

import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.LimitPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accumulator for usage-time and launch-count limits, keyed by profile id. Counters reset at the
 * start of each period (hour/day). Reads the current rules from [BlockingEngine] to know which
 * profiles to track and with what period.
 *
 * Phase 5: counters are persisted to Room so a daily/hourly limit survives process death and
 * reboot. [load] restores them on startup (resetting any whose period has since rolled over);
 * mutations write through asynchronously.
 */
@Singleton
class UsageTracker @Inject constructor(
    private val engine: BlockingEngine,
    private val repository: BlockerRepository,
) {
    private val usedSeconds = ConcurrentHashMap<Long, Int>()
    private val launches = ConcurrentHashMap<Long, Int>()
    private val usagePeriod = ConcurrentHashMap<Long, Long>()
    private val launchPeriod = ConcurrentHashMap<Long, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Restore persisted counters on startup; drop any whose period has already rolled over. */
    fun load() = scope.launch {
        repository.loadUsageCounters().forEach { c ->
            usedSeconds[c.profileId] = c.usedSeconds
            launches[c.profileId] = c.launches
            usagePeriod[c.profileId] = c.usagePeriodKey
            launchPeriod[c.profileId] = c.launchPeriodKey
        }
    }

    /** Add [seconds] of foreground use of [pkg] to every matching usage-limited profile. */
    fun addUsage(pkg: String, seconds: Int) {
        if (seconds <= 0) return
        engine.profiles.forEach { rule ->
            val spec = rule.usageLimit ?: return@forEach
            if (pkg !in rule.apps) return@forEach
            resetIfNeeded(rule.id, usagePeriod, usedSeconds, periodIndex(spec.period))
            usedSeconds.merge(rule.id, seconds, Int::plus)
            persist(rule.id)
        }
    }

    /** Count a launch of [pkg] against every matching launch-limited profile. */
    fun onLaunch(pkg: String) {
        engine.profiles.forEach { rule ->
            val spec = rule.launchLimit ?: return@forEach
            if (pkg !in rule.apps) return@forEach
            resetIfNeeded(rule.id, launchPeriod, launches, periodIndex(spec.period))
            launches.merge(rule.id, 1, Int::plus)
            persist(rule.id)
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

    /** Write the current counters for [id] through to Room (fire-and-forget). */
    private fun persist(id: Long) {
        val used = usedSeconds[id] ?: 0
        val launchCount = launches[id] ?: 0
        val usageKey = usagePeriod[id] ?: 0
        val launchKey = launchPeriod[id] ?: 0
        scope.launch { repository.saveUsageCounter(id, used, launchCount, usageKey, launchKey) }
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
