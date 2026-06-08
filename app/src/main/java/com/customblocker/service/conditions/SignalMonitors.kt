package com.customblocker.service.conditions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.customblocker.util.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks charger state into [LiveSignals.charging]. */
@Singleton
class ChargerMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signals: LiveSignals,
) {
    fun start() {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        signals.charging = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                signals.charging = when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    else -> signals.charging
                }
                L.d(L.COND, "charger -> ${signals.charging}")
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
    }
}

/** Tracks the connected Wi-Fi SSID into [LiveSignals.wifiSsid] via the default network callback. */
@Singleton
class WifiMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signals: LiveSignals,
) {
    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateSsid()
            override fun onLost(network: Network) {
                signals.wifiSsid = null
                L.d(L.COND, "wifi -> none")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) updateSsid()
                else signals.wifiSsid = null
            }
        }
        cm.registerDefaultNetworkCallback(callback)
    }

    @Suppress("DEPRECATION")
    private fun updateSsid() {
        try {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            // NOTE: getSSID requires ACCESS_FINE_LOCATION on API 27+; without it this returns
            // "<unknown ssid>". The injection harness covers Wi-Fi testing on the emulator.
            val raw = wm.connectionInfo?.ssid?.trim('"')
            signals.wifiSsid = if (raw.isNullOrBlank() || raw == "<unknown ssid>") null else raw
            L.d(L.COND, "wifi -> ${signals.wifiSsid}")
        } catch (e: Exception) {
            L.w(L.COND, "wifi ssid read failed: ${e.message}")
        }
    }
}

/** Starts all signal monitors once (from the Application). */
@Singleton
class SignalsCoordinator @Inject constructor(
    private val chargerMonitor: ChargerMonitor,
    private val wifiMonitor: WifiMonitor,
) {
    @Volatile private var started = false
    fun start() {
        if (started) return
        started = true
        chargerMonitor.start()
        wifiMonitor.start()
    }
}
