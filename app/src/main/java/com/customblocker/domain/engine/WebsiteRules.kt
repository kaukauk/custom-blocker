package com.customblocker.domain.engine

/**
 * Pure website/keyword matching (PLAN.md §10). No Android imports → JVM-unit-testable.
 *
 * The accessibility service reads a browser's URL-bar text and hands the engine whatever it found
 * (a bare host like "youtube.com", a full URL, or even a partial query). [UrlMatcher.host] extracts
 * a best-effort host; [WebsiteRule.matches] decides whether a given URL/host hits the rule.
 */
enum class UrlMatchType {
    /** Host equals the value OR is a subdomain of it (youtube.com also blocks m.youtube.com). */
    DOMAIN,

    /** The value appears anywhere in the full URL string (case-insensitive substring). */
    KEYWORD,

    /** The full URL equals the value exactly. */
    EXACT,
}

/** One blocked website/keyword entry within a profile. [value] is stored already normalized. */
data class WebsiteRule(val value: String, val matchType: UrlMatchType) {

    fun matches(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty() || value.isEmpty()) return false
        val lurl = url.lowercase()
        return when (matchType) {
            UrlMatchType.KEYWORD -> lurl.contains(value)
            UrlMatchType.EXACT -> lurl == value || UrlMatcher.stripScheme(lurl) == value
            UrlMatchType.DOMAIN -> {
                val host = UrlMatcher.host(url) ?: return false
                host == value || host.endsWith(".$value")
            }
        }
    }
}

object UrlMatcher {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Drop a leading scheme ("https://") if present. */
    fun stripScheme(url: String): String = url.replace(SCHEME, "")

    /**
     * Best-effort host extraction from URL-bar text. Handles bare hosts ("youtube.com"), full URLs
     * ("https://m.youtube.com/watch?v=.."), and a leading "www." prefix. Returns null when the text
     * doesn't look like a host (e.g. a plain search query with spaces).
     */
    fun host(rawUrl: String): String? {
        var s = stripScheme(rawUrl.trim().lowercase())
        if (s.isEmpty()) return null
        // strip userinfo
        s = s.substringAfterLast('@')
        // host ends at the first '/', '?', '#', or ':'
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        if (s.isEmpty()) return null
        // must look like a domain: no spaces, at least one dot, valid-ish chars
        if (s.contains(' ')) return null
        if (!s.contains('.')) return null
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return s
    }

    /** Normalize a user-entered value for storage given its match type. */
    fun normalize(value: String, matchType: UrlMatchType): String {
        val v = value.trim().lowercase()
        return when (matchType) {
            UrlMatchType.KEYWORD -> v
            UrlMatchType.EXACT -> stripScheme(v)
            UrlMatchType.DOMAIN -> host(v) ?: stripScheme(v).substringBefore('/').removePrefix("www.")
        }
    }
}
