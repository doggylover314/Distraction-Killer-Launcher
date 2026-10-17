package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PresetStoreTest {

    private val adult = CompactDomainSet.of(setOf("adult.example", "casino.example"))
    private val allowedByPreset = CompactDomainSet.of(setOf("wikipedia.example"))

    @Test
    fun `nothing loaded yet yields no presets under either mode`() {
        val store = PresetStore()
        assertTrue(store.domainsFor(WebsiteMode.BLOCKLIST).isEmpty())
        assertTrue(store.domainsFor(WebsiteMode.ALLOWLIST).isEmpty())
    }

    @Test
    fun `settled only once the newest load has landed for that mode`() {
        val store = PresetStore()
        val first = store.beginLoad()
        assertFalse(store.isSettledFor(WebsiteMode.ALLOWLIST))
        store.publish(first, WebsiteMode.ALLOWLIST, allowedByPreset)
        assertTrue(store.isSettledFor(WebsiteMode.ALLOWLIST))
        assertFalse(store.isSettledFor(WebsiteMode.BLOCKLIST))
        // A newer reload starts: pending again until it lands.
        val second = store.beginLoad()
        assertFalse(store.isSettledFor(WebsiteMode.ALLOWLIST))
        store.publish(second, WebsiteMode.ALLOWLIST, allowedByPreset)
        assertTrue(store.isSettledFor(WebsiteMode.ALLOWLIST))
    }

    @Test
    fun `a set is served only under the mode it was loaded for`() {
        val store = PresetStore()
        assertTrue(store.publish(store.beginLoad(), WebsiteMode.BLOCKLIST, adult))
        assertSame(adult, store.domainsFor(WebsiteMode.BLOCKLIST))
        assertTrue(store.domainsFor(WebsiteMode.ALLOWLIST).isEmpty())
    }

    @Test
    fun `switching blocklist to allowlist does not let the blocklist presets count as allowed`() {
        val store = PresetStore()
        store.publish(store.beginLoad(), WebsiteMode.BLOCKLIST, adult)
        val custom = setOf("work.example")

        // The preferences already say allowlist; the allowlist presets have not loaded yet.
        // Handed the stale set, allowlist would call every adult domain "listed", so allowed.
        assertFalse(SiteRules.shouldBlock("adult.example", WebsiteMode.ALLOWLIST, custom, adult))
        // Through the store the stale set is withheld, so the user's own list decides and fails closed.
        val during = store.domainsFor(WebsiteMode.ALLOWLIST)
        assertTrue(SiteRules.shouldBlock("adult.example", WebsiteMode.ALLOWLIST, custom, during))
        assertTrue(SiteRules.shouldBlock("anything.example", WebsiteMode.ALLOWLIST, custom, during))
        assertFalse(SiteRules.shouldBlock("work.example", WebsiteMode.ALLOWLIST, custom, during))
    }

    @Test
    fun `switching allowlist to blocklist does not block the allowlist presets`() {
        val store = PresetStore()
        store.publish(store.beginLoad(), WebsiteMode.ALLOWLIST, allowedByPreset)
        val during = store.domainsFor(WebsiteMode.BLOCKLIST)
        assertFalse(SiteRules.shouldBlock("wikipedia.example", WebsiteMode.BLOCKLIST, setOf("blocked.example"), during))
        assertTrue(SiteRules.shouldBlock("blocked.example", WebsiteMode.BLOCKLIST, setOf("blocked.example"), during))
    }

    @Test
    fun `once the reload for the new mode lands its set is served`() {
        val store = PresetStore()
        store.publish(store.beginLoad(), WebsiteMode.BLOCKLIST, adult)
        val ticket = store.beginLoad()
        assertTrue(store.domainsFor(WebsiteMode.ALLOWLIST).isEmpty())
        assertTrue(store.publish(ticket, WebsiteMode.ALLOWLIST, allowedByPreset))
        assertSame(allowedByPreset, store.domainsFor(WebsiteMode.ALLOWLIST))
        assertTrue(store.domainsFor(WebsiteMode.BLOCKLIST).isEmpty())
    }

    @Test
    fun `an older load that finishes last cannot put stale domains back`() {
        val store = PresetStore()
        val first = store.beginLoad()
        val second = store.beginLoad()
        assertTrue(store.publish(second, WebsiteMode.BLOCKLIST, adult))
        assertFalse(store.publish(first, WebsiteMode.BLOCKLIST, CompactDomainSet.EMPTY))
        assertSame(adult, store.domainsFor(WebsiteMode.BLOCKLIST))
    }

    @Test
    fun `a load superseded before it finished is dropped`() {
        val store = PresetStore()
        val old = store.beginLoad()
        store.beginLoad()
        assertFalse(store.publish(old, WebsiteMode.BLOCKLIST, adult))
        assertTrue(store.domainsFor(WebsiteMode.BLOCKLIST).isEmpty())
    }

    @Test
    fun `concurrent reload requests never share a ticket`() {
        // A plain ++ on a volatile loses updates under contention and would hand two
        // callers the same ticket, letting an older load pass the freshness check.
        val store = PresetStore()
        val threads = 8
        val perThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results = (0 until threads).map {
                pool.submit<IntArray> {
                    start.await()
                    IntArray(perThread) { store.beginLoad() }
                }
            }
            start.countDown()
            val all = results.flatMap { it.get(30, TimeUnit.SECONDS).toList() }
            assertEquals(threads * perThread, all.size)
            assertEquals(threads * perThread, all.toSet().size)
            assertEquals(threads * perThread, all.max())
        } finally {
            pool.shutdownNow()
        }
    }
}
