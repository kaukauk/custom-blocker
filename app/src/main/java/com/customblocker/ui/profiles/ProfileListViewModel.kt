package com.customblocker.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.ProfileEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.strict.ProfileLockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    profileLockState: ProfileLockState,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> =
        repository.observeProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-profile lock info: a profile is edit-locked when Strict Mode is on or it's active. */
    val lockInfo: StateFlow<ProfileLockState.LockInfo> =
        profileLockState.lockInfo()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileLockState.LockInfo(false, emptySet()))

    /** Creates a profile and returns its id to the caller (to navigate into it). */
    fun createProfile(title: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repository.createProfile(title))
    }

    // Locked: a profile can be enabled (more blocking) but not disabled.
    fun setEnabled(profileId: Long, enabled: Boolean) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId) && !enabled) return@launch
        repository.setEnabled(profileId, enabled)
    }

    // Locked profiles can't be deleted.
    fun deleteProfile(profileId: Long) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId)) return@launch
        repository.deleteProfile(profileId)
    }
}
