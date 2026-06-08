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

    fun update(newProfiles: List<ProfileRule>) {
        profiles = newProfiles
    }

    fun decide(packageName: String?, ctx: BlockContext): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (packageName in neverBlock) return BlockDecision.Allow

        for (rule in profiles) {
            if (!ConditionEvaluator.isActive(rule, ctx)) continue
            val blocked = when (rule.mode) {
                BlockingMode.BLOCKLIST -> packageName in rule.apps
                BlockingMode.ALLOWLIST -> packageName !in rule.apps
            }
            if (blocked) {
                val how = if (rule.mode == BlockingMode.BLOCKLIST) "block list" else "allow list"
                return BlockDecision.Block(packageName, "by \"${rule.title}\" ($how)")
            }
        }
        return BlockDecision.Allow
    }
}
