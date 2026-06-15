package com.customblocker.service.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.customblocker.data.db.CapturedNotificationEntity
import com.customblocker.data.prefs.AppSettingsStore
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.data.stats.StatsRecorder
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.NotifActionType
import com.customblocker.domain.engine.NotifContext
import com.customblocker.domain.engine.NotifFilterOutcome
import com.customblocker.domain.engine.NotificationAction
import com.customblocker.domain.engine.NotificationFilterEngine
import com.customblocker.service.conditions.ContextProvider
import com.customblocker.util.L
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Handles posted notifications. Two independent systems run here:
 *
 *  1. User-defined notification filters ([NotificationFilterEngine], PLAN.md §4b): match by app +
 *     regex over the title/text and apply Dismiss / Catch / Batch / Delay. These take precedence.
 *  2. Profile notification suppression ([BlockingEngine], PLAN.md §4): hide/batch notifications
 *     from apps targeted by an active blocking profile.
 *
 * Every non-ongoing notification is also logged to the in-app history/inbox so the user can review
 * what came in and build filters from real examples.
 *
 * Requires notification access; if not granted the service never binds.
 */
@AndroidEntryPoint
class BlockerNotificationListener : NotificationListenerService() {

    @Inject lateinit var engine: BlockingEngine
    @Inject lateinit var filterEngine: NotificationFilterEngine
    @Inject lateinit var repository: BlockerRepository
    @Inject lateinit var contextProvider: ContextProvider
    @Inject lateinit var statsRecorder: StatsRecorder
    @Inject lateinit var appSettings: AppSettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last (package → "title\ntext") seen, to drop the rapid duplicate updates ongoing apps post. */
    @Volatile private var lastSeen: Pair<String, String>? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // never touch our own (e.g. the FGS status notification)
        // Group-summary rows have no content of their own; acting on them just flickers the shade.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Ongoing notifications (media transport, downloads, calls) aren't "inbox" items and update
        // constantly — skip them entirely so neither the filters nor the history get spammed.
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return // nothing meaningful to match or display

        // 1) User notification filters.
        val now = LocalDateTime.now()
        val ctx = NotifContext(
            packageName = pkg,
            title = title,
            content = text,
            minuteOfDay = now.hour * 60 + now.minute,
            dayBit = contextProvider.current().dayBit,
            nowMillis = System.currentTimeMillis(),
        )
        when (val outcome = filterEngine.evaluate(ctx)) {
            is NotifFilterOutcome.None -> {} // fall through to profile suppression below
            is NotifFilterOutcome.Dismiss -> {
                L.d(L.ENGINE, "filter dismiss notification from $pkg")
                runCatching { cancelNotification(sbn.key) }
                logCaptured(pkg, title, text, outcome.filterId, NotifActionType.DISMISS)
                statsRecorder.recordNotificationBlock(pkg)
                return
            }
            is NotifFilterOutcome.Catch -> {
                L.d(L.ENGINE, "filter catch notification from $pkg")
                runCatching { cancelNotification(sbn.key) }
                logCaptured(pkg, title, text, outcome.filterId, NotifActionType.CATCH)
                statsRecorder.recordNotificationBlock(pkg)
                return
            }
            is NotifFilterOutcome.Snooze -> {
                val delay = outcome.untilMillis - System.currentTimeMillis()
                logCaptured(pkg, title, text, outcome.filterId, outcome.action)
                if (delay >= 1_000L) {
                    L.d(L.ENGINE, "filter ${outcome.action} notification from $pkg for ${delay}ms")
                    runCatching { snoozeNotification(sbn.key, delay) }
                    statsRecorder.recordNotificationBlock(pkg)
                }
                return
            }
        }

        // 2) Profile notification suppression (existing behaviour) + observe in history.
        logCaptured(pkg, title, text, filterId = null, action = null)
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

    /** Append to the in-app history, deduping the rapid identical re-posts some apps make. */
    private fun logCaptured(
        pkg: String,
        title: String,
        text: String,
        filterId: Long?,
        action: NotifActionType?,
    ) {
        val fingerprint = pkg to "$title\n$text"
        if (fingerprint == lastSeen) return
        lastSeen = fingerprint
        val entity = CapturedNotificationEntity(
            packageName = pkg,
            title = title,
            text = text,
            postedAtMillis = System.currentTimeMillis(),
            filterId = filterId,
            actionApplied = action?.ordinal ?: -1,
        )
        scope.launch {
            runCatching {
                repository.logCaptured(entity)
                filterId?.let { repository.incrementFilterHit(it) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
