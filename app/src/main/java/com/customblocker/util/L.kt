package com.customblocker.util

import android.util.Log

/**
 * Tagged logging — the primary test signal for the blocking engine (see PLAN.md §15).
 * Filter with: adb logcat -s BlockEngine:* FgDetect:* Overlay:* Cond:*
 */
object L {
    const val ENGINE = "BlockEngine"
    const val FG = "FgDetect"
    const val OVERLAY = "Overlay"
    const val COND = "Cond"

    fun d(tag: String, msg: String) = Log.d(tag, msg)
    fun w(tag: String, msg: String) = Log.w(tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(tag, msg, t)
}
