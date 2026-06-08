package com.customblocker.service.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device-admin receiver for Strict Mode L2 (PLAN.md §11). While this admin is active, Android
 * refuses to uninstall the app — the user must first deactivate it, which our UI gates behind the
 * unlock condition. We request no special policies; merely being an active admin blocks uninstall.
 */
class BlockerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling this removes Custom Blocker's uninstall protection."

    companion object {
        fun component(context: Context) = ComponentName(context, BlockerDeviceAdminReceiver::class.java)

        private fun dpm(context: Context) =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        fun isActive(context: Context): Boolean = dpm(context).isAdminActive(component(context))

        /** Intent that opens the system "activate device admin?" screen. */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Allows Custom Blocker to prevent itself from being uninstalled while Strict Mode is on.",
                )

        /** An app may deactivate its own admin programmatically; we gate this behind unlock. */
        fun deactivate(context: Context) {
            val d = dpm(context)
            if (d.isAdminActive(component(context))) d.removeActiveAdmin(component(context))
        }
    }
}
