package com.customblocker.ui.conditions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.ConditionTypes
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

    /** While locked, conditions are frozen (only limit amounts may be decreased). */
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
        if (locked) return@launch
        repository.setConditionEnabled(profileId, bit, enabled)
    }

    fun setChargerInverted(inverted: Boolean) = viewModelScope.launch {
        if (locked) return@launch
        repository.setChargerInverted(profileId, inverted)
    }

    fun toggleDay(dayBit: Int) = viewModelScope.launch {
        if (locked) return@launch
        repository.setDayFlags(profileId, state.value.dayFlags xor dayBit)
    }

    fun addTimeWindow(fromMinute: Int, toMinute: Int) = viewModelScope.launch {
        if (locked) return@launch
        repository.addTimeWindow(profileId, fromMinute, toMinute)
    }

    fun updateTimeWindow(id: Long, fromMinute: Int, toMinute: Int) = viewModelScope.launch {
        if (locked) return@launch
        repository.updateTimeWindow(id, fromMinute, toMinute)
    }

    fun removeInterval(id: Long) = viewModelScope.launch {
        if (locked) return@launch
        repository.removeInterval(id)
    }

    fun addWifi(ssid: String) = viewModelScope.launch {
        if (locked || ssid.isBlank()) return@launch
        repository.addWifi(profileId, ssid.trim())
    }

    fun removeWifi(id: Long) = viewModelScope.launch {
        if (locked) return@launch
        repository.removeWifi(id)
    }

    fun removeGeo(id: Long) = viewModelScope.launch {
        if (locked) return@launch
        repository.removeGeo(id)
    }

    fun toggleUsage(enabled: Boolean) = viewModelScope.launch {
        if (locked) return@launch
        repository.setConditionEnabled(profileId, ConditionTypes.USAGE_LIMIT, enabled)
        if (enabled && state.value.usageMinutes == null) repository.setUsageLimit(profileId, 15, true)
    }

    fun setUsageLimit(minutes: Int, daily: Boolean) = viewModelScope.launch {
        // Locked: only a decrease of the amount is allowed (more restrictive); period stays put.
        if (locked) {
            val cur = state.value.usageMinutes ?: return@launch
            if (minutes >= cur || daily != state.value.usageDaily) return@launch
        }
        repository.setUsageLimit(profileId, minutes, daily)
    }

    fun toggleLaunch(enabled: Boolean) = viewModelScope.launch {
        if (locked) return@launch
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
}
