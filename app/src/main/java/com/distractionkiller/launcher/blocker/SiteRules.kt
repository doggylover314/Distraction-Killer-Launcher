package com.distractionkiller.launcher.blocker

/** Whether the website list is a "these are blocked" or a "only these open" list. */
enum class WebsiteMode {
    BLOCKLIST,
    ALLOWLIST,
    ;

    companion object {
        fun fromStoredValue(value: String?): WebsiteMode =
            entries.firstOrNull { it.name == value } ?: BLOCKLIST
    }
}

/**
 * The decision for one host, given the mode, the user's own list and the
 * union of every enabled preset. Pure and fast: membership is a walk up the
 * host's parent domains, so a 40,000-entry preset costs the same handful of
 * lookups as a 4-entry one. The custom list is exact strings; the presets are
 * a [CompactDomainSet], which is approximate by a margin explained there.
 */
object SiteRules {

    /** True if [host] or any parent domain of it is in [domains]. Exact; for the user's own list. */
    fun matchesAny(host: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var candidate = host
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /** True if [host] or any parent domain of it is in [domains]. For the bundled presets. */
    fun matchesAny(host: String, domains: CompactDomainSet): Boolean = domains.matchesHostOrParent(host)

    fun shouldBlock(
        host: String?,
        mode: WebsiteMode,
        customList: Set<String>,
        presetDomains: CompactDomainSet,
    ): Boolean {
        if (host == null) return false
        val listed = matchesAny(host, customList) || matchesAny(host, presetDomains)
        return when (mode) {
            WebsiteMode.BLOCKLIST -> listed
            WebsiteMode.ALLOWLIST -> !listed
        }
    }
}
