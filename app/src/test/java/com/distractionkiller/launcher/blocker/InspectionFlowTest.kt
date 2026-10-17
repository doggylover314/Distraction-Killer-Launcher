package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions EnforcementService makes between "an event arrived" and "a
 * window was judged". The service itself needs a device, so these drive the
 * real policy, memo, scanner and verdict rules in the order the service uses
 * them, over fake trees.
 */
class InspectionFlowTest {

    private val windowId = 7
    private val pkg = "com.example.social"

    /** What one ordinary window's bookkeeping looks like across a stream of events. */
    private class Replay(val windowId: Int, val pkg: String) {
        val memo = WindowMemo()
        var lastSlowAt = 0L
        val walks = ArrayList<Long>()

        /**
         * One event, in the order inspectWindow does things: ask the policy, note the
         * slow-path time, walk, then let [InspectionPolicy.verdictToRecord] decide
         * what the memo may learn. Returns the scan, or null when the policy declined.
         */
        fun event(now: Long, stateChange: Boolean, tree: FakeNode): UrlScan? {
            val known = memo.find(windowId, pkg)
            if (!InspectionPolicy.shouldInspect(WindowKind.OTHER, stateChange, known, now, 0L, lastSlowAt)) return null
            if (!stateChange) lastSlowAt = now
            val scan = UrlScanner.scan(tree, FakeAccess(), InspectionPolicy.GENERIC_LIMITS)
            InspectionPolicy.verdictToRecord(scan)?.let { memo.record(windowId, pkg, it, now) }
            walks.add(now)
            return scan
        }

        /** Time between consecutive walks, ignoring the first [skip] walks. */
        fun gaps(skip: Int = 0): List<Long> = walks.drop(skip).zipWithNext { a, b -> b - a }
    }

    private fun scan(root: FakeNode, limits: WalkLimits = InspectionPolicy.GENERIC_LIMITS) =
        UrlScanner.scan(root, FakeAccess(), limits)

    private fun text(t: String) = FakeNode(name = t, text = t)

    // ---------------------------------------------- H1: what a walk may teach

    @Test
    fun `a walk cut short by the node budget does not cache a page-free window`() {
        // 300 rows, then the page: past the generic budget of 150 nodes.
        val tree = FakeNode(name = "root", children = List(300) { FakeNode(name = "row$it") } + listOf(webView(true)))
        val result = scan(tree)
        assertFalse(result.webViewSeen)
        assertTrue(result.stats!!.truncated)
        assertNull(InspectionPolicy.verdictToRecord(result))
    }

    @Test
    fun `a walk that read the whole window and found no page may cache a page-free window`() {
        assertEquals(WindowVerdict.NO_WEBVIEW, InspectionPolicy.verdictToRecord(scan(wide(10))))
        // An ad banner is a WebView that is not a page, and the walk finished.
        val banner = node(text("hello"), webView(false), name = "root")
        assertEquals(WindowVerdict.NO_WEBVIEW, InspectionPolicy.verdictToRecord(scan(banner)))
    }

    @Test
    fun `the budget boundary is exact`() {
        val budget = InspectionPolicy.GENERIC_LIMITS.maxNodes
        // Root plus (budget - 1) children is exactly the budget, and nothing was left unread.
        assertEquals(WindowVerdict.NO_WEBVIEW, InspectionPolicy.verdictToRecord(scan(wide(budget - 1))))
        // One more child exists that the walk could not afford.
        assertNull(InspectionPolicy.verdictToRecord(scan(wide(budget))))
    }

    @Test
    fun `a walk stopped by the depth limit does not cache a page-free window either`() {
        val depth = InspectionPolicy.GENERIC_LIMITS.maxDepth
        assertEquals(WindowVerdict.NO_WEBVIEW, InspectionPolicy.verdictToRecord(scan(chain(depth))))
        assertNull(InspectionPolicy.verdictToRecord(scan(chain(depth + 1))))
    }

    @Test
    fun `a page that was seen is recorded however the walk ended`() {
        val tree = FakeNode(name = "root", children = listOf(webView(true)) + List(300) { FakeNode(name = "row$it") })
        val result = scan(tree)
        assertTrue(result.stats!!.truncated)
        assertEquals(WindowVerdict.HAS_WEBVIEW, InspectionPolicy.verdictToRecord(result))
        assertEquals(WindowVerdict.HAS_WEBVIEW, InspectionPolicy.verdictToRecord(scan(node(webView(true), name = "root"))))
    }

