package com.customblocker.domain.engine

import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of evaluating a foreground app against the active rules. */
sealed interface BlockDecision {
    data class Block(val packageName: String, val reason: String) : BlockDecision
    data object Allow : BlockDecision
}

enum class BlockingMode { BLOCKLIST, ALLOWLIST }

/**
 * Pure decision core (PLAN.md §7). Holds the current set of profile rules; on each foreground
 * event the accessibility service supplies a [BlockContext] and asks for a [decide]. A package is
 * blocked if ANY currently-active profile blocks it.
 */
@Singleton
class BlockingEngine @Inject constructor() {

    /** Volatile: written from the DB-sync coroutine, read on the accessibility thread. */
    @Volatile
    var profiles: List<ProfileRule> = emptyList()
        private set

    /** Packages we must never block (would soft-brick the device / lock the user out). */
    private val neverBlock: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
    )

    /**
     * Our own package — set once at startup. Never blocked in any mode (incl. allow-list), so the
     * user can always reach the app to change or disable a profile and can't lock themselves out.
     */
    @Volatile
    var selfPackage: String? = null

    /**
     * System packages resolved at runtime that must never be blocked, even by an allow-list:
     * the active IME(s) (keyboard) and the current home launcher. Without this an allow-list that
     * omits the keyboard would block it, leaving the user unable to type. Set from the Android side.
     */
    @Volatile
    var systemPackages: Set<String> = emptySet()

    private fun isNeverBlocked(packageName: String): Boolean =
        packageName in neverBlock || packageName == selfPackage || packageName in systemPackages

    fun update(newProfiles: List<ProfileRule>) {
        profiles = newProfiles
    }

    fun decide(packageName: String?, ctx: BlockContext): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (isNeverBlocked(packageName)) return BlockDecision.Allow

        for (rule in profiles) {
            if (!rule.blockLaunch) continue
            if (!ConditionEvaluator.isActive(rule, ctx)) continue
            if (rule.targets(packageName)) {
                val how = if (rule.mode == BlockingMode.BLOCKLIST) "block list" else "allow list"
                return BlockDecision.Block(packageName, "by \"${rule.title}\" ($how)")
            }
        }
        return BlockDecision.Allow
    }

    /**
     * Whether notifications from [packageName] should be suppressed right now: any active profile
     * that targets the package AND has notification-blocking enabled (PLAN.md §4). Independent of
     * [decide]'s launch blocking, so a profile can suppress notifications without ejecting.
     */
    fun shouldBlockNotification(packageName: String?, ctx: BlockContext): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (isNeverBlocked(packageName)) return false
        return profiles.any { rule ->
            rule.blockNotifications && rule.targets(packageName) && ConditionEvaluator.isActive(rule, ctx)
        }
    }

    /**
     * Website decision (PLAN.md §10). Called when [packageName] is a browser. [url] is the raw
     * URL-bar text (null/blank when it couldn't be read). A site is blocked if ANY active profile
     * has a matching website rule; if the URL is unreadable, an active profile with
     * [ProfileRule.blockUnsupportedBrowsers] blocks the whole browser.
     */
    fun decideUrl(packageName: String?, url: String?, ctx: BlockContext): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (isNeverBlocked(packageName)) return BlockDecision.Allow

        val readable = !url.isNullOrBlank()
        for (rule in profiles) {
            if (rule.websites.isEmpty() && !rule.blockUnsupportedBrowsers) continue
            if (!ConditionEvaluator.isActive(rule, ctx)) continue
            if (readable) {
                val hit = rule.websites.firstOrNull { it.matches(url!!) }
                if (hit != null) {
                    return BlockDecision.Block(packageName, "site \"${hit.value}\" by \"${rule.title}\"")
                }
            } else if (rule.blockUnsupportedBrowsers) {
                return BlockDecision.Block(packageName, "unreadable URL by \"${rule.title}\"")
            }
        }
        return BlockDecision.Allow
    }
}
