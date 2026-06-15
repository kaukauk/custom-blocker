package com.customblocker.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.CapturedNotificationEntity
import com.customblocker.data.db.NotifFilterEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.NotifActionType
import com.customblocker.ui.AppLister
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A captured notification with its app label resolved for display. */
data class CapturedRow(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    val actionApplied: Int,
)

/** A notification filter summarised for the list. */
data class FilterRow(
    val id: Long,
    val title: String,
    val enabled: Boolean,
    val appsLabel: String,
    val actionLabel: String,
    val patternLabel: String,
    val hits: Int,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
) : ViewModel() {

    val history: StateFlow<List<CapturedRow>> =
        repository.observeCaptured()
            .map { rows -> rows.map { it.toRow() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filters: StateFlow<List<FilterRow>> =
        repository.observeNotifFilters()
            .map { rows -> rows.map { it.toRow() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilterEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        repository.setNotifFilterEnabled(id, enabled)
    }

    fun deleteFilter(id: Long) = viewModelScope.launch { repository.deleteNotifFilter(id) }

    fun deleteCaptured(id: Long) = viewModelScope.launch { repository.deleteCaptured(id) }

    fun clearHistory() = viewModelScope.launch { repository.clearCaptured() }

    fun iconBitmap(pkg: String) = lister.iconBitmap(pkg)

    private fun CapturedNotificationEntity.toRow() = CapturedRow(
        id = id,
        packageName = packageName,
        appLabel = lister.label(packageName),
        title = title,
        text = text,
        postedAtMillis = postedAtMillis,
        actionApplied = actionApplied,
    )

    private fun NotifFilterEntity.toRow(): FilterRow {
        val packages = packagesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val appsLabel = when {
            packages.isEmpty() -> "Any app"
            packages.size == 1 -> lister.label(packages.first())
            else -> "${lister.label(packages.first())} +${packages.size - 1}"
        }
        val action = NotifActionType.entries.getOrElse(action) { NotifActionType.DISMISS }
        val actionLabel = when (action) {
            NotifActionType.DISMISS -> "Dismiss"
            NotifActionType.CATCH -> "Catch"
            NotifActionType.BATCH -> "Batch every ${formatMinutes(batchIntervalMinutes)}"
            NotifActionType.DELAY -> "Delay"
        }
        val patternLabel = if (regexPattern.isBlank()) "All notifications" else "/$regexPattern/"
        return FilterRow(id, title, enabled, appsLabel, actionLabel, patternLabel, hits)
    }

    private fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
