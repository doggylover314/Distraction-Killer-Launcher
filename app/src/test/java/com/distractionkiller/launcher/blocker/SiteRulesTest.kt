package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRulesTest {

    private val domains = setOf("reddit.com", "youtube.com", "news.google.com")
    private val none = CompactDomainSet.EMPTY

    @Test
    fun `matches the domain itself and any subdomain`() {
        assertTrue(SiteRules.matchesAny("reddit.com", domains))
        assertTrue(SiteRules.matchesAny("www.reddit.com", domains))
        assertTrue(SiteRules.matchesAny("a.b.c.reddit.com", domains))
        assertTrue(SiteRules.matchesAny("m.youtube.com", domains))
    }

    @Test
    fun `a deeper entry does not cover its parent`() {
        assertTrue(SiteRules.matchesAny("news.google.com", domains))
        assertTrue(SiteRules.matchesAny("edition.news.google.com", domains))
        assertFalse(SiteRules.matchesAny("google.com", domains))
        assertFalse(SiteRules.matchesAny("mail.google.com", domains))
    }

    @Test
    fun `lookalikes do not match`() {
        assertFalse(SiteRules.matchesAny("notreddit.com", domains))
        assertFalse(SiteRules.matchesAny("reddit.com.evil.net", domains))
        assertFalse(SiteRules.matchesAny("com", domains))
        assertFalse(SiteRules.matchesAny("reddit", domains))
        assertFalse(SiteRules.matchesAny("anything.com", emptySet()))
    }

    @Test
    fun `blocklist mode blocks listed hosts only`() {
        assertTrue(SiteRules.shouldBlock("old.reddit.com", WebsiteMode.BLOCKLIST, domains, none))
        assertFalse(SiteRules.shouldBlock("wikipedia.org", WebsiteMode.BLOCKLIST, domains, none))
        assertFalse(SiteRules.shouldBlock(null, WebsiteMode.BLOCKLIST, domains, none))
    }

    @Test
    fun `allowlist mode blocks everything that is not listed`() {
        assertFalse(SiteRules.shouldBlock("old.reddit.com", WebsiteMode.ALLOWLIST, domains, none))
        assertTrue(SiteRules.shouldBlock("wikipedia.org", WebsiteMode.ALLOWLIST, domains, none))
        assertFalse(SiteRules.shouldBlock(null, WebsiteMode.ALLOWLIST, domains, none))
    }

    @Test
    fun `presets and the custom list are combined`() {
        val presets = CompactDomainSet.of(setOf("wikipedia.org"))
        assertTrue(SiteRules.shouldBlock("en.wikipedia.org", WebsiteMode.BLOCKLIST, emptySet(), presets))
        assertFalse(SiteRules.shouldBlock("en.wikipedia.org", WebsiteMode.ALLOWLIST, emptySet(), presets))
        assertFalse(SiteRules.shouldBlock("reddit.com", WebsiteMode.ALLOWLIST, domains, presets))
    }

    @Test
    fun `lookup cost does not grow with the list`() {
        // 50,000 entries: still just a handful of hash lookups per host.
        val big = (1..50_000).mapTo(HashSet()) { "site$it.example" }
        val start = System.nanoTime()
        repeat(1_000) { SiteRules.matchesAny("deep.sub.site49999.example", big) }
        val perLookupMicros = (System.nanoTime() - start) / 1_000 / 1_000.0
        assertTrue("took $perLookupMicros us per lookup", perLookupMicros < 50)
        assertEquals(true, SiteRules.matchesAny("x.site1.example", big))
    }

    @Test
    fun `preset lookups use the same suffix rules as the custom list`() {
        val presets = CompactDomainSet.of(domains)
        listOf("reddit.com", "www.reddit.com", "a.b.c.reddit.com", "edition.news.google.com").forEach {
            assertTrue(it, SiteRules.matchesAny(it, presets))
        }
        listOf("google.com", "notreddit.com", "reddit.com.evil.net", "com", "reddit", "").forEach {
            assertFalse(it, SiteRules.matchesAny(it, presets))
        }
    }

    @Test
    fun `stored mode values round trip with a safe default`() {
        assertEquals(WebsiteMode.ALLOWLIST, WebsiteMode.fromStoredValue("ALLOWLIST"))
        assertEquals(WebsiteMode.BLOCKLIST, WebsiteMode.fromStoredValue(null))
        assertEquals(WebsiteMode.BLOCKLIST, WebsiteMode.fromStoredValue("garbage"))
    }
}
