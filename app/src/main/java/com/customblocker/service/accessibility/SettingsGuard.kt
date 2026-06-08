package com.customblocker.service.accessibility

/**
 * Strict Mode L3 anti-tamper (PLAN.md §11), scoped to Pixel/AOSP. While L3 is active and locked, we
 * block the device Settings app so the user can't disable our accessibility service, force-stop us,
 * or remove the device admin.
 *
 * Design note: the PLAN hoped to whitelist a few harmless sub-screens (connections, Bluetooth,
 * sound, language & input). In practice that isn't achievable reliably from accessibility events —
 * AOSP Settings fires multiple TYPE_WINDOW_STATE_CHANGED events per screen whose classNames are
 * generic view classes (e.g. `android.widget.FrameLayout`), not the activity alias, so any
 * className whitelist leaks. The strict-correct, honest behaviour is therefore to block ALL of
 * `com.android.settings` under L3; the user can pass the unlock gate to reach Settings.
 */
object SettingsGuard {

    const val SETTINGS_PKG = "com.android.settings"

    /** True if a Settings screen should be blocked under L3. */
    fun shouldBlock(packageName: String, className: String?): Boolean = packageName == SETTINGS_PKG
}
