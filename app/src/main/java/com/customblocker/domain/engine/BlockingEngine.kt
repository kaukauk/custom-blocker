package com.customblocker.domain.engine

import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of evaluating a foreground app/URL against the active rules. */
sealed interface BlockDecision {
    data class Block(val packageName: String, val reason: String) : BlockDecision
    data object Allow : BlockDecision
}

/**
 * Pure decision core — NO Android imports, so it unit-tests on the JVM (PLAN.md §7).
 *
 * Phase 1: a hardcoded/settable block set is enough to prove the accessibility + overlay
 * mechanism end to end. Phase 2 replaces [blockedPackages] with a DB-backed snapshot, and
 * Phase 3 layers the condition (time/wifi/location/...) evaluation on top before this returns
 * Block. The interface (`decide`) stays the same.
 */
@Singleton
class BlockingEngine @Inject constructor() {

    /** Packages currently subject to blocking. Volatile: written from UI/IPC, read on the a11y thread. */
    @Volatile
    var blockedPackages: Set<String> = DEFAULT_BLOCKED

    /** Packages we must never block (would soft-brick the device / block ourselves). */
    private val neverBlock: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
    )

    fun decide(packageName: String?): BlockDecision {
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (packageName in neverBlock) return BlockDecision.Allow
        if (packageName in blockedPackages) {
            return BlockDecision.Block(packageName, "in block list")
        }
        return BlockDecision.Allow
    }

    companion object {
        /** Seeded for the Phase 1 demo; Chrome is the canonical test target from PLAN.md §17. */
        val DEFAULT_BLOCKED: Set<String> = setOf("com.android.chrome")
    }
}
