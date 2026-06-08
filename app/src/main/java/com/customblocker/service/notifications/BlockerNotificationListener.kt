package com.customblocker.service.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.util.L
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Suppresses notifications from blocked apps (PLAN.md §4) for profiles with "block notifications"
 * on. On each posted notification we ask the engine whether it should be suppressed right now
 * (active profile, targeted package), and cancel it if so. Requires the user to grant notification
 * access; if not granted the service simply never binds.
 */
@AndroidEntryPoint
class BlockerNotificationListener : NotificationListenerService() {

    @Inject lateinit var engine: BlockingEngine
    @Inject lateinit var contextProvider: ContextProvider

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // never cancel our own (e.g. the FGS status notification)
        if (engine.shouldBlockNotification(pkg, contextProvider.current())) {
            L.d(L.ENGINE, "suppress notification from $pkg")
            runCatching { cancelNotification(sbn.key) }
        }
    }
}
