package com.customblocker.service.accessibility

import android.provider.Settings

/**
 * Strict Mode L3 anti-tamper (PLAN.md §11), scoped to Pixel/AOSP. While L3 is active and locked, we
 * keep the user out of the screens they'd use to undo blocking:
 *  - the device **Settings** app (disable accessibility, force-stop, remove device admin), and
 *  - the **package installer** (uninstall / permission screens) — defence-in-depth alongside the L2
 *    device-admin uninstall block.
 *
 * The installer is blocked outright. Settings is gated through an "Allowed Settings" launcher
 * ([com.customblocker.ui.settings.AllowedSettingsActivity]): opening Settings while locked pops that
 * menu instead of the real app, and tapping an allowed shortcut deep-links into the real screen and
 * opens a short permit ([SettingsAccessGate]) so the user isn't ejected while they're in there.
 *
 * We can't reliably whitelist *which* Settings screen is showing (AOSP fires generic `FrameLayout`
 * classNames and locale-dependent titles), so the permit — not title-matching — is what keeps the
 * allowed screen usable. Title-matching survives only as a [isDangerous] backstop: even while
 * permitted, screens that can disable the blocker (Accessibility, Apps/force-stop, Device admin,
 * Security, Developer options, Reset, Date & time) eject the user back to the menu.
 */
object SettingsGuard {

    const val SETTINGS_PKG = "com.android.settings"

    /** Packages the L3 guard inspects at all. */
    val GUARDED_PACKAGES = setOf(
        SETTINGS_PKG,
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
    )

    fun isInstaller(packageName: String): Boolean =
        packageName != SETTINGS_PKG && packageName in GUARDED_PACKAGES

    /**
     * The curated shortcuts shown in the Allowed Settings launcher. [action] is a documented
     * `Settings.ACTION_*` intent that deep-links straight to the screen, bypassing the (blocked)
     * Settings home.
     */
    enum class AllowedShortcut(val label: String, val action: String) {
        WIFI("Wi-Fi & internet", Settings.ACTION_WIFI_SETTINGS),
        BLUETOOTH("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        LANGUAGE("Language & input", Settings.ACTION_LOCALE_SETTINGS),
        SOUND("Sound & vibration", Settings.ACTION_SOUND_SETTINGS),
    }

    /**
     * Screens that can be used to disable the blocker — ejected even while a permit is held. Matched
     * as normalized-title substrings; deliberately broad since the cost of a false positive (bounce
     * to the menu) is low and the cost of a miss (escape) defeats Strict Mode.
     */
    private val DANGEROUS_SUBSTRINGS = listOf(
        "accessibility",
        "app info", "app details", "all apps", "installed apps", "manage apps",
        "device admin",
        "security", "encrypt",
        "developer option",
        "reset", "erase",
        "date & time", "date and time",
        "fingerprint", "face", "biometric",
        "lock screen", "screen lock",
    )

    /** Lowercase, collapse the various unicode hyphens AOSP uses ("Wi‑Fi") to a plain '-'. */
    private fun normalize(s: CharSequence): String =
        s.toString().lowercase().replace('‑', '-').replace('–', '-').trim()

    /**
     * True if the current Settings screen is a tamper surface that must eject the user even while a
     * permit is held. [titles] is the window-state-changed event text.
     */
    fun isDangerous(titles: List<CharSequence>): Boolean {
        val norm = titles.map(::normalize).filter { it.isNotEmpty() }
        return norm.any { t -> DANGEROUS_SUBSTRINGS.any { t.contains(it) } }
    }
}
