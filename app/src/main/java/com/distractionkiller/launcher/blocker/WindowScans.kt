package com.distractionkiller.launcher.blocker

/**
 * A node that might be showing the page's URL, copied out of the tree during
 * the walk. Holding the facts instead of the node means the walk can release
 * every node it fetched, and the verdict never has to go back across the
 * process boundary to re-read a field.
 */
internal class UrlCandidate(
    val text: CharSequence,
    /** Host of [text], taken before the hint rule is applied (see [SiteVerdict]). */
    val host: String?,
    /** True for a proper address bar (known id, URL-ish id, or editable). */
    val isAddressBar: Boolean,
    val focused: Boolean,
    val showingHint: Boolean,
)

/** Everything one walk of a window learns; a window is read once, not once per question. */
internal class UrlScan {
    var webViewSeen = false
    var idMatch: UrlCandidate? = null
    var editable: UrlCandidate? = null
    var plain: UrlCandidate? = null
    var stats: TreeWalk.Stats? = null

    /**
     * The one candidate to judge. A URL-ish id without a host only counts in a
     * real browser, where an empty editable bar is meaningful (new tab).
     * Elsewhere it is a search box or a login field and says nothing about the
     * page. And a non-browser window is judged at all only if it holds a large
     * WebView, because that is what separates an in-app browser from a chat.
     */
    fun best(isBrowser: Boolean): UrlCandidate? {
        if (!isBrowser && !webViewSeen) return null
        return idMatch?.takeIf { isBrowser || it.host != null } ?: editable ?: plain
    }
}

/**
 * How tall a WebView must be to count as the page of an in-app browser and not
 * an ad banner. The yardstick is the window the WebView lives in. The display
 * is the wrong one: in split-screen the app's whole window is about half the
 * display, so a WebView filling it could never reach 40% of the display's
 * height. A miss is costly, because a window that holds no large WebView is not
 * judged at all.
 *
 * A sheet shorter than its own window can still fall short of the threshold.
 * That miss no longer sticks, since a page-free verdict now expires (see
 * [InspectionPolicy.RECHECK_INTERVAL_MS]), but it can last that long.
 */
internal object WebViewSize {

    fun minHeightPx(windowHeightPx: Int, displayHeightPx: Int): Int {
        // Bounds that are empty, or taller than the display, say nothing
        // trustworthy about the window, so the display stands in.
        val reference = if (windowHeightPx > 0 && (displayHeightPx <= 0 || windowHeightPx < displayHeightPx)) {
            windowHeightPx
        } else {
            displayHeightPx
        }
        return (reference.coerceAtLeast(0).toLong() * 2 / 5).toInt()
    }
}

internal object UrlScanner {

    /** Substrings of view ids that mark a URL display in any app. */
    private val URL_VIEW_ID_HINTS = arrayOf(
        "url_bar", "urlbar", "url_view", "url_text", "url_field", "omnibox", "omnibar",
        "address_bar", "addressbar", "location_bar", "locationbar",
    )

    fun hasUrlHint(viewId: String): Boolean {
        for (hint in URL_VIEW_ID_HINTS) if (viewId.contains(hint, ignoreCase = true)) return true
        return false
    }

