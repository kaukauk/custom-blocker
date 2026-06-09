package com.customblocker.data.stats

import com.customblocker.data.db.BlockerDao
import com.customblocker.data.prefs.StatsPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records statistics events (PLAN.md §13) into the per-app/day aggregate table. Fire-and-forget so
 * callers (the accessibility service, notification listener) stay non-blocking. Foreground time
 * reuses the same enter/leave seconds the usage-limit tracker computes — one source of truth.
 *
 * Packages on the stats ignore list are dropped at the source: no new events are recorded for them
 * (an in-memory snapshot of the set is kept current so the hot recording path stays synchronous).
 */
@Singleton
class StatsRecorder @Inject constructor(
    private val dao: BlockerDao,
    statsPrefs: StatsPrefsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var ignored: Set<String> = emptySet()

    init {
        statsPrefs.ignored.onEach { ignored = it }.launchIn(scope)
    }

    private fun today(): Long = LocalDate.now().toEpochDay()

    fun recordUsage(packageName: String, seconds: Int) {
        if (seconds <= 0 || packageName in ignored) return
        scope.launch { dao.addUsageSeconds(packageName, today(), seconds) }
    }

    fun recordBlock(packageName: String) {
        if (packageName in ignored) return
        scope.launch { dao.incrementBlock(packageName, today()) }
    }

    fun recordNotificationBlock(packageName: String) {
        if (packageName in ignored) return
        scope.launch { dao.incrementNotificationBlock(packageName, today()) }
    }
}
