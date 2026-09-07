package com.distractionkiller.launcher.blocker

import java.net.IDN
import java.util.Locale

/**
 * Turns whatever a browser shows in its address bar into a host name, and
 * decides whether that host is on the blocklist. Pure, so the matching rules
 * are unit-tested rather than discovered on the phone.
 *
 * Patterns are bare domains. "reddit.com" blocks reddit.com, www.reddit.com,
 * old.reddit.com and any other subdomain, but not "notreddit.com".
 */
object UrlMatcher {

    /**
     * Host part of address-bar text, lower-cased, or null if the text does not
     * look like a URL or host at all (search terms, empty bar, page titles).
     *
     * Accepts what real address bars show: "https://www.x.com/a", "x.com/a",
     * "x.com", "user@x.com:8080/p", and trims whitespace.
     */
    fun hostOf(addressBarText: CharSequence?): String? {
        var text = addressBarText?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        // Wrappers that show another page: judge the page, not the wrapper.
        text = text.removePrefix("view-source:")
        if (text.lowercase(Locale.ROOT).startsWith("about:reader")) {
            text = text.substringAfter("url=", "").substringBefore('&')
            text = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
            if (text.isEmpty()) return null
        }

        val schemeEnd = text.indexOf("://")
        // Without a scheme, "name@host" is far more likely an e-mail address
        // shown somewhere in an app than a URL with credentials. Treating it
        // as a host would let a contact card trigger a block.
        if (schemeEnd < 0 && '@' in text) return null
        if (schemeEnd >= 0) {
            val scheme = text.substring(0, schemeEnd).lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            text = text.substring(schemeEnd + 3)
        }

        var host = text.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfterLast('@')
        // IPv6 literals keep their brackets and colons; everything else drops a port.
        host = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        host = host.trimEnd('.').lowercase(Locale.ROOT)

        if (host.isEmpty()) return null
        // A host needs a dot (or be localhost / an IPv6 literal); "reddit" alone
        // is a search, not a navigation.
        if (!host.contains('.') && host != "localhost" && !host.startsWith("[")) return null
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '[' || it == ']' || it == ':') }) return null
        // The last label must be a real TLD: letters, at least two. That
        // rejects "4.7", "12.99" and "v1.2.3", which ordinary apps show all
        // the time. Dotted-quad IPv4 literals are the one numeric exception.
        if (host != "localhost" && !host.startsWith("[")) {
            val tld = host.substringAfterLast('.')
            val tldIsWord = tld.length >= 2 && tld.all { it.isLetter() }
            if (!tldIsWord && !isIpv4(host)) return null
        }
        // Browsers show internationalised names in Unicode; lists hold the
        // ASCII (xn--) form. Compare in ASCII so the two agree.
        if (host.any { it.code > 127 }) {
            host = runCatching { IDN.toASCII(host).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        }
        return host
    }

    /**
     * Address-bar states that are the browser's own, not a page: empty, the
     * new-tab page, about:blank. Allowlist mode lets these through and blocks
     * every other non-host text (data:, file:, view-source:, chrome://).
     */
    fun isBrowserInternal(addressBarText: CharSequence?): Boolean {
        val text = addressBarText?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (text.isEmpty()) return true
        return INTERNAL_PREFIXES.any { text.startsWith(it) }
    }

    private val INTERNAL_PREFIXES = listOf(
        "about:blank",
        "about:newtab",
        "chrome://newtab",
        "chrome-native://newtab",
        "chrome://new-tab-page",
        "brave://newtab",
        "edge://newtab",
    )

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    /**
     * Whether text looks like something typed into an address bar rather than
     * a name that happens to contain a dot: it carries a scheme or a path.
     * Used to keep "Booking.com" in a sender line from counting as a page.
     */
    fun looksLikeUrl(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim().orEmpty()
        return value.contains("://") || value.contains('/')
    }

    /** Canonical form of a user-entered pattern, or null if it is unusable. */
    fun normalizePattern(input: String): String? {
        val host = hostOf(input.trim().removePrefix("*.").removePrefix(".")) ?: return null
        return host.removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    fun isBlocked(host: String?, patterns: Set<String>): Boolean =
        host != null && SiteRules.matchesAny(host, patterns)

    /** Convenience: address-bar text straight to a verdict, with the host for the toast. */
    fun blockedHost(addressBarText: CharSequence?, patterns: Set<String>): String? {
        val host = hostOf(addressBarText) ?: return null
        return host.takeIf { isBlocked(it, patterns) }
    }
}
