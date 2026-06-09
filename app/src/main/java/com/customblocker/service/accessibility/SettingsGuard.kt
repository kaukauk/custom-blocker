package com.customblocker.service.accessibility

/**
 * Strict Mode L3 anti-tamper (PLAN.md §11), scoped to Pixel/AOSP. While L3 is active and locked, we
 * eject from the screens a user would use to undo blocking:
 *  - the device **Settings** app (disable accessibility, force-stop, remove device admin), and
 *  - the **package installer** (uninstall / permission screens) — defence-in-depth alongside the L2
 *    device-admin uninstall block.
 *
 * The package installer is blocked outright. Settings is *mostly* blocked, but a small whitelist of
 * harmless leaf screens (Wi-Fi, Bluetooth, Language & input, …) and the Settings home are allowed so
 * the user can still toggle connectivity. We match on the screen **title** carried in the
 * TYPE_WINDOW_STATE_CHANGED event's text (AOSP fires generic `FrameLayout` classNames, so a
 * className whitelist can't work — but the event text reliably carries the toolbar title).
 *
 * Allowing the Settings home is safe: navigating from it to a dangerous leaf (Apps, Accessibility,
 * Security, …) lands on a screen whose title is NOT whitelisted, so that leaf is ejected.
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

    /** Always-blocked tamper surfaces (no safe screens to whitelist here). */
    private val INSTALLER_PACKAGES = GUARDED_PACKAGES - SETTINGS_PKG

    /** Settings home titles — allowed so the user can navigate to the whitelisted leaves. */
    private val HOME_TITLES = setOf("settings", "search settings")

    /** Substrings (in a normalized title) that mark a Settings leaf the user is allowed to reach. */
    private val ALLOWED_SUBSTRINGS = listOf(
        "wi-fi", "wifi", "bluetooth", "language", "input", "sound", "vibration",
    )

    /** Lowercase, collapse the various unicode hyphens AOSP uses ("Wi‑Fi") to a plain '-'. */
    private fun normalize(s: CharSequence): String =
        s.toString().lowercase().replace('‑', '-').replace('–', '-').trim()

    /**
     * True if this Settings/installer screen should be ejected under L3. [titles] is the event text
     * (screen title(s)); an installer is always blocked, Settings is blocked unless a title is the
     * home or a whitelisted leaf.
     */
    fun shouldBlock(packageName: String, titles: List<CharSequence>): Boolean {
        if (packageName in INSTALLER_PACKAGES) return true
        if (packageName != SETTINGS_PKG) return false
        val norm = titles.map(::normalize).filter { it.isNotEmpty() }
        if (norm.isEmpty()) return true                                   // unknown screen → block
        if (norm.any { it in HOME_TITLES }) return false                  // allow the home
        return norm.none { t -> ALLOWED_SUBSTRINGS.any { t.contains(it) } }
    }
}
