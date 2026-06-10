package com.customblocker.service.conditions

import com.customblocker.domain.engine.BlockContext
import com.customblocker.domain.engine.DayFlags
import java.time.DayOfWeek
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Assembles a [BlockContext] from the system clock + [LiveSignals], honouring debug overrides. */
@Singleton
class ContextProvider @Inject constructor(
    private val signals: LiveSignals,
    private val usageTracker: UsageTracker,
) {
    fun current(): BlockContext {
        val now = LocalDateTime.now()
        val minute = signals.debugMinuteOfDay ?: (now.hour * 60 + now.minute)
        val dayBit = signals.debugDayBit ?: dayBitOf(now.dayOfWeek)
        val wifi = if (signals.debugWifiActive) signals.debugWifi else signals.wifiSsid
        val charging = signals.debugCharging ?: signals.charging
        val geofences = signals.debugGeofences ?: signals.insideGeofenceIds
        val (used, launches) = usageTracker.snapshot()
        return BlockContext(
            dayBit = dayBit,
            minuteOfDay = minute,
            wifiSsid = wifi,
            charging = charging,
            insideGeofenceIds = geofences,
            usageUsedSeconds = used,
            launchCounts = launches,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun dayBitOf(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> DayFlags.MON
        DayOfWeek.TUESDAY -> DayFlags.TUE
        DayOfWeek.WEDNESDAY -> DayFlags.WED
        DayOfWeek.THURSDAY -> DayFlags.THU
        DayOfWeek.FRIDAY -> DayFlags.FRI
        DayOfWeek.SATURDAY -> DayFlags.SAT
        DayOfWeek.SUNDAY -> DayFlags.SUN
    }
}
