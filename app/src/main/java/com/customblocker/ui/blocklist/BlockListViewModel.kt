package com.customblocker.ui.blocklist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.strict.ProfileLockState
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
    /** When blocking notifications: true = batch (hold & re-deliver), false = hide. */
    val batchNotifications: Boolean = false,
    val addNewApps: Boolean = false,
)

@HiltViewModel
class BlockListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
    profileLockState: ProfileLockState,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    /** While locked, only restrictiveness-increasing edits are allowed (mode-dependent for apps). */
    val editLocked: StateFlow<Boolean> = profileLockState.editLocked(profileId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val locked get() = editLocked.value
    private val isAllowlist get() = state.value.mode == BlockingMode.ALLOWLIST

    val state: StateFlow<BlockListUiState> =
        combine(repository.observeProfile(profileId), repository.observeApps(profileId)) { profile, apps ->
            BlockListUiState(
                title = profile?.title ?: "",
                enabled = profile?.enabled ?: false,
                mode = if (profile?.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                apps = apps.map { InstalledApp(it.packageName, lister.label(it.packageName)) },
                blockLaunch = profile?.blockLaunch ?: true,
                blockNotifications = profile?.blockNotifications ?: false,
                batchNotifications = profile?.batchNotifications ?: false,
                addNewApps = profile?.addNewApps ?: false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockListUiState())

    // Locked: can enable a profile (more blocking) but not disable it.
    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        if (locked && !enabled) return@launch
        repository.setEnabled(profileId, enabled)
    }

    // Locked: mode is frozen (switching block/allow list can lower restrictiveness).
    fun setMode(mode: BlockingMode) = viewModelScope.launch {
        if (locked) return@launch
        repository.setMode(profileId, mode)
    }

    // Locked: blocklist removals lower restrictiveness (blocked); allowlist removals raise it (ok).
    fun removeApp(packageName: String) = viewModelScope.launch {
        if (locked && !isAllowlist) return@launch
        repository.removeApp(profileId, packageName)
    }

    // Locked behaviour toggles: can turn ON (more restrictive), can't turn OFF.
    fun setBlockLaunch(v: Boolean) = viewModelScope.launch {
        if (locked && !v) return@launch
        repository.setBlockLaunch(profileId, v)
    }
    fun setBlockNotifications(v: Boolean) = viewModelScope.launch {
        if (locked && !v) return@launch
        repository.setBlockNotifications(profileId, v)
    }
    // Batch (hold & re-deliver) is less restrictive than hide, so locked profiles can't switch it on.
    fun setBatchNotifications(v: Boolean) = viewModelScope.launch {
        if (locked && v) return@launch
        repository.setBatchNotifications(profileId, v)
    }
    fun setAddNewApps(v: Boolean) = viewModelScope.launch {
        if (locked && !v) return@launch
        repository.setAddNewApps(profileId, v)
    }
}
