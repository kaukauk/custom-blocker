package com.customblocker.domain.engine

import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of evaluating a foreground app/URL against the active rules. */
sealed interface BlockDecision {
    data class Block(val packageName: String, val reason: String) : BlockDecision
    data object Allow : BlockDecision
}

enum class BlockingMode { BLOCKLIST, ALLOWLIST }

/**
 * Immutable snapshot the engine evaluates against. Produced from the DB (Phase 2) and, later,
 * narrowed by active conditions (Phase 3). Swapped atomically via [BlockingEngine.update].
 */
data class EngineSnapshot(
    val enabled: Boolean = false,
    val mode: BlockingMode = BlockingMode.BLOCKLIST,
    val apps: Set<String> = emptySet(),
)

/**
 * Pure decision core — NO Android imports, so it unit-tests on the JVM (PLAN.md §7).
 * The `decide` contract is stable; only what feeds [snapshot] grows in later phases.
 */
@Singleton
class BlockingEngine @Inject constructor() {

    /** Volatile: written from the DB-sync coroutine, read on the accessibility thread. */
    @Volatile
    var snapshot: EngineSnapshot = EngineSnapshot()
        private set

    /** Packages we must never block (would soft-brick the device / lock the user out). */
    private val neverBlock: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
    )

    fun update(newSnapshot: EngineSnapshot) {
        snapshot = newSnapshot
    }

    fun decide(packageName: String?): BlockDecision {
        val s = snapshot
        if (!s.enabled) return BlockDecision.Allow
        if (packageName.isNullOrBlank()) return BlockDecision.Allow
        if (packageName in neverBlock) return BlockDecision.Allow

        val blocked = when (s.mode) {
            BlockingMode.BLOCKLIST -> packageName in s.apps
            BlockingMode.ALLOWLIST -> packageName !in s.apps
        }
        if (!blocked) return BlockDecision.Allow
        val reason = if (s.mode == BlockingMode.BLOCKLIST) "in block list" else "not in allow list"
        return BlockDecision.Block(packageName, reason)
    }
}
