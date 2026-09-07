package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the real files under app/src/main/assets/presets with the same code
 * the app uses. Unit tests run with the module directory as working dir.
 */
class BundledPresetsTest {

    private val dir = File("src/main/assets/presets")

    @Test
    fun `catalog exists and every preset it lists is present and well formed`() {
        val index = File(dir, "index.tsv")
        assertTrue("missing ${index.path}; run tools/build_presets.py", index.exists())

        val catalog = index.bufferedReader().useLines(PresetParser::parseCatalog)
        assertTrue("catalog is empty", catalog.isNotEmpty())
        assertEquals("duplicate ids", catalog.size, catalog.map { it.id }.toSet().size)

        val expectedIds = setOf(
            "distractions", "news", "shopping", "games", "adult", "gambling", "proxies",
            "essentials", "work", "learning",
        )
        assertEquals(expectedIds, catalog.map { it.id }.toSet())

        catalog.forEach { preset ->
            assertTrue("bad id ${preset.id}", Regex("^[a-z0-9_]+$").matches(preset.id))
            assertTrue("no name for ${preset.id}", preset.name.isNotBlank())
            assertTrue("no description for ${preset.id}", preset.description.isNotBlank())
            assertTrue("description too long for ${preset.id}", preset.description.length <= 120)

            val file = File(dir, "${preset.id}.txt")
            assertTrue("missing ${file.path}", file.exists())
            val raw = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
            val parsed = PresetParser.parseDomains(raw.asSequence())
            assertEquals("lines dropped by the parser in ${preset.id}", raw.size, parsed.size)
            assertTrue("${preset.id} is empty", parsed.isNotEmpty())
            assertFalse("${preset.id} contains a www. entry", parsed.any { it.startsWith("www.") })
        }
    }

    @Test
    fun `block and allow kinds are both present`() {
        val catalog = File(dir, "index.tsv").bufferedReader().useLines(PresetParser::parseCatalog)
        assertTrue(catalog.any { it.appliesTo == WebsiteMode.BLOCKLIST })
        assertTrue(catalog.any { it.appliesTo == WebsiteMode.ALLOWLIST })
    }

    @Test
    fun `the distractions preset catches the obvious sites and not their lookalikes`() {
        val domains = File(dir, "distractions.txt").bufferedReader().useLines(PresetParser::parseDomains)
        listOf("www.reddit.com", "m.youtube.com", "instagram.com", "tiktok.com", "old.reddit.com").forEach {
            assertTrue("$it should match", SiteRules.matchesAny(it, domains))
        }
        listOf("wikipedia.org", "google.com", "github.com").forEach {
            assertFalse("$it should not match", SiteRules.matchesAny(it, domains))
        }
    }

    @Test
    fun `the proxies preset covers translate and archive front ends and nothing that is allowed`() {
        val proxies = File(dir, "proxies.txt").bufferedReader().useLines(PresetParser::parseDomains)
        listOf("reddit-com.translate.goog", "translate.google.com", "web.archive.org", "12ft.io").forEach {
            assertTrue("$it should match", SiteRules.matchesAny(it, proxies))
        }
        val allowed = listOf("essentials", "work", "learning")
            .flatMap { File(dir, "$it.txt").bufferedReader().useLines(PresetParser::parseDomains) }
        allowed.forEach { assertFalse("$it is both allowed and a proxy", SiteRules.matchesAny(it, proxies)) }
    }

    @Test
    fun `total bundled size stays within budget`() {
        val bytes = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("presets are ${bytes / 1024} KB; budget is 1536 KB", bytes < 1536 * 1024)
    }

    @Test
    fun `parser drops garbage lines`() {
        val parsed = PresetParser.parseDomains(
            sequenceOf("# comment", "", "Reddit.com", "http://x.com", "bad line", "0.0.0.0 y.com", "ok.example"),
        )
        assertEquals(setOf("reddit.com", "ok.example"), parsed)
    }
}
