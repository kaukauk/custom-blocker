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
        // App management — the route to force-stop / clear-data / uninstall / per-app battery + the
        // app list itself ("Apps" on One UI). Broad on purpose: over-ejecting here is harmless.
        "apps", "app info", "app details", "all apps", "installed apps", "manage apps", "default apps",
        "force stop", "uninstall", "clear data", "clear storage",
        "device admin",
        "security", "encrypt",
        "developer option",
        "reset", "erase",
        "date & time", "date and time",
        "fingerprint", "face", "biometric",
        "lock screen", "screen lock",
        // Special-app-access umbrella + its leaves: each can directly cripple the blocker
        // (revoke notification access, kill us via battery restriction, disable overlay, etc.).
        "special app access", "special access",
        "notification access", "device & app notification", "notification listener",
        "usage access",
        "display over other apps", "appear on top", "draw over",
        "modify system settings",
        "install unknown apps", "unknown sources",
        "battery", "power",
        // Adding a second user / guest is a classic way to reach uninstall around the lock.
        "users", "guest", "add user", "multiple users",
    )

    /**
     * Titles that ARE allowed in "Safe screens only" mode: the four shortcuts and their children
     * (e.g. a Wi-Fi network's detail page, keyboard settings, ringtone picker). Used as a positive
     * allow-list — any Settings screen whose title matches none of these (and isn't blank) is ejected,
     * so the user is confined to the allowed areas instead of roaming all of Settings.
     */
    private val ALLOWED_SUBSTRINGS = listOf(
        // Wi-Fi & internet
        "wi-fi", "wifi", "internet", "network", "hotspot", "tethering",
        // Bluetooth
        "bluetooth", "paired", "previously connected",
        // Language & input
        "language", "input", "keyboard", "spell", "dictionary", "text-to-speech", "speech",
        "pointer speed",
        // Sound & vibration
        "sound", "volume", "ringtone", "vibrat", "audio", "do not disturb",
    )

    /** Lowercase, collapse the various unicode hyphens AOSP uses ("Wi‑Fi") to a plain '-'. */
    private fun normalize(s: CharSequence): String =
        s.toString().lowercase().replace('‑', '-').replace('–', '-').trim()

    /** True if every non-blank title belongs to an allowed area (Safe-screens-only confinement). */
    fun isAllowed(titles: List<CharSequence>): Boolean {
        val norm = titles.map(::normalize).filter { it.isNotEmpty() }
        if (norm.isEmpty()) return false   // nothing to classify — caller decides (transient window)
        return norm.all { t -> ALLOWED_SUBSTRINGS.any { t.contains(it) } }
    }

    /** True if the event carried no usable title text (a transient/sub-window we can't classify). */
    fun hasNoTitle(titles: List<CharSequence>): Boolean =
        titles.map(::normalize).none { it.isNotEmpty() }

    /**
     * True if the current Settings screen is a tamper surface that must eject the user even while a
     * permit is held. [titles] is the window-state-changed event text.
     */
    fun isDangerous(titles: List<CharSequence>): Boolean {
        val norm = titles.map(::normalize).filter { it.isNotEmpty() }
        return norm.any { t -> DANGEROUS_SUBSTRINGS.any { t.contains(it) } }
    }
}
