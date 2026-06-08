package com.customblocker.ui.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.ui.AppLister
import com.customblocker.ui.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppPickerViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    private val allApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val query = MutableStateFlow("")

    val selected: StateFlow<Set<String>> =
        repository.observeApps(profileId)
            .map { apps -> apps.map { it.packageName }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val visibleApps: StateFlow<List<InstalledApp>> =
        combine(allApps, query) { list, q ->
            if (q.isBlank()) list
            else list.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { allApps.value = lister.launchableApps() }
    }

    fun setQuery(q: String) { query.value = q }

    fun toggle(packageName: String, add: Boolean) = viewModelScope.launch {
        if (add) repository.addApp(profileId, packageName) else repository.removeApp(profileId, packageName)
    }
}
