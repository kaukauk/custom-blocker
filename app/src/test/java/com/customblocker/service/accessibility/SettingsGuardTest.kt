package com.customblocker.service.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {

    // The package installer + permission UI are blocked outright by the L3 guard.
    @Test fun installer_isInstaller() {
        assertTrue(SettingsGuard.isInstaller("com.android.packageinstaller"))
        assertTrue(SettingsGuard.isInstaller("com.google.android.packageinstaller"))
        assertTrue(SettingsGuard.isInstaller("com.android.permissioncontroller"))
    }

    // Settings is guarded, but gated through the Allowed Settings launcher — not "installer"-blocked.
    @Test fun settings_guardedButNotInstaller() {
        assertTrue(SettingsGuard.SETTINGS_PKG in SettingsGuard.GUARDED_PACKAGES)
        assertFalse(SettingsGuard.isInstaller(SettingsGuard.SETTINGS_PKG))
    }

    @Test fun otherApps_notGuarded() {
        assertFalse("com.android.chrome" in SettingsGuard.GUARDED_PACKAGES)
        assertFalse(SettingsGuard.isInstaller("com.android.chrome"))
        assertFalse(SettingsGuard.isInstaller("com.google.android.apps.nexuslauncher"))
    }

    // Tamper surfaces eject the user even while a Settings permit is held; innocuous screens don't.
    @Test fun dangerousScreens_detected() {
        assertTrue(SettingsGuard.isDangerous(listOf("Accessibility")))
        assertTrue(SettingsGuard.isDangerous(listOf("App info")))
        assertTrue(SettingsGuard.isDangerous(listOf("Device admin apps")))
        assertTrue(SettingsGuard.isDangerous(listOf("Security")))
    }

    @Test fun safeScreens_allowed() {
        assertFalse(SettingsGuard.isDangerous(listOf("Wi-Fi")))
        assertFalse(SettingsGuard.isDangerous(listOf("Bluetooth")))
        assertFalse(SettingsGuard.isDangerous(emptyList()))
    }

    // Widened danger list: app management + integrity-critical surfaces eject in both modes.
    @Test fun widenedDangerous_detected() {
        assertTrue(SettingsGuard.isDangerous(listOf("Apps")))
        assertTrue(SettingsGuard.isDangerous(listOf("Force stop")))
        assertTrue(SettingsGuard.isDangerous(listOf("Special app access")))
        assertTrue(SettingsGuard.isDangerous(listOf("Notification access")))
        assertTrue(SettingsGuard.isDangerous(listOf("Battery")))
        assertTrue(SettingsGuard.isDangerous(listOf("Users")))
        assertTrue(SettingsGuard.isDangerous(listOf("Display over other apps")))
    }

    // Safe-screens-only confinement: the four shortcuts and their children are allowed…
    @Test fun allowList_shortcutsAndChildren() {
        assertTrue(SettingsGuard.isAllowed(listOf("Wi-Fi")))
        assertTrue(SettingsGuard.isAllowed(listOf("Saved networks")))
        assertTrue(SettingsGuard.isAllowed(listOf("Bluetooth")))
        assertTrue(SettingsGuard.isAllowed(listOf("Languages & input")))
        assertTrue(SettingsGuard.isAllowed(listOf("On-screen keyboard")))
        assertTrue(SettingsGuard.isAllowed(listOf("Sound & vibration")))
        assertTrue(SettingsGuard.isAllowed(listOf("Phone ringtone")))
    }

    // …and everything else (home, other categories) is not — so the user is confined.
    @Test fun allowList_rejectsOtherScreens() {
        assertFalse(SettingsGuard.isAllowed(listOf("Settings")))
        assertFalse(SettingsGuard.isAllowed(listOf("Display")))
        assertFalse(SettingsGuard.isAllowed(listOf("Storage")))
        assertFalse(SettingsGuard.isAllowed(emptyList()))   // blank → caller decides, not "allowed"
    }

    @Test fun hasNoTitle_onlyForBlank() {
        assertTrue(SettingsGuard.hasNoTitle(emptyList()))
        assertTrue(SettingsGuard.hasNoTitle(listOf("", "  ")))
        assertFalse(SettingsGuard.hasNoTitle(listOf("Wi-Fi")))
    }
}
