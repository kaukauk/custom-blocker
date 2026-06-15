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
}
