package com.customblocker.ui.conditions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.data.repo.BlockerRepository
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
)

@HiltViewModel
class ConditionsViewModel @Inject constructor(
    private val repository: BlockerRepository,
) : ViewModel() {

    val state: StateFlow<ConditionsUiState> = combine(
        repository.observeProfile(),
        repository.observeIntervals(),
        repository.observeWifi(),
    ) { profile, intervals, wifi ->
        ConditionsUiState(
            conditionsMask = profile?.conditionsMask ?: 0,
            dayFlags = profile?.dayFlags ?: 127,
            intervals = intervals,
            wifi = wifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConditionsUiState())

    fun setConditionEnabled(bit: Int, enabled: Boolean) =
        viewModelScope.launch { repository.setConditionEnabled(bit, enabled) }

    fun toggleDay(dayBit: Int) = viewModelScope.launch {
        val current = state.value.dayFlags
        repository.setDayFlags(current xor dayBit)
    }

    fun addTimeWindow(fromMinute: Int, toMinute: Int) =
        viewModelScope.launch { repository.addTimeWindow(fromMinute, toMinute) }

    fun removeInterval(id: Long) = viewModelScope.launch { repository.removeInterval(id) }

    fun addWifi(ssid: String) = viewModelScope.launch {
        if (ssid.isNotBlank()) repository.addWifi(ssid.trim())
    }

    fun removeWifi(id: Long) = viewModelScope.launch { repository.removeWifi(id) }
}
