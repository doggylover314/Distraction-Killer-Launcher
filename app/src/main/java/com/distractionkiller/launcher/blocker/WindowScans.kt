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
