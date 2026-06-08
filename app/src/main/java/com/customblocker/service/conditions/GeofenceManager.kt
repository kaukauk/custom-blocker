package com.customblocker.service.conditions

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.util.L
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers/clears real OS geofences (Play Services) for the app's geo_addresses. Transitions are
 * delivered to [GeofenceTransitionReceiver], which updates [LiveSignals.insideGeofenceIds].
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceTransitionReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Replace all registered geofences with [geos]. No-op (logs) if location isn't granted. */
    @Suppress("MissingPermission")
    fun sync(geos: List<GeoAddressEntity>) {
        client.removeGeofences(pendingIntent)
        if (geos.isEmpty()) return
        if (!hasLocationPermission()) {
            L.w(L.COND, "geofence sync skipped: location permission not granted")
            return
        }
        val fences = geos.map { geo ->
            Geofence.Builder()
                .setRequestId(geo.geofenceId)
                .setCircularRegion(geo.latitude, geo.longitude, geo.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()
        client.addGeofences(request, pendingIntent)
            .addOnSuccessListener { L.d(L.COND, "geofences registered: ${geos.map { it.geofenceId }}") }
            .addOnFailureListener { L.e(L.COND, "geofence registration failed", it) }
    }
}
