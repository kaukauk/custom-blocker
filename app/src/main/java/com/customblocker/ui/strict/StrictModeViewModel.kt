package com.customblocker.ui.strict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.prefs.StrictModeConfig
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StrictModeViewModel @Inject constructor(
    private val store: StrictModeStore,
) : ViewModel() {

    val config: StateFlow<StrictModeConfig> =
        store.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrictModeConfig())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        // Enabling with the TIMER gate starts a fixed-deadline commitment (can't be turned off early).
        if (enabled && config.value.gate == UnlockGate.TIMER) {
            store.startTimedLock(config.value.timerMinutes, System.currentTimeMillis())
        } else {
            store.setEnabled(enabled)
        }
    }
    fun setLevel(level: Int) = viewModelScope.launch { store.setLevel(level) }
    fun setGate(gate: UnlockGate) = viewModelScope.launch { store.setGate(gate) }
    fun setTimerMinutes(minutes: Int) = viewModelScope.launch { store.setTimerMinutes(minutes) }
    fun setPin(pin: String) = viewModelScope.launch { store.setPin(pin) }
    fun setEarnedUnlockEnabled(enabled: Boolean) = viewModelScope.launch { store.setEarnedUnlockEnabled(enabled) }
    fun setEarnWaitMinutes(minutes: Int) = viewModelScope.launch { store.setEarnWaitMinutes(minutes) }
    fun setEarnMaxMinutes(minutes: Int) = viewModelScope.launch { store.setEarnMaxMinutes(minutes) }
}
