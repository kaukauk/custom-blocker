package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingEngineTest {

    private fun engine(snapshot: EngineSnapshot) = BlockingEngine().apply { update(snapshot) }

    private fun blocklist(vararg apps: String, enabled: Boolean = true) =
        EngineSnapshot(enabled = enabled, mode = BlockingMode.BLOCKLIST, apps = apps.toSet())

    private fun allowlist(vararg apps: String, enabled: Boolean = true) =
        EngineSnapshot(enabled = enabled, mode = BlockingMode.ALLOWLIST, apps = apps.toSet())

    @Test
    fun blocklist_blocksListedPackage() {
        val d = engine(blocklist("com.android.chrome")).decide("com.android.chrome")
        assertTrue(d is BlockDecision.Block)
        assertEquals("com.android.chrome", (d as BlockDecision.Block).packageName)
    }

    @Test
    fun blocklist_allowsUnlistedPackage() {
        assertEquals(BlockDecision.Allow, engine(blocklist("com.android.chrome")).decide("com.foo.bar"))
    }

    @Test
    fun allowlist_blocksUnlistedPackage() {
        val d = engine(allowlist("com.foo.bar")).decide("com.android.chrome")
        assertTrue(d is BlockDecision.Block)
    }

    @Test
    fun allowlist_allowsListedPackage() {
        assertEquals(BlockDecision.Allow, engine(allowlist("com.foo.bar")).decide("com.foo.bar"))
    }

    @Test
    fun disabledProfile_allowsEverything() {
        assertEquals(BlockDecision.Allow, engine(blocklist("com.android.chrome", enabled = false)).decide("com.android.chrome"))
    }

    @Test
    fun allowsNullOrBlankPackage() {
        val e = engine(blocklist("com.android.chrome"))
        assertEquals(BlockDecision.Allow, e.decide(null))
        assertEquals(BlockDecision.Allow, e.decide(""))
        assertEquals(BlockDecision.Allow, e.decide("   "))
    }

    @Test
    fun neverBlocksSystemUiOrLauncher_inBothModes() {
        assertEquals(BlockDecision.Allow, engine(blocklist("com.android.systemui")).decide("com.android.systemui"))
        // allow-list with an empty-ish set would otherwise block the launcher.
        assertEquals(BlockDecision.Allow, engine(allowlist("com.foo.bar")).decide("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun emptyEnabledBlocklist_allowsEverything() {
        assertEquals(BlockDecision.Allow, engine(blocklist()).decide("com.android.chrome"))
    }
}
