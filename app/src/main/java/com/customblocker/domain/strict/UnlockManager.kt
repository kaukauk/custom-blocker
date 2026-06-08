package com.customblocker.domain.strict

import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.service.conditions.LiveSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether Strict Mode is currently "unlocked" for this app session (PLAN.md §11). Unlock is
 * in-memory and resets on process death, so closing the app re-locks. Passing the configured gate
 * (PIN / timer / charger) flips it. Locked config edits in the UI check [unlocked].
 */
@Singleton
class UnlockManager @Inject constructor(
    private val store: StrictModeStore,
    private val liveSignals: LiveSignals,
) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun markUnlocked() { _unlocked.value = true }
    fun relock() { _unlocked.value = false }

    suspend fun verifyPin(pin: String): Boolean = store.verifyPin(pin)

    fun isCharging(): Boolean = liveSignals.debugCharging ?: liveSignals.charging
}
