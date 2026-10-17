package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A window whose page sits past the generic 150-node budget is walked once more
 * at the browser budget. These drive the real walker, policy and memo over fake
 * trees and count node fetches, which is what the escalation costs on a device.
 */
class WindowEscalationTest {

    private val generic = InspectionPolicy.GENERIC_LIMITS.maxNodes
    private val wideBudget = InspectionPolicy.BROWSER_LIMITS.maxNodes

    /** Child fetches of a full pass over a tree that fits every budget in play. */
    private fun fetchesOf(tree: FakeNode) = tree.count() - 1

    private fun rows(n: Int) = List(n) { FakeNode(name = "row$it") }

    /** [rowsBefore] empty rows, then a large WebView (pre-order index rowsBefore + 1) and a host-like label. */
    private fun deepPage(rowsBefore: Int) = FakeNode(
        name = "root",
        children = rows(rowsBefore) + listOf(webView(true), FakeNode(name = "label", text = "late.example")),
    )

    private fun walk(
        tree: FakeNode,
        kind: WindowKind = WindowKind.OTHER,
        known: WindowMemo.Entry? = null,
        mayEscalate: Boolean = true,
    ): Pair<WindowWalk, FakeAccess> {
        val access = FakeAccess()
        return WindowWalker.walk(tree, access, kind, known, mayEscalate) to access
    }

    private fun entry(verdict: WindowVerdict, escalated: Boolean, updatedAt: Long = 0L) =
        WindowMemo.Entry(1, "pkg", verdict, updatedAt, escalated)

    /** One ordinary window's life, in the order EnforcementService.inspectWindow does things. */
    private class Replay(val kind: WindowKind = WindowKind.OTHER, private val remembers: Boolean = true) {
        private val windowId = 7
        private val pkg = "com.example.social"
        val memo = WindowMemo()
        var lastSlowAt = 0L

        class Look(val at: Long, val walk: WindowWalk, val fetches: Int, val verdictAfter: WindowVerdict?)

        val looks = ArrayList<Look>()
        val entry: WindowMemo.Entry? get() = memo.find(windowId, pkg)

        fun event(now: Long, stateChange: Boolean, tree: FakeNode): Look? {
            val known = memo.find(windowId, pkg)
            if (!InspectionPolicy.shouldInspect(kind, stateChange, known, now, 0L, lastSlowAt)) return null
            if (!stateChange) lastSlowAt = now
            val access = FakeAccess()
            val walk = WindowWalker.walk(tree, access, kind, if (remembers) known else null, remembers)
            if (remembers) {
                InspectionPolicy.memoUpdate(walk, known)?.let { memo.record(windowId, pkg, it.verdict, now, it.escalated) }
            }
            return Look(now, walk, access.childCalls, memo.find(windowId, pkg)?.verdict).also { looks.add(it) }
        }

        /** Content changes every [every] ms from [from] until [until], as a live screen raises them. */
        fun contentChanges(from: Long, until: Long, every: Long, tree: FakeNode) {
            var now = from
            while (now <= until) {
                event(now, stateChange = false, tree = tree)
                now += every
            }
        }
    }

    // ------------------------------------------------ a window that fits never escalates

    @Test
    fun `a window inside the generic budget is walked once and never escalated`() {
        val fits = wide(generic - 1) // exactly 150 nodes
        val (result, access) = walk(fits)
        assertFalse(result.wide)
        assertEquals(generic - 1, access.childCalls)
        assertEquals(WindowVerdict.NO_WEBVIEW, InspectionPolicy.memoUpdate(result, null)!!.verdict)
        assertFalse(InspectionPolicy.memoUpdate(result, null)!!.escalated)
    }

    @Test
    fun `a page found inside the generic budget is not escalated either`() {
        val (result, access) = walk(deepPage(10))
        assertFalse(result.wide)
        assertEquals(fetchesOf(deepPage(10)), access.childCalls)
        assertTrue(result.scan.webViewSeen)
    }

