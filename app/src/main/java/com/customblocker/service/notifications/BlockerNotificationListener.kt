package com.customblocker.service.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.customblocker.data.prefs.AppSettingsStore
import com.customblocker.data.stats.StatsRecorder
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.NotificationAction
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.util.L
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles notifications from blocked apps (PLAN.md §4) for profiles with "block notifications" on.
 * On each posted notification we ask the engine what to do right now:
 *  - [NotificationAction.Allow]: leave it.
 *  - [NotificationAction.Hide]: cancel it silently.
 *  - [NotificationAction.Batch]: snooze it until the profile next stops restricting (e.g. the top of
 *    the hour for a 5-min/hour usage limit). The OS re-delivers the original notification then; if
 *    it's still blocked it simply gets snoozed again, so the timing is self-correcting.
 *
 * Requires notification access; if not granted the service never binds.
 */
@AndroidEntryPoint
class BlockerNotificationListener : NotificationListenerService() {

    @Inject lateinit var engine: BlockingEngine
    @Inject lateinit var contextProvider: ContextProvider
    @Inject lateinit var statsRecorder: StatsRecorder
    @Inject lateinit var appSettings: AppSettingsStore

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // never touch our own (e.g. the FGS status notification)
        // Group-summary rows have no content of their own; acting on them just flickers the shade.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        when (val action = engine.notificationAction(pkg, contextProvider.current(), appSettings.dayStartHour)) {
            is NotificationAction.Allow -> {}
            is NotificationAction.Hide -> {
                L.d(L.ENGINE, "hide notification from $pkg")
                runCatching { cancelNotification(sbn.key) }
                statsRecorder.recordNotificationBlock(pkg)
            }
            is NotificationAction.Batch -> {
                val delay = action.untilMillis - System.currentTimeMillis()
                if (delay < 1_000L) return // basically at the boundary — let it through
                L.d(L.ENGINE, "batch notification from $pkg for ${delay}ms")
                runCatching { snoozeNotification(sbn.key, delay) }
                statsRecorder.recordNotificationBlock(pkg)
            }
        }
    }
}
