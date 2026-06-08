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
 * Bridges the DB to the engine: collects the repository's [EngineSnapshot] stream and pushes
 * each change into [BlockingEngine]. Started once from the Application (single-process design,
 * so the accessibility service reads the same singleton engine). PLAN.md §4 data-flow.
 */
@Singleton
class EngineSync @Inject constructor(
    private val repository: BlockerRepository,
    private val engine: BlockingEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.ensureDefaultProfile()
            repository.engineSnapshot().distinctUntilChanged().collect { snapshot ->
                engine.update(snapshot)
                L.d(L.ENGINE, "snapshot updated: enabled=${snapshot.enabled} mode=${snapshot.mode} apps=${snapshot.apps.size}")
            }
        }
    }
}
