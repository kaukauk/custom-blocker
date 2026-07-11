package com.customblocker.ui.strict

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.prefs.StrictModeConfig
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.service.admin.BlockerDeviceAdminReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StrictModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: StrictModeStore,
) : ViewModel() {

    val config: StateFlow<StrictModeConfig> =
        store.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrictModeConfig())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        // The PIN gate is meaningless without a PIN — don't let Strict Mode turn on until one is set.
        if (enabled && config.value.gate == UnlockGate.PIN && !config.value.hasPin) return@launch
        // L2/L3 rely on Device admin (uninstall protection) — don't turn on until it's granted.
        if (enabled && config.value.level >= 2 && !BlockerDeviceAdminReceiver.isActive(context)) return@launch
        // Enabling with the TIMER gate starts a fixed-deadline commitment (can't be turned off early).
        if (enabled && config.value.gate == UnlockGate.TIMER) {
            store.startTimedLock(config.value.timerMinutes, System.currentTimeMillis())
        } else {
            store.setEnabled(enabled)
        }
    }
    /**
     * Change the Strict Mode level. While Strict Mode is active the level can only be RAISED, never
     * lowered (lowering would weaken protection); the UI confirms a raise first. L2/L3 rely on Device
     * admin (uninstall protection) and can't function without it, so raising into them while active
     * requires it to be granted — mirroring the enable guard. When inactive, any level may be chosen
     * freely (the master toggle still blocks turning on at L2/L3 until Device admin is granted).
     */
    fun setLevel(level: Int) = viewModelScope.launch {
        val cfg = config.value
        if (cfg.enabled && level < cfg.level) return@launch
        if (cfg.enabled && level >= 2 && !BlockerDeviceAdminReceiver.isActive(context)) return@launch
        store.setLevel(level)
    }
    fun setGate(gate: UnlockGate) = viewModelScope.launch { store.setGate(gate) }
    fun setSettingsGuardMode(mode: com.customblocker.data.prefs.SettingsGuardMode) =
        viewModelScope.launch { store.setSettingsGuardMode(mode) }
    fun setTimerMinutes(minutes: Int) = viewModelScope.launch { store.setTimerMinutes(minutes) }
    fun setPin(pin: String) = viewModelScope.launch { store.setPin(pin) }
    fun setPinEmergencyHours(hours: Int) = viewModelScope.launch { store.setPinEmergencyHours(hours) }
    fun setEarnedUnlockEnabled(enabled: Boolean) = viewModelScope.launch { store.setEarnedUnlockEnabled(enabled) }
    fun setEarnWaitMinutes(minutes: Int) = viewModelScope.launch { store.setEarnWaitMinutes(minutes) }
    fun setEarnMaxMinutes(minutes: Int) = viewModelScope.launch { store.setEarnMaxMinutes(minutes) }
}
