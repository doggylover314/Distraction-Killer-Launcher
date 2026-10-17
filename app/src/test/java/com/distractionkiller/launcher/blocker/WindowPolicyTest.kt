package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPolicyTest {

    private fun entry(verdict: WindowVerdict, updatedAt: Long = 0L) = WindowMemo.Entry(1, "pkg", verdict, updatedAt)

    private fun inspect(
        kind: WindowKind,
        stateChange: Boolean,
        known: WindowMemo.Entry? = null,
        now: Long = 100_000L,
        lastFastAt: Long = 0L,
        lastSlowAt: Long = 0L,
    ) = InspectionPolicy.shouldInspect(kind, stateChange, known, now, lastFastAt, lastSlowAt)

    // ------------------------------------------------------------ the policy

    @Test
    fun `scrolling an ordinary app costs nothing once its window is known`() {
        val known = entry(WindowVerdict.NO_WEBVIEW, updatedAt = 99_000L)
        // A thousand content events over ten minutes, none of them due to anything.
        var inspections = 0
        for (i in 0 until 1_000) {
            if (inspect(WindowKind.OTHER, stateChange = false, known = known, now = 100_000L + i * 600L)) inspections++
        }
        assertEquals(0, inspections)
    }

    @Test
    fun `an unseen ordinary window is looked at once, at the slow interval`() {
        assertTrue(inspect(WindowKind.OTHER, stateChange = false, known = null, now = 10_000L, lastSlowAt = 0L))
        assertFalse(inspect(WindowKind.OTHER, stateChange = false, known = null, now = 10_000L, lastSlowAt = 9_500L))
        assertTrue(inspect(WindowKind.OTHER, stateChange = false, known = null, now = 10_000L, lastSlowAt = 8_800L))
    }

    @Test
    fun `a window known to host a page keeps being followed at the slow interval`() {
        val page = entry(WindowVerdict.HAS_WEBVIEW)
        assertTrue(inspect(WindowKind.OTHER, stateChange = false, known = page, now = 50_000L, lastSlowAt = 48_000L))
        assertFalse(inspect(WindowKind.OTHER, stateChange = false, known = page, now = 50_000L, lastSlowAt = 49_000L))
    }

    @Test
    fun `a window state change always inspects an unseen or page-hosting window`() {
        assertTrue(inspect(WindowKind.OTHER, stateChange = true, known = null, lastSlowAt = Long.MAX_VALUE / 2))
        assertTrue(inspect(WindowKind.OTHER, stateChange = true, known = entry(WindowVerdict.HAS_WEBVIEW), lastSlowAt = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a window known to have no page is not walked again until the recheck interval passes`() {
        val known = entry(WindowVerdict.NO_WEBVIEW, updatedAt = 100_000L)
        assertFalse(inspect(WindowKind.OTHER, stateChange = true, known = known, now = 100_100L))
        assertFalse(inspect(WindowKind.OTHER, stateChange = true, known = known, now = 104_999L))
        assertTrue(inspect(WindowKind.OTHER, stateChange = true, known = known, now = 105_000L))
    }

    @Test
    fun `a window that is not an application window is not inspected again`() {
        val overlay = entry(WindowVerdict.NOT_INSPECTABLE)
        listOf(WindowKind.BROWSER, WindowKind.OTHER).forEach { kind ->
            assertFalse(inspect(kind, stateChange = true, known = overlay))
            assertFalse(inspect(kind, stateChange = false, known = overlay))
        }
    }

    @Test
    fun `the lock is not switched off by a stale not-inspectable entry`() {
        val overlay = entry(WindowVerdict.NOT_INSPECTABLE)
        assertTrue(inspect(WindowKind.LOCKABLE, stateChange = true, known = overlay))
        assertTrue(inspect(WindowKind.LOCKABLE, stateChange = false, known = overlay, lastFastAt = 0L))
    }

    @Test
    fun `browsers and lockable windows keep the fast cadence and always judge a state change`() {
        listOf(WindowKind.BROWSER, WindowKind.LOCKABLE).forEach { kind ->
            // The memo says nothing about them even if it has an entry.
            val known = entry(WindowVerdict.NO_WEBVIEW, updatedAt = 99_999L)
            assertTrue(inspect(kind, stateChange = true, known = known, lastFastAt = 99_990L))
            assertFalse(inspect(kind, stateChange = false, known = known, now = 100_000L, lastFastAt = 99_900L))
            assertTrue(inspect(kind, stateChange = false, known = known, now = 100_000L, lastFastAt = 99_700L))
            assertTrue(inspect(kind, stateChange = false, known = null, now = 100_000L, lastFastAt = 0L))
        }
    }

    @Test
    fun `the secondary sweep is rarer than the address bar check`() {
        assertFalse(InspectionPolicy.sweepDue(now = 10_000L, lastSweepAt = 9_500L))
        assertTrue(InspectionPolicy.sweepDue(now = 10_000L, lastSweepAt = 8_800L))
    }

    @Test
    fun `limits are tighter for ordinary apps than for browsers and settings`() {
        assertTrue(InspectionPolicy.GENERIC_LIMITS.maxNodes < InspectionPolicy.BROWSER_LIMITS.maxNodes)
        assertTrue(InspectionPolicy.GENERIC_LIMITS.maxDepth < InspectionPolicy.BROWSER_LIMITS.maxDepth)
        assertEquals(500, InspectionPolicy.LOCK_LIMITS.maxNodes)
    }

    // ----------------------------------------------------- the focused root

    @Test
    fun `the focused window answers for an event from that same window`() {
        assertTrue(InspectionPolicy.activeRootAnswers(7, 7, "com.android.chrome", "com.android.chrome"))
    }

    @Test
    fun `a browser in the unfocused half of split-screen is not answered by the focused window`() {
        // The chat app has focus (window 3); the event is from Chrome's pane (window 5).
        assertFalse(InspectionPolicy.activeRootAnswers(5, 3, "com.android.chrome", "com.whatsapp"))
        // Two panes of the same browser: same package, different windows.
        assertFalse(InspectionPolicy.activeRootAnswers(5, 3, "com.android.chrome", "com.android.chrome"))
    }

    @Test
    fun `an event with no window id or a mismatched package takes the window-list path`() {
        assertFalse(InspectionPolicy.activeRootAnswers(-1, -1, "p", "p"))
        assertFalse(InspectionPolicy.activeRootAnswers(7, 7, "p", "q"))
        assertFalse(InspectionPolicy.activeRootAnswers(7, 7, "p", null))
    }

    // ------------------------------------------------------------- the memo

    @Test
    fun `a window is found by id together with its package`() {
        val memo = WindowMemo()
        memo.record(10, "a", WindowVerdict.NO_WEBVIEW, now = 1L)
        assertNotNull(memo.find(10, "a"))
        assertNull(memo.find(10, "b"))
        assertNull(memo.find(11, "a"))
    }

    @Test
    fun `recording again updates in place rather than adding`() {
        val memo = WindowMemo()
        memo.record(10, "a", WindowVerdict.NO_WEBVIEW, now = 1L)
        val first = memo.find(10, "a")
        memo.record(10, "a", WindowVerdict.HAS_WEBVIEW, now = 9L)
        assertEquals(1, memo.size)
        assertSame(first, memo.find(10, "a"))
        assertEquals(WindowVerdict.HAS_WEBVIEW, first!!.verdict)
        assertEquals(9L, first.updatedAt)
    }

    @Test
    fun `the memo never grows past its capacity however many windows come and go`() {
        val memo = WindowMemo(capacity = 8)
        repeat(10_000) { memo.record(it, "pkg", WindowVerdict.NO_WEBVIEW, now = it.toLong()) }
        assertEquals(8, memo.size)
    }

    @Test
    fun `the least recently used window is the one replaced`() {
        val memo = WindowMemo(capacity = 3)
        memo.record(1, "p", WindowVerdict.NO_WEBVIEW, 0L)
        memo.record(2, "p", WindowVerdict.NO_WEBVIEW, 0L)
        memo.record(3, "p", WindowVerdict.NO_WEBVIEW, 0L)
        // Touching window 1 makes window 2 the oldest.
        memo.find(1, "p")
        memo.record(4, "p", WindowVerdict.HAS_WEBVIEW, 0L)
        assertNotNull(memo.find(1, "p"))
        assertNull(memo.find(2, "p"))
        assertNotNull(memo.find(3, "p"))
        assertNotNull(memo.find(4, "p"))
    }

    @Test
    fun `clearing forgets everything and the slots are reusable`() {
        val memo = WindowMemo(capacity = 2)
        memo.record(1, "p", WindowVerdict.HAS_WEBVIEW, 0L)
        memo.record(2, "p", WindowVerdict.HAS_WEBVIEW, 0L)
        memo.clear()
        assertEquals(0, memo.size)
        assertNull(memo.find(1, "p"))
        memo.record(3, "p", WindowVerdict.NO_WEBVIEW, 0L)
        assertEquals(1, memo.size)
        assertEquals(WindowVerdict.NO_WEBVIEW, memo.find(3, "p")!!.verdict)
    }

    @Test
    fun `an undefined window id is never remembered`() {
        val memo = WindowMemo()
        memo.record(-1, "p", WindowVerdict.NO_WEBVIEW, 0L)
        assertEquals(0, memo.size)
        assertNull(memo.find(-1, "p"))
    }
}
