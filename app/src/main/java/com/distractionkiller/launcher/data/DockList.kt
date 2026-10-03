package com.distractionkiller.launcher.data

/**
 * The dock is an ordered list of package names, so it cannot live in a
 * SharedPreferences string set (getStringSet makes no ordering promise). It is
 * stored as one newline-delimited string instead: a package name can never
 * contain a newline, so the split is unambiguous.
 *
 * Pure functions over plain lists, so the codec, the reorder rules and the
 * step that keeps the dock inside the visible app list are covered by ordinary
 * JVM unit tests.
 */
object DockList {

    const val MAX_ENTRIES = 5

    private const val SEPARATOR = '\n'

    /** Normalised on the way out as well as in, so what is written is what reads back. */
    fun encode(packages: List<String>): String =
        normalized(packages).joinToString(SEPARATOR.toString())

    /**
     * Total and lenient: whatever is on disk, this returns a usable list. A
     * hand-edited or half-written value must never be able to throw on the
     * home screen's main thread.
     */
    fun decode(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        return normalized(stored.split(SEPARATOR))
    }

    // Deduplicated before truncating, so a repeat cannot use up a slot.
    private fun normalized(entries: List<String>): List<String> =
        entries.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ENTRIES)

    /**
     * What the home screen draws: the saved order, kept only for packages in
     * [visible]. Lives here, away from the composable, because this one step is
     * what stops the dock reaching an app the current mode and lists hide, so
     * it must stay a tested function of the already-filtered list. A saved
     * entry that is hidden, blocked or uninstalled simply draws nothing.
     */
    fun resolve(order: List<String>, visible: List<LaunchableApp>): List<LaunchableApp> {
        val byPackage = HashMap<String, LaunchableApp>()
        for (app in visible) byPackage.putIfAbsent(app.packageName, app)
        return order.distinct().mapNotNull { byPackage[it] }
    }

    /** Removes the package if it is docked, otherwise appends it unless the dock is full. */
    fun toggled(packages: List<String>, packageName: String): List<String> = when {
        packageName in packages -> packages - packageName
        packages.size >= MAX_ENTRIES -> packages
        else -> packages + packageName
    }

    /**
     * [toggled] for a caller that knows which saved entries still resolve to a
     * visible app ([resolvable]). Pinning drops the entries that do not, since
     * they draw nothing yet would otherwise hold slots, for instance after the
     * protected tier blocks apps that were pinned. Unpinning leaves them be:
     * they come back if the app is allowed again.
     *
     * An empty [resolvable] means the visible list is unknown (not loaded, or
     * the load failed), so nothing is dropped; a failed query must not be able
     * to wipe the dock.
     */
    fun toggledAmong(packages: List<String>, packageName: String, resolvable: Set<String>): List<String> {
        if (packageName in packages || resolvable.isEmpty()) return toggled(packages, packageName)
        return toggled(packages.filter { it in resolvable }, packageName)
    }

    /** Shifts one entry by [delta] places, clamped to the ends; unchanged if it is not docked. */
    fun moved(packages: List<String>, packageName: String, delta: Int): List<String> {
        val from = packages.indexOf(packageName)
        if (from < 0) return packages
        val to = (from.toLong() + delta).coerceIn(0L, (packages.size - 1).toLong()).toInt()
        if (to == from) return packages
        val result = packages.toMutableList()
        result.removeAt(from)
        result.add(to, packageName)
        return result
    }
}
