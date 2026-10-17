package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetParserTest {

    private fun accepted(line: String): Boolean = PresetParser.addLine(line, CompactDomainSet.Builder())

    /** What the old string-based parser did to a line. */
    private fun oldRule(line: String): Boolean {
        val t = line.trim().lowercase()
        return t.isNotEmpty() && !t.startsWith('#') && PresetParser.DOMAIN_LINE.matches(t)
    }

    @Test
    fun `the hand-written scanner accepts exactly what the documented pattern accepts`() {
        val samples = listOf(
            "reddit.com", "Reddit.COM", "  reddit.com  ", "\treddit.com\r", "a.b.c.example", "xn--bcher-kva.example",
            "a-b.example", "-a.example", "a-.example", "9.9.9.example", "1.2.3.4",
            "", "   ", "#comment", "# reddit.com", "reddit", ".com", "reddit.", "reddit..com", "red dit.com",
            "http://x.com", "x.com/path", "0.0.0.0 y.com", "user@x.com", "x.com:80", "bücher.example", "a_b.example",
            "[::1]", "x.com\u0000",
        )
        samples.forEach { assertEquals("'$it'", oldRule(it), accepted(it)) }
    }

    @Test
    fun `accepted lines hash like the normalised domain`() {
        val builder = CompactDomainSet.Builder()
        assertTrue(PresetParser.addLine("  Reddit.COM\r\n", builder))
        val set = builder.build()
        assertTrue(set.containsExact("reddit.com"))
        assertTrue(set.matchesHostOrParent("old.reddit.com"))
        assertEquals(1, set.size)
    }

    @Test
    fun `addLines reports how many lines were taken`() {
        val builder = CompactDomainSet.Builder()
        val taken = PresetParser.addLines(
            sequenceOf("# header", "a.com", "", "b.com", "not a domain", "A.com"),
            builder,
        )
        assertEquals(3, taken)
        // A.com and a.com are one domain.
        assertEquals(2, builder.build().size)
    }

    @Test
    fun `rejected lines add nothing`() {
        val builder = CompactDomainSet.Builder()
        listOf("# x", "", "bad line", "http://x.com").forEach { assertFalse(PresetParser.addLine(it, builder)) }
        assertEquals(0, builder.size)
    }
}
