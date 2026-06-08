package com.customblocker.service.pkg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Auto-block newly-installed apps (PLAN.md §12). On a fresh package install (not an update), adds
 * the new launchable app to every enabled profile flagged "Auto-block new apps".
 *
 * IMPORTANT: ACTION_PACKAGE_ADDED is *not* exempt from the Android 8+ implicit-broadcast ban, so a
 * manifest-declared receiver never fires for it. This receiver must be **registered at runtime**
 * from a long-lived component (we register it from the Application; the process stays alive via the
 * foreground accessibility service).
 */
class PackageAddedReceiver(
    private val repository: BlockerRepository,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // update, not new install
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        if (context.packageManager.getLaunchIntentForPackage(pkg) == null) return // not user-launchable

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profiles = repository.profilesAutoAddingApps()
                profiles.forEach { repository.addApp(it.id, pkg) }
                if (profiles.isNotEmpty()) {
                    L.d(L.ENGINE, "auto-blocked new app $pkg in ${profiles.size} profile(s)")
                }
            } catch (t: Throwable) {
                L.e(L.ENGINE, "auto-block failed for $pkg", t)
            } finally {
                pending.finish()
            }
        }
    }
}
