package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The website rules as the service applies them to whatever the window showed. */
class SiteVerdictTest {

    private val custom = setOf("blocked.example")
    private val presets = CompactDomainSet.of(setOf("preset-blocked.example"))
    private var webViewAsked = 0
    private var plainAsked = 0

    private fun bar(text: String, focused: Boolean = false, hint: Boolean = false) = UrlCandidate(
        text = text, host = UrlMatcher.hostOf(text), isAddressBar = true, focused = focused, showingHint = hint,
    )

    private fun plain(text: String) = UrlCandidate(
        text = text, host = UrlMatcher.hostOf(text), isAddressBar = false, focused = false, showingHint = false,
    )

    private fun judge(
        view: UrlCandidate?,
        mode: WebsiteMode,
        isBrowser: Boolean = true,
        webView: Boolean = false,
        plainHost: String? = null,
        custom: Set<String> = this.custom,
        presets: CompactDomainSet = this.presets,
        presetsPending: Boolean = false,
    ): SiteBlock? = SiteVerdict.judge(
        view, isBrowser, mode, custom, presets,
        webViewShown = { webViewAsked++; webView },
        plainHost = { plainAsked++; plainHost },
        presetsPending = presetsPending,
    )

    @Test
    fun `allowlist fails closed while its presets are still loading`() {
        val none = emptySet<String>()
        assertNotNull(judge(bar("https://x.example/"), WebsiteMode.ALLOWLIST, custom = none, presets = CompactDomainSet.EMPTY, presetsPending = true))
        assertNotNull(judge(null, WebsiteMode.ALLOWLIST, webView = true, custom = none, presets = CompactDomainSet.EMPTY, presetsPending = true))
        // The browser's own new-tab page is still let through.
        assertNull(judge(bar("chrome://newtab/"), WebsiteMode.ALLOWLIST, custom = none, presets = CompactDomainSet.EMPTY, presetsPending = true))
        // Blocklist mode has nothing to fail closed on.
        assertNull(judge(bar("https://x.example/"), WebsiteMode.BLOCKLIST, custom = none, presets = CompactDomainSet.EMPTY, presetsPending = true))
    }

    // --------------------------------------------------------- blocklist mode

    @Test
    fun `blocklist blocks custom and preset hosts in the address bar`() {
        assertEquals("blocked.example", judge(bar("https://blocked.example/a"), WebsiteMode.BLOCKLIST)?.label)
        assertEquals("m.preset-blocked.example", judge(bar("https://m.preset-blocked.example/"), WebsiteMode.BLOCKLIST)?.key)
        assertNull(judge(bar("https://fine.example/"), WebsiteMode.BLOCKLIST))
    }

    @Test
    fun `blocklist trusts a bare name in ordinary text, because it can only block what was listed`() {
        assertNotNull(judge(plain("blocked.example"), WebsiteMode.BLOCKLIST, isBrowser = false))
    }

    @Test
    fun `a clean address bar is followed by a look for another page's host outside the web content`() {
        val block = judge(bar("https://fine.example/"), WebsiteMode.BLOCKLIST, plainHost = "blocked.example")
        assertEquals("blocked.example", block?.key)
        assertEquals(1, plainAsked)
        // The same host in the bar and in the sweep is one page, not two.
        assertNull(judge(bar("https://fine.example/"), WebsiteMode.BLOCKLIST, plainHost = "fine.example"))
    }

    @Test
    fun `the sweep is not asked for when the bar already blocks, outside a browser, or in allowlist mode`() {
        plainAsked = 0
        judge(bar("https://blocked.example/"), WebsiteMode.BLOCKLIST)
        judge(plain("fine.example"), WebsiteMode.BLOCKLIST, isBrowser = false)
        judge(bar("https://allowed.example/"), WebsiteMode.ALLOWLIST, custom = setOf("allowed.example"))
        assertEquals(0, plainAsked)
    }

    @Test
    fun `a focused address bar is not judged while the user types`() {
        assertNull(judge(bar("https://blocked.exam", focused = true), WebsiteMode.BLOCKLIST))
        assertNull(judge(bar("anything", focused = true), WebsiteMode.ALLOWLIST))
        assertEquals(0, plainAsked)
    }

