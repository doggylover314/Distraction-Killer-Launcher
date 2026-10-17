package com.distractionkiller.launcher.blocker

/** A page to turn away: [key] decides Back versus Home, [label] goes in the toast. */
internal class SiteBlock(val key: String, val label: String)

/**
 * The website decision for what one window is showing, separated from the
 * accessibility plumbing so the allowlist and blocklist rules (including the
 * fail-closed cases) are unit-tested instead of discovered on a phone.
 */
internal object SiteVerdict {

    /**
     * @param view the URL candidate found in the window, or null if none
     * @param webViewShown asked only when [view] is null: does the window hold
     *   a large WebView? Lazy so the caller can answer from a walk it already did.
     * @param plainHost asked only in a browser's blocklist mode once the address
     *   bar has passed: the host of a plain-text candidate outside the web
     *   content, such as a preview sheet showing another page. Lazy because
     *   answering it can need a walk the caller may throttle.
     * @param presetsPending the allowlist presets the user switched on have not
     *   loaded yet. Empty lists then mean "not known yet", and allowlist mode
     *   fails closed instead of reading them as "nothing configured".
     */
    inline fun judge(
        view: UrlCandidate?,
        isBrowser: Boolean,
        mode: WebsiteMode,
        custom: Set<String>,
        presets: CompactDomainSet,
        webViewShown: () -> Boolean,
        plainHost: () -> String?,
        presetsPending: Boolean = false,
    ): SiteBlock? {
        // Blocklist with nothing on it is a no-op. Allowlist with nothing on
        // it would block the whole web, which is only ever a mistake, unless
        // the list is only empty because its presets are still loading.
        val allowlistPending = presetsPending && mode == WebsiteMode.ALLOWLIST
        if (custom.isEmpty() && presets.isEmpty() && !allowlistPending) return null

        if (view == null) {
            // A browser window with a page but no address bar at all: an
            // installed PWA, a Trusted Web Activity, fullscreen. There is no
            // host to check, so in allowlist mode it is not on the list.
            if (isBrowser && mode == WebsiteMode.ALLOWLIST && webViewShown()) {
                return SiteBlock(key = "no-address-bar", label = "page")
            }
            return null
        }
        // While the user is typing, the bar holds half a URL. Judging that
        // would make every keystroke in allowlist mode a Back press. The page
        // is judged once it loads and the bar gives up focus.
        if (view.isAddressBar && view.focused) return null

        // A hint ("Search or type URL") is the field's placeholder, not a page,
        // even when the placeholder happens to look like a host.
        val text: CharSequence = if (view.showingHint) "" else view.text
        val host = if (view.showingHint) null else view.host
        val block = when {
            host != null -> {
                // A bare name in ordinary text ("Booking.com" in a sender
                // line next to a mail body) is only trusted in blocklist
                // mode, where it can at most block what you listed. In
                // allowlist mode it has to look like a URL.
                val trusted = view.isAddressBar || mode == WebsiteMode.BLOCKLIST || UrlMatcher.looksLikeUrl(text)
                trusted && SiteRules.shouldBlock(host, mode, custom, presets)
            }
            // A real address bar showing a non-host page (data:, file:,
            // view-source:, chrome://) is a page too, and in allowlist mode it
            // is not on the list. Search queries also sit in the bar on a
            // results page, so only scheme-like text (it keeps a colon)
            // counts, and the browser's own new-tab states never do.
            view.isAddressBar && mode == WebsiteMode.ALLOWLIST ->
                text.contains(':') && !UrlMatcher.isBrowserInternal(text)
            else -> false
        }
        if (block) return SiteBlock(key = host ?: text.toString(), label = host ?: "page")

        // Blocklist mode: the address bar may be clean while a preview sheet
        // or panel shows another page's host; a plain host on screen outside
        // the web content is still worth a look.
        if (isBrowser && view.isAddressBar && mode == WebsiteMode.BLOCKLIST) {
            val plain = plainHost()
            if (plain != null && plain != host && SiteRules.shouldBlock(plain, mode, custom, presets)) {
                return SiteBlock(key = plain, label = plain)
            }
        }
        return null
    }
}
