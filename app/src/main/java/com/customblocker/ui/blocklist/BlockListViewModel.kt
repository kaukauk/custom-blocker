package com.customblocker.ui.blocklist

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
    val enabled: Boolean = false,
    val mode: BlockingMode = BlockingMode.BLOCKLIST,
    val apps: List<InstalledApp> = emptyList(),
)

@HiltViewModel
class BlockListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
) : ViewModel() {

    val state: StateFlow<BlockListUiState> =
        combine(repository.observeProfile(), repository.observeApps()) { profile, apps ->
            BlockListUiState(
                enabled = profile?.enabled ?: false,
                mode = if (profile?.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                apps = apps.map { InstalledApp(it.packageName, lister.label(it.packageName)) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockListUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { repository.setEnabled(enabled) }
    fun setMode(mode: BlockingMode) = viewModelScope.launch { repository.setMode(mode) }
    fun removeApp(packageName: String) = viewModelScope.launch { repository.removeApp(packageName) }
}
