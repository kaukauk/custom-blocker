package com.customblocker.service.accessibility

import android.provider.Settings

/**
 * Strict Mode L3 anti-tamper (PLAN.md §11). While L3 is active and locked, we keep the user out of
 * the screens they'd use to undo blocking — but *surgically*, so the rest of the device stays usable.
 *
 * Window titles are an unreliable signal on modern Android (Settings Panel Architecture reports
 * generic titles like "Settings", and window-state events often carry a transient like an icon's
 * content-description), so detection is driven by the **active window's node tree** plus the window
 * **class**, not titles:
 *  - **Our app is the subject** of a Settings/installer screen (its App info / our uninstall dialog):
 *    our label/package appears together with single-app action controls → eject. Merely seeing our
 *    name as one row in the all-apps list (no action controls / no toggle) does NOT eject, so other
 *    apps stay fully manageable.
 *  - **Capability-granting list screens** (Accessibility, Notification access, Device admin, Special
 *    app access, Developer options, Date & time, Reset, Users) — toggling our row there disables us
 *    and our row may be scrolled off-screen, so these are ejected wholesale by their window class.
 *
 * Titles survive only as an extra app-agnostic backstop ([isDangerous]); they never widen blocking to
 * other apps' screens.
 */
object SettingsGuard {

    const val SETTINGS_PKG = "com.android.settings"

    /**
     * Packages the L3 guard inspects. The installers are guarded so *our* uninstall dialog is caught
     * (defence-in-depth alongside the L2 device-admin uninstall block); other apps' uninstalls are
     * left alone. The permission controller is intentionally NOT guarded — revoking a runtime
     * permission can't disable the blocker (we rely on accessibility / notification-listener / device
     * admin / overlay), and the user must stay able to manage other apps' permissions.
     */
    /**
     * Dedicated capability surfaces some OEMs split out of the main Settings app into their own
     * package — e.g. Samsung hosts the Accessibility screen in `com.samsung.accessibility`, reachable
     * by deep-link without passing through the (guarded) Settings home. Their whole purpose is granting
     * a capability that can disable the blocker, so they're guarded and ejected wholesale like any
     * capability list (their activity class still matches [CAPABILITY_CLASS_MARKERS]) — never treated
     * as a per-app installer surface.
     */
    val CAPABILITY_PACKAGES: Set<String> = setOf(
        "com.samsung.accessibility",
    )

    val GUARDED_PACKAGES = setOf(
        SETTINGS_PKG,
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    ) + CAPABILITY_PACKAGES

    /** A single-app surface (installer dialog) we only act on when it concerns OUR app. */
    fun isSingleAppSurface(packageName: String): Boolean =
        packageName != SETTINGS_PKG &&
            packageName !in CAPABILITY_PACKAGES &&
            packageName in GUARDED_PACKAGES

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
     * Window-class fragments for capability-granting LIST screens, ejected wholesale (our row there
     * disables us and may be scrolled off-screen). Matched as lowercase substrings of the window
     * class name. Deliberately broad — these are system surfaces, not per-app management, so ejecting
     * the whole screen never blocks managing *other* apps.
     */
    private val CAPABILITY_CLASS_MARKERS = listOf(
        // "accessibility" (not "accessibilitysettings") so OEM variants match too — Samsung's
        // top-level screen is e.g. ...accessibility.AccessibilityMainActivity, which lacks the longer
        // fragment. Matching the bare root ejects the whole Accessibility tree (top + every child).
        "accessibility",
        "notificationaccess", "notificationlistener", "zenaccess",
        "usageaccess",
        "deviceadmin",
        "specialaccess", "specialappaccess",
        "developmentsettings", "developeroption",
        "datetimesettings",
        "resetdashboard", "factoryreset", "resetapppref",
        "usersettings", "userssettings",
        "overlaysettings", "drawoverlay",
    )

