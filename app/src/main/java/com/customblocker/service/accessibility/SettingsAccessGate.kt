package com.customblocker.service.accessibility

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared, process-wide permit that lets the user briefly use the device Settings app while Strict
 * Mode L3 is locked. The "Allowed Settings" launcher ([com.customblocker.ui.settings.AllowedSettingsActivity])
 * grants a permit the instant the user taps an allowed shortcut (e.g. Wi-Fi); the accessibility
 * service reads the same gate and, while the permit holds, stops ejecting Settings — so landing on
 * the allowed screen no longer bounces the user straight back out.
 *
 * The permit is time-boxed (so a missed "left Settings" signal can't keep it open forever) and is
 * refreshed ([touch]) on every safe Settings screen, so it stays alive as long as the user is
 * actively in an allowed screen and lapses shortly after they leave. The service also clears it
 * outright when the foreground leaves Settings, and dangerous screens ignore it entirely.
 */
@Singleton
class SettingsAccessGate @Inject constructor() {

    @Volatile private var permittedUntilMillis: Long = 0L

    /** Open / refresh the permit window from [nowMillis]. */
    fun permit(nowMillis: Long) { permittedUntilMillis = nowMillis + WINDOW_MS }

    /** Keep the permit alive while the user stays on an allowed screen. */
    fun touch(nowMillis: Long) { if (permittedUntilMillis > 0L) permittedUntilMillis = nowMillis + WINDOW_MS }

    fun isPermitted(nowMillis: Long): Boolean = permittedUntilMillis > nowMillis

    fun clear() { permittedUntilMillis = 0L }

    private companion object {
        /** How long a single allowed-Settings session stays permitted without interaction. */
        const val WINDOW_MS = 2 * 60 * 1000L
    }
}
