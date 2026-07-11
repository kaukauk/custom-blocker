package com.customblocker.ui.map

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.strict.ProfileLockState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BlockerRepository,
    profileLockState: ProfileLockState,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L
    private val geoId: Long = savedStateHandle.get<Long>("geoId") ?: -1L

    /** Editing an existing geofence (vs adding a new one). */
    val isEditing: Boolean = geoId > 0

    // Eager so their .value is current when save() reads them (save is a one-shot, not a subscriber).
    private val locked: StateFlow<Boolean> = profileLockState.editLocked(profileId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val locationEnabled: StateFlow<Boolean> = repository.observeProfile(profileId)
        .map { (it?.conditionsMask ?: 0) and ConditionTypes.LOCATION != 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The geofence being edited, once loaded (null while loading or when adding a new one). */
    private val _editing = MutableStateFlow<GeoAddressEntity?>(null)
    val editing: StateFlow<GeoAddressEntity?> = _editing

    init {
        if (isEditing) viewModelScope.launch { _editing.value = repository.getGeo(geoId) }
    }

    fun save(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        inverted: Boolean,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        // Strict Mode: adding a place widens where the profile applies (more restrictive) → allowed,
        // but editing an existing fence could shrink its reach, and adding the FIRST place would turn
        // the Location gate on (making the profile apply in fewer places = less restrictive). Block both.
        if (locked.value && (isEditing || !locationEnabled.value)) return@launch
        // Reverse-geocode a readable name so the list shows e.g. "International House, Birmingham".
        val label = Geocoding.describe(context, latitude, longitude)
        if (isEditing) {
            repository.updateGeo(geoId, latitude, longitude, radiusMeters, inverted, label)
        } else {
            repository.addGeo(profileId, latitude, longitude, radiusMeters, inverted, label)
        }
        // Also enable the LOCATION condition so the geofence takes effect immediately.
        repository.setConditionEnabled(profileId, com.customblocker.domain.engine.ConditionTypes.LOCATION, true)
        onDone()
    }
}