    /**
     * Identity-confirmation surfaces (PIN / pattern / password / fingerprint) — e.g. a payment app
     * asking the user to verify themselves. On Android these are commonly hosted by
     * `com.android.settings` (ConfirmDeviceCredential / ConfirmLockPassword|Pattern), so the L3 guard
     * would otherwise eject them. They expose no capability toggles and return straight to their
     * caller, so they can't be used to disable the blocker — hence they're exempt. Matched as
     * lowercase substrings of the window class name.
     */
    private val AUTH_CONFIRM_CLASS_MARKERS = listOf(
        "confirmdevicecredential",
        "confirmlockpassword",
        "confirmlockpattern",
        "confirmcredential",
        "biometricprompt",
    )

    /**
     * Credential-entry phrasing, a backstop for [isAuthConfirmation] when the class isn't available
     * (the confirm screen's content-changed re-scans arrive with a generic view class). Specific to
     * identity entry, so they don't appear on capability screens.
     */
    private val AUTH_CONFIRM_TEXT_MARKERS = listOf(
        "enter your pin", "confirm your pin", "enter pin",
        "enter your password", "confirm your password",
        "draw your pattern", "enter your pattern", "confirm your pattern",
        "use your fingerprint", "use fingerprint", "touch the fingerprint sensor",
        "verify it's you", "verify its you", "verify your identity",
        "confirm it's you", "confirm its you",
    )

    /** Single-app action controls present only on an app's detail page (never on the app list). */
    private val SINGLE_APP_ACTIONS = listOf(
        "force stop", "uninstall", "disable", "clear data", "clear storage", "storage & cache",
    )

    /**
     * Headings that identify a capability LIST screen when seen in the node tree (more reliable than
     * the window class, which sometimes arrives as a generic FrameLayout). Only treated as a tamper
     * screen when the screen also has a toggle — so the Settings home, which merely *lists* these as
     * navigation entries (no toggles), is never falsely caught. Specific phrases only, so they don't
     * appear on innocuous toggle screens like Display.
     */
    private val CAPABILITY_TEXT_MARKERS = listOf(
        "usage access",
        "device admin",
        "notification access",
        "special app access",
        "display over other apps",
        "modify system settings",
        "install unknown apps",
    )

    /**
     * App-agnostic tamper titles kept ONLY as an extra backstop. None of these are per-app management
     * terms, so matching them never blocks another app's settings — they only add a second chance to
     * catch a system disable-surface if the class signal is missed.
     */
    private val DANGEROUS_SUBSTRINGS = listOf(
        "accessibility",
        "device admin",
        "developer option",
        "date & time", "date and time",
        "special app access", "special access",
        "notification access", "notification listener", "device & app notification",
        "usage access",
        "display over other apps", "appear on top", "draw over",
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
        // Bluetooth (AOSP routes the Bluetooth shortcut to the "Connected devices" dashboard)
        "bluetooth", "paired", "previously connected", "connected device",
        // Language & input
        "language", "input", "keyboard", "spell", "dictionary", "text-to-speech", "speech",
        "pointer speed",
        // Sound & vibration
        "sound", "volume", "ringtone", "vibrat", "audio", "do not disturb",
    )

