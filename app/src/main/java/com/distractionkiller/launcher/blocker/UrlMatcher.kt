package com.distractionkiller.launcher.blocker

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

        val schemeEnd = text.indexOf("://")
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
        return host
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
