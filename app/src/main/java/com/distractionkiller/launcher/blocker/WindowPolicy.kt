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
 * app scrolls, so an ordinary app costs at most one bounded walk per window per
 * [RECHECK_INTERVAL_MS], and the old cadence is kept only where the user is
 * actually navigating something the service must judge.
 */
internal object InspectionPolicy {
    const val FAST_INTERVAL_MS = 300L
    const val SLOW_INTERVAL_MS = 1_200L

    /**
     * An ordinary window already known to hold no web page is looked at again,
     * on a state change or a content change, only this long after the last
     * look. A fragment swap, a bottom sheet or a Compose destination can bring
     * a WebView into a window that was judged page-free, and then the window
     * may raise no state event at all, only content changes. A look refreshes
     * the entry (see [WindowMemo.record]), so a chatty app is walked once per
     * interval and not once per event.
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
                // "No page" is a snapshot, not a fact. Without this expiry a window
                // judged page-free once was never read again on content changes, so
                // an in-app browser opened inside it was never judged.
                else -> now - known.updatedAt >= RECHECK_INTERVAL_MS && now - lastSlowAt >= SLOW_INTERVAL_MS
            }
        }
    }

    /**
     * What a walk of an ordinary window may teach the memo, or null to teach it
     * nothing. A page that was seen stays true however the walk ended. "No page"
     * is only true if the walk read the whole window: when the node or depth
     * budget ran out the WebView may simply lie in the part not read, and the
     * budget is deterministic over an unchanged hierarchy, so a cached negative
     * would be re-derived identically on every recheck and never corrected.
     * Left unrecorded, the window keeps the cadence of one never seen, which is
     * the right cost for a window the service could not read. Missing stats are
     * treated like truncation.
     */
    fun verdictToRecord(scan: UrlScan): WindowVerdict? = when {
        scan.webViewSeen -> WindowVerdict.HAS_WEBVIEW
        scan.stats?.truncated == false -> WindowVerdict.NO_WEBVIEW
        else -> null
    }

    /**
     * Remembers that a window is not an application window (keyboard, overlay),
     * except for a lockable package. [shouldInspect] already ignores the entry for
     * those, but a system window of a lockable package can later become the
     * focused one, so the write side refuses too and neither protection depends
     * on the other staying in place.
     */
    fun rememberNotInspectable(memo: WindowMemo, windowId: Int, packageName: String, now: Long) {
        if (SettingsLockDetector.isLockablePackage(packageName)) return
        memo.record(windowId, packageName, WindowVerdict.NOT_INSPECTABLE, now)
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

    /**
     * Whether the focused window's root stands in when the window list yielded
     * no root for the event. Deliberately blind to the event's window id: the
     * list can come back empty for an id it was given (the window's root is
     * still null right after a window change, or the window is not in the list
     * yet), and an event that carries an id is the common case, so gating this
     * on a missing id left exactly those events with no inspection at all, for
     * the Settings lock as much as for websites. The package check is what
     * stops another app's tree being walked under this package's name.
     */
    fun activeRootStandsIn(rootsFromWindowList: Int, eventPackage: String, activePackage: String?): Boolean =
        rootsFromWindowList == 0 && activePackage != null && activePackage == eventPackage
}
