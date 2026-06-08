package com.customblocker.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapPickerViewModel @Inject constructor(
    private val repository: BlockerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    fun save(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        inverted: Boolean,
        label: String?,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        repository.addGeo(profileId, latitude, longitude, radiusMeters, inverted, label)
        // Also enable the LOCATION condition so the new geofence takes effect immediately.
        repository.setConditionEnabled(profileId, com.customblocker.domain.engine.ConditionTypes.LOCATION, true)
        onDone()
    }
}