    /**
     * One pass over [root] that records the candidate URL nodes, whether a
     * large WebView is present and (via [UrlScan.stats]) what the pass cost.
     *
     * Text inside a WebView is never read, so a page that merely mentions
     * "reddit.com" cannot trigger a block. The first node in each category
     * wins, and a slot that is already taken is not examined again, which also
     * skips the host parse for every later node that could not change the
     * outcome.
     */
    fun <N : Any> scan(root: N, access: NodeAccess<N>, limits: WalkLimits): UrlScan {
        val scan = UrlScan()
        scan.stats = TreeWalk.walk(root, access, limits) { node, _ ->
            if (access.isWebView(node)) {
                // Only a WebView that takes up a real share of the screen marks
                // this window as showing a page; an ad banner does not.
                if (access.isLargeWebView(node)) scan.webViewSeen = true
                return@walk false // never descend into page content
            }
            val id = access.viewId(node)
            val editable = access.isEditable(node)
            when {
                id != null && hasUrlHint(id) -> if (scan.idMatch == null) {
                    val text = access.displayText(node)
                    val host = text?.let { UrlMatcher.hostOf(it) }
                    if (host != null || editable) scan.idMatch = candidate(access, node, text, host, true)
                }
                editable -> if (scan.editable == null) {
                    val text = access.displayText(node)
                    val host = text?.let { UrlMatcher.hostOf(it) }
                    if (host != null) scan.editable = candidate(access, node, text, host, true)
                }
                else -> if (scan.plain == null) {
                    val text = access.displayText(node)
                    val host = text?.let { UrlMatcher.hostOf(it) }
                    if (host != null) scan.plain = candidate(access, node, text, host, false)
                }
            }
            true
        }
        return scan
    }

    private fun <N : Any> candidate(
        access: NodeAccess<N>,
        node: N,
        text: CharSequence?,
        host: String?,
        isAddressBar: Boolean,
    ) = UrlCandidate(
        text = text ?: "",
        host = host,
        isAddressBar = isAddressBar,
        focused = access.isFocused(node),
        showingHint = access.isShowingHint(node),
    )
}

/** What walking one window produced, and whether it took the wide budget to get it. */
internal class WindowWalk(val scan: UrlScan, val wide: Boolean)

internal object WindowWalker {

    /**
     * Walks [root] once, or twice for an ordinary window that does not fit the
     * generic budget (see [InspectionPolicy.shouldEscalate]). The second walk
     * happens immediately and not on the window's next look, for two reasons: a
     * static screen may never raise another event, so a deferred escalation could
     * simply never happen and the blind spot would stay; and doing it here means
     * the page is judged in the same inspection that found it. The price is up to
     * 500 fetches in one event, paid once per window, which is no more than the
     * browser path pays on every look.
     *
     * [mayEscalate] must be false whenever the outcome cannot be remembered (no
     * window id, or a root that is not the event's window): with nowhere to keep
     * the flag, nothing would stop the same window being escalated on every look.
     * [known] is the window's entry, or null when it is not to be consulted.
     *
     * Never throws from the escalation: if the wide walk fails, the generic result
     * stands and is reported as wide, so the failure also counts as the window's
     * one escalation instead of being retried on every look.
     */
    fun <N : Any> walk(root: N, access: NodeAccess<N>, kind: WindowKind, known: WindowMemo.Entry?, mayEscalate: Boolean): WindowWalk {
        val wide = InspectionPolicy.startsWide(kind, known)
        val limits = if (wide) InspectionPolicy.BROWSER_LIMITS else InspectionPolicy.GENERIC_LIMITS
        val first = UrlScanner.scan(root, access, limits)
        if (!mayEscalate || !InspectionPolicy.shouldEscalate(kind, first, wide, known)) return WindowWalk(first, wide)
        val second = runCatching { UrlScanner.scan(root, access, InspectionPolicy.BROWSER_LIMITS) }.getOrNull()
        return WindowWalk(second ?: first, wide = true)
    }
}

/** Every visible text in a window, for the Settings lock. */
internal class TextScan(val texts: List<String>, val stats: TreeWalk.Stats)

internal object TextScanner {

    /**
     * Texts and content descriptions in tree order, trimmed, blanks dropped.
     * The lock detectors trim and discard blanks themselves, so collecting them
     * pre-cleaned changes no verdict; it just stops the walk from allocating a
     * list slot for the many nodes that carry no text. Unlike the URL scan this
     * does descend into WebViews, as before.
     */
    fun <N : Any> collect(root: N, access: NodeAccess<N>, limits: WalkLimits): TextScan {
        val texts = ArrayList<String>(64)
        val stats = TreeWalk.walk(root, access, limits) { node, _ ->
            access.text(node)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            access.description(node)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            true
        }
        return TextScan(texts, stats)
    }
}
