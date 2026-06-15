package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPatternSuggesterTest {

    private val SNAP = "com.snapchat.android"

    private fun snap(name: String, body: String) = NotifSample(SNAP, name, body)

    // The classic case: title = a person's name (varies), content = "sent you a snap" (recurs).
    @Test fun findsRecurringContentPhrase() {
        val samples = listOf(
            snap("Alice", "sent you a snap"),
            snap("Bob", "sent you a snap"),
            snap("Carl", "sent you a chat"),
            snap("Dana", "sent you a snap"),
        )
        val out = NotificationPatternSuggester.suggest(samples, SNAP)
        val top = out.first()
        assertEquals(NotifMatchTarget.CONTENT, top.target)
        assertTrue("expected 'sent you a snap', got '${top.phrase}'", top.phrase.contains("sent you a snap"))
        assertEquals(3, top.matchCount)
    }

    // The varying name appears once each → must NOT be suggested.
    @Test fun ignoresOneOffNames() {
        val samples = listOf(snap("Alice", "sent you a snap"), snap("Bob", "sent you a snap"))
        val out = NotificationPatternSuggester.suggest(samples, SNAP)
        assertTrue(out.none { it.phrase == "alice" || it.phrase == "bob" })
    }

    // Suggested regex is a literal that actually matches the source text (case-insensitively).
    @Test fun suggestedRegexMatchesSource() {
        val samples = listOf(snap("Alice", "Sent you a Snap!"), snap("Bob", "Sent you a Snap!"))
        val out = NotificationPatternSuggester.suggest(samples, SNAP)
        assertTrue(out.isNotEmpty())
        val rx = Regex(out.first().regex, RegexOption.IGNORE_CASE)
        assertTrue(rx.containsMatchIn("Sent you a Snap!"))
    }

    @Test fun focusRestrictsToTappedNotification() {
        val samples = listOf(
            snap("Alice", "sent you a snap"),
            snap("Bob", "sent you a snap"),
            snap("Carl", "added you as a friend"),
            snap("Dana", "added you as a friend"),
        )
        val out = NotificationPatternSuggester.suggest(samples, SNAP, focus = snap("Eve", "added you as a friend"))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.phrase.contains("added") || it.phrase.contains("friend") })
        assertTrue(out.none { it.phrase.contains("snap") })
    }

    @Test fun otherAppsExcluded() {
        val samples = listOf(
            snap("Alice", "sent you a snap"),
            NotifSample("com.whatsapp", "Mom", "sent you a snap"),
        )
        // Only one Snapchat sample → below the recurrence threshold → no suggestions.
        assertTrue(NotificationPatternSuggester.suggest(samples, SNAP).isEmpty())
    }

    @Test fun escapesRegexMetacharacters() {
        val samples = listOf(
            NotifSample("x", "n", "promo: 50% off (today)"),
            NotifSample("x", "n", "promo: 50% off (today)"),
        )
        val out = NotificationPatternSuggester.suggest(samples, "x")
        assertTrue(out.isNotEmpty())
        // Whatever is suggested must compile and match the original.
        out.forEach { s ->
            val rx = Regex(s.regex, RegexOption.IGNORE_CASE)
            assertTrue(rx.containsMatchIn("promo: 50% off (today)"))
        }
    }
}
