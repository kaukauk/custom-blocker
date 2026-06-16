package com.customblocker.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.customblocker.service.PermissionReturn
import com.customblocker.service.accessibility.BlockerAccessibilityService
import com.customblocker.service.notifications.BlockerNotificationListener

/** Checks + Settings deep-links for the two permissions the core engine needs (PLAN.md §14). */
object Permissions {

    // Settings reuses a single task: launching a deep-link with only NEW_TASK can surface whatever
    // screen the user last left Settings on (e.g. they wandered off to another app's settings).
    // CLEAR_TASK resets that task to the screen we actually asked for.
    private const val FRESH_TASK = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    // Undocumented-but-stable AOSP/Pixel extras: highlight + scroll to a specific preference (by its
    // key) within a Settings list. We use it to point the user straight at our app's row.
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /** Adds the highlight-this-preference extras so Settings scrolls to and flashes [key]. */
    private fun Intent.highlight(key: String): Intent {
        putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
        putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        return this
    }

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
            ).addFlags(FRESH_TASK),
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
        PermissionReturn.arm()   // pop back to the app once access is toggled on
        val component = ComponentName(context, BlockerNotificationListener::class.java)
        // API 30+ can deep-link to OUR per-app notification-access screen (a single toggle) instead
        // of the long list. Fall back to the list (highlighting our row) on older devices.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .addFlags(FRESH_TASK)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component.flattenToString(),
                )
            if (runCatching { context.startActivity(detail) }.isSuccess) return
        }
        context.startActivity(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .addFlags(FRESH_TASK)
                .highlight(component.flattenToString()),
        )
    }

    fun openAccessibilitySettings(context: Context) {
        PermissionReturn.arm()   // pop back to the app once the service is enabled
        val key = ComponentName(context, BlockerAccessibilityService::class.java).flattenToString()
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(FRESH_TASK)
                .highlight(key),
        )
    }

    fun openOverlaySettings(context: Context) {
        // Overlay has no grant callback, so poll for it and pop the app back once it flips on.
        PermissionReturn.watchOverlayGrant(context)
        // The package: URI is the canonical way to land on OUR per-app overlay toggle. We use plain
        // NEW_TASK (not CLEAR_TASK) and no highlight extras: both can override the package routing on
        // some builds and bounce the user to the general list instead.
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The system battery-optimization list, where the exemption can be turned on or off. */
    fun openBatteryOptimizationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(FRESH_TASK)
                .highlight(context.packageName),
        )
    }
}
