package com.customblocker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.AppStat
import com.customblocker.data.prefs.StatsPrefsStore
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.ui.AppLister
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** Apps the user has excluded from stats — shown collapsed so they can be restored. */
    val ignored: List<StatRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val statsPrefs: StatsPrefsStore,
    private val lister: AppLister,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.TODAY)

    val state: StateFlow<StatsUiState> =
        range.flatMapLatest { r ->
            val today = LocalDate.now().toEpochDay()
            val from = today - (r.days - 1)
            combine(repository.observeAppStats(from, today), statsPrefs.ignored) { stats, ignored ->
                toUiState(r, stats, ignored)
            }
        }.flowOn(Dispatchers.Default) // label resolution hits PackageManager — keep it off the main thread
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setRange(r: StatsRange) { range.value = r }

    /** Ignore an app: stop recording it (StatsRecorder honours the same set) and purge its history. */
    fun ignore(packageName: String) = viewModelScope.launch {
        statsPrefs.ignore(packageName)
        repository.deleteStatsForPackage(packageName)
    }

    fun restore(packageName: String) = viewModelScope.launch { statsPrefs.unignore(packageName) }

    private fun toUiState(r: StatsRange, stats: List<AppStat>, ignored: Set<String>): StatsUiState {
        // Recording already skips ignored apps and their history is purged on ignore, so any rows
        // here are already non-ignored; the filter is just belt-and-suspenders for in-flight events.
        val visible = stats.filter { it.packageName !in ignored }
        return StatsUiState(
            range = r,
            totalUsageSeconds = visible.sumOf { it.usageSeconds },
            totalBlocks = visible.sumOf { it.blockCount },
            totalNotificationBlocks = visible.sumOf { it.notificationBlockCount },
            rows = visible.map { it.toRow() },
            // The ignored section is driven by the set itself (no stats remain) so it can be restored.
            ignored = ignored.sorted().map { StatRow(it, lister.label(it), 0, 0, 0) },
        )
    }

    private fun AppStat.toRow() =
        StatRow(packageName, lister.label(packageName), usageSeconds, blockCount, notificationBlockCount)
}
