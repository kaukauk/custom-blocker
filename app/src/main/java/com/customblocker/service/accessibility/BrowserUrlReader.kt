package com.customblocker.service.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the address-bar text of known browsers from the accessibility tree (PLAN.md §10).
 *
 * Each supported browser exposes its URL bar under a stable view id (e.g.
 * `com.android.chrome:id/url_bar`). These could live in a DB table so new browsers can be
 * added without an app update; for v1 we hardcode the common ones and treat anything else as an
 * "unsupported browser" (which the block-unsupported-browsers flag can then act on).
 */
object BrowserUrlReader {

    /** package → URL-bar view id. Add entries here to support more browsers. */
    private val URL_BAR_IDS: Map<String, String> = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.chrome.beta" to "com.chrome.beta:id/url_bar",
        "com.chrome.dev" to "com.chrome.dev:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
        "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
    )

    fun isKnownBrowser(pkg: String): Boolean = pkg in URL_BAR_IDS

    /**
     * Returns the URL-bar text for [pkg] from [root], or null if it can't be read (unknown browser,
     * node absent, or empty). The caller decides what to do with an unreadable URL.
     */
    fun readUrl(pkg: String, root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val viewId = URL_BAR_IDS[pkg] ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        for (node in nodes) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
}
