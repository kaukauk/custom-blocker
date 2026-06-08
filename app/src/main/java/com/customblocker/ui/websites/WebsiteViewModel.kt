package com.customblocker.ui.websites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.WebsiteEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.UrlMatchType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebsiteUiState(
    val websites: List<WebsiteEntity> = emptyList(),
    val blockUnsupportedBrowsers: Boolean = false,
)

@HiltViewModel
class WebsiteViewModel @Inject constructor(
    private val repository: BlockerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    val state: StateFlow<WebsiteUiState> =
        combine(
            repository.observeWebsites(profileId),
            repository.observeProfile(profileId).map { it?.blockUnsupportedBrowsers ?: false },
        ) { sites, blockUnsupported ->
            WebsiteUiState(websites = sites, blockUnsupportedBrowsers = blockUnsupported)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebsiteUiState())

    fun add(value: String, matchType: UrlMatchType) = viewModelScope.launch {
        repository.addWebsite(profileId, value, matchType)
    }

    fun remove(id: Long) = viewModelScope.launch { repository.removeWebsite(id) }

    fun setBlockUnsupportedBrowsers(enabled: Boolean) = viewModelScope.launch {
        repository.setBlockUnsupportedBrowsers(profileId, enabled)
    }
}
