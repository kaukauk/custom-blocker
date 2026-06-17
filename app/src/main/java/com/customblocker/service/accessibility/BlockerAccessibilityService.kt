package com.customblocker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.customblocker.data.prefs.StrictModeConfig
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.data.stats.StatsRecorder
import com.customblocker.domain.engine.BlockDecision
import com.customblocker.domain.engine.BlockingEngine
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.customblocker.domain.strict.UnlockManager
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.service.conditions.TimeBoundaryScheduler
import com.customblocker.service.conditions.UsageTracker
import com.customblocker.service.monitoring.StatusNotification
import com.customblocker.service.overlay.OverlayController
import com.customblocker.util.L
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single event-driven foreground detector (PLAN.md §4). On each window-state change it
 * resolves the foreground package, accrues usage time + launch counts (for limit conditions),
 * evaluates the engine, and enforces (overlay + eject). A periodic ticker re-evaluates while a
 * usage-limited app stays open so a mid-session limit crossing still triggers a block.
 */
@AndroidEntryPoint
class BlockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var engine: BlockingEngine
    @Inject lateinit var overlay: OverlayController
    @Inject lateinit var contextProvider: ContextProvider
    @Inject lateinit var usageTracker: UsageTracker
    @Inject lateinit var strictModeStore: StrictModeStore
    @Inject lateinit var unlockManager: UnlockManager
    @Inject lateinit var timeBoundaryScheduler: TimeBoundaryScheduler
    @Inject lateinit var statsRecorder: StatsRecorder
    @Inject lateinit var settingsGate: SettingsAccessGate

    /** Fired by [TimeBoundaryScheduler] at a TIME window edge: re-evaluate now + reschedule. */
    private val reevalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            L.d(L.COND, "time boundary reached — re-evaluating")
            accrueUsage()
            trackedPkg?.let { evaluate(it) }
            timeBoundaryScheduler.reschedule()
        }
    }

    /**
     * Screen on/off. We only count foreground time while the screen is on: [SystemClock.elapsedRealtime]
     * keeps advancing through sleep/Doze, so without this the whole screen-off gap would be banked in
     * one lump the moment the device wakes. On SCREEN_OFF we bank the time up to now and stop counting;
     * on SCREEN_ON we restart the clock from now so the asleep period is never credited.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { accrueUsage(); screenOn = false }
                // Reset the clock BEFORE re-enabling accrual so a ticker tick that races the wake-up
                // can't bank the sleep gap (it sees screenOn = false and skips).
                Intent.ACTION_SCREEN_ON -> { sinceMs = SystemClock.elapsedRealtime(); screenOn = true }
            }
        }
    }

    @Volatile private var strictConfig: StrictModeConfig = StrictModeConfig()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var powerManager: PowerManager

    private var lastForeground: String? = null
    /** The package whose foreground time we are currently accruing. */
    private var trackedPkg: String? = null
    /**
     * Packages that draw transient windows OVER the current app — the status bar / shade / heads-up
     * notifications / immersive-mode chrome (SystemUI) and the on-screen keyboard (IMEs). They fire
     * TYPE_WINDOW_STATE_CHANGED constantly (especially during fullscreen video), but they are NOT a
     * foreground app switch: the real app is still underneath. Tracking them would stop usage accrual
     * and stop re-evaluating the real app for blocking. So we ignore their window-state events.
     */
    @Volatile private var transientOverlays: Set<String> = setOf(SYSTEM_UI_PKG)
    private var sinceMs: Long = 0
    /** Whether the screen is on; usage only accrues while it is (see [screenReceiver]). */
    @Volatile private var screenOn: Boolean = true
    /** Last URL we evaluated, to skip re-evaluating unchanged content-changed spam. */
    private var lastUrl: String? = null
    /** Package we last recorded a block for, to avoid re-counting the 3s ticker re-evaluations. */
    private var lastBlockedPkg: String? = null
    /**
     * When we last ejected Settings to the Allowed-Settings launcher. The Settings app fires a burst
     * of window-state events while it loads/animates a screen; without debouncing, each one re-runs
     * the eject (relaunching our launcher), which thrashes and makes the launcher flash up and vanish.
     * One eject per burst is enough — the launcher then sits in front of Settings.
     */
    private var lastSettingsEjectMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        powerManager = getSystemService(PowerManager::class.java)
        engine.selfPackage = packageName   // never block ourselves (can't lock the user out)
        engine.systemPackages = resolveSystemPackages()  // keyboard(s) + launcher: never block
        promoteToForeground()
        registerReceiver(
            reevalReceiver,
            IntentFilter(TimeBoundaryScheduler.ACTION_REEVAL),
            Context.RECEIVER_NOT_EXPORTED,
        )
        screenOn = powerManager.isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        L.d(L.FG, "accessibility service connected")
        // If the user just enabled us from the app, pop back to the app instead of stranding them in Settings.
        com.customblocker.service.PermissionReturn.returnToAppIfArmed(this)
        // Keep the latest Strict Mode config for the L3 settings guard.
        scope.launch { strictModeStore.config.collect { strictConfig = it } }
        // Mid-session ticker: accrue usage + re-evaluate the current app every few seconds, and
        // auto-release a Strict Mode TIMER commitment once its deadline passes.
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val c = strictConfig
                if (c.enabled && c.gate == UnlockGate.TIMER && c.lockedUntilMillis in 1..System.currentTimeMillis()) {
                    strictModeStore.setEnabled(false)   // also clears the deadline
                }
                if (powerManager.isInteractive) {
                    accrueUsage()
                    trackedPkg?.let { evaluate(it) }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Our own package: never act on it. Two sub-cases share this package:
        //  - our overlay window appearing (className is a View, e.g. FrameLayout) — must be ignored,
        //    else we'd hide the very block screen we just raised.
        //  - the user actually opening our UI (an Activity) — dismiss any block screen and stop the
        //    ticker so it can't keep ejecting us to home while a profile is edited.
        if (pkg == packageName) {
            val isOurActivity = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.className?.toString().orEmpty().contains("Activity")
            if (isOurActivity) {
                if (pkg != lastForeground) {
                    lastForeground = pkg
                    accrueUsage()
                    trackedPkg = null
                    lastUrl = null
                }
                overlay.hide()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A transient overlay (status bar / keyboard / heads-up) opened over the current app —
                // not a foreground app switch. Ignore it so we keep tracking & re-evaluating the real
                // app underneath (otherwise usage stops accruing and the app is never re-blocked).
                if (pkg in transientOverlays) return
                // Strict Mode L3: block the Settings app (except whitelisted screens like Wi-Fi /
                // Bluetooth / Language) to stop the user disabling our service / force-stopping us /
                // removing the device admin. Respects the TIMER commitment (lifts at its deadline).
                val strictlyLocked =
                    if (strictConfig.gate == UnlockGate.TIMER) strictConfig.timedLockActive(System.currentTimeMillis())
                    else strictConfig.enabled && !unlockManager.unlocked.value
                if (strictlyLocked && strictConfig.level >= 3 && pkg in SettingsGuard.GUARDED_PACKAGES) {
                    L.d(L.ENGINE, "STRICT L3 settings: pkg=$pkg titles=${event.text}")
                    if (guardSettings(pkg, event.text)) return
                }
                if (pkg != lastForeground) {
                    lastForeground = pkg
                    lastUrl = null
                    lastBlockedPkg = null         // new foreground entry → a fresh block can count
                    // Left the Settings app → end any allowed-settings permit.
                    if (pkg != SettingsGuard.SETTINGS_PKG) settingsGate.clear()
                    // Left the guarded surfaces entirely → reset the eject debounce so reopening
                    // Settings later ejects immediately rather than being swallowed.
                    if (pkg !in SettingsGuard.GUARDED_PACKAGES) lastSettingsEjectMs = 0L

                    accrueUsage()                 // finalize the previous app's foreground time
                    trackedPkg = pkg
                    sinceMs = SystemClock.elapsedRealtime()
                    usageTracker.onLaunch(pkg, contextProvider.current())  // count a launch for launch-limited profiles

                    L.d(L.FG, "foreground=$pkg cls=${event.className}")
                    if (evaluate(pkg)) return     // app blocked — no point reading its URL
                }
                if (BrowserUrlReader.isKnownBrowser(pkg)) evaluateUrl(pkg)
            }
            // Browsers fire content-changed as the page/address bar updates; re-check the URL.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (BrowserUrlReader.isKnownBrowser(pkg)) evaluateUrl(pkg)
            }
        }
    }

    /** A granted earned-unlock is bypassing all app/website blocking right now. */
    private fun unlockActive(): Boolean =
        strictConfig.unlockUntilMillis > System.currentTimeMillis()

    /** Read the foreground browser's URL bar and enforce website/keyword rules. */
    private fun evaluateUrl(pkg: String) {
        if (unlockActive()) return
        val url = BrowserUrlReader.readUrl(pkg, rootInActiveWindow)
        if (url == lastUrl) return
        lastUrl = url
        when (val decision = engine.decideUrl(pkg, url, contextProvider.current())) {
            is BlockDecision.Block -> {
                L.d(L.URL, "BLOCK $pkg url=${url ?: "<unreadable>"} :: ${decision.reason}")
                enforceBlock(decision)
            }
            is BlockDecision.Allow -> if (url != null) L.d(L.URL, "allow $pkg url=$url")
        }
    }

    private fun accrueUsage() {
        val pkg = trackedPkg ?: return
        if (!screenOn) return   // don't bank time while the screen is off — elapsedRealtime spans sleep
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - sinceMs) / 1000L).toInt()
        if (seconds > 0) {
            usageTracker.addUsage(pkg, seconds, contextProvider.current())
            statsRecorder.recordUsage(pkg, seconds)
            sinceMs = now
        }
    }

    /** Returns true if the app was blocked. */
    private fun evaluate(pkg: String): Boolean {
        if (unlockActive()) { overlay.hide(); return false }
        return when (val decision = engine.decide(pkg, contextProvider.current())) {
            is BlockDecision.Block -> {
                L.d(L.ENGINE, "BLOCK $pkg :: ${decision.reason}")
                enforceBlock(decision)
                true
            }
            is BlockDecision.Allow -> false
        }
    }

    private fun enforceBlock(decision: BlockDecision.Block) {
        // Count each block once per foreground entry, not on every 3s ticker re-evaluation.
        if (decision.packageName != lastBlockedPkg) {
            statsRecorder.recordBlock(decision.packageName)
            lastBlockedPkg = decision.packageName
        }
        overlay.show(appLabel = decision.packageName, reason = decision.reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * L3 Settings/installer handling. Returns true if it acted (caller should stop processing).
     *  - Installer screens: ejected to home outright.
     *  - Settings while no permit is held: replaced with the Allowed Settings launcher.
     *  - Settings while permitted: allowed to stay, except dangerous (tamper) screens which clear the
     *    permit and bounce back to the launcher. Safe screens refresh the permit so it doesn't lapse.
     */
    private fun guardSettings(pkg: String, titles: List<CharSequence>): Boolean {
        if (SettingsGuard.isInstaller(pkg)) {
            enforceBlock(BlockDecision.Block(pkg, "Blocked by Strict Mode"))
            return true
        }
        val now = System.currentTimeMillis()
        if (!settingsGate.isPermitted(now)) {
            ejectToAllowedSettings(pkg)
            return true
        }
        if (SettingsGuard.isDangerous(titles)) {
            settingsGate.clear()
            ejectToAllowedSettings(pkg)
            return true
        }
        settingsGate.touch(now)   // safe screen — keep the permit alive
        return false
    }

    /**
     * Eject the real Settings app and present our curated Allowed Settings launcher instead, but at
     * most once per burst of Settings window events (see [lastSettingsEjectMs]). Settings emits several
     * window-state changes while loading a screen; acting on every one relaunches our launcher
     * repeatedly, which thrashes and makes it flash up and immediately vanish.
     */
    private fun ejectToAllowedSettings(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSettingsEjectMs < SETTINGS_EJECT_DEBOUNCE_MS) return
        lastSettingsEjectMs = now
        if (pkg != lastBlockedPkg) {
            statsRecorder.recordBlock(pkg)
            lastBlockedPkg = pkg
        }
        // Launch our launcher straight over Settings (no GLOBAL_ACTION_HOME first): going home first
        // flashes the launcher and lets a late Settings window event win the race, so our activity
        // appears for a fraction of a second and then disappears. A direct NEW_TASK launch covers
        // Settings cleanly.
        startActivity(
            Intent(this, com.customblocker.ui.settings.AllowedSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    /**
     * Resolve the packages we must never block on top of the static set: every enabled IME (so an
     * allow-list can't block the keyboard the user types with) and the current home launcher.
     */
    private fun resolveSystemPackages(): Set<String> {
        val pkgs = mutableSetOf<String>()
        val imes = mutableSetOf<String>()
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach {
            imes += it.packageName
        }
        pkgs += imes
        // IMEs + SystemUI are transient overlays (drawn over the real app), not foreground switches.
        // The launcher is NOT included here: going home IS a real foreground change.
        transientOverlays = imes + SYSTEM_UI_PKG
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { pkgs += it }
        L.d(L.FG, "system never-block: $pkgs ; transient overlays: $transientOverlays")
        return pkgs
    }

    /**
     * Promote this (system-bound) service to a foreground service so the process gets high priority
     * and resists being killed under memory pressure. Best-effort: if the platform refuses (it
     * shouldn't here — we're already running), blocking still works without it.
     */
    private fun promoteToForeground() {
        try {
            StatusNotification.ensureChannel(this)
            val notification = StatusNotification.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    StatusNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(StatusNotification.NOTIFICATION_ID, notification)
            }
            L.d(L.FG, "promoted to foreground service")
        } catch (t: Throwable) {
            L.e(L.FG, "startForeground failed (continuing without FGS)", t)
        }
    }

    override fun onInterrupt() {
        L.w(L.FG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlay.hide()
        runCatching { unregisterReceiver(reevalReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        L.w(L.FG, "accessibility service destroyed")
    }

    private companion object {
        const val TICK_MS = 3_000L
        const val SYSTEM_UI_PKG = "com.android.systemui"
        /** Coalesce a burst of Settings window events into a single eject (see [ejectToAllowedSettings]). */
        const val SETTINGS_EJECT_DEBOUNCE_MS = 1_200L
    }
}
