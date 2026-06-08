package com.customblocker.ui.conditions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.ConditionTypes
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
)

@HiltViewModel
class ConditionsViewModel @Inject constructor(
    private val repository: BlockerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConditionsUiState())

    fun setConditionEnabled(bit: Int, enabled: Boolean) =
        viewModelScope.launch { repository.setConditionEnabled(profileId, bit, enabled) }

    fun toggleDay(dayBit: Int) = viewModelScope.launch {
        repository.setDayFlags(profileId, state.value.dayFlags xor dayBit)
    }

    fun addTimeWindow(fromMinute: Int, toMinute: Int) =
        viewModelScope.launch { repository.addTimeWindow(profileId, fromMinute, toMinute) }

    fun removeInterval(id: Long) = viewModelScope.launch { repository.removeInterval(id) }

    fun addWifi(ssid: String) = viewModelScope.launch {
        if (ssid.isNotBlank()) repository.addWifi(profileId, ssid.trim())
    }

    fun removeWifi(id: Long) = viewModelScope.launch { repository.removeWifi(id) }

    fun removeGeo(id: Long) = viewModelScope.launch { repository.removeGeo(id) }

    fun toggleUsage(enabled: Boolean) = viewModelScope.launch {
        repository.setConditionEnabled(profileId, ConditionTypes.USAGE_LIMIT, enabled)
        if (enabled && state.value.usageMinutes == null) repository.setUsageLimit(profileId, 15, true)
    }

    fun setUsageLimit(minutes: Int, daily: Boolean) =
        viewModelScope.launch { repository.setUsageLimit(profileId, minutes, daily) }

    fun toggleLaunch(enabled: Boolean) = viewModelScope.launch {
        repository.setConditionEnabled(profileId, ConditionTypes.LAUNCH_COUNT, enabled)
        if (enabled && state.value.launchCount == null) repository.setLaunchLimit(profileId, 5, true)
    }

    fun setLaunchLimit(count: Int, daily: Boolean) =
        viewModelScope.launch { repository.setLaunchLimit(profileId, count, daily) }
}
