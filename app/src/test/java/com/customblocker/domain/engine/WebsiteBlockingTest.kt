package com.customblocker.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteBlockingTest {

    private val CHROME = "com.android.chrome"

    private fun engine(vararg rules: ProfileRule) = BlockingEngine().apply { update(rules.toList()) }

    private fun ctx(charging: Boolean = false) =
        BlockContext(DayFlags.MON, 10 * 60, null, charging, emptySet(), emptyMap(), emptyMap())

    private fun rule(
        mask: Int = 0,
        websites: List<WebsiteRule> = emptyList(),
        blockUnsupported: Boolean = false,
    ) = ProfileRule(
        1, "Test", true, BlockingMode.BLOCKLIST, emptySet(), mask, DayFlags.ALL,
        emptyList(), emptySet(), emptyList(), null, null, websites, blockUnsupported,
    )

    private fun blocked(d: BlockDecision) = d is BlockDecision.Block

    // --- host extraction ---
    @Test fun host_bareDomain() = assertEquals("youtube.com", UrlMatcher.host("youtube.com"))
    @Test fun host_fullUrl() = assertEquals("youtube.com", UrlMatcher.host("https://youtube.com/watch?v=x"))
    @Test fun host_stripsWww() = assertEquals("google.com", UrlMatcher.host("https://www.google.com/search?q=a"))
    @Test fun host_keepsSubdomain() = assertEquals("m.youtube.com", UrlMatcher.host("https://m.youtube.com/feed"))
    @Test fun host_rejectsSearchQuery() = assertNull(UrlMatcher.host("how to focus better"))
    @Test fun host_rejectsNoDot() = assertNull(UrlMatcher.host("localhost"))

    // --- DOMAIN matching (incl. subdomains) ---
    @Test fun domain_matchesExactAndSubdomain() {
        val w = WebsiteRule("youtube.com", UrlMatchType.DOMAIN)
        assertTrue(w.matches("youtube.com"))
        assertTrue(w.matches("https://www.youtube.com/feed"))
        assertTrue(w.matches("https://m.youtube.com/watch?v=x"))
        assertFalse(w.matches("https://notyoutube.com"))
        assertFalse(w.matches("https://google.com"))
    }

    // --- KEYWORD matching (substring anywhere) ---
    @Test fun keyword_matchesAnywhere() {
        val w = WebsiteRule("casino", UrlMatchType.KEYWORD)
        assertTrue(w.matches("https://example.com/online-casino-games"))
        assertTrue(w.matches("https://google.com/search?q=best+casino"))
        assertFalse(w.matches("https://example.com/poker"))
    }

    // --- EXACT matching ---
    @Test fun exact_matchesWholeUrlSchemeInsensitive() {
        val w = WebsiteRule("example.com/page", UrlMatchType.EXACT)
        assertTrue(w.matches("https://example.com/page"))
        assertTrue(w.matches("example.com/page"))
        assertFalse(w.matches("https://example.com/page/other"))
    }

    // --- engine.decideUrl ---
    @Test fun decideUrl_blocksMatchingSite_whenActive() {
        val r = rule(websites = listOf(WebsiteRule("youtube.com", UrlMatchType.DOMAIN)))
        assertTrue(blocked(engine(r).decideUrl(CHROME, "https://www.youtube.com", ctx())))
        assertEquals(BlockDecision.Allow, engine(r).decideUrl(CHROME, "https://google.com", ctx()))
    }

    @Test fun decideUrl_respectsConditions() {
        val r = rule(mask = ConditionTypes.CHARGER, websites = listOf(WebsiteRule("youtube.com", UrlMatchType.DOMAIN)))
        assertEquals(BlockDecision.Allow, engine(r).decideUrl(CHROME, "https://youtube.com", ctx(charging = false)))
        assertTrue(blocked(engine(r).decideUrl(CHROME, "https://youtube.com", ctx(charging = true))))
    }

    @Test fun decideUrl_blockUnsupportedBrowsers_blocksWhenUnreadable() {
        val r = rule(websites = listOf(WebsiteRule("youtube.com", UrlMatchType.DOMAIN)), blockUnsupported = true)
        assertTrue(blocked(engine(r).decideUrl(CHROME, null, ctx())))
        assertTrue(blocked(engine(r).decideUrl(CHROME, "  ", ctx())))
    }

    @Test fun decideUrl_unreadable_allowsWhenFlagOff() {
        val r = rule(websites = listOf(WebsiteRule("youtube.com", UrlMatchType.DOMAIN)), blockUnsupported = false)
        assertEquals(BlockDecision.Allow, engine(r).decideUrl(CHROME, null, ctx()))
    }

    @Test fun decideUrl_neverBlocksNonBrowserGuards() {
        val r = rule(websites = listOf(WebsiteRule("youtube.com", UrlMatchType.DOMAIN)), blockUnsupported = true)
        assertEquals(BlockDecision.Allow, engine(r).decideUrl(null, null, ctx()))
        assertEquals(BlockDecision.Allow, engine(r).decideUrl("com.android.systemui", null, ctx()))
    }

    @Test fun normalize_domainStripsSchemeAndWww() {
        assertEquals("youtube.com", UrlMatcher.normalize("https://www.YouTube.com/feed", UrlMatchType.DOMAIN))
        assertEquals("casino", UrlMatcher.normalize("  Casino ", UrlMatchType.KEYWORD))
    }
}
