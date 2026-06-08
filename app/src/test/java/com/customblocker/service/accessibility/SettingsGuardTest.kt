package com.customblocker.service.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {

    @Test fun blocksSettings() {
        assertTrue(SettingsGuard.shouldBlock("com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity"))
        assertTrue(SettingsGuard.shouldBlock("com.android.settings", "android.widget.FrameLayout"))
    }

    @Test fun blocksPackageInstallerAndPermissionUi() {
        assertTrue(SettingsGuard.shouldBlock("com.android.packageinstaller", null))
        assertTrue(SettingsGuard.shouldBlock("com.google.android.packageinstaller", null))
        assertTrue(SettingsGuard.shouldBlock("com.android.permissioncontroller", null))
    }

    @Test fun allowsEverythingElse() {
        assertFalse(SettingsGuard.shouldBlock("com.android.chrome", null))
        assertFalse(SettingsGuard.shouldBlock("com.android.systemui", null))
        assertFalse(SettingsGuard.shouldBlock("com.google.android.apps.nexuslauncher", null))
    }
}
