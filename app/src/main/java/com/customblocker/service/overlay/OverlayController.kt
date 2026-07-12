package com.customblocker.service.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.customblocker.util.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Draws / removes the full-screen block overlay using a TYPE_APPLICATION_OVERLAY window
 * (PLAN.md §9). Phase 1 uses a programmatic View for reliability; a Compose block screen
 * is a later polish step. Must be called on the main thread (the a11y callbacks already are).
 */
@Singleton
class OverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null

    /** The nothing-new pause window; kept separate so it can't clash with the block overlay. */
    private var countdownView: View? = null
    private var countdownLabel: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownTick: Runnable? = null

    val isShowing: Boolean get() = overlayView != null

    val isCountingDown: Boolean get() = countdownView != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** Optional callback invoked when the user taps "Close" on the block screen. */
    var onClose: (() -> Unit)? = null

    fun show(appLabel: String, reason: String) {
        if (!canDrawOverlays()) {
            L.w(L.OVERLAY, "cannot show: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        if (overlayView != null) {
            L.d(L.OVERLAY, "already showing; skip")
            return
        }
        val view = buildView(appLabel, reason)
        try {
            windowManager.addView(view, layoutParams())
            overlayView = view
            L.d(L.OVERLAY, "shown for $appLabel ($reason)")
        } catch (t: Throwable) {
            // BadTokenException etc. — window may have gone away.
            L.e(L.OVERLAY, "addView failed", t)
        }
    }

    fun hide() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            L.e(L.OVERLAY, "removeView failed", t)
        } finally {
            overlayView = null
            L.d(L.OVERLAY, "hidden")
        }
    }

    /**
     * Hold the whole screen for [seconds] with a live countdown — the "nothing-new pause" shown when
     * the user unlocks and nothing arrived while they were away. Deliberately has no dismiss button:
     * it clears itself when the countdown reaches zero (or on [hideCountdown], e.g. the screen going
     * off). Re-showing while one is already up is a no-op, so a burst of unlock events can't stack it.
     */
    fun showCountdown(seconds: Int, onFinished: (() -> Unit)? = null) {
        if (!canDrawOverlays()) {
            L.w(L.OVERLAY, "countdown: cannot show, SYSTEM_ALERT_WINDOW not granted")
            return
        }
        if (countdownView != null) return
        val total = seconds.coerceAtLeast(1)
        val view = buildCountdownView(total)
        try {
            windowManager.addView(view, layoutParams())
            countdownView = view
            L.d(L.OVERLAY, "nothing-new pause: holding screen ${total}s")
        } catch (t: Throwable) {
            L.e(L.OVERLAY, "countdown addView failed", t)
            return
        }
        var remaining = total
        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    hideCountdown()
                    onFinished?.invoke()
                    return
                }
                countdownLabel?.text = formatRemaining(remaining)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        countdownTick = tick
        mainHandler.postDelayed(tick, 1_000L)
    }

    fun hideCountdown() {
        countdownTick?.let { mainHandler.removeCallbacks(it) }
        countdownTick = null
        countdownLabel = null
        val view = countdownView ?: return
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            L.e(L.OVERLAY, "countdown removeView failed", t)
        } finally {
            countdownView = null
            L.d(L.OVERLAY, "nothing-new pause: released")
        }
    }

    private fun formatRemaining(seconds: Int): String =
        if (seconds >= 60) "%d:%02d".format(seconds / 60, seconds % 60) else seconds.toString()

    private fun buildCountdownView(totalSeconds: Int): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F20E1726")) // ~95% opaque dark, same as the block screen
            // Swallow every touch so the screen really is held, not just covered.
            isClickable = true
            isFocusable = false
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        column.addView(TextView(context).apply {
            text = "Nothing new"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(context).apply {
            text = "Nothing arrived while you were away — so this pickup wasn't prompted by anything."
            setTextColor(Color.parseColor("#B0FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        })
        countdownLabel = TextView(context).apply {
            text = formatRemaining(totalSeconds)
            setTextColor(Color.parseColor("#CFE5FF"))
            textSize = 56f
            gravity = Gravity.CENTER
        }
        column.addView(countdownLabel)
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER },
        )
        return root
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Not focusable (don't steal IME/keys) but still covers + receives touches.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
    }

    private fun buildView(appLabel: String, reason: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F20E1726")) // ~95% opaque dark
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        column.addView(TextView(context).apply {
            text = "Blocked"
            setTextColor(Color.WHITE)
            textSize = 32f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(context).apply {
            text = appLabel
            setTextColor(Color.parseColor("#CFE5FF"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(context).apply {
            text = reason
            setTextColor(Color.parseColor("#B0FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        })
        column.addView(Button(context).apply {
            text = "Close"
            setOnClickListener {
                hide()
                onClose?.invoke()
            }
        })
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER },
        )
        return root
    }
}
