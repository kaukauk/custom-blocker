package com.customblocker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.AppStat
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.ui.AppLister
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

enum class StatsRange(val label: String, val days: Int) { TODAY("Today", 1), WEEK("7 days", 7) }

data class StatRow(
    val packageName: String,
    val label: String,
    val usageSeconds: Int,
    val blockCount: Int,
    val notificationBlockCount: Int,
)

data class StatsUiState(
    val range: StatsRange = StatsRange.TODAY,
    val totalUsageSeconds: Int = 0,
    val totalBlocks: Int = 0,
    val totalNotificationBlocks: Int = 0,
    val rows: List<StatRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val lister: AppLister,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.TODAY)

    val state: StateFlow<StatsUiState> =
        range.flatMapLatest { r ->
            val today = LocalDate.now().toEpochDay()
            val from = today - (r.days - 1)
            repository.observeAppStats(from, today).map { stats -> toUiState(r, stats) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setRange(r: StatsRange) { range.value = r }

    private fun toUiState(r: StatsRange, stats: List<AppStat>): StatsUiState = StatsUiState(
        range = r,
        totalUsageSeconds = stats.sumOf { it.usageSeconds },
        totalBlocks = stats.sumOf { it.blockCount },
        totalNotificationBlocks = stats.sumOf { it.notificationBlockCount },
        rows = stats.map {
            StatRow(it.packageName, lister.label(it.packageName), it.usageSeconds, it.blockCount, it.notificationBlockCount)
        },
    )
}
