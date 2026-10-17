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

    /**
     * Walked at the widest budget and still cut short, so whether it holds a page
     * is unknown. Holds the "already escalated" flag for a window that has no
     * page verdict to hang it on; the policy looks at it as it would a window
     * never seen.
     */
    UNREADABLE,
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
        /**
         * Set once this window has needed the wide walk budget (see
         * [InspectionPolicy.shouldEscalate]). Never cleared, which is what stops a
         * window being escalated a second time.
         */
        var escalated: Boolean = false,
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

    fun record(windowId: Int, packageName: String, verdict: WindowVerdict, now: Long, escalated: Boolean = false) {
        if (windowId < 0) return
        val existing = find(windowId, packageName)
        if (existing != null) {
            existing.verdict = verdict
            existing.updatedAt = now
            existing.escalated = existing.escalated || escalated
            return
        }
        val slot = if (used < capacity) used++ else leastRecentlyUsed()
        val entry = slots[slot]
        if (entry == null) {
            slots[slot] = Entry(windowId, packageName, verdict, now, escalated).also { it.lastUsed = ++tick }
        } else {
            entry.windowId = windowId
            entry.packageName = packageName
            entry.verdict = verdict
            entry.updatedAt = now
            entry.escalated = escalated
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

    /**
     * How often a page inside a window too deep for [GENERIC_LIMITS] is
     * followed. Such a window starts wide once escalated, so every follow-up
     * costs a walk of up to [BROWSER_LIMITS] nodes. Following it at
     * [SLOW_INTERVAL_MS] would cost more per minute than the unconditional
     * walking this whole change set replaced, which would be a strange way to
     * answer a complaint about lag. A deep page is followed at half the rate
     * instead: a URL change is noticed up to this long after it happens, which
     * is the price of reading a hierarchy that large at all. Shallow in-app
     * browsers, the common case, are unaffected and keep [SLOW_INTERVAL_MS].
     *
     * The value is set by that cost ceiling, not picked for roundness: a wide
     * walk is [BROWSER_LIMITS] nodes against the generic [GENERIC_LIMITS], so
     * the interval has to be more than three times [SLOW_INTERVAL_MS] to come
     * out below what it replaced. It happens to match [RECHECK_INTERVAL_MS],
     * which is a separate rule and free to move independently.
     */
    const val DEEP_PAGE_INTERVAL_MS = 5_000L

    /** Browser chrome and Settings are shallow and the strings that matter can be anywhere in them. */
    val BROWSER_LIMITS = WalkLimits(maxNodes = 500, maxDepth = 32)
    val LOCK_LIMITS = WalkLimits(maxNodes = 500, maxDepth = 32)

    /**
     * An in-app browser is a toolbar and a WebView, tens of nodes. A tighter
     * budget and depth than the browser path means a big native screen cannot
     * cost more than a small fraction of the old walk. A window that does not
     * fit is walked once more at [BROWSER_LIMITS] (see [shouldEscalate]).
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
            WindowKind.OTHER -> {
                // Unjudged: never seen (the service attached mid-window, or the state
                // event was coalesced away), or too big to read even at the widest
                // budget. Neither holds a page verdict, so both get the unseen cadence.
                val unjudged = known == null || known.verdict == WindowVerdict.UNREADABLE
                val age = if (known == null) Long.MAX_VALUE else now - known.updatedAt
                val followsPage = known?.verdict == WindowVerdict.HAS_WEBVIEW
                // A deep page costs a wide walk every time it is followed, so it
                // is followed less often. See [DEEP_PAGE_INTERVAL_MS].
                val followInterval =
                    if (followsPage && known?.escalated == true) DEEP_PAGE_INTERVAL_MS else SLOW_INTERVAL_MS
                when {
                    stateChange -> unjudged || followsPage || age >= RECHECK_INTERVAL_MS
                    // Unjudged: look once. Known to hold a page: keep following it.
                    unjudged || followsPage -> now - lastSlowAt >= followInterval
                    // "No page" is a snapshot, not a fact. Without this expiry a window
                    // judged page-free once was never read again on content changes, so
                    // an in-app browser opened inside it was never judged.
                    else -> age >= RECHECK_INTERVAL_MS && now - lastSlowAt >= SLOW_INTERVAL_MS
                }
            }
        }
    }

    /**
     * How long until a look that [shouldInspect] just turned down would be
     * allowed, for the windows the user navigates (browsers and lockable
     * system screens), or null when no follow-up look is owed. Without one,
     * the last content change of a burst that lands inside the throttle (the
     * address bar settling on the final URL, a Settings page that changes once
     * and then sits still) is never judged if nothing else happens after it.
     * Ordinary windows are left to their own slower cadence.
     */
    fun trailingDelay(kind: WindowKind, stateChange: Boolean, known: WindowMemo.Entry?, now: Long, lastFastAt: Long): Long? {
        if (stateChange || kind == WindowKind.OTHER) return null
        if (kind != WindowKind.LOCKABLE && known?.verdict == WindowVerdict.NOT_INSPECTABLE) return null
        val wait = FAST_INTERVAL_MS - (now - lastFastAt)
        return if (wait > 0) wait else null
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
     * Whether the first walk of a window already uses the wide budget. Browsers
     * and the lock always do. An ordinary window does once it has been escalated,
     * because from then on a walk at the generic budget would just truncate again:
     * a page found past node 150 would be missed, and a page-free verdict could
     * never be refreshed. An unreadable window goes back to the generic budget,
     * since a second wide walk has already shown it will not fit.
     */
    fun startsWide(kind: WindowKind, known: WindowMemo.Entry?): Boolean = when (kind) {
        WindowKind.BROWSER, WindowKind.LOCKABLE -> true
        WindowKind.OTHER -> known != null && known.escalated && known.verdict != WindowVerdict.UNREADABLE
    }

    /**
     * Whether an ordinary window whose generic walk just ended should be walked
     * once more at [BROWSER_LIMITS]. A page that sits past node 150 is otherwise
     * invisible: the budget is deterministic, so every later look truncates at the
     * same node, and the window is either cached as page-free or re-walked at
     * the unseen cadence forever to learn nothing.
     *
     * Once per window: [WindowMemo.Entry.escalated] is set by whatever the wide
     * walk finds, so a window that has it never qualifies again. Browsers and the
     * lock already walk wide, and a page that was seen needs no second look.
     */
    fun shouldEscalate(kind: WindowKind, scan: UrlScan, walkedWide: Boolean, known: WindowMemo.Entry?): Boolean =
        kind == WindowKind.OTHER &&
            !walkedWide &&
            known?.escalated != true &&
            !scan.webViewSeen &&
            scan.stats?.truncated == true

    /** What an inspection leaves in the memo: the verdict, and whether the window has now been escalated. */
    class MemoUpdate(val verdict: WindowVerdict, val escalated: Boolean)

    /**
     * What a finished walk of an ordinary window writes to the memo, or null to
     * leave the entry alone. A complete walk, or a page seen, is [verdictToRecord].
     * A wide walk that was still cut short has nothing durable to say about the
     * page, but it must still leave the escalation flag, or the next look would
     * escalate again; it is recorded as [WindowVerdict.UNREADABLE], except that a
     * window already known to host a page keeps that positive observation.
     */
    fun memoUpdate(walk: WindowWalk, known: WindowMemo.Entry?): MemoUpdate? {
        verdictToRecord(walk.scan)?.let { return MemoUpdate(it, walk.wide) }
        if (!walk.wide) return null
        val keepsPage = known?.verdict == WindowVerdict.HAS_WEBVIEW
        return MemoUpdate(if (keepsPage) WindowVerdict.HAS_WEBVIEW else WindowVerdict.UNREADABLE, true)
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
