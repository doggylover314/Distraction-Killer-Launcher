package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CompactDomainSetTest {

    private val letters = "abcdefghijklmnopqrstuvwxyz0123456789"

    private fun label(rng: Random, length: Int) = buildString {
        repeat(length) { append(letters[rng.nextInt(letters.length)]) }
    }

    /** Registrable-looking domains, with some multi-label entries like the real lists have. */
    private fun generate(count: Int, seed: Long): List<String> {
        val rng = Random(seed)
        val tlds = listOf("com", "net", "org", "co.uk", "io", "xyz", "info")
        val seen = HashSet<String>()
        while (seen.size < count) {
            val sub = if (rng.nextInt(10) == 0) label(rng, 2 + rng.nextInt(5)) + "." else ""
            seen.add(sub + label(rng, 3 + rng.nextInt(10)) + "." + tlds[rng.nextInt(tlds.size)])
        }
        return seen.toList()
    }

    @Test
    fun `matches a domain and every subdomain but not lookalikes`() {
        val set = CompactDomainSet.of(listOf("reddit.com", "news.google.com"))
        listOf("reddit.com", "www.reddit.com", "a.b.c.reddit.com", "news.google.com", "edition.news.google.com")
            .forEach { assertTrue(it, set.matchesHostOrParent(it)) }
        listOf("google.com", "mail.google.com", "notreddit.com", "reddit.com.evil.net", "redditblog.com", "com", "reddit", "")
            .forEach { assertFalse(it, set.matchesHostOrParent(it)) }
    }

    @Test
    fun `an empty set matches nothing and the shared empty instance is empty`() {
        assertFalse(CompactDomainSet.EMPTY.matchesHostOrParent("reddit.com"))
        assertTrue(CompactDomainSet.EMPTY.isEmpty())
        assertTrue(CompactDomainSet.Builder().build().isEmpty())
        assertEquals(0, CompactDomainSet.of(emptyList()).size)
    }

    @Test
    fun `exact lookup does not walk up to parents`() {
        val set = CompactDomainSet.of(listOf("reddit.com"))
        assertTrue(set.containsExact("reddit.com"))
        assertFalse(set.containsExact("www.reddit.com"))
    }

    @Test
    fun `duplicates collapse and order does not matter`() {
        val a = CompactDomainSet.of(listOf("a.com", "b.com", "a.com", "c.com", "b.com"))
        assertEquals(3, a.size)
        val builder = CompactDomainSet.Builder(2)
        listOf("c.com", "b.com", "a.com").forEach(builder::addDomain)
        assertEquals(3, builder.build().size)
    }

    @Test
    fun `a half-read preset can be rolled back`() {
        val builder = CompactDomainSet.Builder()
        builder.addDomain("keep.com")
        val mark = builder.size
        builder.addDomain("drop.com")
        builder.addDomain("drop2.com")
        builder.truncate(mark)
        val set = builder.build()
        assertEquals(1, set.size)
        assertTrue(set.containsExact("keep.com"))
        assertFalse(set.containsExact("drop.com"))
    }

    @Test
    fun `suffix matching over a large generated set agrees with the exact string set`() {
        val domains = generate(100_000, seed = 7)
        val exact = domains.toHashSet()
        val compact = CompactDomainSet.of(domains)

        // Hash collisions would show up as fewer distinct entries than domains.
        assertEquals(domains.size, compact.size)

        val rng = Random(99)
        var members = 0
        repeat(300_000) {
            val base = domains[rng.nextInt(domains.size)]
            val host = when (rng.nextInt(6)) {
                0 -> base
                1 -> label(rng, 4) + "." + base
                2 -> label(rng, 3) + "." + label(rng, 5) + "." + base
                3 -> "not$base"
                4 -> base + ".evil.net"
                else -> label(rng, 4 + rng.nextInt(8)) + "." + label(rng, 3) // almost surely unlisted
            }
            val expected = SiteRules.matchesAny(host, exact)
            if (expected) members++
            assertEquals(host, expected, compact.matchesHostOrParent(host))
        }
        // The sample really exercised both outcomes.
        assertTrue("members=$members", members in 100_000..250_000)
    }

    @Test
    fun `every member and a subdomain of it matches in a large set`() {
        val domains = generate(50_000, seed = 11)
        val compact = CompactDomainSet.of(domains)
        domains.forEach {
            assertTrue(it, compact.matchesHostOrParent(it))
            assertTrue(it, compact.matchesHostOrParent("sub.$it"))
            assertTrue(it, compact.containsExact(it))
        }
    }

    @Test
    fun `no false positives across a quarter of a million unrelated hosts`() {
        val compact = CompactDomainSet.of(generate(42_000, seed = 3))
        val rng = Random(1234)
        var hits = 0
        repeat(250_000) {
            // Seven-plus character labels under a TLD the set never uses: nothing can legitimately match.
            if (compact.matchesHostOrParent(label(rng, 7 + rng.nextInt(6)) + "." + label(rng, 5) + ".zzz")) hits++
        }
        assertEquals(0, hits)
    }

    @Test
    fun `the footprint is eight bytes per domain`() {
        // 64-bit entries in one flat array. The HashSet<String> it replaces costs on the
        // order of 90 bytes per entry once String, byte[] and map node are counted.
        val field = CompactDomainSet::class.java.getDeclaredField("hashes").also { it.isAccessible = true }
        val array = field.get(CompactDomainSet.of(generate(1_000, seed = 5))) as LongArray
        assertEquals(1_000, array.size)
        assertEquals(8L * 1_000, array.size * java.lang.Long.BYTES.toLong())
    }

    @Test
    fun `the hash is the documented function`() {
        // Pins the constants so an edit cannot silently change every stored hash.
        // Computed independently: FNV-1a 64 over the reversed text, then murmur3 fmix64.
        fun reference(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in s.reversed()) h = (h xor c.code.toLong()) * 0x100000001b3L
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
            h = h xor (h ushr 33)
            return h
        }
        listOf("reddit.com", "a.b", "xn--bcher-kva.example", "").forEach {
            assertEquals(it, reference(it), CompactDomainSet.hashOf(it))
        }
    }

    @Test
    fun `a host matches in any ASCII case and with trailing root dots`() {
        val set = CompactDomainSet.of(listOf("reddit.com", "news.google.com"))
        // hostOf already produces the lower-case, dot-free form, but the set must not depend on that.
        listOf(
            "Reddit.com", "WWW.REDDIT.COM", "wWw.ReDdIt.CoM", "reddit.com.", "www.reddit.com..", "Old.Reddit.COM.",
            "EDITION.news.GOOGLE.com", "news.google.com.",
        ).forEach { assertTrue(it, set.matchesHostOrParent(it)) }
    }

    @Test
    fun `normalising the host does not widen the match`() {
        val set = CompactDomainSet.of(listOf("reddit.com", "news.google.com"))
        listOf(
            "Google.com", "NOTREDDIT.COM", "reddit.com.evil.net.", "Reddit.com.Evil.NET", "com.", ".", "..", "", ".reddit",
        ).forEach { assertFalse(it, set.matchesHostOrParent(it)) }
    }

    @Test
    fun `a set built from mixed-case or dotted names answers the same as one built from the plain names`() {
        val plain = CompactDomainSet.of(listOf("reddit.com", "news.google.com"))
        val awkward = CompactDomainSet.of(listOf("Reddit.COM", "News.Google.Com."))
        assertEquals(plain.size, awkward.size)
        listOf("reddit.com", "www.reddit.com", "news.google.com", "google.com", "notreddit.com").forEach {
            assertEquals(it, plain.matchesHostOrParent(it), awkward.matchesHostOrParent(it))
        }
        assertTrue(awkward.containsExact("REDDIT.com"))
        assertTrue(awkward.containsExact("news.google.com."))
        assertFalse(awkward.containsExact("www.reddit.com"))
    }

    @Test
    fun `random case and trailing dots never change a verdict`() {
        val domains = generate(5_000, seed = 21)
        val compact = CompactDomainSet.of(domains)
        val rng = Random(77)
        repeat(50_000) {
            val base = domains[rng.nextInt(domains.size)]
            val host = when (rng.nextInt(3)) {
                0 -> base
                1 -> label(rng, 4) + "." + base
                else -> "not$base"
            }
            val disguised = buildString {
                host.forEach { append(if (rng.nextBoolean()) it.uppercaseChar() else it) }
                repeat(rng.nextInt(3)) { append('.') }
            }
            assertEquals(disguised, compact.matchesHostOrParent(host), compact.matchesHostOrParent(disguised))
        }
    }

    @Test
    fun `the raw hash stays case and dot sensitive, which is what pins the stored form`() {
        // The set normalises at its entry points; hashOf is the documented function underneath
        // and must not, or the "documented function" test above would stop meaning anything.
        assertNotEquals(CompactDomainSet.hashOf("Reddit.com"), CompactDomainSet.hashOf("reddit.com"))
        assertNotEquals(CompactDomainSet.hashOf("reddit.com."), CompactDomainSet.hashOf("reddit.com"))
    }

    @Test
    fun `case folding in the range hash matches hashing the lower-cased text`() {
        val upper = "  Reddit.COM "
        assertEquals(
            CompactDomainSet.hashOf("reddit.com"),
            CompactDomainSet.hashRange(upper, 2, upper.length - 1, foldAsciiCase = true),
        )
        assertTrue(CompactDomainSet.hashOf("Reddit.com") != CompactDomainSet.hashOf("reddit.com"))
    }
}
