package com.customblocker.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.db.UsageLimitEntity
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.strict.ProfileLockState
import com.customblocker.service.conditions.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A profile-list row enriched with a human summary of its mode + active conditions. */
data class ProfileRow(
    val id: Long,
    val title: String,
    val enabled: Boolean,
    /** "Block list • Schedule • Wi-Fi • 1m 30s / 5m this hour" */
    val summary: String,
    /** A usage/launch limit has been hit — the row is outlined to flag it's now blocking. */
    val limitExceeded: Boolean,
)

@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    usageTracker: UsageTracker,
    profileLockState: ProfileLockState,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileRow>> =
        combine(
            repository.observeProfiles(),
            repository.observeAllUsageLimits(),
            usageTracker.live,
        ) { profiles, limits, live ->
            val usedSeconds = live.first
            val launches = live.second
            profiles.map { p ->
                val mine = limits.filter { it.profileId == p.id }
                val usage = mine.find { it.limitType == BlockerRepository.LIMIT_USAGE }
                val launch = mine.find { it.limitType == BlockerRepository.LIMIT_LAUNCH }
                val used = usedSeconds[p.id] ?: 0
                val launchesUsed = launches[p.id] ?: 0
                val usageExceeded = p.conditionsMask and ConditionTypes.USAGE_LIMIT != 0 &&
                    usage != null && used >= usage.amount * 60
                val launchExceeded = p.conditionsMask and ConditionTypes.LAUNCH_COUNT != 0 &&
                    launch != null && launchesUsed >= launch.amount
                ProfileRow(
                    id = p.id,
                    title = p.title,
                    enabled = p.enabled,
                    summary = summarize(
                        isAllowlist = p.blockingMode == 1,
                        mask = p.conditionsMask,
                        usage = usage,
                        launch = launch,
                        usedSeconds = used,
                        launchesUsed = launchesUsed,
                        usageExceeded = usageExceeded,
                        launchExceeded = launchExceeded,
                    ),
                    limitExceeded = p.enabled && (usageExceeded || launchExceeded),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-profile lock info: a profile is edit-locked when Strict Mode is on. */
    val lockInfo: StateFlow<ProfileLockState.LockInfo> =
        profileLockState.lockInfo()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileLockState.LockInfo(false))

    /** Creates a profile and returns its id to the caller (to navigate into it). */
    fun createProfile(title: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repository.createProfile(title))
    }

    // Locked: a profile can be enabled (more blocking) but not disabled.
    fun setEnabled(profileId: Long, enabled: Boolean) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId) && !enabled) return@launch
        repository.setEnabled(profileId, enabled)
    }

    // Locked profiles can't be deleted.
    fun deleteProfile(profileId: Long) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId)) return@launch
        repository.deleteProfile(profileId)
    }

    private fun summarize(
        isAllowlist: Boolean,
        mask: Int,
        usage: UsageLimitEntity?,
        launch: UsageLimitEntity?,
        usedSeconds: Int,
        launchesUsed: Int,
        usageExceeded: Boolean,
        launchExceeded: Boolean,
    ): String {
        val parts = mutableListOf(if (isAllowlist) "Allow list" else "Block list")
        if (mask and ConditionTypes.TIME != 0) parts += "Schedule"
        if (mask and ConditionTypes.WIFI != 0) parts += "Wi-Fi"
        if (mask and ConditionTypes.LOCATION != 0) parts += "Location"
        if (mask and ConditionTypes.CHARGER != 0) parts += "Charging"
        if (mask and ConditionTypes.USAGE_LIMIT != 0 && usage != null) {
            parts += if (usageExceeded) "No time left ${period(usage.periodDaily)}"
            else "${formatUsed(usedSeconds)} / ${usage.amount}m ${period(usage.periodDaily)}"
        }
        if (mask and ConditionTypes.LAUNCH_COUNT != 0 && launch != null) {
            parts += if (launchExceeded) "No launches left ${period(launch.periodDaily)}"
            else "$launchesUsed / ${launch.amount} launches ${period(launch.periodDaily)}"
        }
        return parts.joinToString(" • ")
    }

    private fun period(daily: Boolean) = if (daily) "today" else "this hour"

    private fun formatUsed(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
