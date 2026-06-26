package com.customblocker.service.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {

    private val PKG = "com.customblocker.debug"
    private val LABEL = "Custom Blocker"

    // The package installer is a single-app surface (acted on only for OUR app); Settings is not.
    @Test fun installer_isSingleAppSurface() {
        assertTrue(SettingsGuard.isSingleAppSurface("com.android.packageinstaller"))
        assertTrue(SettingsGuard.isSingleAppSurface("com.google.android.packageinstaller"))
        assertFalse(SettingsGuard.isSingleAppSurface(SettingsGuard.SETTINGS_PKG))
    }

    // Permission controller is intentionally NOT guarded (runtime perms can't disable the blocker).
    @Test fun permissionController_notGuarded() {
        assertFalse("com.google.android.permissioncontroller" in SettingsGuard.GUARDED_PACKAGES)
        assertFalse(SettingsGuard.isSingleAppSurface("com.google.android.permissioncontroller"))
    }

    @Test fun otherApps_notGuarded() {
        assertFalse("com.android.chrome" in SettingsGuard.GUARDED_PACKAGES)
        assertFalse(SettingsGuard.isSingleAppSurface("com.android.chrome"))
    }

    // --- node-tree tamper detection (the real signal) ---

    // Capability lists eject wholesale by window class, even when our row isn't in the node text.
    @Test fun capabilityLists_ejectByClass() {
        for (cls in listOf(
            "com.android.settings.Settings\$AccessibilitySettingsActivity",
            "com.android.settings.Settings\$NotificationAccessSettingsActivity",
            "com.android.settings.Settings\$UsageAccessSettingsActivity",
            "com.android.settings.Settings\$DeviceAdminSettingsActivity",
        )) {
            assertTrue(cls, SettingsGuard.isTamperScreen(cls, emptyList(), false, PKG, LABEL))
        }
    }

    // OEM-variant accessibility screens (e.g. Samsung's) must also eject — the class marker is the
    // bare "accessibility" root, not the longer AOSP fragment, so the whole tree is covered.
    @Test fun accessibilityTree_ejectsAcrossOemVariants() {
        for (cls in listOf(
            "com.samsung.android.settings.accessibility.AccessibilityMainActivity",
            "com.samsung.android.settings.accessibility.InstalledServiceActivity",
            "com.android.settings.SubSettings", // detail page hosting an accessibility fragment
        )) {
            // The bare "SubSettings" alone must NOT match (it hosts benign screens too); the
            // accessibility ones do.
            val expected = cls.lowercase().contains("accessibility")
            assertTrue(cls, SettingsGuard.isTamperScreen(cls, emptyList(), false, PKG, LABEL) == expected)
        }
    }

    // OUR app-info: our label + a single-app action control → eject.
    @Test fun ourAppInfo_ejects() {
        val texts = listOf("custom blocker", "open", "uninstall", "force stop", "storage & cache")
        assertTrue(SettingsGuard.isTamperScreen("com.android.settings.SubSettings", texts, false, PKG, LABEL))
    }

    // The all-apps LIST shows our name as one row but has no single-app controls / toggles → allowed.
    @Test fun appsList_withOurNameAsRow_notEjected() {
        val texts = listOf("apps", "chrome", "custom blocker", "gmail", "messages", "settings")
        assertFalse(SettingsGuard.isTamperScreen("com.android.settings.spa.SpaActivity", texts, false, PKG, LABEL))
    }

    // Another app's info page (no mention of us) → allowed, even with action controls present.
    @Test fun otherAppInfo_notEjected() {
        val texts = listOf("chrome", "open", "uninstall", "force stop")
        assertFalse(SettingsGuard.isTamperScreen("com.android.settings.SubSettings", texts, false, PKG, LABEL))
    }

    // Our row visible on a per-app toggle list (e.g. notification access) → eject.
    @Test fun ourRowWithToggle_ejects() {
        val texts = listOf("custom blocker")
        assertTrue(SettingsGuard.isTamperScreen("com.android.settings.spa.SpaActivity", texts, true, PKG, LABEL))
    }

    @Test fun mentionsSelf_matchesLabelOrPackage() {
        assertTrue(SettingsGuard.mentionsSelf(listOf("custom blocker"), PKG, LABEL))
        assertTrue(SettingsGuard.mentionsSelf(listOf("com.customblocker.debug"), PKG, LABEL))
        assertFalse(SettingsGuard.mentionsSelf(listOf("chrome", "gmail"), PKG, LABEL))
    }

    // --- app-agnostic title backstop: system disable-surfaces, never per-app management ---
    @Test fun dangerousTitleBackstop() {
        assertTrue(SettingsGuard.isDangerous(listOf("Accessibility")))
        assertTrue(SettingsGuard.isDangerous(listOf("Device admin apps")))
        assertTrue(SettingsGuard.isDangerous(listOf("Special app access")))
        assertTrue(SettingsGuard.isDangerous(listOf("Notification access")))
    }

    // Backstop must NOT match per-app management titles (those are handled surgically by node tree).
    @Test fun dangerousTitleBackstop_ignoresAppManagement() {
        assertFalse(SettingsGuard.isDangerous(listOf("Apps")))
        assertFalse(SettingsGuard.isDangerous(listOf("App info")))
        assertFalse(SettingsGuard.isDangerous(listOf("Battery")))
        assertFalse(SettingsGuard.isDangerous(listOf("Wi-Fi")))
        assertFalse(SettingsGuard.isDangerous(emptyList()))
    }

    // --- safe-screens-only confinement ---
    @Test fun allowList_shortcutsAndChildren() {
        assertTrue(SettingsGuard.isAllowed(listOf("Wi-Fi")))
        assertTrue(SettingsGuard.isAllowed(listOf("Saved networks")))
        assertTrue(SettingsGuard.isAllowed(listOf("Bluetooth")))
        assertTrue(SettingsGuard.isAllowed(listOf("Languages & input")))
        assertTrue(SettingsGuard.isAllowed(listOf("Sound & vibration")))
        assertTrue(SettingsGuard.isAllowed(listOf("Phone ringtone")))
    }

    @Test fun allowList_rejectsOtherScreens() {
        assertFalse(SettingsGuard.isAllowed(listOf("Settings")))
        assertFalse(SettingsGuard.isAllowed(listOf("Display")))
        assertFalse(SettingsGuard.isAllowed(emptyList()))
    }

    // --- allow-list confinement: foreign hubs (the leak the user hit on the S20) ---

    // The Settings home dashboard — even with a blank title — is recognised by its node tree and
    // treated as a foreign hub, so it can't be used to climb back to the capability screens.
    @Test fun foreignArea_catchesSettingsHomeByNodeTree() {
        val home = listOf("network & internet", "connected devices", "apps", "notifications",
            "battery", "storage", "sound & vibration", "display", "about phone")
        assertTrue(SettingsGuard.isForeignArea(home))
        // With a blank title, the whole-screen verdict must still classify it as NOT allowed.
        assertFalse(SettingsGuard.isAllowedScreen(emptyList(), home))
    }

    // OneUI top-level hubs are foreign too (different labels, same intent).
    @Test fun foreignArea_catchesOneUiHubs() {
        assertTrue(SettingsGuard.isForeignArea(listOf("advanced features")))
        assertTrue(SettingsGuard.isForeignArea(listOf("general management")))
        assertTrue(SettingsGuard.isForeignArea(listOf("battery and device care")))
        assertTrue(SettingsGuard.isForeignArea(listOf("software update")))
    }

    // Allowed screens are NOT foreign and stay usable even when the title arrives blank — the node
    // tree alone confirms them (so the strict eject doesn't false-trigger on the allowed area).
    @Test fun allowedScreen_confirmedByNodeTreeWhenTitleBlank() {
        val wifi = listOf("wi-fi", "homenet", "guestnet", "add network", "saved networks",
            "network preferences")
        assertFalse(SettingsGuard.isForeignArea(wifi))
        assertTrue(SettingsGuard.isAllowedScreen(emptyList(), wifi))

        val sound = listOf("media volume", "call volume", "ring volume", "phone ringtone",
            "default notification sound", "vibration & haptics")
        assertFalse(SettingsGuard.isForeignArea(sound))
        assertTrue(SettingsGuard.isAllowedScreen(emptyList(), sound))
    }

    // Title-based allow still works (children with clean titles, e.g. ringtone picker).
    @Test fun allowedScreen_confirmedByTitle() {
        assertTrue(SettingsGuard.isAllowedScreen(listOf("Phone ringtone"), emptyList()))
        assertTrue(SettingsGuard.isAllowedScreen(listOf("Saved networks"), emptyList()))
    }

    // --- identity/biometric confirmation exemption (payment fingerprint/PIN under Strict Mode) ---

    // Confirm screens hosted by com.android.settings are recognised by class, so they're never ejected.
    @Test fun authConfirmation_byClass() {
        assertTrue(SettingsGuard.isAuthConfirmation(
            "com.android.settings.password.ConfirmDeviceCredentialActivity", emptyList()))
        assertTrue(SettingsGuard.isAuthConfirmation(
            "com.android.settings.password.ConfirmLockPassword", emptyList()))
        assertTrue(SettingsGuard.isAuthConfirmation(
            "com.android.settings.password.ConfirmLockPattern", emptyList()))
    }

    // The class is lost on the confirm screen's content-changed re-scans (generic view), so the
    // credential-entry text is a backstop.
    @Test fun authConfirmation_byText() {
        assertTrue(SettingsGuard.isAuthConfirmation("android.widget.FrameLayout",
            listOf("enter your pin", "cancel")))
        assertTrue(SettingsGuard.isAuthConfirmation("android.widget.FrameLayout",
            listOf("verify it's you", "use fingerprint")))
    }

    // A real capability/tamper screen is NOT mistaken for an auth confirmation.
    @Test fun authConfirmation_notTriggeredByCapabilityScreens() {
        assertFalse(SettingsGuard.isAuthConfirmation(
            "com.android.settings.Settings\$AccessibilitySettingsActivity",
            listOf("accessibility", "custom blocker")))
        assertFalse(SettingsGuard.isAuthConfirmation("android.widget.FrameLayout",
            listOf("network & internet", "battery", "storage")))
    }

    @Test fun hasNoTitle_onlyForBlank() {
        assertTrue(SettingsGuard.hasNoTitle(emptyList()))
        assertTrue(SettingsGuard.hasNoTitle(listOf("", "  ")))
        assertFalse(SettingsGuard.hasNoTitle(listOf("Wi-Fi")))
    }
}
