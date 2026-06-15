package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotificationFilterEngineTest {

    private val SNAP = "com.snapchat.android"

    private fun engine(vararg rules: NotifFilterRule) =
        NotificationFilterEngine().apply { update(rules.toList()) }

    private fun rule(
        id: Long = 1,
        packages: Set<String> = setOf(SNAP),
        regex: String = "",
        target: NotifMatchTarget = NotifMatchTarget.EITHER,
        secondary: String = "",
        action: NotifActionType = NotifActionType.DISMISS,
        batchMinutes: Int = 60,
        releaseTimes: List<Int> = emptyList(),
        startMinute: Int = 0,
        endMinute: Int = 1439,
        dayFlags: Int = DayFlags.ALL,
        priority: Int = 0,
        enabled: Boolean = true,
    ) = NotifFilterRule(
        id, "test", enabled, packages, regex, target, secondary, action,
        batchMinutes, releaseTimes, startMinute, endMinute, dayFlags, priority,
    )

    private fun ctx(
        pkg: String = SNAP,
        title: String = "Alice",
        content: String = "sent you a snap",
        minute: Int = 10 * 60,
        dayBit: Int = DayFlags.MON,
        nowMillis: Long = 0L,
    ) = NotifContext(pkg, title, content, minute, dayBit, nowMillis)

    // --- app + text matching ---
    @Test fun noPattern_matchesAnyFromApp() {
        val o = engine(rule()).evaluate(ctx())
        assertTrue(o is NotifFilterOutcome.Dismiss)
    }

    @Test fun emptyPackages_matchesAnyApp() {
        val o = engine(rule(packages = emptySet())).evaluate(ctx(pkg = "com.whatever"))
        assertTrue(o is NotifFilterOutcome.Dismiss)
    }

    @Test fun wrongApp_noMatch() {
        assertEquals(NotifFilterOutcome.None, engine(rule()).evaluate(ctx(pkg = "com.other")))
    }

    @Test fun contentRegex_matchesAndMisses() {
        val r = rule(regex = "sent you a (snap|chat)", target = NotifMatchTarget.CONTENT)
        assertTrue(engine(r).evaluate(ctx(content = "sent you a snap")) is NotifFilterOutcome.Dismiss)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx(content = "is typing")))
    }

    @Test fun titleRegex_caseInsensitive() {
        val r = rule(regex = "alice", target = NotifMatchTarget.TITLE)
        assertTrue(engine(r).evaluate(ctx(title = "ALICE")) is NotifFilterOutcome.Dismiss)
    }

    @Test fun bothTarget_requiresTitleAndContent() {
        val r = rule(regex = "alice", target = NotifMatchTarget.BOTH, secondary = "snap")
        assertTrue(engine(r).evaluate(ctx(title = "Alice", content = "sent you a snap")) is NotifFilterOutcome.Dismiss)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx(title = "Bob", content = "sent you a snap")))
    }

    @Test fun invalidRegex_doesNotMatch() {
        val r = rule(regex = "(unclosed", target = NotifMatchTarget.CONTENT)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx()))
    }

    @Test fun disabledFilter_ignored() {
        assertEquals(NotifFilterOutcome.None, engine(rule(enabled = false)).evaluate(ctx()))
    }

    // --- schedule ---
    @Test fun outsideWindow_noMatch() {
        val r = rule(startMinute = 9 * 60, endMinute = 17 * 60)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx(minute = 20 * 60)))
    }

    @Test fun windowWrapsMidnight() {
        val r = rule(startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(engine(r).evaluate(ctx(minute = 23 * 60)) is NotifFilterOutcome.Dismiss)
        assertTrue(engine(r).evaluate(ctx(minute = 2 * 60)) is NotifFilterOutcome.Dismiss)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx(minute = 12 * 60)))
    }

    @Test fun wrongDay_noMatch() {
        val r = rule(dayFlags = DayFlags.SAT or DayFlags.SUN)
        assertEquals(NotifFilterOutcome.None, engine(r).evaluate(ctx(dayBit = DayFlags.MON)))
    }

    // --- priority ---
    @Test fun lowestPriorityWins() {
        val dismiss = rule(id = 1, action = NotifActionType.DISMISS, priority = 5)
        val catch = rule(id = 2, action = NotifActionType.CATCH, priority = 1)
        val o = engine(dismiss, catch).evaluate(ctx())
        assertEquals(2L, (o as NotifFilterOutcome.Catch).filterId)
    }

    // --- batch boundary ---
    @Test fun batch_snoozesToNextIntervalFromMidnight() {
        val zone = ZoneId.systemDefault()
        // 09:30 local on an arbitrary date.
        val now = java.time.LocalDate.of(2026, 6, 15).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val r = rule(action = NotifActionType.BATCH, batchMinutes = 120) // every 2h → next is 10:00
        val o = engine(r).evaluate(ctx(nowMillis = now)) as NotifFilterOutcome.Snooze
        val released = Instant.ofEpochMilli(o.untilMillis).atZone(zone)
        assertEquals(10, released.hour)
        assertEquals(0, released.minute)
        assertEquals(NotifActionType.BATCH, o.action)
    }

    // --- delay release times ---
    @Test fun delay_snoozesToNextReleaseTime() {
        val zone = ZoneId.systemDefault()
        val now = java.time.LocalDate.of(2026, 6, 15).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val r = rule(action = NotifActionType.DELAY, releaseTimes = listOf(13 * 60, 16 * 60)) // 13:00, 16:00
        val o = engine(r).evaluate(ctx(nowMillis = now)) as NotifFilterOutcome.Snooze
        val released = Instant.ofEpochMilli(o.untilMillis).atZone(zone)
        assertEquals(13, released.hour)
        assertEquals(0, released.minute)
    }

    @Test fun delay_afterLastTime_rollsToTomorrow() {
        val zone = ZoneId.systemDefault()
        val now = java.time.LocalDate.of(2026, 6, 15).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val r = rule(action = NotifActionType.DELAY, releaseTimes = listOf(13 * 60)) // only 13:00, already passed
        val o = engine(r).evaluate(ctx(nowMillis = now)) as NotifFilterOutcome.Snooze
        val released = Instant.ofEpochMilli(o.untilMillis).atZone(zone)
        assertEquals(16, released.dayOfMonth) // next day
        assertEquals(13, released.hour)
    }

    @Test fun delay_noReleaseTimes_fallsBackToCatch() {
        val r = rule(action = NotifActionType.DELAY, releaseTimes = emptyList())
        assertTrue(engine(r).evaluate(ctx()) is NotifFilterOutcome.Catch)
    }
}
