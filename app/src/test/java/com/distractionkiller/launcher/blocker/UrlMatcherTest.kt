package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `end to end from address bar text to verdict`() {
        val patterns = setOf("reddit.com")
        assertEquals("www.reddit.com", UrlMatcher.blockedHost("https://www.reddit.com/", patterns))
        assertEquals("reddit.com", UrlMatcher.blockedHost("reddit.com", patterns))
        assertNull(UrlMatcher.blockedHost("https://example.com/", patterns))
        assertNull(UrlMatcher.blockedHost("reddit", patterns))
    }
}
