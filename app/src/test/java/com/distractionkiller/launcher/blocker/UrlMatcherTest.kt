package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class UrlMatcherTest {

    @Test
    fun `host is extracted from the forms real address bars show`() {
        assertEquals("www.reddit.com", UrlMatcher.hostOf("https://www.reddit.com/r/all"))
        assertEquals("reddit.com", UrlMatcher.hostOf("reddit.com/r/all"))
        assertEquals("reddit.com", UrlMatcher.hostOf("reddit.com"))
        assertEquals("reddit.com", UrlMatcher.hostOf("  reddit.com  "))
        assertEquals("reddit.com", UrlMatcher.hostOf("http://reddit.com:8080/x?y=1#z"))
        assertEquals("reddit.com", UrlMatcher.hostOf("https://user:pw@reddit.com/"))
        assertEquals("reddit.com", UrlMatcher.hostOf("REDDIT.COM"))
        assertEquals("reddit.com", UrlMatcher.hostOf("reddit.com."))
        assertEquals("localhost", UrlMatcher.hostOf("localhost:3000/app"))
        assertEquals("[::1]", UrlMatcher.hostOf("http://[::1]:8080/"))
        assertEquals("192.168.1.1", UrlMatcher.hostOf("192.168.1.1:8080/admin"))
    }

    @Test
    fun `numbers and file names that contain dots are not hosts`() {
        assertNull(UrlMatcher.hostOf("4.7"))
        assertNull(UrlMatcher.hostOf("12.99"))
        assertNull(UrlMatcher.hostOf("v1.2.3"))
        assertNull(UrlMatcher.hostOf("1.2.3.4.5"))
        assertNull(UrlMatcher.hostOf("999.1.1.1"))
        // A file name still parses; the caller decides whether a bare name counts.
        assertEquals("invoice.pdf", UrlMatcher.hostOf("invoice.pdf"))
    }

    @Test
    fun `dotted text whose numbers overflow Int is not a host and does not throw`() {
        // Each of these used to reach String.toInt() and throw NumberFormatException,
        // which unwound the whole tree walk that called hostOf.
        assertNull(UrlMatcher.hostOf("1.2.3.99999999999"))
        assertNull(UrlMatcher.hostOf("99999999999.1.1.1"))
        assertNull(UrlMatcher.hostOf("1.2.3.2147483648")) // Int.MAX_VALUE + 1
        assertNull(UrlMatcher.hostOf("1.2.3.4294967296")) // 2^32, wraps to 0 in a naive parse
        assertNull(UrlMatcher.hostOf("http://1.2.3.99999999999/path"))
        assertNull(UrlMatcher.hostOf("1.2.3.99999999999:8080"))
        // The in-range boundary is unchanged.
        assertEquals("255.255.255.255", UrlMatcher.hostOf("255.255.255.255"))
        assertEquals("0.0.0.0", UrlMatcher.hostOf("0.0.0.0"))
        assertNull(UrlMatcher.hostOf("1.2.3.256"))
    }

    @Test
    fun `no input makes hostOf throw`() {
        // The scan calls hostOf from inside the tree walk, so one throw loses the whole window.
        val alphabet = "0123456789.:/@[]-_%#?&=aAzZ\u00e9\u0661\u200b "
        val rng = Random(2024)
        repeat(200_000) {
            val text = buildString { repeat(1 + rng.nextInt(24)) { append(alphabet[rng.nextInt(alphabet.length)]) } }
            try {
                UrlMatcher.hostOf(text)
            } catch (e: Exception) {
                throw AssertionError("hostOf threw for \"$text\"", e)
            }
        }
        // Dotted numbers with runs of digits of every length: the shape that reaches the IPv4 check.
        repeat(50_000) {
            val parts = List(1 + rng.nextInt(5)) { buildString { repeat(1 + rng.nextInt(14)) { append(('0' + rng.nextInt(10))) } } }
            val text = parts.joinToString(".") + listOf("", "/x", ":80", "?q=1")[rng.nextInt(4)]
            try {
                UrlMatcher.hostOf(text)
            } catch (e: Exception) {
                throw AssertionError("hostOf threw for \"$text\"", e)
            }
        }
        // Shapes that are valid-looking up to the point they go wrong.
        listOf(
            "9".repeat(5_000) + ".9.9.9", "1.1.1." + "9".repeat(5_000), "1.2.3.\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0661\u0662",
            "[" + "1".repeat(1_000), "about:reader?url=%", "about:reader?url=%zz.com",
        ).forEach { UrlMatcher.hostOf(it) }
    }

    @Test
    fun `wrappers that show another page are unwrapped`() {
        assertEquals("reddit.com", UrlMatcher.hostOf("view-source:https://reddit.com/r/all"))
        assertEquals("www.reddit.com", UrlMatcher.hostOf("about:reader?url=https%3A%2F%2Fwww.reddit.com%2Fr%2Fall"))
        assertNull(UrlMatcher.hostOf("about:reader"))
    }

    @Test
    fun `looks like a url versus a bare name`() {
        assertTrue(UrlMatcher.looksLikeUrl("https://reddit.com"))
        assertTrue(UrlMatcher.looksLikeUrl("reddit.com/r/all"))
        assertFalse(UrlMatcher.looksLikeUrl("Booking.com"))
        assertFalse(UrlMatcher.looksLikeUrl("invoice.pdf"))
        assertFalse(UrlMatcher.looksLikeUrl(null))
    }

    @Test
    fun `search terms and page titles are not hosts`() {
        assertNull(UrlMatcher.hostOf(null))
        assertNull(UrlMatcher.hostOf(""))
        assertNull(UrlMatcher.hostOf("reddit"))
        assertNull(UrlMatcher.hostOf("how to focus better"))
        assertNull(UrlMatcher.hostOf("Search or type URL"))
        assertNull(UrlMatcher.hostOf("ftp://reddit.com"))
        assertNull(UrlMatcher.hostOf("chrome://flags"))
        assertNull(UrlMatcher.hostOf("about:blank"))
        // An e-mail address on screen is not a navigation.
        assertNull(UrlMatcher.hostOf("someone@reddit.com"))
    }

    @Test
    fun `internationalised hosts compare in ascii`() {
        assertEquals("xn--bcher-kva.example", UrlMatcher.hostOf("https://bücher.example/"))
        assertEquals("xn--bcher-kva.example", UrlMatcher.hostOf("BÜCHER.example"))
        assertEquals("xn--bcher-kva.example", UrlMatcher.normalizePattern("www.bücher.example"))
    }

    @Test
    fun `browser internal states`() {
        assertTrue(UrlMatcher.isBrowserInternal(null))
        assertTrue(UrlMatcher.isBrowserInternal("   "))
        assertTrue(UrlMatcher.isBrowserInternal("about:blank"))
        assertTrue(UrlMatcher.isBrowserInternal("chrome://newtab/"))
        assertFalse(UrlMatcher.isBrowserInternal("chrome://flags"))
        assertFalse(UrlMatcher.isBrowserInternal("data:text/html,<h1>hi</h1>"))
        assertFalse(UrlMatcher.isBrowserInternal("view-source:https://reddit.com"))
    }

    @Test
    fun `patterns match the domain and every subdomain but nothing else`() {
        val patterns = setOf("reddit.com", "youtube.com")
        assertTrue(UrlMatcher.isBlocked("reddit.com", patterns))
        assertTrue(UrlMatcher.isBlocked("www.reddit.com", patterns))
        assertTrue(UrlMatcher.isBlocked("old.reddit.com", patterns))
        assertTrue(UrlMatcher.isBlocked("m.youtube.com", patterns))
        assertFalse(UrlMatcher.isBlocked("notreddit.com", patterns))
        assertFalse(UrlMatcher.isBlocked("reddit.com.evil.net", patterns))
        assertFalse(UrlMatcher.isBlocked("redditblog.com", patterns))
        assertFalse(UrlMatcher.isBlocked(null, patterns))
        assertFalse(UrlMatcher.isBlocked("reddit.com", emptySet()))
    }

    @Test
    fun `patterns typed with www or a wildcard are normalised`() {
        assertEquals("reddit.com", UrlMatcher.normalizePattern("www.reddit.com"))
        assertEquals("reddit.com", UrlMatcher.normalizePattern("*.reddit.com"))
        assertEquals("reddit.com", UrlMatcher.normalizePattern("https://www.reddit.com/r/all"))
        assertEquals("reddit.com", UrlMatcher.normalizePattern("  Reddit.COM  "))
        assertEquals("old.reddit.com", UrlMatcher.normalizePattern("old.reddit.com"))
        assertNull(UrlMatcher.normalizePattern("reddit"))
        assertNull(UrlMatcher.normalizePattern(""))
        assertNull(UrlMatcher.normalizePattern("not a domain"))
    }

    @Test
    fun `hosts with an underscore are still hosts`() {
        // Browsers load these, so rejecting them let the page past both modes.
        assertEquals("my_site.example.com", UrlMatcher.hostOf("https://my_site.example.com/page"))
        assertEquals("a_b.reddit.com", UrlMatcher.blockedHost("a_b.reddit.com/r/all", setOf("reddit.com")))
        assertTrue(SiteRules.shouldBlock(UrlMatcher.hostOf("x_y.example.org"), WebsiteMode.ALLOWLIST, setOf("wikipedia.org"), CompactDomainSet.EMPTY))
    }

    @Test
    fun `end to end from address bar text to verdict`() {
        val patterns = setOf("reddit.com")
        assertEquals("www.reddit.com", UrlMatcher.blockedHost("https://www.reddit.com/", patterns))
        assertEquals("reddit.com", UrlMatcher.blockedHost("reddit.com", patterns))
        assertNull(UrlMatcher.blockedHost("https://example.com/", patterns))
        assertNull(UrlMatcher.blockedHost("reddit", patterns))
    }
}
