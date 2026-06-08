package com.customblocker.data.stats

import com.customblocker.data.db.BlockerDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records statistics events (PLAN.md §13) into the per-app/day aggregate table. Fire-and-forget so
 * callers (the accessibility service, notification listener) stay non-blocking. Foreground time
 * reuses the same enter/leave seconds the usage-limit tracker computes — one source of truth.
 */
@Singleton
class StatsRecorder @Inject constructor(
    private val dao: BlockerDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun today(): Long = LocalDate.now().toEpochDay()

    fun recordUsage(packageName: String, seconds: Int) {
        if (seconds <= 0) return
        scope.launch { dao.addUsageSeconds(packageName, today(), seconds) }
    }

    fun recordBlock(packageName: String) {
        scope.launch { dao.incrementBlock(packageName, today()) }
    }

    fun recordNotificationBlock(packageName: String) {
        scope.launch { dao.incrementNotificationBlock(packageName, today()) }
    }
}
