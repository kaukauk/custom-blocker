package com.customblocker.domain.strict

import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether profile editing is locked down. Profiles can't be edited at all while Strict Mode is
 * active (incl. an unexpired TIMER commitment); when Strict Mode is off they're freely editable.
 *
 * A 1 Hz ticker keeps a TIMER commitment's lock lifting live the moment its deadline passes.
 */
@Singleton
class ProfileLockState @Inject constructor(
    private val strictStore: StrictModeStore,
    private val unlock: UnlockManager,
) {
    private fun strictLocked(): Flow<Boolean> {
        val ticker: Flow<Int> = flow { var t = 0; while (true) { emit(t++); delay(1_000) } }
        return combine(strictStore.config, unlock.unlocked, ticker) { cfg, unlocked, _ ->
            if (cfg.gate == UnlockGate.TIMER) cfg.timedLockActive(System.currentTimeMillis())
            else cfg.enabled && !unlocked
        }
    }

    fun editLocked(profileId: Long): Flow<Boolean> = strictLocked()

    /** Lock info for the profile list. Strict Mode locks every profile uniformly. */
    fun lockInfo(): Flow<LockInfo> = strictLocked().map { LockInfo(it) }

    data class LockInfo(val strictLocked: Boolean) {
        fun locked(@Suppress("UNUSED_PARAMETER") profileId: Long): Boolean = strictLocked
    }
}
