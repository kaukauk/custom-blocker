package com.customblocker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.customblocker.data.prefs.SettingsGuardMode
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
import com.customblocker.ui.onboarding.Permissions
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
            for (p in accruing) if (evaluate(p)) break
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

    /**
     * User tapped the "block the popout?" prompt. Arm the scoped Strict-Mode exemption, then open the
     * notification-access grant screen — routing through here (not a direct Settings deep-link) is what
     * lets the screen survive the L3 Settings guard.
     */
    private val enableNotifAccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            notifGrantExemptUntilMs = SystemClock.elapsedRealtime() + NOTIF_GRANT_WINDOW_MS
            L.d(L.ENGINE, "notification-access grant armed; opening grant screen")
            Permissions.openNotificationAccessSettings(this@BlockerAccessibilityService)
        }
    }

    @Volatile private var strictConfig: StrictModeConfig = StrictModeConfig()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var powerManager: PowerManager

    private var lastForeground: String? = null
    /** The focused/active package — drives URL reading, launch counts and the Settings guard. */
    private var trackedPkg: String? = null
    /**
     * Every application currently on screen, whose foreground time we accrue this instant — the
     * focused app PLUS a split-screen partner pane PLUS a picture-in-picture tile (e.g. YouTube
     * popout). Derived from [windows] (we request `flagRetrieveInteractiveWindows`), not from the
     * single event package, so two side-by-side apps both burn their limits at the normal rate and
     * a PiP video keeps burning YouTube's limit while you're in another app. All members share the
     * one [sinceMs] clock (they are all visible for the same wall-clock interval).
     */
    private var accruing: Set<String> = emptySet()
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
    /**
     * Sticky current-page URL for the foreground browser, used for usage accrual. Unlike [lastUrl]
     * (which goes null the instant the URL bar leaves the tree — e.g. Chrome hides the toolbar on
     * scroll, YouTube in fullscreen), this holds the last URL actually read and is only cleared when
     * the foreground app changes. Without it, website time would accrue only on the rare frames the
     * toolbar is visible.
     */
    private var trackedUrl: String? = null
    /** Package we last recorded a block for, to avoid re-counting the 3s ticker re-evaluations. */
    private var lastBlockedPkg: String? = null
    /**
     * When we last ejected Settings to the Allowed-Settings launcher. The Settings app fires a burst
     * of window-state events while it loads/animates a screen; without debouncing, each one re-runs
     * the eject (relaunching our launcher), which thrashes and makes the launcher flash up and vanish.
     * One eject per burst is enough — the launcher then sits in front of Settings.
     */
    private var lastSettingsEjectMs: Long = 0L
    /** Throttle for re-scanning a guarded Settings screen on its (frequent) content-changed events. */
    private var lastSettingsScanMs: Long = 0L
    /**
     * While set (elapsedRealtime), an identity-confirmation screen is on top and the Settings guard is
     * suspended so it isn't ejected. Bridges the confirm screen's content-changed re-scans (which lose
     * the activity class); cleared on leaving the guarded packages. See [SettingsGuard.isAuthConfirmation].
     */
    private var authExemptUntilMs: Long = 0L
    /** Our own app label ("Custom Blocker"), used to spot screens that are about US in the node tree. */
    private var selfLabel: String = ""
    /** Whether the "block the popout?" prompt is currently posted (avoid re-notifying each tick). */
    private var popoutPromptShown: Boolean = false
    /**
     * Until this (elapsedRealtime) the Strict-Mode Settings guard lets the notification-access grant
     * screen through — armed only when the user taps our popout prompt. Scoped to that one screen;
     * every real tamper surface (accessibility, device admin, our app-info) is still ejected, so it
     * can't be abused to disable blocking. See [SettingsGuard.isNotificationAccessGrantSurface].
     */
    private var notifGrantExemptUntilMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        powerManager = getSystemService(PowerManager::class.java)
        engine.selfPackage = packageName   // never block ourselves (can't lock the user out)
        selfLabel = runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }.getOrDefault("")
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
        registerReceiver(
            enableNotifAccessReceiver,
            IntentFilter(StatusNotification.ACTION_ENABLE_NOTIF_ACCESS),
            Context.RECEIVER_NOT_EXPORTED,
        )
        L.d(L.FG, "accessibility service connected")
        updatePopoutPrompt()   // reflect any popout already on screen the moment blocking starts
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
                updatePopoutPrompt()   // prompt only while a popout is on screen and access is off

                if (powerManager.isInteractive) {
                    refreshAccruing()   // pick up a PiP tile / split pane that opened without an event we track
                    accrueUsage()
                    // Re-evaluate EVERY visible app (focused, split partner, PiP tile) so a background
                    // pane or a popout that crosses its usage limit is blocked, not just the focused app.
                    for (p in accruing) {
                        if (evaluate(p)) break
                        // Re-check the current browser page too, so a website usage limit blocks
                        // promptly once exhausted even if the page didn't change.
                        if (p == trackedPkg && BrowserUrlReader.isKnownBrowser(p)) recheckBrowserUrl(p)
                    }
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
                    refreshAccruing()   // credit prior apps; our own UI isn't an accruing target → set empties
                    trackedPkg = null
                    lastUrl = null
                    trackedUrl = null
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
                // Strict Mode L3: block the Settings app to stop the user disabling our service /
                // force-stopping us / removing the device admin. Respects the TIMER commitment.
                if (maybeGuardSettings(pkg, event)) return
                if (pkg != lastForeground) {
                    lastForeground = pkg
                    lastBlockedPkg = null         // new foreground entry → a fresh block can count
                    // Left the Settings app → end any allowed-settings permit.
                    if (pkg != SettingsGuard.SETTINGS_PKG) settingsGate.clear()
                    // Left the guarded surfaces entirely → reset the eject debounce so reopening
                    // Settings later ejects immediately rather than being swallowed.
                    if (pkg !in SettingsGuard.GUARDED_PACKAGES) {
                        lastSettingsEjectMs = 0L
                        authExemptUntilMs = 0L   // left the guarded surfaces → end any auth exemption
                    }

                    // Credit the previously-visible set up to now (trackedPkg is still the OLD focused
                    // app, so a browser's final website time lands on the right profile), then swap the
                    // accruing set to what's on screen now and restart the shared clock. If the new
                    // focus was already accruing (a split partner gaining focus), the set is unchanged
                    // and the clock keeps running — both panes accrue continuously.
                    refreshAccruing()
                    // Reset the tracked URLs only AFTER crediting the previous app's final segment, so
                    // a browser's last bit of website time isn't lost on the switch away.
                    lastUrl = null
                    trackedUrl = null
                    trackedPkg = pkg
                    usageTracker.onLaunch(pkg, contextProvider.current())  // count a launch for launch-limited profiles

                    L.d(L.FG, "foreground=$pkg cls=${event.className}")
                    if (evaluate(pkg)) return     // app blocked — no point reading its URL
                }
                if (BrowserUrlReader.isKnownBrowser(pkg)) evaluateUrl(pkg)
            }
            // Browsers fire content-changed as the page/address bar updates; re-check the URL.
            // Settings/SPA screens load their content asynchronously after the window-state event, so
            // re-scan here too (throttled) to catch our app-info / a capability list once it populates.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (BrowserUrlReader.isKnownBrowser(pkg)) evaluateUrl(pkg)
                if (pkg in SettingsGuard.GUARDED_PACKAGES) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSettingsScanMs >= SETTINGS_SCAN_THROTTLE_MS) {
                        lastSettingsScanMs = now
                        maybeGuardSettings(pkg, event)
                    }
                }
            }
        }
    }

    /**
     * Run the L3 Settings guard if Strict Mode is locked at level 3 and [pkg] is a guarded surface.
     * Returns true if it ejected (caller should stop processing the event).
     */
    private fun maybeGuardSettings(pkg: String, event: AccessibilityEvent): Boolean {
        if (pkg !in SettingsGuard.GUARDED_PACKAGES) return false
        if (strictConfig.level < 3) return false
        val strictlyLocked =
            if (strictConfig.gate == UnlockGate.TIMER) strictConfig.timedLockActive(System.currentTimeMillis())
            else strictConfig.enabled && !unlockManager.unlocked.value
        if (!strictlyLocked) return false
        return guardSettings(pkg, event.text, event.className?.toString())
    }

    /** A granted earned-unlock is bypassing all app/website blocking right now. */
    private fun unlockActive(): Boolean =
        strictConfig.unlockUntilMillis > System.currentTimeMillis()

    /** Read the foreground browser's URL bar and enforce website/keyword rules. */
    private fun evaluateUrl(pkg: String) {
        if (unlockActive()) return
        val url = BrowserUrlReader.readUrl(pkg, rootInActiveWindow)
        // Remember the last real URL for accrual; a momentarily-hidden toolbar reads null but the user
        // is still on the same page, so don't clear the sticky URL on null.
        if (url != null) trackedUrl = url
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

    /**
     * Re-apply website rules to the current page on the ticker, so a website usage limit blocks
     * promptly once its allowance is spent even without a page change. Decides against the sticky
     * [trackedUrl] (not a fresh read) so a hidden toolbar isn't mistaken for an unreadable URL.
     */
    private fun recheckBrowserUrl(pkg: String) {
        if (unlockActive()) return
        val url = trackedUrl ?: return
        val decision = engine.decideUrl(pkg, url, contextProvider.current())
        if (decision is BlockDecision.Block) {
            L.d(L.URL, "BLOCK $pkg url=$url :: ${decision.reason}")
            enforceBlock(decision)
        }
    }

    /**
     * Credit the elapsed segment to EVERY app in [accruing] — the focused app, a split-screen
     * partner and a PiP tile all burn their own profile's limit simultaneously. They share the one
     * [sinceMs] clock because they were all on screen for the same interval.
     */
    private fun accrueUsage() {
        if (!screenOn) return   // don't bank time while the screen is off — elapsedRealtime spans sleep
        if (accruing.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - sinceMs) / 1000L).toInt()
        if (seconds <= 0) return
        val ctx = contextProvider.current()
        for (pkg in accruing) {
            // trackedUrl is the sticky current page for the FOCUSED browser (survives a hidden
            // toolbar), so a browser's website-matched time is credited to the right profile; a
            // non-focused pane has no URL context.
            val url = if (pkg == trackedPkg) trackedUrl else null
            usageTracker.addUsage(pkg, url, seconds, ctx)
            statsRecorder.recordUsage(pkg, seconds)
        }
        sinceMs = now
    }

    /**
     * Recompute the on-screen app set from [windows]. If it changed, finalize the OLD set's elapsed
     * time first (still using the current [trackedPkg]/[trackedUrl] for browser credit) and restart
     * the shared clock, so no interval is double-counted or lost across the swap.
     */
    private fun refreshAccruing() {
        val visible = visibleAppPackages()
        if (visible == accruing) return
        accrueUsage()                               // credit the old set up to now
        accruing = visible
        sinceMs = SystemClock.elapsedRealtime()     // restart the clock for the new set
    }

    /**
     * The packages of every application window currently on screen: the focused app, a split-screen
     * partner pane, and a picture-in-picture tile (still a [AccessibilityWindowInfo.TYPE_APPLICATION]
     * window, flagged [AccessibilityWindowInfo.isInPictureInPictureMode]). Excludes ourselves, the
     * SystemUI/IME transient overlays and the launcher — none of those should burn a usage limit.
     * Falls back to the current set if [windows] is momentarily unavailable.
     */
    private fun visibleAppPackages(): Set<String> = runCatching {
        val out = LinkedHashSet<String>()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = w.root?.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            if (pkg in transientOverlays) continue
            if (pkg in engine.systemPackages) continue   // launcher / recents
            out.add(pkg)
        }
        out as Set<String>
    }.getOrDefault(accruing)

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
        // A picture-in-picture tile (e.g. YouTube popout) floats ABOVE our overlay and survives HOME,
        // so covering the screen isn't enough — the video keeps playing. Kill the process to close it.
        dismissPipIfPresent(decision.packageName)
    }

    /**
     * If [pkg] currently has a picture-in-picture window (e.g. a YouTube popout), close it on a
     * block. Sending the app HOME does NOT dismiss a PiP tile, and [killBackgroundProcesses] can't
     * reap it either: an actively-playing PiP keeps its process 'perceptible', which the platform
     * treats as unsafe to kill. So we first STOP the playback through the app's media session — that
     * ends the PiP session and lets the video/audio actually stop — then reap the now-idle process.
     */
    private fun dismissPipIfPresent(pkg: String) {
        val hasPip = runCatching {
            windows.any { it.isInPictureInPictureMode && it.root?.packageName?.toString() == pkg }
        }.getOrDefault(false)
        if (!hasPip) return
        L.d(L.ENGINE, "closing PiP for blocked $pkg")
        val stopped = stopMediaPlayback(pkg)
        if (!stopped) L.w(L.ENGINE, "no media session found for $pkg — PiP may persist")
        // Playback stopped → the tile releases and the process drops to a killable state; reap it so
        // nothing lingers. Harmless if the media stop already closed everything.
        runCatching { getSystemService(android.app.ActivityManager::class.java)?.killBackgroundProcesses(pkg) }
    }

    /**
     * Stop every active media session owned by [pkg] via [MediaSessionManager]. Authorised by our
     * enabled notification listener ([BlockerNotificationListener]); the accessibility service shares
     * the app's identity, so it may pass that listener component. Returns true if a session was hit.
     */
    private fun stopMediaPlayback(pkg: String): Boolean = runCatching {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return false
        val listener = ComponentName(
            this,
            com.customblocker.service.notifications.BlockerNotificationListener::class.java,
        )
        var acted = false
        for (controller in msm.getActiveSessions(listener)) {
            if (controller.packageName == pkg) {
                controller.transportControls.stop()
                acted = true
            }
        }
        acted
    }.getOrElse {
        L.e(L.ENGINE, "stopMediaPlayback failed for $pkg", it)
        false
    }

    /**
     * L3 Settings/installer handling. Returns true if it acted (caller should stop processing).
     * Detection is driven by the active window's node tree + class (titles are unreliable on modern
     * Android — see [SettingsGuard]).
     *  - Installer (single-app surface): only ejects when the dialog concerns OUR app.
     *  - BLOCK_RISKY: Settings is freely usable; only a tamper screen (our app-info / a capability
     *    list) ejects to home. Other apps' screens stay usable.
     *  - SAFE_ONLY: Settings is gated behind the Allowed Settings launcher; with a permit the user is
     *    confined to the allowed screens + their children, and any tamper/other screen ejects back.
     */
    private fun guardSettings(pkg: String, titles: List<CharSequence>, className: String?): Boolean {
        val (texts, hasToggle) = scanActiveWindow()

        // Scoped Strict-Mode exemption: the user tapped our popout prompt to turn ON notification
        // access, so let ONLY the notification-access grant screen through while the short permit is
        // live. Every other tamper surface still falls through to the eject logic below, so this can't
        // be used to reach Accessibility / Device admin / our app-info and disable blocking.
        if (SystemClock.elapsedRealtime() < notifGrantExemptUntilMs &&
            SettingsGuard.isNotificationAccessGrantSurface(className, texts)
        ) {
            return false
        }

        // Installer: a single-app surface (e.g. the uninstall dialog). Only act when it's about us;
        // uninstalling / managing other apps stays allowed.
        if (SettingsGuard.isSingleAppSurface(pkg)) {
            if (SettingsGuard.mentionsSelf(texts, packageName, selfLabel)) {
                enforceBlock(BlockDecision.Block(pkg, "Blocked by Strict Mode"))
                return true
            }
            return false
        }

        // A dedicated OEM capability package (e.g. com.samsung.accessibility) is a tamper surface in
        // its entirety — eject it in either mode regardless of the (often generic) per-event class.
        val tamper = pkg in SettingsGuard.CAPABILITY_PACKAGES ||
            SettingsGuard.isTamperScreen(className, texts, hasToggle, packageName, selfLabel) ||
            SettingsGuard.isDangerous(titles)
        if (com.customblocker.BuildConfig.DEBUG) {
            L.d(L.ENGINE, "settings-scan pkg=$pkg cls=$className tamper=$tamper toggle=$hasToggle titles=$titles")
        }

        // Identity/biometric confirmation (e.g. a payment app's fingerprint/PIN prompt) can be hosted
        // by com.android.settings (ConfirmDeviceCredential / ConfirmLock*). It can't reach any
        // capability screen and returns to its caller, so never eject/block it — otherwise Strict Mode
        // breaks unrelated authentication. Held for a short grace so it survives the confirm screen's
        // content-changed re-scans (which lose the activity class), but never shields a tamper screen.
        if (!tamper) {
            val nowEl = SystemClock.elapsedRealtime()
            if (SettingsGuard.isAuthConfirmation(className, texts)) {
                authExemptUntilMs = nowEl + AUTH_EXEMPT_MS
                return false
            }
            if (nowEl < authExemptUntilMs) return false
        }

        // Block-list mode: Settings stays freely usable; only tamper screens eject (straight to home).
        if (strictConfig.settingsGuardMode == SettingsGuardMode.BLOCK_RISKY) {
            if (tamper) {
                enforceBlock(BlockDecision.Block(pkg, "Blocked by Strict Mode"))
                resetSettingsApp()
                return true
            }
            return false
        }
        // Allow-list mode (safe screens only). Settings is gated behind the curated launcher; once
        // the user taps a shortcut a permit opens and they may use that allowed screen + its children,
        // but NOTHING else — wandering to any other Settings screen (incl. the home list) ejects them
        // back to the launcher.
        val now = System.currentTimeMillis()
        if (!settingsGate.isPermitted(now)) {
            // A momentary empty/unclassifiable loading frame (Settings emits one while a screen
            // attaches). Don't eject on it — wait for the populated event so an identity-confirmation
            // screen can be recognised (above) instead of being kicked out on its first blank frame.
            // The next event classifies it (real Settings → eject; auth → exempt).
            if (texts.isEmpty() && SettingsGuard.hasNoTitle(titles)) return false
            ejectToAllowedSettings(pkg)
            return true
        }
        // Tamper screens always eject, even mid-grace.
        if (tamper) {
            settingsGate.clear()
            ejectToAllowedSettings(pkg)
            resetSettingsApp()
            return true
        }
        // On an allowed screen (or one of its children) → keep the permit alive, let them stay.
        // Classified from the node tree (not just the title) so a legit allowed screen that reports a
        // blank title still confirms instead of being ejected.
        if (SettingsGuard.isAllowedScreen(titles, texts)) {
            settingsGate.touch(now)
            return false
        }
        // Roamed to the Settings home or another top-level hub — the way users climb back to the
        // capability screens. These report a blank/generic title, so we detect them by their node tree
        // and eject HARD (even mid-grace), which is what keeps allow-list confinement strong.
        if (SettingsGuard.isForeignArea(texts)) {
            settingsGate.clear()
            ejectToAllowedSettings(pkg)
            resetSettingsApp()
            return true
        }
        // Just tapped a shortcut: the deep-link briefly shows the home/loading frames. Don't treat
        // that as roaming — let it settle until the allowed screen surfaces.
        if (settingsGate.inGrace(now)) return false
        // A genuinely empty/transient window we can't classify at all (no title AND no node text,
        // e.g. a momentary sub-window). Leave the user where they are rather than risk a false eject;
        // a populated-but-unrecognised screen falls through to the eject below.
        if (texts.isEmpty() && SettingsGuard.hasNoTitle(titles)) return false
        // Any other populated, unrecognised screen: roamed off the allowed area → eject.
        settingsGate.clear()
        ejectToAllowedSettings(pkg)
        return true
    }

    /**
     * Collect the active window's node text + whether it has any toggle, for [SettingsGuard] to
     * classify. Bounded DFS so a pathological tree can't stall the event thread. Returns lowercased
     * text/content-descriptions and a hasToggle flag.
     */
    private fun scanActiveWindow(): Pair<List<String>, Boolean> {
        val root = rootInActiveWindow ?: return emptyList<String>() to false
        val texts = ArrayList<String>(64)
        var hasToggle = false
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.addLast(root)
        var budget = 800
        while (stack.isNotEmpty() && budget-- > 0) {
            val n = stack.removeLast()
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.lowercase()) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.lowercase()) }
            if (n.isCheckable) hasToggle = true
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return texts to hasToggle
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
     * After ejecting from a tamper screen, wipe the Settings app's back stack so the user can't simply
     * re-open Settings (icon / recents) and resume deep inside a capability screen. The eject has
     * already sent Settings to the background (HOME / our launcher over it); a backgrounded app is
     * killable, so [ActivityManager.killBackgroundProcesses] tears its task down and the next launch
     * starts fresh at the Settings home. Throttled with the eject so a window-event burst kills once.
     */
    private fun resetSettingsApp() {
        // Kill slightly after the eject: HOME / our launcher is async, so Settings isn't backgrounded
        // yet on this tick and killBackgroundProcesses (which only touches backgrounded apps) would
        // no-op. A short delay lets the eject land first.
        scope.launch {
            delay(SETTINGS_RESET_DELAY_MS)
            val am = getSystemService(android.app.ActivityManager::class.java)
            // Also tear down any OEM capability package (e.g. com.samsung.accessibility) so it can't be
            // resumed from recents deep inside the Accessibility screen.
            for (p in SettingsGuard.CAPABILITY_PACKAGES + SettingsGuard.SETTINGS_PKG) {
                runCatching { am?.killBackgroundProcesses(p) }
            }
        }
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
        // Every registered home launcher, not just the default: on Samsung the recents/overview UI is
        // served by com.sec.android.app.launcher even when a third-party launcher is the active home,
        // so an allow-list would otherwise block "recent apps". Settings registers a HOME fallback too
        // — exclude it (it's governed by the Strict Mode guard, not the app allow-list).
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).forEach { ri ->
            ri.activityInfo?.packageName
                ?.takeIf { it != SettingsGuard.SETTINGS_PKG }
                ?.let { pkgs += it }
        }
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

    /**
     * Show the "block the popout?" prompt only in the moment it matters: a picture-in-picture window
     * is on screen AND notification access is off (so we can't stop it via its media session). Blocking
     * apps still works without the permission; this nudges the user exactly when a popout could slip
     * past its limit. Only touches the notification on an actual state change.
     */
    private fun updatePopoutPrompt() {
        val pipActive = runCatching { windows.any { it.isInPictureInPictureMode } }.getOrDefault(false)
        val shouldPrompt = pipActive && !Permissions.isNotificationAccessGranted(this)
        if (shouldPrompt == popoutPromptShown) return
        popoutPromptShown = shouldPrompt
        if (shouldPrompt) StatusNotification.showPopoutPrompt(this)
        else StatusNotification.clearPopoutPrompt(this)
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
        runCatching { unregisterReceiver(enableNotifAccessReceiver) }
        L.w(L.FG, "accessibility service destroyed")
    }

    private companion object {
        const val TICK_MS = 3_000L
        const val SYSTEM_UI_PKG = "com.android.systemui"
        /** Coalesce a burst of Settings window events into a single eject (see [ejectToAllowedSettings]). */
        const val SETTINGS_EJECT_DEBOUNCE_MS = 1_200L
        /** Min gap between node-tree re-scans on a guarded screen's content-changed event stream. */
        const val SETTINGS_SCAN_THROTTLE_MS = 350L
        /** How long an identity-confirmation screen suspends the Settings guard (bridges re-scans). */
        const val AUTH_EXEMPT_MS = 8_000L
        /** Delay before resetting Settings after an eject, so it's backgrounded first (killable). */
        const val SETTINGS_RESET_DELAY_MS = 400L
        /** How long the notification-access grant screen stays reachable under Strict Mode after a tap. */
        const val NOTIF_GRANT_WINDOW_MS = 2 * 60_000L
    }
}
