package com.customblocker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.customblocker.domain.engine.DayFlags
import com.customblocker.service.conditions.LiveSignals
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only condition-injection harness (PLAN.md §15). Forces [LiveSignals] overrides from adb so
 * conditions can be tested deterministically on the emulator without real wifi/time/location.
 *
 * Examples (component name = <appId>/<class>):
 *   adb shell am broadcast -n com.customblocker.debug/com.customblocker.debug.DebugSignalReceiver \
 *     -a com.customblocker.debug.SET_TIME --ei minute 600 --es day MON
 *   ... SET_WIFI --es ssid Office      (omit --es ssid to simulate "no wifi")
 *   ... SET_CHARGING --ez value true
 *   ... SET_GEOFENCE --es ids home,work
 *   ... CLEAR
 */
class DebugSignalReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SignalsEntryPoint {
        fun liveSignals(): LiveSignals
    }

    override fun onReceive(context: Context, intent: Intent) {
        val signals = EntryPointAccessors
            .fromApplication(context.applicationContext, SignalsEntryPoint::class.java)
            .liveSignals()

        when (intent.action) {
            "$PKG.SET_WIFI" -> {
                signals.debugWifiActive = true
                signals.debugWifi = intent.getStringExtra("ssid")
            }
            "$PKG.SET_CHARGING" -> signals.debugCharging = intent.getBooleanExtra("value", false)
            "$PKG.SET_TIME" -> {
                if (intent.hasExtra("minute")) signals.debugMinuteOfDay = intent.getIntExtra("minute", 0)
                intent.getStringExtra("day")?.let { signals.debugDayBit = dayBit(it) }
            }
            "$PKG.SET_GEOFENCE" -> {
                signals.debugGeofences = intent.getStringExtra("ids")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            }
            "$PKG.CLEAR" -> signals.clearDebug()
        }
        Log.d(
            "Cond",
            "DEBUG ${intent.action}: wifiActive=${signals.debugWifiActive} wifi=${signals.debugWifi} " +
                "charging=${signals.debugCharging} minute=${signals.debugMinuteOfDay} " +
                "dayBit=${signals.debugDayBit} geo=${signals.debugGeofences}",
        )
    }

    private fun dayBit(day: String): Int = when (day.uppercase()) {
        "MON" -> DayFlags.MON
        "TUE" -> DayFlags.TUE
        "WED" -> DayFlags.WED
        "THU" -> DayFlags.THU
        "FRI" -> DayFlags.FRI
        "SAT" -> DayFlags.SAT
        "SUN" -> DayFlags.SUN
        else -> 0
    }

    private companion object {
        const val PKG = "com.customblocker.debug"
    }
}
