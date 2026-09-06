package com.distractionkiller.launcher.data

/**
 * "Opened N times today", the small counter next to each app name.
 *
 * Kept as a pure value type over a date key and a map, so the daily reset and
 * the encoding to a SharedPreferences string set can be unit-tested. The date
 * key is whatever the caller passes (ISO yyyy-MM-dd in practice); this class
 * only compares it for equality.
 */
data class LaunchCounter(
    val dateKey: String,
    val counts: Map<String, Int>,
) {
    fun recorded(packageName: String, todayKey: String): LaunchCounter {
        val base = if (todayKey == dateKey) counts else emptyMap()
        return LaunchCounter(todayKey, base + (packageName to (base[packageName] ?: 0) + 1))
    }

    /** Counts are only meaningful for today; yesterday's map reads as all zeros. */
    fun countFor(packageName: String, todayKey: String): Int =
        if (todayKey == dateKey) counts[packageName] ?: 0 else 0

    fun encode(): Set<String> = counts.map { (pkg, n) -> "$pkg$SEPARATOR$n" }.toSet()

    companion object {
        // '|' cannot appear in a package name, so the split is unambiguous.
        private const val SEPARATOR = '|'

        fun decode(dateKey: String?, encoded: Set<String>?): LaunchCounter {
            if (dateKey == null || encoded == null) return LaunchCounter("", emptyMap())
            val counts = encoded.mapNotNull { entry ->
                val split = entry.lastIndexOf(SEPARATOR)
                if (split <= 0) return@mapNotNull null
                val n = entry.substring(split + 1).toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, split) to n
            }.toMap()
            return LaunchCounter(dateKey, counts)
        }
    }
}
