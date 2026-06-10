package com.customblocker.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.ConditionEvaluator
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.LimitPeriod
import com.customblocker.domain.engine.ProfileRule
import com.customblocker.domain.strict.ProfileLockState
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.service.conditions.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A profile-list row enriched with a human summary + its current activity state. */
data class ProfileRow(
    val id: Long,
    val title: String,
    /** "Block list • Schedule • Wi-Fi • 1m 30s / 5m used this hour" (or "Paused until 15:45"). */
    val summary: String,
    /** Currently restricting (or set to restrict) right now → gets an outline. */
    val active: Boolean,
    /** A usage/launch limit is exhausted right now → outline turns red. */
    val limitExceeded: Boolean,
    /** Temporarily paused. */
    val paused: Boolean,
)

@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val repository: BlockerRepository,
    private val contextProvider: ContextProvider,
    private val appSettings: com.customblocker.data.prefs.AppSettingsStore,
    usageTracker: UsageTracker,
    profileLockState: ProfileLockState,
) : ViewModel() {

    // 1 Hz so schedule windows opening/closing and pauses expiring reflect live in the list.
    private val ticker = flow { var t = 0; while (true) { emit(t++); delay(1_000) } }

    val profiles: StateFlow<List<ProfileRow>> =
        combine(repository.profileRules(), usageTracker.live, ticker) { rules, live, _ ->
            val ctx = contextProvider.current()
            val usedSeconds = live.first
            val launches = live.second
            rules.map { rule ->
                val usageOn = rule.conditionsMask and ConditionTypes.USAGE_LIMIT != 0 && rule.usageLimit != null
                val launchOn = rule.conditionsMask and ConditionTypes.LAUNCH_COUNT != 0 && rule.launchLimit != null
                val used = usedSeconds[rule.id] ?: 0
                val launchesUsed = launches[rule.id] ?: 0
                val usageExceeded = usageOn && used >= rule.usageLimit!!.limitSeconds
                val launchExceeded = launchOn && launchesUsed >= rule.launchLimit!!.limit

                // "Active" = enabled, not paused, and all NON-limit conditions met right now. A
                // usage/launch limit doesn't gate activeness — the profile is in effect, and the
                // limit running out is what flips it to actually-blocking (red).
                val nonLimitMask = rule.conditionsMask and
                    (ConditionTypes.USAGE_LIMIT or ConditionTypes.LAUNCH_COUNT).inv()
                val active = ConditionEvaluator.isActive(rule.copy(conditionsMask = nonLimitMask), ctx)
                val paused = rule.pausedUntilMillis > ctx.nowMillis

                ProfileRow(
                    id = rule.id,
                    title = rule.title,
                    summary = if (paused) "Paused until ${clock(rule.pausedUntilMillis)}"
                    else summarize(rule, used, launchesUsed, usageOn, launchOn, usageExceeded, launchExceeded),
                    active = active,
                    limitExceeded = active && (usageExceeded || launchExceeded),
                    paused = paused,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-profile lock info: a profile is edit-locked when Strict Mode is on. */
    val lockInfo: StateFlow<ProfileLockState.LockInfo> =
        profileLockState.lockInfo()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileLockState.LockInfo(false))

    /** Creates a profile (in the configured default mode) and returns its id to the caller. */
    fun createProfile(title: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repository.createProfile(title, appSettings.defaultAllowlist))
    }

    /** Pause a profile for [durationMillis]. Blocked while Strict Mode is active (would un-restrict). */
    fun pauseProfile(profileId: Long, durationMillis: Long) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId)) return@launch
        repository.setPausedUntil(profileId, System.currentTimeMillis() + durationMillis)
    }

    /** Resume a paused profile now (always allowed — it increases restrictiveness). */
    fun resumeProfile(profileId: Long) = viewModelScope.launch {
        repository.setPausedUntil(profileId, 0)
    }

    // Locked profiles can't be deleted.
    fun deleteProfile(profileId: Long) = viewModelScope.launch {
        if (lockInfo.value.locked(profileId)) return@launch
        repository.deleteProfile(profileId)
    }

    private fun summarize(
        rule: ProfileRule,
        usedSeconds: Int,
        launchesUsed: Int,
        usageOn: Boolean,
        launchOn: Boolean,
        usageExceeded: Boolean,
        launchExceeded: Boolean,
    ): String {
        val parts = mutableListOf(if (rule.mode == BlockingMode.ALLOWLIST) "Allow list" else "Block list")
        val m = rule.conditionsMask
        if (m and ConditionTypes.TIME != 0) parts += "Schedule"
        if (m and ConditionTypes.LOCATION != 0) parts += "Location"
        if (m and ConditionTypes.CHARGER != 0) parts += if (rule.chargerInverted) "Not charging" else "Charging"
        if (usageOn) {
            val limitMin = rule.usageLimit!!.limitSeconds / 60
            val per = period(rule.usageLimit!!.period)
            parts += if (usageExceeded) "No time left $per"
            else "${formatUsed(usedSeconds)} / ${limitMin}m used $per"
        }
        if (launchOn) {
            val per = period(rule.launchLimit!!.period)
            parts += if (launchExceeded) "No launches left $per"
            else "$launchesUsed / ${rule.launchLimit!!.limit} launches $per"
        }
        return parts.joinToString(" • ")
    }

    private fun period(p: LimitPeriod) = if (p == LimitPeriod.DAILY) "today" else "this hour"

    private fun formatUsed(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private fun clock(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(clockFmt)
}
