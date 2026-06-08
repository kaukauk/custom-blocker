package com.customblocker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.customblocker.domain.engine.BlockDecision
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.service.overlay.OverlayController
import com.customblocker.util.L
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single event-driven foreground detector (PLAN.md §4). On each window-state change it
 * resolves the foreground package, asks the engine for a decision, and enforces:
 * Block -> show overlay + eject to home; Allow -> (no-op here; overlay dismissed via its Close).
 */
@AndroidEntryPoint
class BlockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var engine: BlockingEngine
    @Inject lateinit var overlay: OverlayController

    private var lastForeground: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        L.d(L.FG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore our own UI and the block overlay's own activity churn.
        if (pkg == packageName) return
        if (pkg == lastForeground) return
        lastForeground = pkg

        L.d(L.FG, "foreground=$pkg cls=${event.className}")
        when (val decision = engine.decide(pkg)) {
            is BlockDecision.Block -> {
                L.d(L.ENGINE, "BLOCK $pkg :: ${decision.reason}")
                overlay.show(appLabel = pkg, reason = decision.reason)
                // Eject: send the user home so the blocked app isn't behind the overlay.
                performGlobalAction(GLOBAL_ACTION_HOME)
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            is BlockDecision.Allow -> {
                L.d(L.ENGINE, "ALLOW $pkg")
            }
        }
    }

    override fun onInterrupt() {
        L.w(L.FG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay.hide()
        L.w(L.FG, "accessibility service destroyed")
    }
}
