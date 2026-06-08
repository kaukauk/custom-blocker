package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingEngineTest {

    private fun engine(blocked: Set<String>) = BlockingEngine().apply { blockedPackages = blocked }

    @Test
    fun blocksPackageInBlockList() {
        val d = engine(setOf("com.android.chrome")).decide("com.android.chrome")
        assertTrue(d is BlockDecision.Block)
        assertEquals("com.android.chrome", (d as BlockDecision.Block).packageName)
    }

    @Test
    fun allowsPackageNotInBlockList() {
        assertEquals(BlockDecision.Allow, engine(setOf("com.android.chrome")).decide("com.foo.bar"))
    }

    @Test
    fun allowsNullOrBlankPackage() {
        val e = engine(setOf("com.android.chrome"))
        assertEquals(BlockDecision.Allow, e.decide(null))
        assertEquals(BlockDecision.Allow, e.decide(""))
        assertEquals(BlockDecision.Allow, e.decide("   "))
    }

    @Test
    fun neverBlocksSystemUiOrLauncher_evenIfListed() {
        val e = engine(setOf("com.android.systemui", "com.google.android.apps.nexuslauncher"))
        assertEquals(BlockDecision.Allow, e.decide("com.android.systemui"))
        assertEquals(BlockDecision.Allow, e.decide("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun emptyBlockListAllowsEverything() {
        assertEquals(BlockDecision.Allow, engine(emptySet()).decide("com.android.chrome"))
    }
}
