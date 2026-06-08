package com.customblocker.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.ProfileEntity
import com.customblocker.data.repo.BlockerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val repository: BlockerRepository,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> =
        repository.observeProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a profile and returns its id to the caller (to navigate into it). */
    fun createProfile(title: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repository.createProfile(title))
    }

    fun setEnabled(profileId: Long, enabled: Boolean) =
        viewModelScope.launch { repository.setEnabled(profileId, enabled) }

    fun deleteProfile(profileId: Long) = viewModelScope.launch { repository.deleteProfile(profileId) }
}