    /**
     * Markers of NON-allowed Settings areas — the home dashboard and other top-level hubs the user
     * must not climb to. Chosen to be exclusive to those screens: none of these ever appears in the
     * node tree of an allowed area (Wi-Fi/internet, Bluetooth, Language & input, Sound & vibration),
     * so spotting one means the user has roamed out of confinement. This is what makes allow-list mode
     * strong: modern Android (and OneUI) reports the Settings home with a blank/generic *title*, but
     * its node *tree* always lists these — so we eject on the tree even when the title tells us nothing.
     */
    private val FOREIGN_AREA_MARKERS = listOf(
        "battery", "storage",
        "about phone", "about device", "about emulated", "about tablet",
        "digital wellbeing", "wallpaper", "device care",
        "software update", "system update",
        "safety & emergency", "safety and emergency",
        "tips & support", "tips and support",
        "advanced features",            // OneUI hub
        "general management",           // OneUI hub
        "lock screen", "home screen",
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

    /**
     * True if the node tree shows a NON-allowed Settings hub (home / dashboard) — see
     * [FOREIGN_AREA_MARKERS]. Driven by the node tree (always populated) rather than the title (often
     * blank on these screens), so the allow-list confinement holds even when titles tell us nothing.
     * [texts] must be lowercased node text/content-descriptions.
     */
    fun isForeignArea(texts: List<String>): Boolean =
        texts.any { t -> FOREIGN_AREA_MARKERS.any { t.contains(it) } }

    /**
     * Allow-list verdict for a whole screen, robust to blank titles. Allowed when the *title* is fully
     * within an allowed area ([isAllowed]) OR the node *tree* names an allowed area and shows no
     * foreign hub ([isForeignArea]). The foreign exclusion is what stops the Settings home — whose
     * tree contains "Network & internet" (an allowed substring) yet also "Battery"/"Storage" — from
     * being mistaken for an allowed screen.
     */
    fun isAllowedScreen(titles: List<CharSequence>, texts: List<String>): Boolean {
        if (isAllowed(titles)) return true
        if (isForeignArea(texts)) return false
        return texts.any { t -> ALLOWED_SUBSTRINGS.any { t.contains(it) } }
    }

    /** True if the event carried no usable title text (a transient/sub-window we can't classify). */
    fun hasNoTitle(titles: List<CharSequence>): Boolean =
        titles.map(::normalize).none { it.isNotEmpty() }

    /**
     * True if this screen is an identity/biometric confirmation (see [AUTH_CONFIRM_CLASS_MARKERS] /
     * [AUTH_CONFIRM_TEXT_MARKERS]) — must never be ejected/blocked, or Strict Mode breaks unrelated
     * authentication (e.g. a payment app's fingerprint prompt). [texts] must be lowercased node text.
     */
    fun isAuthConfirmation(className: String?, texts: List<String>): Boolean {
        val cls = className?.lowercase().orEmpty()
        if (AUTH_CONFIRM_CLASS_MARKERS.any { cls.contains(it) }) return true
        return texts.any { t -> AUTH_CONFIRM_TEXT_MARKERS.any { t.contains(it) } }
    }

    /** App-agnostic title backstop (see [DANGEROUS_SUBSTRINGS]). */
    fun isDangerous(titles: List<CharSequence>): Boolean {
        val norm = titles.map(::normalize).filter { it.isNotEmpty() }
        return norm.any { t -> DANGEROUS_SUBSTRINGS.any { t.contains(it) } }
    }

    /** True if any collected node text/description names our app (label or package). */
    fun mentionsSelf(texts: List<String>, selfPackage: String, selfLabel: String): Boolean {
        val label = selfLabel.lowercase()
        return texts.any { it.contains(selfPackage) || (label.isNotBlank() && it.contains(label)) }
    }

    /**
     * The core node-tree verdict for a Settings screen: should we eject?
     *  - a capability list (by window class) → yes, wholesale;
     *  - our app's detail page (our identity + a single-app action control) → yes;
     *  - our row visible on a per-app toggle list (our identity + a toggle) → yes;
     *  - otherwise (incl. our name as a plain row in the all-apps list) → no.
     *
     * [texts] must be lowercased node text/content-descriptions; [hasToggle] true if any node is
     * checkable; [className] the active window's class name.
     */
    fun isTamperScreen(
        className: String?,
        texts: List<String>,
        hasToggle: Boolean,
        selfPackage: String,
        selfLabel: String,
    ): Boolean {
        val cls = className?.lowercase().orEmpty()
        if (CAPABILITY_CLASS_MARKERS.any { cls.contains(it) }) return true
        // Capability list identified by its heading in the node tree (class can be a generic
        // FrameLayout). Gated by hasToggle so the Settings home — which only lists these — is exempt.
        if (hasToggle && texts.any { t -> CAPABILITY_TEXT_MARKERS.any { t.contains(it) } }) return true
        if (!mentionsSelf(texts, selfPackage, selfLabel)) return false
        if (texts.any { t -> SINGLE_APP_ACTIONS.any { t.contains(it) } }) return true
        return hasToggle
    }
}
