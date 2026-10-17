package com.distractionkiller.launcher.blocker

/** What kind of window an event came from; decides how closely it is watched. */
internal enum class WindowKind {
    /** A browser: the user is navigating, so the address bar is checked often. */
    BROWSER,

    /** Settings and the system pickers: the user is navigating, so checked often. */
    LOCKABLE,

    /** Everything else. Only worth a look if it turns out to host a web page. */
    OTHER,
}

/** What a past inspection of one window taught us. */
internal enum class WindowVerdict {
    NO_WEBVIEW,

    /** An in-app browser or similar: its URL changes without a new window. */
    HAS_WEBVIEW,

    /** Not an application window (keyboard, overlay, shade). Its type never changes. */
    NOT_INSPECTABLE,
}

/**
 * What was learned per window, so the same answer is not re-derived on every
 * event. Keyed on the accessibility window id together with the package: ids
 * are not reused while a window lives, and a new window (an activity, a dialog)
 * gets a new one, which is what invalidates the entry. A fixed number of slots
 * with least-recently-used replacement keeps it bounded however many windows
 * come and go, and a linear scan over a few dozen ints allocates nothing.
 */
internal class WindowMemo(private val capacity: Int = DEFAULT_CAPACITY) {

    class Entry(
        var windowId: Int,
        var packageName: String,
        var verdict: WindowVerdict,
        /** uptimeMillis of the inspection that produced [verdict]. */
        var updatedAt: Long,
    ) {
        internal var lastUsed = 0L
    }

    private val slots = arrayOfNulls<Entry>(capacity)
    private var used = 0
    private var tick = 0L

    val size: Int get() = used

    fun find(windowId: Int, packageName: String): Entry? {
        if (windowId < 0) return null
        for (i in 0 until used) {
            val e = slots[i]!!
            if (e.windowId == windowId && e.packageName == packageName) {
                e.lastUsed = ++tick
                return e
            }
        }
        return null
    }

    fun record(windowId: Int, packageName: String, verdict: WindowVerdict, now: Long) {
        if (windowId < 0) return
        val existing = find(windowId, packageName)
        if (existing != null) {
            existing.verdict = verdict
            existing.updatedAt = now
            return
        }
        val slot = if (used < capacity) used++ else leastRecentlyUsed()
        val entry = slots[slot]
        if (entry == null) {
            slots[slot] = Entry(windowId, packageName, verdict, now).also { it.lastUsed = ++tick }
        } else {
            entry.windowId = windowId
            entry.packageName = packageName
            entry.verdict = verdict
            entry.updatedAt = now
            entry.lastUsed = ++tick
        }
    }

    fun clear() {
        used = 0
        tick = 0
        slots.fill(null)
    }

    private fun leastRecentlyUsed(): Int {
        var oldest = 0
        for (i in 1 until used) if (slots[i]!!.lastUsed < slots[oldest]!!.lastUsed) oldest = i
        return oldest
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
    }
}

/**
 * When a window is worth reading. The expensive part of inspecting is walking
 * the tree, because every node fetched is a cross-process call served by the
 * inspected app's UI thread. Content-changed events fire continuously while an
 * app scrolls, so the policy spends nothing on them for ordinary apps and
 * keeps the old cadence only where the user is actually navigating something
 * the service must judge.
 */
internal object InspectionPolicy {
    const val FAST_INTERVAL_MS = 300L
    const val SLOW_INTERVAL_MS = 1_200L

    /**
     * An ordinary window already known to hold no web page is looked at again
     * on a window-state event only this long after the last look. A fragment
     * swap that brings in a WebView (some cross-platform apps navigate to a web
     * view screen inside one window) announces itself as a state change, while
     * a chatty app that repeats state changes does not get walked each time.
     */
    const val RECHECK_INTERVAL_MS = 5_000L

    /** The secondary host sweep in a browser (see [SiteVerdict.judge]) is rarer than the address-bar check. */
    const val SWEEP_INTERVAL_MS = 1_200L

    /** Browser chrome and Settings are shallow and the strings that matter can be anywhere in them. */
    val BROWSER_LIMITS = WalkLimits(maxNodes = 500, maxDepth = 32)
    val LOCK_LIMITS = WalkLimits(maxNodes = 500, maxDepth = 32)

    /**
     * An in-app browser is a toolbar and a WebView, tens of nodes. A tighter
     * budget and depth than the browser path means a big native screen cannot
     * cost more than a small fraction of the old walk.
     */
    val GENERIC_LIMITS = WalkLimits(maxNodes = 150, maxDepth = 20)

    fun shouldInspect(
        kind: WindowKind,
        stateChange: Boolean,
        known: WindowMemo.Entry?,
        now: Long,
        lastFastAt: Long,
        lastSlowAt: Long,
    ): Boolean {
        // The lock keeps looking regardless: a system window of a lockable package
        // can later become the focused one, and the focused window is always
        // answerable without the window list, so there is nothing to save.
        if (kind != WindowKind.LOCKABLE && known?.verdict == WindowVerdict.NOT_INSPECTABLE) return false
        return when (kind) {
            WindowKind.BROWSER, WindowKind.LOCKABLE -> stateChange || now - lastFastAt >= FAST_INTERVAL_MS
            WindowKind.OTHER -> when {
                stateChange ->
                    known == null || known.verdict == WindowVerdict.HAS_WEBVIEW ||
                        now - known.updatedAt >= RECHECK_INTERVAL_MS
                // Never seen (the service attached mid-window, or the state event was
                // coalesced away): look once. Known to hold a page: keep following it.
                known == null || known.verdict == WindowVerdict.HAS_WEBVIEW -> now - lastSlowAt >= SLOW_INTERVAL_MS
                else -> false
            }
        }
    }

    fun sweepDue(now: Long, lastSweepAt: Long): Boolean = now - lastSweepAt >= SWEEP_INTERVAL_MS

    /**
     * Whether the root of the active window is the root of the window that
     * raised the event, so the window list need not be read at all. In
     * split-screen the unfocused pane has its own window id, so a browser in
     * the half without focus fails this test and takes the slower path that
     * looks the window up by id.
     */
    fun activeRootAnswers(
        eventWindowId: Int,
        activeWindowId: Int,
        eventPackage: String,
        activePackage: String?,
    ): Boolean = eventWindowId >= 0 && eventWindowId == activeWindowId && eventPackage == activePackage
}
