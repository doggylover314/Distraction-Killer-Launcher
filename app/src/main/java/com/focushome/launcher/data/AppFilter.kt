package com.focushome.launcher.data

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
     * Substring match on both the visible name and the package id, so the
     * settings search box finds "com.whatsapp" as well as "WhatsApp".
     */
    fun search(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
        val needle = query.trim()
        if (needle.isEmpty()) return apps
        return apps.filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }
}
