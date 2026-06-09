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

    /** Fired by [TimeBoundaryScheduler] at a TIME window edge: re-evaluate now + reschedule. */
    private val reevalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            L.d(L.COND, "time boundary reached — re-evaluating")
            accrueUsage()
            trackedPkg?.let { evaluate(it) }
            timeBoundaryScheduler.reschedule()
        }
    }

    @Volatile private var strictConfig: StrictModeConfig = StrictModeConfig()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var powerManager: PowerManager

    private var lastForeground: String? = null
    /** The package whose foreground time we are currently accruing. */
    private var trackedPkg: String? = null
    private var sinceMs: Long = 0
    /** Last URL we evaluated, to skip re-evaluating unchanged content-changed spam. */
    private var lastUrl: String? = null
    /** Package we last recorded a block for, to avoid re-counting the 3s ticker re-evaluations. */
    private var lastBlockedPkg: String? = null

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
        L.d(L.FG, "accessibility service connected")
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
                // Strict Mode L3: block the Settings app (except whitelisted screens like Wi-Fi /
                // Bluetooth / Language) to stop the user disabling our service / force-stopping us /
                // removing the device admin. Respects the TIMER commitment (lifts at its deadline).
                val strictlyLocked =
                    if (strictConfig.gate == UnlockGate.TIMER) strictConfig.timedLockActive(System.currentTimeMillis())
                    else strictConfig.enabled && !unlockManager.unlocked.value
                if (strictlyLocked && strictConfig.level >= 3 && pkg in SettingsGuard.GUARDED_PACKAGES) {
                    L.d(L.ENGINE, "STRICT L3 settings: pkg=$pkg titles=${event.text}")
                    if (SettingsGuard.shouldBlock(pkg, event.text)) {
                        enforceBlock(BlockDecision.Block(pkg, "Blocked by Strict Mode"))
                        return
                    }
                }
                if (pkg != lastForeground) {
                    lastForeground = pkg
                    lastUrl = null
                    lastBlockedPkg = null         // new foreground entry → a fresh block can count

                    accrueUsage()                 // finalize the previous app's foreground time
                    trackedPkg = pkg
                    sinceMs = SystemClock.elapsedRealtime()
                    usageTracker.onLaunch(pkg)    // count a launch for launch-limited profiles

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
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - sinceMs) / 1000L).toInt()
        if (seconds > 0) {
            usageTracker.addUsage(pkg, seconds)
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
     * Resolve the packages we must never block on top of the static set: every enabled IME (so an
     * allow-list can't block the keyboard the user types with) and the current home launcher.
     */
    private fun resolveSystemPackages(): Set<String> {
        val pkgs = mutableSetOf<String>()
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach {
            pkgs += it.packageName
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(home, 0)?.activityInfo?.packageName?.let { pkgs += it }
        L.d(L.FG, "system never-block: $pkgs")
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
        L.w(L.FG, "accessibility service destroyed")
    }

    private companion object {
        const val TICK_MS = 3_000L
    }
}