    @Test
    fun `a page the first walk saw needs no second look even if that walk was cut short`() {
        val tree = FakeNode(name = "root", children = listOf(webView(true)) + rows(300))
        val (result, access) = walk(tree)
        assertTrue(result.scan.stats!!.truncated)
        assertTrue(result.scan.webViewSeen)
        assertFalse(result.wide)
        assertEquals(generic - 1, access.childCalls)
    }

    @Test
    fun `a window that fits in 150 nodes behaves as before over a long run`() {
        val replay = Replay()
        val screen = wide(40)
        replay.event(0L, stateChange = true, tree = screen)
        replay.contentChanges(from = 600L, until = 120_000L, every = 600L, tree = screen)
        assertTrue(replay.looks.all { it.fetches == 40 && !it.walk.wide })
        assertFalse(replay.entry!!.escalated)
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.entry!!.verdict)
        assertTrue(replay.looks.size <= 1 + 120_000 / InspectionPolicy.RECHECK_INTERVAL_MS)
    }

    // ---------------------------------------- a page past 150 but inside 500 is found

    @Test
    fun `one node over the generic budget is read by the escalated walk`() {
        val (result, access) = walk(wide(generic)) // 151 nodes
        assertTrue(result.wide)
        assertFalse(result.scan.stats!!.truncated)
        assertEquals((generic - 1) + generic, access.childCalls)
        val update = InspectionPolicy.memoUpdate(result, null)!!
        assertEquals(WindowVerdict.NO_WEBVIEW, update.verdict)
        assertTrue(update.escalated)
    }

    @Test
    fun `a page past node 150 and inside 500 is found and judged in the same inspection`() {
        val tree = deepPage(300)
        val (result, _) = walk(tree)
        assertTrue(result.wide)
        assertTrue(result.scan.webViewSeen)
        assertEquals("late.example", result.scan.best(isBrowser = false)?.host)
        val update = InspectionPolicy.memoUpdate(result, null)!!
        assertEquals(WindowVerdict.HAS_WEBVIEW, update.verdict)
        assertTrue(update.escalated)

        val block = SiteVerdict.judge(
            view = result.scan.best(isBrowser = false),
            isBrowser = false,
            mode = WebsiteMode.BLOCKLIST,
            custom = setOf("late.example"),
            presets = CompactDomainSet.EMPTY,
            webViewShown = { result.scan.webViewSeen },
            plainHost = { null },
        )
        assertNotNull(block)
    }

    @Test
    fun `a deep page is escalated once and then followed at the wide budget without another escalation`() {
        val tree = deepPage(300)
        val full = fetchesOf(tree)
        val replay = Replay()
        val first = replay.event(0L, stateChange = true, tree = tree)!!
        assertEquals((generic - 1) + full, first.fetches)
        assertEquals(WindowVerdict.HAS_WEBVIEW, replay.entry!!.verdict)
        assertTrue(replay.entry!!.escalated)

        replay.contentChanges(from = 600L, until = 60_000L, every = 600L, tree = tree)
        val followUps = replay.looks.drop(1)
        assertTrue(followUps.isNotEmpty())
        // Each follow-up is one wide pass. Walking generic first would add 149 to each.
        assertTrue(followUps.all { it.fetches == full && it.walk.wide })
        // Was SLOW_INTERVAL_MS. A deep page is followed at DEEP_PAGE_INTERVAL_MS
        // instead, because each of these follow-ups is a wide walk and doing
        // that at the slow interval cost more per minute than the blanket
        // walking this change set removed. Asserted as a floor, since the
        // event cadence here is not a divisor of the interval.
        assertTrue(
            followUps.zipWithNext { a, b -> b.at - a.at }
                .all { it >= InspectionPolicy.DEEP_PAGE_INTERVAL_MS },
        )
    }

    @Test
    fun `a deep page-free window is escalated once and then costs nothing between rechecks`() {
        val tree = wide(300)
        val full = fetchesOf(tree)
        val replay = Replay()
        val first = replay.event(0L, stateChange = true, tree = tree)!!
        assertEquals((generic - 1) + full, first.fetches)
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.entry!!.verdict)
        assertTrue(replay.entry!!.escalated)

        // Scrolling it: nothing at all until the recheck interval has passed.
        assertNull(replay.event(600L, stateChange = false, tree = tree))
        assertNull(replay.event(InspectionPolicy.RECHECK_INTERVAL_MS - 1, stateChange = false, tree = tree))

        replay.contentChanges(from = 600L, until = 60_000L, every = 600L, tree = tree)
        val rechecks = replay.looks.drop(1)
        assertTrue(rechecks.all { it.fetches == full && it.walk.wide })
        assertTrue(rechecks.zipWithNext { a, b -> b.at - a.at }.all { it >= InspectionPolicy.RECHECK_INTERVAL_MS })
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.entry!!.verdict)
    }

    @Test
    fun `escalating is cheaper over a minute than re-deriving the truncated answer every 1,2 seconds`() {
        val tree = wide(300)
        val escalating = Replay()
        escalating.event(0L, stateChange = true, tree = tree)
        escalating.contentChanges(600L, 60_000L, 600L, tree)

        // The same window when the outcome cannot be remembered: the old behaviour.
        val reDeriving = Replay(remembers = false)
        reDeriving.event(0L, stateChange = true, tree = tree)
        reDeriving.contentChanges(600L, 60_000L, 600L, tree)

        val after = escalating.looks.sumOf { it.fetches }
        val before = reDeriving.looks.sumOf { it.fetches }
        assertTrue("after=$after before=$before", after < before)
        assertTrue(reDeriving.looks.none { it.walk.wide })
    }

    // ------------------------------------ a window that truncates even at 500 escalates once

    @Test
    fun `a window too big for the wide budget is recorded unreadable, not page-free`() {
        val (result, access) = walk(deepPage(700))
        assertTrue(result.wide)
        assertTrue(result.scan.stats!!.truncated)
        assertFalse(result.scan.webViewSeen)
        assertEquals((generic - 1) + (wideBudget - 1), access.childCalls)
        val update = InspectionPolicy.memoUpdate(result, null)!!
        assertEquals(WindowVerdict.UNREADABLE, update.verdict)
        assertTrue(update.escalated)
        assertNull(InspectionPolicy.verdictToRecord(result.scan))
    }

    @Test
    fun `a window that truncates at 500 escalates exactly once and never again`() {
        val tree = deepPage(700)
        val replay = Replay()
        replay.event(0L, stateChange = true, tree = tree)
        replay.contentChanges(from = 600L, until = 120_000L, every = 600L, tree = tree)
        // A window-state event now and then, which is the other way a look is triggered.
        listOf(30_000L, 60_000L, 90_000L).forEach { replay.event(it + 1, stateChange = true, tree = tree) }

        val escalatingLooks = replay.looks.filter { it.fetches > generic - 1 }
        assertEquals(1, escalatingLooks.size)
        assertEquals(0L, escalatingLooks.single().at)
        assertTrue(replay.looks.drop(1).all { it.fetches == generic - 1 && !it.walk.wide })

        // The page verdict is never cached: at no point does the memo say page-free or page.
        assertTrue(replay.looks.all { it.verdictAfter == WindowVerdict.UNREADABLE })
        assertTrue(replay.entry!!.escalated)
        assertTrue("looks=${replay.looks.size}", replay.looks.size > 50)
    }

    @Test
    fun `an unreadable window is looked at like one never seen`() {
        val unreadable = entry(WindowVerdict.UNREADABLE, escalated = true, updatedAt = 99_999L)
        fun inspect(stateChange: Boolean, now: Long, lastSlowAt: Long) =
            InspectionPolicy.shouldInspect(WindowKind.OTHER, stateChange, unreadable, now, 0L, lastSlowAt)
        // A state change always looks, however recently it was last read.
        assertTrue(inspect(stateChange = true, now = 100_000L, lastSlowAt = 99_999L))
        // Content changes follow the slow interval, with no 5 s expiry in the way.
        assertTrue(inspect(stateChange = false, now = 100_000L, lastSlowAt = 98_800L))
        assertFalse(inspect(stateChange = false, now = 100_000L, lastSlowAt = 99_500L))
    }

    @Test
    fun `an unreadable window goes back to the generic budget`() {
        assertFalse(InspectionPolicy.startsWide(WindowKind.OTHER, entry(WindowVerdict.UNREADABLE, escalated = true)))
        val (result, access) = walk(deepPage(700), known = entry(WindowVerdict.UNREADABLE, escalated = true))
        assertFalse(result.wide)
        assertEquals(generic - 1, access.childCalls)
        assertNull(InspectionPolicy.memoUpdate(result, entry(WindowVerdict.UNREADABLE, escalated = true)))
    }

    @Test
    fun `an unreadable window that shrinks into the generic budget gets a real verdict`() {
        val replay = Replay()
        replay.event(0L, stateChange = true, tree = deepPage(700))
        assertEquals(WindowVerdict.UNREADABLE, replay.entry!!.verdict)
        val look = replay.event(2_000L, stateChange = true, tree = wide(20))!!
        assertFalse(look.walk.wide)
        assertEquals(WindowVerdict.NO_WEBVIEW, replay.entry!!.verdict)
        // The flag stays: a window that once needed the wide budget starts wide from here on.
        assertTrue(replay.entry!!.escalated)
        assertTrue(InspectionPolicy.startsWide(WindowKind.OTHER, replay.entry))
    }

    @Test
    fun `a wide look that is cut short does not overturn a window known to host a page`() {
        val known = entry(WindowVerdict.HAS_WEBVIEW, escalated = true)
        val (result, _) = walk(FakeNode(name = "root", children = rows(700) + listOf(webView(true))), known = known)
        assertTrue(result.wide)
        assertFalse(result.scan.webViewSeen)
        val update = InspectionPolicy.memoUpdate(result, known)!!
        assertEquals(WindowVerdict.HAS_WEBVIEW, update.verdict)
        assertTrue(update.escalated)
    }

    // --------------------------------------------------- the once-only guards

    @Test
    fun `an escalated window never qualifies again, whatever its verdict`() {
        val truncated = UrlScan().also { it.stats = TreeWalk.Stats(150, 149, true) }
        assertTrue(InspectionPolicy.shouldEscalate(WindowKind.OTHER, truncated, walkedWide = false, known = null))
        for (verdict in listOf(WindowVerdict.NO_WEBVIEW, WindowVerdict.HAS_WEBVIEW, WindowVerdict.UNREADABLE)) {
            assertFalse(
                verdict.name,
                InspectionPolicy.shouldEscalate(WindowKind.OTHER, truncated, walkedWide = false, known = entry(verdict, escalated = true)),
            )
        }
        // And a walk that was already wide is never followed by another.
        assertFalse(InspectionPolicy.shouldEscalate(WindowKind.OTHER, truncated, walkedWide = true, known = null))
    }

    @Test
    fun `the escalation flag survives later records of the same window`() {
        val memo = WindowMemo()
        memo.record(7, "p", WindowVerdict.NO_WEBVIEW, 1L, escalated = true)
        memo.record(7, "p", WindowVerdict.HAS_WEBVIEW, 2L)
        assertTrue(memo.find(7, "p")!!.escalated)
    }

    @Test
    fun `a recycled slot does not inherit the previous window's escalation`() {
        val memo = WindowMemo(capacity = 1)
        memo.record(1, "p", WindowVerdict.NO_WEBVIEW, 1L, escalated = true)
        memo.record(2, "p", WindowVerdict.NO_WEBVIEW, 2L)
        assertFalse(memo.find(2, "p")!!.escalated)
        assertNull(memo.find(1, "p"))
    }

    @Test
    fun `a window whose outcome cannot be remembered is never escalated`() {
        // No window id, or a root that is not the event's window: nowhere to keep the flag,
        // so a second walk would be repeated on every look.
        val replay = Replay(remembers = false)
        replay.event(0L, stateChange = true, tree = deepPage(300))
        replay.contentChanges(1_200L, 30_000L, 1_200L, deepPage(300))
        assertTrue(replay.looks.all { it.fetches == generic - 1 && !it.walk.wide })
        assertNull(replay.entry)
        assertFalse(walk(deepPage(300), mayEscalate = false).first.wide)
    }

    @Test
    fun `a wide walk that throws is contained and still counts as the escalation`() {
        val inner = FakeAccess()
        val failing = object : NodeAccess<FakeNode> by inner {
            var calls = 0
            override fun child(node: FakeNode, index: Int): FakeNode? {
                if (++calls > generic - 1) error("binder died")
                return inner.child(node, index)
            }
        }
        val result = WindowWalker.walk(wide(300), failing, WindowKind.OTHER, null, mayEscalate = true)
        assertTrue(result.wide)
        assertTrue(result.scan.stats!!.truncated)
        val update = InspectionPolicy.memoUpdate(result, null)!!
        assertEquals(WindowVerdict.UNREADABLE, update.verdict)
        assertTrue(update.escalated)
    }

    // -------------------------------------- browsers and the lock are untouched

    @Test
    fun `a browser walks once at the wide budget and is never escalated`() {
        val fitsWide = wide(300)
        val (small, smallAccess) = walk(fitsWide, kind = WindowKind.BROWSER)
        assertTrue(small.wide)
        assertEquals(fetchesOf(fitsWide), smallAccess.childCalls)

        val (big, bigAccess) = walk(wide(700), kind = WindowKind.BROWSER)
        assertTrue(big.wide)
        assertTrue(big.scan.stats!!.truncated)
        // One pass cut off at the browser budget; no second pass.
        assertEquals(wideBudget - 1, bigAccess.childCalls)
    }

    @Test
    fun `the lock path is never escalated`() {
        val (big, access) = walk(wide(700), kind = WindowKind.LOCKABLE)
        assertTrue(big.wide)
        assertEquals(wideBudget - 1, access.childCalls)
    }

    @Test
    fun `browser and lockable kinds ignore escalation state entirely`() {
        val truncated = UrlScan().also { it.stats = TreeWalk.Stats(500, 499, true) }
        for (kind in listOf(WindowKind.BROWSER, WindowKind.LOCKABLE)) {
            assertFalse(kind.name, InspectionPolicy.shouldEscalate(kind, truncated, walkedWide = false, known = null))
            assertTrue(kind.name, InspectionPolicy.startsWide(kind, null))
            assertTrue(kind.name, InspectionPolicy.startsWide(kind, entry(WindowVerdict.UNREADABLE, escalated = true)))
            // The cadence does not depend on what the memo holds.
            val entries = listOf(
                null,
                entry(WindowVerdict.NO_WEBVIEW, escalated = true, updatedAt = 99_999L),
                entry(WindowVerdict.UNREADABLE, escalated = true, updatedAt = 99_999L),
                entry(WindowVerdict.HAS_WEBVIEW, escalated = false, updatedAt = 99_999L),
            )
            for (known in entries) {
                assertTrue(InspectionPolicy.shouldInspect(kind, true, known, 100_000L, 99_990L, 0L))
                assertFalse(InspectionPolicy.shouldInspect(kind, false, known, 100_000L, 99_900L, 0L))
                assertTrue(InspectionPolicy.shouldInspect(kind, false, known, 100_000L, 99_700L, 0L))
            }
        }
    }

    @Test
    fun `an ordinary window starts wide only once escalated and not unreadable`() {
        assertFalse(InspectionPolicy.startsWide(WindowKind.OTHER, null))
        assertFalse(InspectionPolicy.startsWide(WindowKind.OTHER, entry(WindowVerdict.NO_WEBVIEW, escalated = false)))
        assertFalse(InspectionPolicy.startsWide(WindowKind.OTHER, entry(WindowVerdict.HAS_WEBVIEW, escalated = false)))
        assertTrue(InspectionPolicy.startsWide(WindowKind.OTHER, entry(WindowVerdict.NO_WEBVIEW, escalated = true)))
        assertTrue(InspectionPolicy.startsWide(WindowKind.OTHER, entry(WindowVerdict.HAS_WEBVIEW, escalated = true)))
    }
}