    @Test
    fun `a hint in the field is a placeholder, not a page`() {
        assertNull(judge(bar("blocked.example", hint = true), WebsiteMode.BLOCKLIST))
        assertNull(judge(bar("data:hint", hint = true), WebsiteMode.ALLOWLIST))
    }

    @Test
    fun `nothing is enforced with no rules at all, in either mode`() {
        assertNull(judge(bar("https://x.example/"), WebsiteMode.BLOCKLIST, custom = emptySet(), presets = CompactDomainSet.EMPTY))
        assertNull(judge(bar("https://x.example/"), WebsiteMode.ALLOWLIST, custom = emptySet(), presets = CompactDomainSet.EMPTY))
        assertNull(judge(null, WebsiteMode.ALLOWLIST, webView = true, custom = emptySet(), presets = CompactDomainSet.EMPTY))
    }

    // --------------------------------------------------------- allowlist mode

    @Test
    fun `allowlist lets listed hosts through and blocks the rest`() {
        val allowed = setOf("allowed.example")
        assertNull(judge(bar("https://www.allowed.example/p"), WebsiteMode.ALLOWLIST, custom = allowed))
        assertEquals("other.example", judge(bar("https://other.example/"), WebsiteMode.ALLOWLIST, custom = allowed)?.label)
    }

    @Test
    fun `allowlist counts a preset as allowed`() {
        assertNull(judge(bar("https://preset-blocked.example/"), WebsiteMode.ALLOWLIST, custom = emptySet()))
        assertNotNull(judge(bar("https://other.example/"), WebsiteMode.ALLOWLIST, custom = emptySet()))
    }

    @Test
    fun `allowlist fails closed on a page with no host in a real address bar`() {
        val allowed = setOf("allowed.example")
        listOf("data:text/html,<h1>hi</h1>", "file:///sdcard/x.html", "view-source:chrome://flags", "chrome://flags", "javascript:alert(1)")
            .forEach { assertNotNull(it, judge(bar(it), WebsiteMode.ALLOWLIST, custom = allowed)) }
        assertEquals("page", judge(bar("data:text/html,x"), WebsiteMode.ALLOWLIST, custom = allowed)?.label)
    }

    @Test
    fun `allowlist lets the browser's own new-tab states and search queries through`() {
        val allowed = setOf("allowed.example")
        listOf("", "about:blank", "chrome://newtab/", "how to focus better", "Search or type URL")
            .forEach { assertNull(it, judge(bar(it), WebsiteMode.ALLOWLIST, custom = allowed)) }
    }

    @Test
    fun `allowlist does not trust a bare name outside the address bar unless it looks like a url`() {
        val allowed = setOf("allowed.example")
        assertNull(judge(plain("Booking.com"), WebsiteMode.ALLOWLIST, isBrowser = false, custom = allowed))
        assertNotNull(judge(plain("https://booking.com/x"), WebsiteMode.ALLOWLIST, isBrowser = false, custom = allowed))
    }

    // ------------------------------------------- a browser with no address bar

    @Test
    fun `allowlist fails closed on a browser window that shows a page but has no address bar`() {
        val block = judge(null, WebsiteMode.ALLOWLIST, isBrowser = true, webView = true, custom = setOf("allowed.example"))
        assertEquals("no-address-bar", block?.key)
        assertEquals("page", block?.label)
    }

    @Test
    fun `no address bar is only an offence in a browser, in allowlist mode, with a page showing`() {
        val allowed = setOf("allowed.example")
        assertNull(judge(null, WebsiteMode.ALLOWLIST, isBrowser = true, webView = false, custom = allowed))
        assertNull(judge(null, WebsiteMode.ALLOWLIST, isBrowser = false, webView = true, custom = allowed))
        assertNull(judge(null, WebsiteMode.BLOCKLIST, isBrowser = true, webView = true))
    }

    @Test
    fun `the web view question is only asked when there is no candidate`() {
        webViewAsked = 0
        judge(bar("https://x.example/"), WebsiteMode.BLOCKLIST)
        assertEquals(0, webViewAsked)
        judge(null, WebsiteMode.ALLOWLIST, webView = true, custom = setOf("allowed.example"))
        assertEquals(1, webViewAsked)
    }
}
