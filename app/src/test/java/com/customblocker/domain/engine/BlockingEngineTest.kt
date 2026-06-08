package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingEngineTest {

    private val CHROME = "com.android.chrome"

    private fun engine(vararg rules: ProfileRule) = BlockingEngine().apply { update(rules.toList()) }

    private fun ctx(
        dayBit: Int = DayFlags.MON,
        minute: Int = 10 * 60,
        wifi: String? = null,
        charging: Boolean = false,
        inside: Set<String> = emptySet(),
        used: Map<Long, Int> = emptyMap(),
        launches: Map<Long, Int> = emptyMap(),
    ) = BlockContext(dayBit, minute, wifi, charging, inside, used, launches)

    private fun rule(
        apps: Set<String> = setOf(CHROME),
        enabled: Boolean = true,
        mode: BlockingMode = BlockingMode.BLOCKLIST,
        mask: Int = 0,
        dayFlags: Int = DayFlags.ALL,
        windows: List<TimeWindow> = emptyList(),
        ssids: Set<String> = emptySet(),
        fences: List<GeofenceSpec> = emptyList(),
        usage: UsageLimitSpec? = null,
        launch: LaunchLimitSpec? = null,
        blockLaunch: Boolean = true,
        blockNotifications: Boolean = false,
    ) = ProfileRule(
        1, "Test", enabled, mode, apps, mask, dayFlags, windows, ssids, fences, usage, launch,
        blockLaunch = blockLaunch, blockNotifications = blockNotifications,
    )

    private fun blocked(d: BlockDecision) = d is BlockDecision.Block

    // --- no conditions (always active) ---
    @Test fun noConditions_blocklist_blocksListed() {
        assertTrue(blocked(engine(rule()).decide(CHROME, ctx())))
    }

    @Test fun disabledProfile_allows() {
        assertEquals(BlockDecision.Allow, engine(rule(enabled = false)).decide(CHROME, ctx()))
    }

    // --- TIME ---
    @Test fun time_insideWindowAndDay_blocks() {
        val r = rule(mask = ConditionTypes.TIME, dayFlags = DayFlags.MON, windows = listOf(TimeWindow(9 * 60, 17 * 60)))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(dayBit = DayFlags.MON, minute = 10 * 60))))
    }

    @Test fun time_outsideWindow_allows() {
        val r = rule(mask = ConditionTypes.TIME, windows = listOf(TimeWindow(9 * 60, 17 * 60)))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(minute = 18 * 60)))
    }

    @Test fun time_wrongDay_allows() {
        val r = rule(mask = ConditionTypes.TIME, dayFlags = DayFlags.SAT or DayFlags.SUN, windows = listOf(TimeWindow(0, 24 * 60 - 1)))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(dayBit = DayFlags.MON)))
    }

    @Test fun time_wrapsMidnight() {
        val r = rule(mask = ConditionTypes.TIME, windows = listOf(TimeWindow(22 * 60, 6 * 60)))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(minute = 23 * 60))))   // 23:00 inside
        assertTrue(blocked(engine(r).decide(CHROME, ctx(minute = 2 * 60))))    // 02:00 inside
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(minute = 12 * 60))) // noon outside
    }

    // --- WIFI ---
    @Test fun wifi_matchingSsid_blocks() {
        val r = rule(mask = ConditionTypes.WIFI, ssids = setOf("Office"))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(wifi = "Office"))))
    }

    @Test fun wifi_differentOrNoSsid_allows() {
        val r = rule(mask = ConditionTypes.WIFI, ssids = setOf("Office"))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(wifi = "Home")))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(wifi = null)))
    }

    // --- CHARGER ---
    @Test fun charger_charging_blocks_otherwiseAllows() {
        val r = rule(mask = ConditionTypes.CHARGER)
        assertTrue(blocked(engine(r).decide(CHROME, ctx(charging = true))))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(charging = false)))
    }

    // --- LOCATION ---
    @Test fun location_insideGeofence_blocks() {
        val r = rule(mask = ConditionTypes.LOCATION, fences = listOf(GeofenceSpec("home")))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(inside = setOf("home")))))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(inside = emptySet())))
    }

    @Test fun location_inverted_blocksWhenOutside() {
        val r = rule(mask = ConditionTypes.LOCATION, fences = listOf(GeofenceSpec("home", inverted = true)))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(inside = emptySet()))))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(inside = setOf("home"))))
    }

    // --- AND combination ---
    @Test fun and_bothConditionsRequired() {
        val r = rule(
            mask = ConditionTypes.TIME or ConditionTypes.WIFI,
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
            ssids = setOf("Office"),
        )
        assertTrue(blocked(engine(r).decide(CHROME, ctx(minute = 10 * 60, wifi = "Office"))))   // both
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(minute = 10 * 60, wifi = "Home"))) // wifi fails
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(minute = 20 * 60, wifi = "Office"))) // time fails
    }

    // --- multi-profile OR ---
    @Test fun multiProfile_anyActiveBlocks() {
        val timeProfile = rule(mask = ConditionTypes.TIME, windows = listOf(TimeWindow(0, 1))) // basically never
        val chargerProfile = rule(mask = ConditionTypes.CHARGER)
        assertTrue(blocked(engine(timeProfile, chargerProfile).decide(CHROME, ctx(minute = 12 * 60, charging = true))))
    }

    // --- usage limit ---
    @Test fun usageLimit_blocksOnceOverLimit() {
        val r = rule(mask = ConditionTypes.USAGE_LIMIT, usage = UsageLimitSpec(900, LimitPeriod.DAILY)) // 15 min
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(used = mapOf(1L to 600)))) // under
        assertTrue(blocked(engine(r).decide(CHROME, ctx(used = mapOf(1L to 900)))))               // at limit
        assertTrue(blocked(engine(r).decide(CHROME, ctx(used = mapOf(1L to 1200)))))              // over
    }

    // --- launch count ---
    @Test fun launchCount_blocksOnceOverLimit() {
        val r = rule(mask = ConditionTypes.LAUNCH_COUNT, launch = LaunchLimitSpec(3, LimitPeriod.DAILY))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(launches = mapOf(1L to 2))))
        assertTrue(blocked(engine(r).decide(CHROME, ctx(launches = mapOf(1L to 3)))))
    }

    @Test fun usageLimit_andTime_bothRequired() {
        val r = rule(
            mask = ConditionTypes.TIME or ConditionTypes.USAGE_LIMIT,
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
            usage = UsageLimitSpec(300, LimitPeriod.DAILY),
        )
        // over limit but outside window -> allowed
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(minute = 20 * 60, used = mapOf(1L to 600))))
        // in window AND over limit -> blocked
        assertTrue(blocked(engine(r).decide(CHROME, ctx(minute = 10 * 60, used = mapOf(1L to 600)))))
    }

    // --- allow-list ---
    @Test fun allowlist_blocksUnlistedWhenActive() {
        val r = rule(apps = setOf("com.foo"), mode = BlockingMode.ALLOWLIST, mask = ConditionTypes.CHARGER)
        assertTrue(blocked(engine(r).decide(CHROME, ctx(charging = true))))
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx(charging = false))) // inactive
    }

    // --- guards ---
    @Test fun neverBlocksLauncherOrSystemUi() {
        val r = rule(apps = setOf("com.android.systemui"))
        assertEquals(BlockDecision.Allow, engine(r).decide("com.android.systemui", ctx()))
    }

    @Test fun neverBlocksSelf_evenInAllowlist() {
        val r = rule(apps = setOf("com.foo"), mode = BlockingMode.ALLOWLIST, mask = ConditionTypes.CHARGER)
        val e = engine(r).apply { selfPackage = "com.customblocker" }
        assertEquals(BlockDecision.Allow, e.decide("com.customblocker", ctx(charging = true)))
        // a different unlisted app is still blocked while active
        assertTrue(blocked(e.decide(CHROME, ctx(charging = true))))
    }

    @Test fun blockLaunchOff_doesNotEject() {
        val r = rule(blockLaunch = false)
        assertEquals(BlockDecision.Allow, engine(r).decide(CHROME, ctx()))
    }

    @Test fun notifications_suppressedOnlyWhenFlagAndActiveAndTargeted() {
        val r = rule(mask = ConditionTypes.CHARGER, blockLaunch = false, blockNotifications = true)
        val e = engine(r)
        assertTrue(e.shouldBlockNotification(CHROME, ctx(charging = true)))      // targeted + active
        assertEquals(false, e.shouldBlockNotification(CHROME, ctx(charging = false))) // inactive
        assertEquals(false, e.shouldBlockNotification("com.other", ctx(charging = true))) // not targeted
    }

    @Test fun notifications_offByDefault() {
        assertEquals(false, engine(rule()).shouldBlockNotification(CHROME, ctx()))
    }

    @Test fun allowsNullOrBlank() {
        assertEquals(BlockDecision.Allow, engine(rule()).decide(null, ctx()))
        assertEquals(BlockDecision.Allow, engine(rule()).decide("  ", ctx()))
    }
}
