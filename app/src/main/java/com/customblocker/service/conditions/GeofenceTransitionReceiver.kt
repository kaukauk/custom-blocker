package com.customblocker.service.conditions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.customblocker.util.L
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives OS geofence ENTER/EXIT transitions and updates [LiveSignals.insideGeofenceIds]. The
 * engine picks the change up on the next foreground event or the 3s usage ticker.
 */
@AndroidEntryPoint
class GeofenceTransitionReceiver : BroadcastReceiver() {

    @Inject lateinit var signals: LiveSignals

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            L.w(L.COND, "geofence event error: ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }?.toSet() ?: emptySet()
        val current = signals.insideGeofenceIds
        signals.insideGeofenceIds = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> current + ids
            Geofence.GEOFENCE_TRANSITION_EXIT -> current - ids
            else -> current
        }
        L.d(L.COND, "geofence transition=${event.geofenceTransition} ids=$ids inside=${signals.insideGeofenceIds}")
    }
}
