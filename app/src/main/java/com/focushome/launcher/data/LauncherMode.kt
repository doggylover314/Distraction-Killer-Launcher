package com.focushome.launcher.data

/**
 * Which of the two lists decides what the home screen shows.
 *
 * ALLOWLIST: only the apps you ticked are visible. Everything else disappears.
 * BLOCKLIST: every launchable app is visible except the ones you ticked.
 *
 * Both lists are stored independently, so switching modes never loses the
 * other list's contents.
 */
enum class LauncherMode {
    ALLOWLIST,
    BLOCKLIST,
    ;

    companion object {
        fun fromStoredValue(value: String?): LauncherMode =
            entries.firstOrNull { it.name == value } ?: ALLOWLIST
    }
}
