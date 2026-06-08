package com.customblocker.service.monitoring

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.customblocker.MainActivity
import com.customblocker.R
import com.customblocker.ui.onboarding.Permissions
import com.customblocker.util.L
import java.util.concurrent.TimeUnit

/**
 * Reliability watchdog (PLAN.md §12). Periodically checks that the accessibility service is still
 * enabled — the OS can revoke it, or the user may turn it off, silently disabling all blocking.
 * We can't re-enable it programmatically, so we post a notification prompting the user to fix it,
 * and clear that notification once it's healthy again.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java)
        if (Permissions.isAccessibilityEnabled(applicationContext)) {
            mgr.cancel(WATCHDOG_NOTIFICATION_ID)
        } else {
            L.w(L.FG, "watchdog: accessibility service is OFF — notifying user")
            StatusNotification.ensureChannel(applicationContext)
            mgr.notify(WATCHDOG_NOTIFICATION_ID, buildAlert())
        }
        return Result.success()
    }

    private fun buildAlert() = android.app.Notification.Builder(applicationContext, StatusNotification.CHANNEL_ID)
        .setContentTitle("Blocking is paused")
        .setContentText("Custom Blocker's accessibility access is off. Tap to re-enable.")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                applicationContext,
                1,
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val WATCHDOG_NOTIFICATION_ID = 1002
        private const val WORK_NAME = "blocker_watchdog"

        /** Schedule the periodic health check (idempotent). */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }
    }
}
