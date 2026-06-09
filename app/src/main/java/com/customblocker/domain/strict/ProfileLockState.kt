package com.customblocker.domain.strict

import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.ConditionEvaluator
import com.customblocker.service.conditions.ContextProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a profile's restrictiveness is currently "locked down" — i.e. the user may only
 * make it MORE restrictive, never less (PLAN.md §11). A profile is edit-locked when either:
 *  - Strict Mode is active and not unlocked (incl. a TIMER commitment still running), or
 *  - the profile is currently active (its conditions are met right now), so loosening it would
 *    immediately weaken live blocking.
 *
 * Profile-active is evaluated against the live [ContextProvider] and re-checked on a 1 Hz ticker so
 * the UI greys/ungreys controls as windows open and close.
 */
@Singleton
class ProfileLockState @Inject constructor(
    private val strictStore: StrictModeStore,
    private val unlock: UnlockManager,
    private val engine: BlockingEngine,
    private val contextProvider: ContextProvider,
) {
    fun editLocked(profileId: Long): Flow<Boolean> =
        lockInfo().map { it.locked(profileId) }

    /** Lock info across all profiles, for screens that render many at once (the profile list). */
    fun lockInfo(): Flow<LockInfo> {
        val ticker = flow { while (true) { emit(Unit); delay(1_000) } }
        return combine(strictStore.config, unlock.unlocked, ticker) { cfg, unlocked, _ ->
            val now = System.currentTimeMillis()
            val strictLocked =
                if (cfg.gate == UnlockGate.TIMER) cfg.timedLockActive(now)
                else cfg.enabled && !unlocked
            val ctx = contextProvider.current()
            val activeIds = engine.profiles.filter { ConditionEvaluator.isActive(it, ctx) }.map { it.id }.toSet()
            LockInfo(strictLocked, activeIds)
        }
    }

    /** [strictLocked] applies to every profile; [activeProfileIds] additionally locks active ones. */
    data class LockInfo(val strictLocked: Boolean, val activeProfileIds: Set<Long>) {
        fun locked(profileId: Long): Boolean = strictLocked || profileId in activeProfileIds
    }
}
