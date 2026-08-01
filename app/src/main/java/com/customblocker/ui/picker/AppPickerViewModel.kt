package com.customblocker.ui.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.strict.ProfileLockState
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
    profileLockState: ProfileLockState,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: Long = savedStateHandle.get<Long>("profileId") ?: 0L

    // Launchable apps are the default browse list; the full installed set (incl. apps with no
    // launcher icon) is what a typed search matches against, so you can add anything by name.
    private val allApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val query = MutableStateFlow("")

    /** While locked: blocklist allows add-only, allowlist allows remove-only. */
    val editLocked: StateFlow<Boolean> = profileLockState.editLocked(profileId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isAllowlist: StateFlow<Boolean> =
        repository.observeProfile(profileId)
            .map { it?.blockingMode == 1 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val selected: StateFlow<Set<String>> =
        repository.observeApps(profileId)
            .map { apps -> apps.map { it.packageName }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val visibleApps: StateFlow<List<InstalledApp>> =
        combine(allApps, installedApps, query) { launchable, installed, q ->
            // Blank query → the tidy launchable list. Typing → search the full installed set so apps
            // without a launcher icon show up too.
            if (q.isBlank()) launchable
            else installed.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A raw package name the user typed that isn't already in the visible results — offered as an
     * "add anyway" so an app that isn't installed (or is hidden) can still be added by package. Only a
     * package-shaped string (a dotted id, no spaces) qualifies; a plain name with no match resolves to
     * nothing rather than storing an unmatchable label.
     */
    val typedPackage: StateFlow<String?> =
        combine(visibleApps, query) { visible, q ->
            val t = q.trim()
            val looksLikePackage = t.contains('.') && t.none { it.isWhitespace() }
            if (looksLikePackage && visible.none { it.packageName.equals(t, ignoreCase = true) }) t else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { allApps.value = lister.launchableApps() }
        viewModelScope.launch { installedApps.value = lister.installedApps() }
    }

    fun setQuery(q: String) { query.value = q }

    /** Whether this add/remove is allowed under the current lock + mode. */
    fun canToggle(add: Boolean): Boolean =
        !editLocked.value || (if (isAllowlist.value) !add else add)

    fun toggle(packageName: String, add: Boolean) = viewModelScope.launch {
        if (!canToggle(add)) return@launch
        if (add) repository.addApp(profileId, packageName) else repository.removeApp(profileId, packageName)
    }
}
