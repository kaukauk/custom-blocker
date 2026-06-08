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

    override fun onServiceConnected() {
        super.onServiceConnected()
        powerManager = getSystemService(PowerManager::class.java)
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
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == lastForeground) return
        lastForeground = pkg

        accrueUsage()                 // finalize the previous app's foreground time
        trackedPkg = pkg
        sinceMs = SystemClock.elapsedRealtime()
        usageTracker.onLaunch(pkg)    // count a launch for launch-limited profiles

        L.d(L.FG, "foreground=$pkg cls=${event.className}")
        evaluate(pkg)
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

    private fun evaluate(pkg: String) {
        when (val decision = engine.decide(pkg, contextProvider.current())) {
            is BlockDecision.Block -> {
                L.d(L.ENGINE, "BLOCK $pkg :: ${decision.reason}")
                overlay.show(appLabel = pkg, reason = decision.reason)
                performGlobalAction(GLOBAL_ACTION_HOME)
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            is BlockDecision.Allow -> {}
        }
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
