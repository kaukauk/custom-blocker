package com.customblocker.ui.notifications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.NotifFilterEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.NotifSample
import com.customblocker.ui.AppLister
import com.customblocker.ui.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable form mirror of [NotifFilterEntity]; the screen owns a copy and saves it back. */
data class FilterForm(
    val id: Long = 0,
    val title: String = "",
    val enabled: Boolean = true,
    val packages: Set<String> = emptySet(),
    val regexPattern: String = "",
    val regexTarget: Int = 2,
    val secondaryPattern: String = "",
    val action: Int = 0,
    val batchIntervalMinutes: Int = 60,
    val releaseTimes: List<Int> = emptyList(),
    val startMinute: Int = 0,
    val endMinute: Int = 1439,
    val dayFlags: Int = 127,
)

@HiltViewModel
class FilterEditViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val filterId: Long = savedStateHandle.get<Long>("filterId") ?: 0L
    private val prefillPkg: String? = savedStateHandle.get<String>("pkg")?.takeIf { it.isNotBlank() }
    private val focusText: String? = savedStateHandle.get<String>("focus")?.takeIf { it.isNotBlank() }

    val isNew: Boolean get() = filterId == 0L

    /** The notification the user tapped to start this filter (if any), used to focus suggestions. */
    val focus: NotifSample? = prefillPkg?.let { NotifSample(it, "", focusText.orEmpty()) }

    private val _form = MutableStateFlow<FilterForm?>(null)
    /** Null until loaded; the screen seeds its editable state from the first non-null value. */
    val form: StateFlow<FilterForm?> = _form.asStateFlow()

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    /** Recent captured notifications, used for the live preview and pattern suggestions. */
    private val _samples = MutableStateFlow<List<NotifSample>>(emptyList())
    val samples: StateFlow<List<NotifSample>> = _samples.asStateFlow()

    init {
        viewModelScope.launch { _apps.value = lister.launchableApps() }
        viewModelScope.launch {
            _samples.value = repository.recentCaptured(200).map { NotifSample(it.packageName, it.title, it.text) }
        }
        viewModelScope.launch {
            _form.value = if (filterId == 0L) {
                FilterForm(packages = prefillPkg?.let { setOf(it) } ?: emptySet())
            } else {
                repository.getNotifFilter(filterId)?.toForm() ?: FilterForm()
            }
        }
    }

    fun label(pkg: String): String = lister.label(pkg)

    fun iconBitmap(pkg: String) = lister.iconBitmap(pkg)

    fun save(form: FilterForm, onDone: () -> Unit) = viewModelScope.launch {
        repository.upsertNotifFilter(form.toEntity())
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        if (filterId != 0L) repository.deleteNotifFilter(filterId)
        onDone()
    }

    private fun NotifFilterEntity.toForm() = FilterForm(
        id = id,
        title = title,
        enabled = enabled,
        packages = packagesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        regexPattern = regexPattern,
        regexTarget = regexTarget,
        secondaryPattern = secondaryPattern,
        action = action,
        batchIntervalMinutes = batchIntervalMinutes,
        releaseTimes = releaseTimesCsv.split(',').mapNotNull { it.trim().toIntOrNull() },
        startMinute = startMinute,
        endMinute = endMinute,
        dayFlags = dayFlags,
    )

    private fun FilterForm.toEntity() = NotifFilterEntity(
        id = id,
        title = title,
        enabled = enabled,
        packagesCsv = packages.joinToString(","),
        regexPattern = regexPattern.trim(),
        regexTarget = regexTarget,
        secondaryPattern = secondaryPattern.trim(),
        action = action,
        batchIntervalMinutes = batchIntervalMinutes.coerceAtLeast(1),
        releaseTimesCsv = releaseTimes.sorted().joinToString(","),
        startMinute = startMinute,
        endMinute = endMinute,
        dayFlags = dayFlags,
    )
}