    @Test
    fun `a scan with no walk statistics is never trusted to say the window is page-free`() {
        assertNull(InspectionPolicy.verdictToRecord(UrlScan()))
        assertEquals(WindowVerdict.HAS_WEBVIEW, InspectionPolicy.verdictToRecord(UrlScan().apply { webViewSeen = true }))
    }

    @Test
    fun `a window the service could not read keeps the old cadence instead of going quiet`() {
        val unreadable = FakeNode(name = "root", children = List(300) { FakeNode(name = "row$it") } + listOf(webView(true)))
        val replay = Replay(windowId, pkg)
        var now = 1_000L
        replay.event(now, stateChange = true, tree = unreadable)
        repeat(100) {
            now += 600L
            replay.event(now, stateChange = false, tree = unreadable)
        }
        assertNull(replay.memo.find(windowId, pkg))
        // Every 1.2 s, as before the refactor: this is the price of a window that cannot be read.
        // (The opening state-change walk is not on the content-change clock, hence skip = 1.)
        assertTrue(replay.gaps(skip = 1).all { it == InspectionPolicy.SLOW_INTERVAL_MS })
        assertTrue("walks=${replay.walks.size}", replay.walks.size >= 40)
    }

    @Test
    fun `a truncated look does not downgrade a window already known to host a page`() {
        val replay = Replay(windowId, pkg)
        replay.event(1_000L, stateChange = true, tree = node(webView(true), name = "root"))
        assertEquals(WindowVerdict.HAS_WEBVIEW, replay.memo.find(windowId, pkg)!!.verdict)
        // The page now sits past the budget; the look is cut short and must not overwrite the verdict.
        val crowded = FakeNode(name = "root", children = List(300) { FakeNode(name = "row$it") } + listOf(webView(true)))
        replay.event(3_000L, stateChange = false, tree = crowded)
        assertEquals(WindowVerdict.HAS_WEBVIEW, replay.memo.find(windowId, pkg)!!.verdict)
    }

    // -------------------------------------------- H2: the page-free expiry

    @Test
    fun `a page-free window is revisited on content changes alone, once per recheck interval`() {
        val screen = wide(20)
        val replay = Replay(windowId, pkg)
        replay.event(1_000L, stateChange = true, tree = screen)
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.memo.find(windowId, pkg)!!.verdict)

        // Ten minutes of content changes, one every 600 ms, as a scrolling feed raises them.
        var now = 1_000L
        while (now < 601_000L) {
            now += 600L
            replay.event(now, stateChange = false, tree = screen)
        }

