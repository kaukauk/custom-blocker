package com.customblocker.ui.blocklist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.WebsiteEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.LimitPeriod
import com.customblocker.domain.engine.ProfileRule
import com.customblocker.domain.engine.UrlMatchType
import com.customblocker.domain.strict.ProfileLockState
import com.customblocker.ui.AppLister
import com.customblocker.ui.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An active condition shown on the profile screen: its name + the variables the user set. */
data class ConditionSummary(val bit: Int, val title: String, val detail: String)

data class BlockListUiState(
    val title: String = "",
    val enabled: Boolean = false,
    val mode: BlockingMode = BlockingMode.BLOCKLIST,
    val apps: List<InstalledApp> = emptyList(),
    /** Web address rules (DOMAIN/EXACT). */
    val websites: List<WebsiteEntity> = emptyList(),
    /** Keyword rules (KEYWORD). */
    val keywords: List<WebsiteEntity> = emptyList(),
    val blockUnsupportedBrowsers: Boolean = false,
    val blockNotifications: Boolean = false,
    /** When blocking notifications: true = batch (hold & re-deliver), false = hide. */
    val batchNotifications: Boolean = false,
    /** Currently-enabled conditions, summarised for the profile screen. */
    val conditions: List<ConditionSummary> = emptyList(),
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

    private val rule = repository.profileRules().map { rules -> rules.find { it.id == profileId } }

    val state: StateFlow<BlockListUiState> =
        combine(
            repository.observeProfile(profileId),
            repository.observeApps(profileId),
            repository.observeWebsites(profileId),
            rule,
        ) { profile, apps, sites, rule ->
            val keywordType = UrlMatchType.KEYWORD.ordinal
            BlockListUiState(
                title = profile?.title ?: "",
                enabled = profile?.enabled ?: false,
                mode = if (profile?.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                apps = apps.map { InstalledApp(it.packageName, lister.label(it.packageName)) },
                websites = sites.filter { it.matchType != keywordType },
                keywords = sites.filter { it.matchType == keywordType },
                blockUnsupportedBrowsers = profile?.blockUnsupportedBrowsers ?: false,
                blockNotifications = profile?.blockNotifications ?: false,
                batchNotifications = profile?.batchNotifications ?: false,
                conditions = summarizeConditions(rule),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockListUiState())

    private fun summarizeConditions(rule: ProfileRule?): List<ConditionSummary> {
        if (rule == null) return emptyList()
        val m = rule.conditionsMask
        val out = mutableListOf<ConditionSummary>()
        if (m and ConditionTypes.TIME != 0) {
            val days = daysDetail(rule.dayFlags)
            val windows = rule.timeWindows.joinToString(", ") { "${hhmm(it.fromMinuteOfDay)}–${hhmm(it.toMinuteOfDay)}" }
            out += ConditionSummary(ConditionTypes.TIME, "Schedule", listOf(days, windows).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "any time" })
        }
        if (m and ConditionTypes.WIFI != 0) {
            out += ConditionSummary(ConditionTypes.WIFI, "Wi-Fi", rule.wifiSsids.joinToString(", ").ifBlank { "any network" })
        }
        if (m and ConditionTypes.LOCATION != 0) {
            val n = rule.geofences.size
            out += ConditionSummary(ConditionTypes.LOCATION, "Location", if (n == 1) "1 place" else "$n places")
        }
        if (m and ConditionTypes.USAGE_LIMIT != 0 && rule.usageLimit != null) {
            out += ConditionSummary(ConditionTypes.USAGE_LIMIT, "Usage time limit", "${durationDetail(rule.usageLimit.limitSeconds)} ${periodDetail(rule.usageLimit.period)}")
        }
        if (m and ConditionTypes.LAUNCH_COUNT != 0 && rule.launchLimit != null) {
            val n = rule.launchLimit.limit
            out += ConditionSummary(ConditionTypes.LAUNCH_COUNT, "Launch limit", "$n ${if (n == 1) "launch" else "launches"} ${periodDetail(rule.launchLimit.period)}")
        }
        if (m and ConditionTypes.CHARGER != 0) {
            out += ConditionSummary(ConditionTypes.CHARGER, "Charging", "while plugged in")
        }
        return out
    }

    private fun daysDetail(flags: Int): String {
        val all = listOf("Mon" to 1, "Tue" to 2, "Wed" to 4, "Thu" to 8, "Fri" to 16, "Sat" to 32, "Sun" to 64)
        val on = all.filter { flags and it.second != 0 }.map { it.first }
        return when {
            on.size == 7 -> "Every day"
            on.isEmpty() -> "No days"
            else -> on.joinToString(", ")
        }
    }

    private fun durationDetail(seconds: Int): String {
        val m = seconds / 60
        return if (m >= 60 && m % 60 != 0) "${m / 60}h ${m % 60}m" else if (m >= 60) "${m / 60}h" else "$m min"
    }

    private fun periodDetail(p: LimitPeriod) = if (p == LimitPeriod.DAILY) "per day" else "per hour"

    private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

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
    fun setBlockNotifications(v: Boolean) = viewModelScope.launch {
        if (locked && !v) return@launch
        repository.setBlockNotifications(profileId, v)
    }
    // Hide vs batch is just a delivery preference (not a restrictiveness change), so it's always editable.
    fun setBatchNotifications(v: Boolean) = viewModelScope.launch {
        repository.setBatchNotifications(profileId, v)
    }

    // Adding a site/keyword raises restrictiveness → always allowed; removing lowers it → blocked when locked.
    fun addWebsite(value: String, matchType: UrlMatchType) = viewModelScope.launch {
        if (value.isBlank()) return@launch
        repository.addWebsite(profileId, value.trim(), matchType)
    }
    fun removeWebsite(id: Long) = viewModelScope.launch {
        if (locked) return@launch
        repository.removeWebsite(id)
    }
    fun setBlockUnsupportedBrowsers(v: Boolean) = viewModelScope.launch {
        if (locked && !v) return@launch
        repository.setBlockUnsupportedBrowsers(profileId, v)
    }
}
