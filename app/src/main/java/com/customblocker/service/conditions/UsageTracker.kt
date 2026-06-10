package com.customblocker.service.conditions

import com.customblocker.data.prefs.AppSettingsStore
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockContext
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.ConditionEvaluator
import com.customblocker.domain.engine.LimitPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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
    private val appSettings: AppSettingsStore,
) {
    private val usedSeconds = ConcurrentHashMap<Long, Int>()
    private val launches = ConcurrentHashMap<Long, Int>()
    private val usagePeriod = ConcurrentHashMap<Long, Long>()
    private val launchPeriod = ConcurrentHashMap<Long, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live per-profile counters (usedSeconds, launches) for the UI to display progress toward limits. */
    private val _live = MutableStateFlow(emptyMap<Long, Int>() to emptyMap<Long, Int>())
    val live: StateFlow<Pair<Map<Long, Int>, Map<Long, Int>>> = _live

    private fun publish() { _live.value = usedSeconds.toMap() to launches.toMap() }

    /** Restore persisted counters on startup; drop any whose period has already rolled over. */
    fun load() = scope.launch {
        repository.loadUsageCounters().forEach { c ->
            usedSeconds[c.profileId] = c.usedSeconds
            launches[c.profileId] = c.launches
            usagePeriod[c.profileId] = c.usagePeriodKey
            launchPeriod[c.profileId] = c.launchPeriodKey
        }
        publish()
    }

    /**
     * Add [seconds] of foreground use of [pkg] to every matching usage-limited profile — but only
     * while that profile is actually applicable ([ConditionEvaluator.isApplicable]): enabled, not
     * paused, and within its schedule/Wi-Fi/location/charging conditions. Outside that, watching
     * shouldn't burn the allowance, so we don't count it.
     */
    fun addUsage(pkg: String, seconds: Int, ctx: BlockContext) {
        if (seconds <= 0) return
        engine.profiles.forEach { rule ->
            val spec = rule.usageLimit ?: return@forEach
            // Count the apps this profile actually targets — for an allow list that's the apps NOT
            // in the list, so `pkg in apps` would wrongly accumulate nothing and never block.
            if (!rule.targets(pkg)) return@forEach
            if (!ConditionEvaluator.isApplicable(rule, ctx)) return@forEach
            resetIfNeeded(rule.id, usagePeriod, usedSeconds, periodIndex(spec.period))
            usedSeconds.merge(rule.id, seconds, Int::plus)
            persist(rule.id)
        }
        publish()
    }

    /** Count a launch of [pkg] against every matching launch-limited profile that's applicable now. */
    fun onLaunch(pkg: String, ctx: BlockContext) {
        engine.profiles.forEach { rule ->
            val spec = rule.launchLimit ?: return@forEach
            if (!rule.targets(pkg)) return@forEach
            if (!ConditionEvaluator.isApplicable(rule, ctx)) return@forEach
            resetIfNeeded(rule.id, launchPeriod, launches, periodIndex(spec.period))
            launches.merge(rule.id, 1, Int::plus)
            persist(rule.id)
        }
        publish()
    }

    /** Current (usedSeconds, launchCounts) maps for the engine context, with stale periods reset. */
    fun snapshot(): Pair<Map<Long, Int>, Map<Long, Int>> {
        engine.profiles.forEach { rule ->
            rule.usageLimit?.let { resetIfNeeded(rule.id, usagePeriod, usedSeconds, periodIndex(it.period)) }
            rule.launchLimit?.let { resetIfNeeded(rule.id, launchPeriod, launches, periodIndex(it.period)) }
        }
        publish()
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
        // The "day" rolls over at the user-configured start hour, not necessarily midnight.
        LimitPeriod.DAILY -> LocalDateTime.now().minusHours(appSettings.dayStartHour.toLong()).toLocalDate().toEpochDay()
        LimitPeriod.HOURLY -> System.currentTimeMillis() / 3_600_000L
    }
}
