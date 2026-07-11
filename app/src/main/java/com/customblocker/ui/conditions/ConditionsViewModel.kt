package com.customblocker.ui.conditions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.TimeWindow
import com.customblocker.domain.strict.ProfileLockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConditionsUiState(
    val conditionsMask: Int = 0,
    val dayFlags: Int = 127,
    val intervals: List<TimeIntervalEntity> = emptyList(),
    val wifi: List<WifiNetworkEntity> = emptyList(),
    val geofences: List<GeoAddressEntity> = emptyList(),
    val usageMinutes: Int? = null,
    val usageDaily: Boolean = true,
    val launchCount: Int? = null,
    val launchDaily: Boolean = true,
    /** Charging condition sense: false = active while charging, true = active while NOT charging. */
    val chargerInverted: Boolean = false,
)

@HiltViewModel
class ConditionsViewModel @Inject constructor(
    private val repository: BlockerRepository,
    profileLockState: ProfileLockState,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    /**
     * While Strict Mode is active, only restrictiveness-INCREASING edits are allowed. Because a
     * profile is active when ALL its enabled conditions hold (AND), *enabling/adding a condition*
     * makes it apply less often → blocked; *disabling/removing* one → allowed. *Growing* an enabled
     * condition's coverage (more days, a wider/added time window, more Wi-Fi/places, a lower
     * usage/launch allowance) increases restrictiveness → allowed. Charging is frozen (binary).
     */
    val editLocked: StateFlow<Boolean> = profileLockState.editLocked(profileId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val locked get() = editLocked.value

    val state: StateFlow<ConditionsUiState> = combine(
        repository.observeProfile(profileId),
        repository.observeIntervals(profileId),
        repository.observeWifi(profileId),
        repository.observeUsageLimits(profileId),
        repository.observeGeo(profileId),
    ) { profile, intervals, wifi, limits, geofences ->
        val usage = limits.find { it.limitType == BlockerRepository.LIMIT_USAGE }
        val launch = limits.find { it.limitType == BlockerRepository.LIMIT_LAUNCH }
        ConditionsUiState(
            conditionsMask = profile?.conditionsMask ?: 0,
            dayFlags = profile?.dayFlags ?: 127,
            intervals = intervals,
            wifi = wifi,
            geofences = geofences,
            usageMinutes = usage?.amount,
            usageDaily = usage?.periodDaily ?: true,
            launchCount = launch?.amount,
            launchDaily = launch?.periodDaily ?: true,
            chargerInverted = profile?.chargerInverted ?: false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConditionsUiState())

    fun setConditionEnabled(bit: Int, enabled: Boolean) = viewModelScope.launch {
        if (locked) {
            if (bit == ConditionTypes.CHARGER) return@launch   // charging is frozen (binary)
            if (enabled) return@launch                          // enabling a condition = less restrictive
            // disabling a condition (enabled == false) removes an AND-gate → more restrictive → allowed
        }
        repository.setConditionEnabled(profileId, bit, enabled)
    }

    fun setChargerInverted(inverted: Boolean) = viewModelScope.launch {
        if (locked) return@launch   // charging is binary — frozen while locked
        repository.setChargerInverted(profileId, inverted)
    }

    fun toggleDay(dayBit: Int) = viewModelScope.launch {
        // Turning a day ON widens the schedule (more restrictive) → allowed; turning one OFF narrows it.
        val dayOn = state.value.dayFlags and dayBit != 0
        if (locked && dayOn) return@launch
        repository.setDayFlags(profileId, state.value.dayFlags xor dayBit)
    }

    fun addTimeWindow(fromMinute: Int, toMinute: Int) = viewModelScope.launch {
        // A new window only adds blocked time → more restrictive → allowed even when locked.
        repository.addTimeWindow(profileId, fromMinute, toMinute)
    }

    fun updateTimeWindow(id: Long, fromMinute: Int, toMinute: Int) = viewModelScope.launch {
        // Locked: only allow an edit that EXTENDS the window (the new range fully contains the old one),
        // never one that shortens or shifts it away.
        if (locked) {
            val old = state.value.intervals.find { it.id == id } ?: return@launch
            if (!windowExtends(old.fromMinuteOfDay, old.toMinuteOfDay, fromMinute, toMinute)) return@launch
        }
        repository.updateTimeWindow(id, fromMinute, toMinute)
    }

    fun removeInterval(id: Long) = viewModelScope.launch {
        if (locked) return@launch   // removing a window frees blocked time = less restrictive
        repository.removeInterval(id)
    }

    fun addWifi(ssid: String) = viewModelScope.launch {
        // Another network the profile applies on = more coverage = more restrictive → allowed.
        if (ssid.isBlank()) return@launch
        repository.addWifi(profileId, ssid.trim())
    }

    fun removeWifi(id: Long) = viewModelScope.launch {
        if (locked) return@launch   // fewer networks = less coverage = less restrictive
        repository.removeWifi(id)
    }

    fun removeGeo(id: Long) = viewModelScope.launch {
        if (locked) return@launch   // fewer places = less coverage = less restrictive
        repository.removeGeo(id)
    }

    fun toggleUsage(enabled: Boolean) = viewModelScope.launch {
        // Enabling a usage limit makes blocking conditional (only after the allowance) = less
        // restrictive → blocked; disabling it blocks unconditionally = more restrictive → allowed.
        if (locked && enabled) return@launch
        repository.setConditionEnabled(profileId, ConditionTypes.USAGE_LIMIT, enabled)
        if (enabled && state.value.usageMinutes == null) repository.setUsageLimit(profileId, 15, true)
    }

    fun setUsageLimit(minutes: Int, daily: Boolean) = viewModelScope.launch {
        // Locked: only a decrease of the amount is allowed (blocks sooner); period stays put.
        if (locked) {
            val cur = state.value.usageMinutes ?: return@launch
            if (minutes >= cur || daily != state.value.usageDaily) return@launch
        }
        repository.setUsageLimit(profileId, minutes, daily)
    }

    fun toggleLaunch(enabled: Boolean) = viewModelScope.launch {
        if (locked && enabled) return@launch   // same as usage: enabling a limit is less restrictive
        repository.setConditionEnabled(profileId, ConditionTypes.LAUNCH_COUNT, enabled)
        if (enabled && state.value.launchCount == null) repository.setLaunchLimit(profileId, 5, true)
    }

    fun setLaunchLimit(count: Int, daily: Boolean) = viewModelScope.launch {
        if (locked) {
            val cur = state.value.launchCount ?: return@launch
            if (count >= cur || daily != state.value.launchDaily) return@launch
        }
        repository.setLaunchLimit(profileId, count, daily)
    }

    /**
     * True if the new [newFrom,newTo) time window fully contains the old [oldFrom,oldTo) one — i.e.
     * the edit only extends it. Checked minute-by-minute so it handles windows that wrap past midnight.
     */
    private fun windowExtends(oldFrom: Int, oldTo: Int, newFrom: Int, newTo: Int): Boolean {
        val old = TimeWindow(oldFrom, oldTo)
        val new = TimeWindow(newFrom, newTo)
        return (0 until 1440).all { m -> !old.contains(m) || new.contains(m) }
    }
}
