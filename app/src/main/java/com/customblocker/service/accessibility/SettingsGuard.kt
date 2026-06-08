package com.customblocker.service.accessibility

/**
 * Strict Mode L3 anti-tamper (PLAN.md §11), scoped to Pixel/AOSP. While L3 is active and locked, we
 * eject from the screens a user would use to undo blocking:
 *  - the device **Settings** app (disable accessibility, force-stop, remove device admin), and
 *  - the **package installer** (uninstall / permission screens) — defence-in-depth alongside the L2
 *    device-admin uninstall block.
 *
 * Deliberately NOT covered: the Recents/overview and notification shade live in `com.android
 * .systemui` / the launcher, which are in the engine's never-block set (blocking them would soft-
 * brick the device). Swiping the app from Recents doesn't stop the system-bound accessibility
 * service anyway, so it isn't a real bypass vector — the force-stop it can reach is in Settings,
 * already blocked here.
 *
 * Design note: the PLAN hoped to whitelist harmless Settings sub-screens (connections, Bluetooth,
 * sound, language & input). That isn't achievable reliably from accessibility events — AOSP Settings
 * fires multiple TYPE_WINDOW_STATE_CHANGED events per screen with generic view classNames (e.g.
 * `android.widget.FrameLayout`), not the activity alias, so any className whitelist leaks. So we
 * block ALL of Settings under L3; the user passes the unlock gate to get in.
 */
object SettingsGuard {

    const val SETTINGS_PKG = "com.android.settings"

    private val BLOCKED_PACKAGES = setOf(
        SETTINGS_PKG,
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
    )

    /** True if [packageName] is a tamper surface that should be blocked under L3. */
    fun shouldBlock(packageName: String, className: String?): Boolean = packageName in BLOCKED_PACKAGES
}
