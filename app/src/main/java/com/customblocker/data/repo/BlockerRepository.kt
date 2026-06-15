package com.customblocker.data.repo

import com.customblocker.data.db.AppStat
import com.customblocker.data.db.BlockerDao
import com.customblocker.data.db.CapturedNotificationEntity
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.data.db.NotifFilterEntity
import com.customblocker.data.db.ProfileAppEntity
import com.customblocker.data.db.ProfileEntity
import com.customblocker.data.db.TimeIntervalEntity
import com.customblocker.data.db.UsageCounterEntity
import com.customblocker.data.db.UsageLimitEntity
import com.customblocker.data.db.WebsiteEntity
import com.customblocker.data.db.WifiNetworkEntity
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.GeofenceSpec
import com.customblocker.domain.engine.LaunchLimitSpec
import com.customblocker.domain.engine.LimitPeriod
import com.customblocker.domain.engine.NotifActionType
import com.customblocker.domain.engine.NotifFilterRule
import com.customblocker.domain.engine.NotifMatchTarget
import com.customblocker.domain.engine.ProfileRule
import com.customblocker.domain.engine.TimeWindow
import com.customblocker.domain.engine.UrlMatchType
import com.customblocker.domain.engine.UrlMatcher
import com.customblocker.domain.engine.UsageLimitSpec
import com.customblocker.domain.engine.WebsiteRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
        combine(baseRules(), dao.observeAllUsageLimits(), dao.observeAllWebsites()) { rules, limits, websites ->
            rules.map { rule ->
                val mine = limits.filter { it.profileId == rule.id }
                rule.copy(
                    usageLimit = mine.find { it.limitType == LIMIT_USAGE }?.let {
                        UsageLimitSpec(it.amount * 60, period(it.periodDaily))
                    },
                    launchLimit = mine.find { it.limitType == LIMIT_LAUNCH }?.let {
                        LaunchLimitSpec(it.amount, period(it.periodDaily))
                    },
                    websites = websites.filter { it.profileId == rule.id && it.enabled }
                        .map { WebsiteRule(it.value, UrlMatchType.entries[it.matchType]) },
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
                blockUnsupportedBrowsers = p.blockUnsupportedBrowsers,
                blockLaunch = p.blockLaunch,
                chargerInverted = p.chargerInverted,
                blockNotifications = p.blockNotifications,
                batchNotifications = p.batchNotifications,
                pausedUntilMillis = p.pausedUntilMillis,
                timeWindows = intervals.filter { it.profileId == p.id }
                    .map { TimeWindow(it.fromMinuteOfDay, it.toMinuteOfDay) },
                wifiSsids = wifi.filter { it.profileId == p.id }.map { it.ssid }.toSet(),
                geofences = geo.filter { it.profileId == p.id }
                    .map { GeofenceSpec(it.geofenceId, it.inverted) },
            )
        }
    }

    private fun period(daily: Boolean) = if (daily) LimitPeriod.DAILY else LimitPeriod.HOURLY

    // --- profile list / lifecycle ---
    fun observeProfiles(): Flow<List<ProfileEntity>> = dao.observeAllProfiles()

    /** All usage/launch limit rows across every profile (for the profile-list summaries). */
    fun observeAllUsageLimits(): Flow<List<UsageLimitEntity>> = dao.observeAllUsageLimits()

    suspend fun createProfile(title: String, allowlist: Boolean = false): Long =
        dao.insertProfile(
            ProfileEntity(
                title = title.ifBlank { "Profile" },
                enabled = true,
                blockingMode = if (allowlist) 1 else 0,
            ),
        )

    suspend fun deleteProfile(id: Long) = dao.deleteProfile(id)

    suspend fun renameProfile(id: Long, title: String) {
        dao.getProfile(id)?.let { dao.updateProfile(it.copy(title = title.ifBlank { it.title })) }
    }

    // --- per-profile observers for the UI ---
    fun observeProfile(profileId: Long): Flow<ProfileEntity?> = dao.observeProfile(profileId)
    fun observeApps(profileId: Long): Flow<List<ProfileAppEntity>> = dao.observeApps(profileId)
    fun observeIntervals(profileId: Long): Flow<List<TimeIntervalEntity>> = dao.observeIntervals(profileId)
    fun observeWifi(profileId: Long): Flow<List<WifiNetworkEntity>> = dao.observeWifi(profileId)
    fun observeUsageLimits(profileId: Long): Flow<List<UsageLimitEntity>> = dao.observeUsageLimits(profileId)
    fun observeWebsites(profileId: Long): Flow<List<WebsiteEntity>> = dao.observeWebsites(profileId)

    // --- per-profile mutations ---
    private suspend fun edit(profileId: Long, block: (ProfileEntity) -> ProfileEntity) {
        dao.getProfile(profileId)?.let { dao.updateProfile(block(it)) }
    }

    suspend fun addApp(profileId: Long, packageName: String) =
        dao.addApp(ProfileAppEntity(profileId = profileId, packageName = packageName))

    suspend fun removeApp(profileId: Long, packageName: String) = dao.removeApp(profileId, packageName)

    suspend fun setEnabled(profileId: Long, enabled: Boolean) = edit(profileId) { it.copy(enabled = enabled) }

    /** Pause a profile until [untilMillis] (0 resumes it immediately). */
    suspend fun setPausedUntil(profileId: Long, untilMillis: Long) = dao.setPausedUntilMillis(profileId, untilMillis)

    suspend fun setMode(profileId: Long, mode: BlockingMode) =
        edit(profileId) { it.copy(blockingMode = if (mode == BlockingMode.ALLOWLIST) 1 else 0) }

    suspend fun setConditionEnabled(profileId: Long, conditionBit: Int, enabled: Boolean) = edit(profileId) {
        it.copy(conditionsMask = if (enabled) it.conditionsMask or conditionBit else it.conditionsMask and conditionBit.inv())
    }

    suspend fun setChargerInverted(profileId: Long, inverted: Boolean) =
        edit(profileId) { it.copy(chargerInverted = inverted) }

    suspend fun setDayFlags(profileId: Long, dayFlags: Int) = edit(profileId) { it.copy(dayFlags = dayFlags) }

    suspend fun addTimeWindow(profileId: Long, fromMinuteOfDay: Int, toMinuteOfDay: Int) =
        dao.addInterval(TimeIntervalEntity(profileId = profileId, fromMinuteOfDay = fromMinuteOfDay, toMinuteOfDay = toMinuteOfDay))

    suspend fun removeInterval(id: Long) = dao.removeInterval(id)

    suspend fun addWifi(profileId: Long, ssid: String) =
        dao.addWifi(WifiNetworkEntity(profileId = profileId, ssid = ssid))

    suspend fun removeWifi(id: Long) = dao.removeWifi(id)

    // --- geofences ---
    fun observeAllGeo(): Flow<List<GeoAddressEntity>> = dao.observeAllGeo()
    fun observeGeo(profileId: Long): Flow<List<GeoAddressEntity>> = dao.observeGeo(profileId)

    suspend fun addGeo(
        profileId: Long,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        inverted: Boolean,
        label: String?,
    ) = dao.addGeo(
        GeoAddressEntity(
            profileId = profileId,
            geofenceId = "geo_${profileId}_${System.currentTimeMillis()}",
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            inverted = inverted,
            label = label,
        ),
    )

    suspend fun getGeo(id: Long): GeoAddressEntity? = dao.getGeo(id)

    suspend fun updateGeo(
        id: Long,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        inverted: Boolean,
        label: String?,
    ) = dao.updateGeo(id, latitude, longitude, radiusMeters, inverted, label)

    suspend fun removeGeo(id: Long) = dao.removeGeo(id)

    // --- websites / keywords ---
    suspend fun addWebsite(profileId: Long, rawValue: String, matchType: UrlMatchType) {
        val value = UrlMatcher.normalize(rawValue, matchType)
        if (value.isBlank()) return
        dao.addWebsite(WebsiteEntity(profileId = profileId, value = value, matchType = matchType.ordinal))
    }

    suspend fun removeWebsite(id: Long) = dao.removeWebsite(id)

    suspend fun setBlockUnsupportedBrowsers(profileId: Long, enabled: Boolean) =
        edit(profileId) { it.copy(blockUnsupportedBrowsers = enabled) }

    suspend fun setBlockLaunch(profileId: Long, enabled: Boolean) =
        edit(profileId) { it.copy(blockLaunch = enabled) }

    suspend fun setBlockNotifications(profileId: Long, enabled: Boolean) =
        edit(profileId) { it.copy(blockNotifications = enabled) }

    suspend fun setBatchNotifications(profileId: Long, batch: Boolean) =
        edit(profileId) { it.copy(batchNotifications = batch) }

    // --- statistics (Phase 7) ---
    fun observeAppStats(fromDay: Long, toDay: Long): Flow<List<AppStat>> =
        dao.observeAppStats(fromDay, toDay)

    suspend fun deleteStatsForPackage(packageName: String) = dao.deleteStatsForPackage(packageName)

    // --- persisted usage counters (Phase 5 reliability) ---
    suspend fun loadUsageCounters(): List<UsageCounterEntity> = dao.getAllUsageCounters()

    suspend fun saveUsageCounter(
        profileId: Long,
        usedSeconds: Int,
        launches: Int,
        usagePeriodKey: Long,
        launchPeriodKey: Long,
    ) = dao.upsertUsageCounter(
        UsageCounterEntity(profileId, usedSeconds, launches, usagePeriodKey, launchPeriodKey),
    )

    suspend fun setUsageLimit(profileId: Long, minutes: Int, daily: Boolean) =
        dao.upsertUsageLimit(UsageLimitEntity(profileId = profileId, limitType = LIMIT_USAGE, amount = minutes, periodDaily = daily))

    suspend fun clearUsageLimit(profileId: Long) = dao.removeUsageLimit(profileId, LIMIT_USAGE)

    suspend fun setLaunchLimit(profileId: Long, count: Int, daily: Boolean) =
        dao.upsertUsageLimit(UsageLimitEntity(profileId = profileId, limitType = LIMIT_LAUNCH, amount = count, periodDaily = daily))

    suspend fun clearLaunchLimit(profileId: Long) = dao.removeUsageLimit(profileId, LIMIT_LAUNCH)

    // --- notification filters (PLAN.md §4b) ---
    /** Engine-facing stream: DB rows mapped to [NotifFilterRule]. */
    fun notifFilterRules(): Flow<List<NotifFilterRule>> =
        dao.observeNotifFilters().map { rows -> rows.map { it.toRule() } }

    fun observeNotifFilters(): Flow<List<NotifFilterEntity>> = dao.observeNotifFilters()

    fun observeNotifFilter(id: Long): Flow<NotifFilterEntity?> = dao.observeNotifFilter(id)

    suspend fun getNotifFilter(id: Long): NotifFilterEntity? = dao.getNotifFilter(id)

    suspend fun upsertNotifFilter(filter: NotifFilterEntity): Long = dao.upsertNotifFilter(filter)

    suspend fun setNotifFilterEnabled(id: Long, enabled: Boolean) = dao.setNotifFilterEnabled(id, enabled)

    suspend fun deleteNotifFilter(id: Long) = dao.deleteNotifFilter(id)

    // --- captured notifications (history / inbox) ---
    fun observeCaptured(): Flow<List<CapturedNotificationEntity>> = dao.observeCaptured()

    suspend fun recentCaptured(limit: Int): List<CapturedNotificationEntity> = dao.recentCaptured(limit)

    /** Append a captured notification, then trim the oldest beyond [CAPTURED_CAP]. */
    suspend fun logCaptured(notification: CapturedNotificationEntity) {
        dao.insertCaptured(notification)
        if (dao.capturedCount() > CAPTURED_CAP) dao.trimCaptured(CAPTURED_CAP)
    }

    suspend fun incrementFilterHit(id: Long) = dao.incrementFilterHit(id)

    suspend fun deleteCaptured(id: Long) = dao.deleteCaptured(id)

    suspend fun clearCaptured() = dao.clearCaptured()

    private fun NotifFilterEntity.toRule() = NotifFilterRule(
        id = id,
        title = title,
        enabled = enabled,
        packages = packagesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        regexPattern = regexPattern,
        target = NotifMatchTarget.entries.getOrElse(regexTarget) { NotifMatchTarget.EITHER },
        secondaryPattern = secondaryPattern,
        action = NotifActionType.entries.getOrElse(action) { NotifActionType.DISMISS },
        batchIntervalMinutes = batchIntervalMinutes,
        releaseTimes = releaseTimesCsv.split(',').mapNotNull { it.trim().toIntOrNull() },
        startMinute = startMinute,
        endMinute = endMinute,
        dayFlags = dayFlags,
        priority = priority,
    )

    companion object {
        const val LIMIT_USAGE = 0
        const val LIMIT_LAUNCH = 1
        /** Max captured notifications kept for the in-app history. */
        const val CAPTURED_CAP = 200
    }
}
