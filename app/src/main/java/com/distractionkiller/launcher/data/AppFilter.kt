package com.distractionkiller.launcher.data

/**
 * Turns "every launchable app on the device" into "what the home screen shows".
 * Pure function, no Android types, so the mode/allowlist/blocklist rules are
 * covered by ordinary JVM unit tests.
 */
object AppFilter {

    fun visibleApps(
        installed: List<LaunchableApp>,
        mode: LauncherMode,
        allowlist: Set<String>,
        blocklist: Set<String>,
        selfPackage: String,
    ): List<LaunchableApp> {
        val filtered = installed.asSequence()
            // Never list ourselves: the home screen is already the home screen.
            .filter { it.packageName != selfPackage }
            .filter { app ->
                when (mode) {
                    LauncherMode.ALLOWLIST -> app.packageName in allowlist
                    LauncherMode.BLOCKLIST -> app.packageName !in blocklist
                }
            }
        return filtered.sortedWith(LABEL_ORDER).toList()
    }

    /** Alphabetical by visible name; package name breaks ties so the order is stable. */
    val LABEL_ORDER: Comparator<LaunchableApp> =
        compareBy(String.CASE_INSENSITIVE_ORDER, LaunchableApp::label)
            .thenBy(LaunchableApp::packageName)

    /**
     * Substring match on the display name, case-insensitively. Package ids are
     * ignored unless [matchPackageNames] is set: people type what they see on
     * screen, and "com.google.android.apps.messaging" matching "me" buries the
     * app they meant.
     *
     * Ranked in three tiers, each in [LABEL_ORDER]: labels that start with the
     * query, other label matches, then package-only matches. A prefix hit is
     * what someone typing a name is almost always after, so "me" lists
     * "Messages" above "Google Home".
     */
    fun search(
        apps: List<LaunchableApp>,
        query: String,
        matchPackageNames: Boolean = false,
    ): List<LaunchableApp> {
        val needle = query.trim()
        if (needle.isEmpty()) return apps
        val prefixMatches = ArrayList<LaunchableApp>()
        val labelMatches = ArrayList<LaunchableApp>()
        val packageMatches = ArrayList<LaunchableApp>()
        for (app in apps) {
            when {
                app.label.startsWith(needle, ignoreCase = true) -> prefixMatches += app
                app.label.contains(needle, ignoreCase = true) -> labelMatches += app
                matchPackageNames && app.packageName.contains(needle, ignoreCase = true) ->
                    packageMatches += app
            }
        }
        return prefixMatches.sortedWith(LABEL_ORDER) +
            labelMatches.sortedWith(LABEL_ORDER) +
            packageMatches.sortedWith(LABEL_ORDER)
    }
}
