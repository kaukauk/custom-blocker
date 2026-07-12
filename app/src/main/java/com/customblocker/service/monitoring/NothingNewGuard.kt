package com.customblocker.service.monitoring

import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Nothing-new pause" (the reflex-check guard).
 *
 * Most phone pickups aren't prompted by anything — you unlock, find nothing waiting, and scroll
 * anyway. This tracks whether anything actually *arrived* while the screen was off, so the
 * accessibility service can hold the screen for a countdown when the answer is "no".
 *
 * The two signals are produced by different services in the same process, so this is the shared
 * singleton between them:
 *  - [BlockerNotificationListener][com.customblocker.service.notifications.BlockerNotificationListener]
 *    calls [onDelivered] for each notification actually delivered to the user (ones our filters
 *    dismissed/hid don't count — the user can't see them, so they're not a reason to be let in).
 *  - [BlockerAccessibilityService][com.customblocker.service.accessibility.BlockerAccessibilityService]
 *    calls [onScreenOff] when the screen goes off (the baseline: everything up to here was on screen
 *    and counts as seen) and checks [nothingNewWhileAway] on ACTION_USER_PRESENT (past the lock screen).
 *
 * Timestamps rather than a flag, so a notification racing the screen-off can't be lost.
 */
@Singleton
class NothingNewGuard @Inject constructor() {

    /** When a notification was last actually delivered to the user (0 = none this boot). */
    @Volatile
    private var lastDeliveredAt: Long = 0L

    /** When the screen last went off — the "everything before this was seen" baseline (0 = never). */
    @Volatile
    private var awaySinceAt: Long = 0L

    /** A notification reached the user. */
    fun onDelivered(nowMillis: Long) { lastDeliveredAt = nowMillis }

    /** Screen off: start a new away period. Anything delivered after this is "new". */
    fun onScreenOff(nowMillis: Long) { awaySinceAt = nowMillis }

    /**
     * True when the user is unlocking but nothing arrived while they were away — i.e. this pickup was
     * prompted by nothing. False until we've seen a screen-off ([awaySinceAt] == 0), so the very first
     * unlock after boot/install is never paused (we have no baseline to judge it against).
     */
    fun nothingNewWhileAway(): Boolean {
        if (awaySinceAt == 0L) return false
        return lastDeliveredAt <= awaySinceAt
    }
}
