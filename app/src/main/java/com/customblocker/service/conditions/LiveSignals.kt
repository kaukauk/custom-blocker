package com.customblocker.service.conditions

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide holder of the current condition signals, written by the monitors
 * (Wi-Fi/charger/geofence) and read by [ContextProvider]. Debug overrides let the injection
 * harness (debug builds only) force any signal from adb — see PLAN.md §15.
 */
@Singleton
class LiveSignals @Inject constructor() {

    // --- real signals (updated by monitors) ---
    @Volatile var wifiSsid: String? = null
    @Volatile var charging: Boolean = false
    @Volatile var insideGeofenceIds: Set<String> = emptySet()

    // --- debug overrides (null/false = not overridden) ---
    @Volatile var debugWifiActive: Boolean = false
    @Volatile var debugWifi: String? = null
    @Volatile var debugCharging: Boolean? = null
    @Volatile var debugMinuteOfDay: Int? = null
    @Volatile var debugDayBit: Int? = null
    @Volatile var debugGeofences: Set<String>? = null

    fun clearDebug() {
        debugWifiActive = false
        debugWifi = null
        debugCharging = null
        debugMinuteOfDay = null
        debugDayBit = null
        debugGeofences = null
    }
}
