package com.customblocker.domain.engine

import com.customblocker.data.repo.BlockerRepository
import com.customblocker.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the DB to the [NotificationFilterEngine]: streams the repository's notification-filter
 * rules into the engine so the notification listener always reads a current snapshot. Mirrors
 * [EngineSync]; started once from the Application (single-process design).
 */
@Singleton
class NotifFilterSync @Inject constructor(
    private val repository: BlockerRepository,
    private val engine: NotificationFilterEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.notifFilterRules().distinctUntilChanged().collect { rules ->
                engine.update(rules)
                L.d(L.ENGINE, "notif filters updated: ${rules.size} filter(s)")
            }
        }
    }
}
