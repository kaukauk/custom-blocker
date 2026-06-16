package com.customblocker.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Bring the app back to the foreground once the permission is granted" so the user doesn't have to
 * back-tap out of a deep Settings screen.
 *
 * Two grant flows are handled:
 *  - Accessibility / notification access: the UI [arm]s this before opening Settings, and the
 *    matching service calls [returnToAppIfArmed] from its connected callback (it fires the instant
 *    the toggle flips).
 *  - Overlay ("display over other apps"): there's no service callback, so [watchOverlayGrant] polls
 *    [Settings.canDrawOverlays] and pops the app back as soon as it turns on.
 *
 * Background-activity-start rules mean the relaunch reliably succeeds once the app is BAL-exempt
 * (granting overlay makes it so); otherwise it's a harmless no-op and the user falls back to Back.
 */
object PermissionReturn {

    private const val WINDOW_MS = 2 * 60_000L
    private const val POLL_INTERVAL_MS = 500L
    private const val OVERLAY_WATCH_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var armedUntil: Long = 0L
    @Volatile private var watchingOverlay: Boolean = false

    /** Called from the UI right before opening a deep permission screen. */
    fun arm() { armedUntil = SystemClock.elapsedRealtime() + WINDOW_MS }

    /** Called from a service's connected callback; pops the app to the front if recently armed. */
    fun returnToAppIfArmed(context: Context) {
        if (SystemClock.elapsedRealtime() > armedUntil) return
        armedUntil = 0L
        relaunch(context)
    }

    /**
     * Poll for the overlay permission turning on (no system callback exists), then pop the app back.
     * Safe to call repeatedly — a single watcher runs at a time and it self-stops on grant or timeout.
     */
    fun watchOverlayGrant(context: Context) {
        val app = context.applicationContext
        if (Settings.canDrawOverlays(app)) return // already granted, nothing to wait for
        if (watchingOverlay) return
        watchingOverlay = true
        scope.launch {
            try {
                val deadline = SystemClock.elapsedRealtime() + OVERLAY_WATCH_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(POLL_INTERVAL_MS)
                    if (Settings.canDrawOverlays(app)) { relaunch(app); break }
                }
            } finally {
                watchingOverlay = false
            }
        }
    }

    private fun relaunch(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        runCatching { context.startActivity(launch) }
    }
}
