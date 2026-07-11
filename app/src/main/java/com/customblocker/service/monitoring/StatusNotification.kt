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

    /** Separate channel + id for permission reminders, so they don't disturb the status channel. */
    const val WARN_CHANNEL_ID = "blocker_warnings"
    const val WARN_NOTIFICATION_ID = 1002

    /**
     * Broadcast (to our own accessibility service) fired when the user taps the popout prompt to
     * grant notification access. Routing through our code — rather than opening Settings directly —
     * lets the service arm a scoped Strict-Mode exemption first, so the notification-access screen is
     * reachable even while Strict Mode would otherwise eject it.
     */
    const val ACTION_ENABLE_NOTIF_ACCESS = "com.customblocker.action.ENABLE_NOTIF_ACCESS"

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

    private fun ensureWarnChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(WARN_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(WARN_CHANNEL_ID, "Setup reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reminds you when a permission Custom Blocker relies on is switched off."
                    setShowBadge(true)
                },
            )
        }
    }

    /**
     * Post (or refresh) the contextual prompt shown while a picture-in-picture popout is on screen
     * but notification access is off — the one moment blocking a popout would matter and can't. Tapping
     * it (or its action) fires [ACTION_ENABLE_NOTIF_ACCESS] to our service, which arms the scoped
     * Strict-Mode exemption and opens the grant screen. Cleared via [clearPopoutPrompt] once access is
     * granted or the popout closes.
     */
    fun showPopoutPrompt(context: Context) {
        ensureWarnChannel(context)
        // Broadcast to our own service (NOT_EXPORTED, same package) so it can arm the exemption before
        // opening Settings — a direct Settings deep-link would just be ejected under Strict Mode.
        val enableIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(ACTION_ENABLE_NOTIF_ACCESS).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = "A video is playing in a popout (picture-in-picture) window. Turn on notification " +
            "access and Custom Blocker can stop it the moment it hits its limit — otherwise it keeps " +
            "playing. Works even while Strict Mode is on."
        val notification = Notification.Builder(context, WARN_CHANNEL_ID)
            .setContentTitle("Block the popout player?")
            .setContentText("Turn on notification access to stop popout videos at their limit.")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(enableIntent)
            .addAction(Notification.Action.Builder(null, "Turn on notification access", enableIntent).build())
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(WARN_NOTIFICATION_ID, notification)
    }

    fun clearPopoutPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(WARN_NOTIFICATION_ID)
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
