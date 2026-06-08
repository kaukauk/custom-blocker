package com.customblocker.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.customblocker.service.accessibility.BlockerAccessibilityService

/** Checks + Settings deep-links for the two permissions the core engine needs (PLAN.md §14). */
object Permissions {

    /** Whether the app is exempt from Doze/battery optimization (improves background reliability). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Prompt the system dialog to exempt this app from battery optimization. */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val target = ComponentName(context, BlockerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Whether our NotificationListenerService is enabled (needed to suppress notifications). */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val pkg = context.packageName
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == pkg }
    }

    fun openNotificationAccessSettings(context: Context) {
        context.startActivity(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
