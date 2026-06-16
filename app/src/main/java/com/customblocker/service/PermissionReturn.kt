package com.customblocker.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * One-shot "bring the app back to the foreground once the permission is granted" signal.
 *
 * Enabling accessibility / notification access drops the user deep inside Settings, so getting back
 * means back-tapping through the hierarchy. Instead, the UI [arm]s this just before opening that
 * Settings screen, and the corresponding service calls [returnToAppIfArmed] from its connected
 * callback — so the moment the user flips the toggle, the app pops back to the front.
 *
 * Background-activity-start rules mean the relaunch only succeeds while the app is BAL-exempt (it
 * holds the overlay permission it needs anyway, and was recently foregrounded); otherwise it's a
 * harmless no-op and the user falls back to pressing back. The arm is time-boxed so a later, unrelated
 * service (re)connect (e.g. after a reboot) never yanks the user into the app.
 */
object PermissionReturn {

    private const val WINDOW_MS = 2 * 60_000L

    @Volatile private var armedUntil: Long = 0L

    /** Called from the UI right before opening a deep permission screen. */
    fun arm() { armedUntil = SystemClock.elapsedRealtime() + WINDOW_MS }

    /** Called from a service's connected callback; pops the app to the front if recently armed. */
    fun returnToAppIfArmed(context: Context) {
        if (SystemClock.elapsedRealtime() > armedUntil) return
        armedUntil = 0L
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        runCatching { context.startActivity(launch) }
    }
}
