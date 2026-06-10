package com.customblocker.ui.map

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Reverse-geocodes coordinates into a short human-readable place name (best-effort, offline-safe). */
object Geocoding {

    /**
     * A concise label like "International House, Birmingham" for [lat]/[lng], or null if it can't be
     * resolved (no Geocoder backend / offline / no match). Runs off the main thread.
     */
    suspend fun describe(context: Context, lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                    ?: return@runCatching null
                val primary = address.featureName?.takeUnless { it.equals(address.adminArea, true) }
                    ?: address.thoroughfare
                    ?: address.subLocality
                val area = address.locality ?: address.subAdminArea ?: address.adminArea
                listOfNotNull(primary, area).distinct().joinToString(", ").ifBlank { null }
            }.getOrNull()
        }
}