        val revisits = replay.walks.size - 1
        assertTrue("revisits=$revisits", revisits >= 100)
        assertTrue("revisits=$revisits", revisits <= 600_000 / InspectionPolicy.RECHECK_INTERVAL_MS)
        assertTrue(replay.gaps().all { it >= InspectionPolicy.RECHECK_INTERVAL_MS })
    }

    @Test
    fun `a flood of content changes still costs one walk per recheck interval`() {
        val screen = wide(20)
        val replay = Replay(windowId, pkg)
        replay.event(0L, stateChange = true, tree = screen)
        var now = 0L
        while (now < 60_000L) {
            now += 10L
            replay.event(now, stateChange = false, tree = screen)
        }
        // Without the look refreshing the entry this would be one walk per 1.2 s, about fifty.
        assertTrue("walks=${replay.walks.size}", replay.walks.size <= 1 + 60_000 / InspectionPolicy.RECHECK_INTERVAL_MS)
        assertTrue(replay.gaps().all { it >= InspectionPolicy.RECHECK_INTERVAL_MS })
    }

    @Test
    fun `an in-app browser that opens inside a page-free window is found by content changes alone`() {
        val feed = wide(20)
        val replay = Replay(windowId, pkg)
        replay.event(0L, stateChange = true, tree = feed)
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.memo.find(windowId, pkg)!!.verdict)

        // The user taps a link; the app shows its browser as a fragment in the same window.
        // The system raises content changes and no window-state event.
        val inAppBrowser = node(
            node(FakeNode(name = "title", id = "app:id/title", text = "blocked.example"), name = "toolbar"),
            webView(true),
            name = "root",
        )
        var found: UrlScan? = null
        var foundAt = 0L
        var now = 0L
        while (found == null && now < 30_000L) {
            now += 600L
            found = replay.event(now, stateChange = false, tree = inAppBrowser)
            foundAt = now
        }

        assertNotNull("the page was never looked at", found)
        assertTrue("found after $foundAt ms", foundAt <= InspectionPolicy.RECHECK_INTERVAL_MS + 600L)
        assertEquals(WindowVerdict.HAS_WEBVIEW, replay.memo.find(windowId, pkg)!!.verdict)
        val block = SiteVerdict.judge(
            view = found!!.best(isBrowser = false),
            isBrowser = false,
            mode = WebsiteMode.BLOCKLIST,
            custom = setOf("blocked.example"),
            presets = CompactDomainSet.EMPTY,
            webViewShown = { found!!.webViewSeen },
            plainHost = { null },
        )
        assertNotNull(block)
        assertEquals("blocked.example", block!!.label)

        // From here on it is followed at the slow interval, as any page-hosting window is.
        val walksBefore = replay.walks.size
        assertNull(replay.event(foundAt + 600L, stateChange = false, tree = inAppBrowser))
        assertNotNull(replay.event(foundAt + InspectionPolicy.SLOW_INTERVAL_MS, stateChange = false, tree = inAppBrowser))
        assertEquals(walksBefore + 1, replay.walks.size)
    }

    // ------------------------------- NOT_INSPECTABLE is never written for the lock

    @Test
    fun `a non-application window of an ordinary package is remembered as not inspectable`() {
        val memo = WindowMemo()
        InspectionPolicy.rememberNotInspectable(memo, 12, "com.example.keyboard", now = 5L)
        assertEquals(WindowVerdict.NOT_INSPECTABLE, memo.find(12, "com.example.keyboard")!!.verdict)
        assertFalse(
            InspectionPolicy.shouldInspect(WindowKind.OTHER, true, memo.find(12, "com.example.keyboard"), 10L, 0L, 0L),
        )
    }

    @Test
    fun `not inspectable is never recorded for any lockable package`() {
        // Iterating the real list means a package added to the lock later is covered too.
        SettingsLockDetector.LOCKABLE_PACKAGES.forEach { lockable ->
            val memo = WindowMemo()
            InspectionPolicy.rememberNotInspectable(memo, 12, lockable, now = 5L)
            assertNull(lockable, memo.find(12, lockable))
            assertEquals(lockable, 0, memo.size)
            // And the read side, with nothing recorded, still inspects it.
            assertTrue(lockable, InspectionPolicy.shouldInspect(WindowKind.LOCKABLE, true, null, 10L, 0L, 0L))
        }
    }

    @Test
    fun `an undefined window id is not remembered as not inspectable`() {
        val memo = WindowMemo()
        InspectionPolicy.rememberNotInspectable(memo, -1, "com.example.keyboard", now = 5L)
        assertEquals(0, memo.size)
    }

    // ------------------------------------- M2: the focused-window fallback

    @Test
    fun `the focused window stands in whenever the window list gave nothing for this package`() {
        // The event came from window 7, whose root was still null in the window list, while
        // the focused window is 9 of the same package. Different ids, so the focused root does
        // not answer up front, and the fallback must not care that the event carried an id.
        assertFalse(InspectionPolicy.activeRootAnswers(7, 9, pkg, pkg))
        assertTrue(InspectionPolicy.activeRootStandsIn(rootsFromWindowList = 0, eventPackage = pkg, activePackage = pkg))
    }

    @Test
    fun `the focused window never stands in for another package or beside a root the list found`() {
        assertFalse(InspectionPolicy.activeRootStandsIn(0, pkg, "com.example.other"))
        assertFalse(InspectionPolicy.activeRootStandsIn(0, pkg, null))
        assertFalse(InspectionPolicy.activeRootStandsIn(1, pkg, pkg))
        assertFalse(InspectionPolicy.activeRootStandsIn(2, pkg, pkg))
    }
}
