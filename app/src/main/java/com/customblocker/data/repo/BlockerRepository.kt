package com.customblocker.data.repo

import com.customblocker.data.db.BlockerDao
import com.customblocker.data.db.ProfileAppEntity
import com.customblocker.data.db.ProfileEntity
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.UsageLimitEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.GeofenceSpec
import com.customblocker.domain.engine.LaunchLimitSpec
import com.customblocker.domain.engine.LimitPeriod
import com.customblocker.domain.engine.ProfileRule
import com.customblocker.domain.engine.TimeWindow
import com.customblocker.domain.engine.UsageLimitSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth over Room. Builds the engine's [ProfileRule] list from profiles + their
 * apps and condition rows, and exposes CRUD for the UI. Phase 3 still edits one implicit "default"
 * profile; the rule stream already supports many profiles so multi-profile UI is additive.
 */
@Singleton
class BlockerRepository @Inject constructor(
    private val dao: BlockerDao,
) {
    suspend fun ensureDefaultProfile(): Long {
        dao.getFirstProfile()?.let { return it.id }
        return dao.insertProfile(ProfileEntity(title = "Default", enabled = true, blockingMode = 0))
    }

    /** The engine's view: every profile flattened with its apps + conditions. */
    fun profileRules(): Flow<List<ProfileRule>> =
        combine(baseRules(), dao.observeAllUsageLimits()) { rules, limits ->
            rules.map { rule ->
                val mine = limits.filter { it.profileId == rule.id }
                rule.copy(
                    usageLimit = mine.find { it.limitType == LIMIT_USAGE }?.let {
                        UsageLimitSpec(it.amount * 60, period(it.periodDaily))
                    },
                    launchLimit = mine.find { it.limitType == LIMIT_LAUNCH }?.let {
                        LaunchLimitSpec(it.amount, period(it.periodDaily))
                    },
                )
            }
        }

    private fun baseRules(): Flow<List<ProfileRule>> = combine(
        dao.observeAllProfiles(),
        dao.observeAllApps(),
        dao.observeAllIntervals(),
        dao.observeAllWifi(),
        dao.observeAllGeo(),
    ) { profiles, apps, intervals, wifi, geo ->
        profiles.map { p ->
            ProfileRule(
                id = p.id,
                title = p.title,
                enabled = p.enabled,
                mode = if (p.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                apps = apps.filter { it.profileId == p.id && it.enabled }.map { it.packageName }.toSet(),
                conditionsMask = p.conditionsMask,
                dayFlags = p.dayFlags,
                timeWindows = intervals.filter { it.profileId == p.id }
                    .map { TimeWindow(it.fromMinuteOfDay, it.toMinuteOfDay) },
                wifiSsids = wifi.filter { it.profileId == p.id }.map { it.ssid }.toSet(),
                geofences = geo.filter { it.profileId == p.id }
                    .map { GeofenceSpec(it.geofenceId, it.inverted) },
            )
        }
    }

    private fun period(daily: Boolean) = if (daily) LimitPeriod.DAILY else LimitPeriod.HOURLY

    // --- default-profile observers for the UI ---
    fun observeProfile(): Flow<ProfileEntity?> = dao.observeFirstProfile()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeApps(): Flow<List<ProfileAppEntity>> =
        dao.observeFirstProfile().flatMapLatest { p ->
            if (p == null) flowOf(emptyList()) else dao.observeApps(p.id)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeIntervals(): Flow<List<TimeIntervalEntity>> =
        dao.observeFirstProfile().flatMapLatest { p ->
            if (p == null) flowOf(emptyList()) else dao.observeIntervals(p.id)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeWifi(): Flow<List<WifiNetworkEntity>> =
        dao.observeFirstProfile().flatMapLatest { p ->
            if (p == null) flowOf(emptyList()) else dao.observeWifi(p.id)
        }

    // --- mutations on the default profile ---
    private suspend fun profile(): ProfileEntity =
        dao.getFirstProfile() ?: ProfileEntity(id = ensureDefaultProfile(), title = "Default")

    suspend fun addApp(packageName: String) =
        dao.addApp(ProfileAppEntity(profileId = ensureDefaultProfile(), packageName = packageName))

    suspend fun removeApp(packageName: String) = dao.removeApp(ensureDefaultProfile(), packageName)

    suspend fun setEnabled(enabled: Boolean) = dao.updateProfile(profile().copy(enabled = enabled))

    suspend fun setMode(mode: BlockingMode) =
        dao.updateProfile(profile().copy(blockingMode = if (mode == BlockingMode.ALLOWLIST) 1 else 0))

    suspend fun setConditionEnabled(conditionBit: Int, enabled: Boolean) {
        val p = profile()
        val mask = if (enabled) p.conditionsMask or conditionBit else p.conditionsMask and conditionBit.inv()
        dao.updateProfile(p.copy(conditionsMask = mask))
    }

    suspend fun setDayFlags(dayFlags: Int) = dao.updateProfile(profile().copy(dayFlags = dayFlags))

    suspend fun addTimeWindow(fromMinuteOfDay: Int, toMinuteOfDay: Int) =
        dao.addInterval(TimeIntervalEntity(profileId = ensureDefaultProfile(), fromMinuteOfDay = fromMinuteOfDay, toMinuteOfDay = toMinuteOfDay))

    suspend fun removeInterval(id: Long) = dao.removeInterval(id)

    suspend fun addWifi(ssid: String) =
        dao.addWifi(WifiNetworkEntity(profileId = ensureDefaultProfile(), ssid = ssid))

    suspend fun removeWifi(id: Long) = dao.removeWifi(id)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeUsageLimits(): Flow<List<UsageLimitEntity>> =
        dao.observeFirstProfile().flatMapLatest { p ->
            if (p == null) flowOf(emptyList()) else dao.observeUsageLimits(p.id)
        }

    suspend fun setUsageLimit(minutes: Int, daily: Boolean) =
        dao.upsertUsageLimit(UsageLimitEntity(profileId = ensureDefaultProfile(), limitType = LIMIT_USAGE, amount = minutes, periodDaily = daily))

    suspend fun clearUsageLimit() = dao.removeUsageLimit(ensureDefaultProfile(), LIMIT_USAGE)

    suspend fun setLaunchLimit(count: Int, daily: Boolean) =
        dao.upsertUsageLimit(UsageLimitEntity(profileId = ensureDefaultProfile(), limitType = LIMIT_LAUNCH, amount = count, periodDaily = daily))

    suspend fun clearLaunchLimit() = dao.removeUsageLimit(ensureDefaultProfile(), LIMIT_LAUNCH)

    companion object {
        const val LIMIT_USAGE = 0
        const val LIMIT_LAUNCH = 1
    }
}
