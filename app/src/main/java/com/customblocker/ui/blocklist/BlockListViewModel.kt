package com.customblocker.ui.blocklist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.ui.AppLister
import com.customblocker.ui.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlockListUiState(
    val title: String = "",
    val enabled: Boolean = false,
    val mode: BlockingMode = BlockingMode.BLOCKLIST,
    val apps: List<InstalledApp> = emptyList(),
    val blockLaunch: Boolean = true,
    val blockNotifications: Boolean = false,
    val addNewApps: Boolean = false,
)

@HiltViewModel
class BlockListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    val state: StateFlow<BlockListUiState> =
        combine(repository.observeProfile(profileId), repository.observeApps(profileId)) { profile, apps ->
            BlockListUiState(
                title = profile?.title ?: "",
                enabled = profile?.enabled ?: false,
                mode = if (profile?.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                apps = apps.map { InstalledApp(it.packageName, lister.label(it.packageName)) },
                blockLaunch = profile?.blockLaunch ?: true,
                blockNotifications = profile?.blockNotifications ?: false,
                addNewApps = profile?.addNewApps ?: false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockListUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { repository.setEnabled(profileId, enabled) }
    fun setMode(mode: BlockingMode) = viewModelScope.launch { repository.setMode(profileId, mode) }
    fun removeApp(packageName: String) = viewModelScope.launch { repository.removeApp(profileId, packageName) }
    fun setBlockLaunch(v: Boolean) = viewModelScope.launch { repository.setBlockLaunch(profileId, v) }
    fun setBlockNotifications(v: Boolean) = viewModelScope.launch { repository.setBlockNotifications(profileId, v) }
    fun setAddNewApps(v: Boolean) = viewModelScope.launch { repository.setAddNewApps(profileId, v) }
}
