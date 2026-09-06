package com.distractionkiller.launcher.blocker

/**
 * Recognises the Android Settings screens that could be used to get around
 * the launcher: the default-home-app picker, this app's own App info page
 * (uninstall, force stop, clear storage, "open by default"), and the
 * accessibility toggle for the enforcement service.
 *
 * Detection is by on-screen text, because the Settings app does not expose
 * distinct activity names for its sub-screens. That makes it English-first;
 * the keyword list is a protected setting so other languages can be added.
 */
object SettingsLockDetector {

    /** Default titles, as shown by Pixel and Samsung settings in English. */
    val DEFAULT_KEYWORDS: Set<String> = setOf(
        "Home app",
        "Default home app",
        "Default apps",
        "Choose default apps",
        "Launcher",
        // Title of the system's own chooser when no default home app is set.
        "Select a Home app",
        "Select Home app",
    )

    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oneplus.security",
        // The system's own "pick a home app" chooser, shown when no default is set.
        "android",
    )

    fun isSettingsPackage(packageName: String?): Boolean = packageName in SETTINGS_PACKAGES

    /**
     * @param windowTexts every visible text node in the window, in any order
     * @param appLabel this app's display name; any Settings screen mentioning
     *   it is one that can manage or remove it
     */
    fun isLockedScreen(
        packageName: String?,
        windowTexts: Collection<CharSequence?>,
        keywords: Collection<String>,
        appLabel: String,
    ): Boolean {
        if (!isSettingsPackage(packageName)) return false
        val texts = windowTexts.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return false
        // Titles are matched whole; the app label may sit inside a longer
        // sentence ("Use Distraction Killer Launcher?").
        return texts.any { text ->
            text.contains(appLabel, ignoreCase = true) ||
                keywords.any { keyword -> text.equals(keyword, ignoreCase = true) }
        }
    }

    fun isUnlocked(unlockedUntilMillis: Long, nowMillis: Long): Boolean =
        unlockedUntilMillis > nowMillis
}
