package com.customblocker.service.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.customblocker.MainActivity
import com.customblocker.R

/**
 * The persistent "blocker is active" notification (PLAN.md §12). Hosting it on a foreground service
 * raises the process's priority so the system is far less likely to kill it under memory pressure,
 * and it gives the user a visible, tappable indicator.
 */
object StatusNotification {

    const val CHANNEL_ID = "blocker_status"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Blocking status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows that Custom Blocker is active and protecting your focus."
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context): Notification {
        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Custom Blocker active")
            .setContentText("Tap to manage your blocking profiles.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }
}
