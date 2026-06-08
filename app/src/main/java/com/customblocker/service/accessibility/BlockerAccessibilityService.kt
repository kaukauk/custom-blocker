package com.customblocker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.customblocker.domain.engine.BlockDecision
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.service.conditions.UsageTracker
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var powerManager: PowerManager

    private var lastForeground: String? = null
    /** The package whose foreground time we are currently accruing. */
    private var trackedPkg: String? = null
    private var sinceMs: Long = 0
    /** Last URL we evaluated, to skip re-evaluating unchanged content-changed spam. */
    private var lastUrl: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        powerManager = getSystemService(PowerManager::class.java)
        engine.selfPackage = packageName   // never block ourselves (can't lock the user out)
        L.d(L.FG, "accessibility service connected")
        // Mid-session ticker: accrue usage + re-evaluate the current app every few seconds.
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
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
                if (pkg != lastForeground) {
                    lastForeground = pkg
                    lastUrl = null

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

    /** Read the foreground browser's URL bar and enforce website/keyword rules. */
    private fun evaluateUrl(pkg: String) {
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
            sinceMs = now
        }
    }

    /** Returns true if the app was blocked. */
    private fun evaluate(pkg: String): Boolean {
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
        overlay.show(appLabel = decision.packageName, reason = decision.reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onInterrupt() {
        L.w(L.FG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlay.hide()
        L.w(L.FG, "accessibility service destroyed")
    }

    private companion object {
        const val TICK_MS = 3_000L
    }
}
