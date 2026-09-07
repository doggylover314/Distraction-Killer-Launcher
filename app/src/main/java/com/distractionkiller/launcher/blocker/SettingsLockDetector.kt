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
        // A guest or second user is an unrestricted phone; the gateway is
        // Settings > System > Multiple users.
        "Multiple users",
        "Users",
        "Add user",
        "Add guest",
        "Guest",
    )

    /**
     * Windows that can switch the home app or remove this one. Not only the
     * Settings app: since Android 10 the "Default apps" and "Home app" pickers
     * are drawn by PermissionController (the role manager UI), and every
     * uninstall route, whatever started it, ends in the package installer's
     * confirmation dialog, which names the app.
     */
    val LOCKABLE_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oneplus.security",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        // The system's own "pick a home app" chooser, shown when no default is
        // set. Recent builds host it in a separate resolver package.
        "android",
        "com.android.intentresolver",
    )

    /**
     * Packages that draw the "Set X as your default home app?" request a
     * launcher can raise itself through RoleManager, with no trip through
     * Settings. Matched on phrase, not on our label: the dialog names the
     * other launcher.
     */
    val ROLE_DIALOG_PACKAGES: Set<String> = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "android",
        "com.android.intentresolver",
    )

    private val HOME_ROLE_PHRASES = listOf(
        "default home app",
        "as your home app",
        "home app",
        "default launcher",
    )

    fun isHomeRoleDialog(packageName: String?, windowTexts: Collection<CharSequence?>): Boolean {
        if (packageName !in ROLE_DIALOG_PACKAGES) return false
        return windowTexts.any { text ->
            val value = text?.toString() ?: return@any false
            HOME_ROLE_PHRASES.any { value.contains(it, ignoreCase = true) }
        }
    }

    fun isLockablePackage(packageName: String?): Boolean = packageName in LOCKABLE_PACKAGES

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
        if (!isLockablePackage(packageName)) return false
        val texts = windowTexts.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return false
        // Titles are matched whole; the app label may sit inside a longer
        // sentence ("Use Distraction Killer Launcher?").
        return texts.any { text ->
            text.contains(appLabel, ignoreCase = true) ||
                keywords.any { keyword -> text.equals(keyword, ignoreCase = true) }
        }
    }

    /**
     * The window is expressed in SystemClock.elapsedRealtime, which the user
     * cannot wind back from the Date & time screen. [grantedAt] catches a
     * reboot: elapsed time restarts at zero, so "now" falling before the grant
     * means the phone restarted and the window is over.
     */
    fun isUnlocked(grantedAtElapsed: Long, untilElapsed: Long, nowElapsed: Long): Boolean =
        grantedAtElapsed <= nowElapsed && nowElapsed < untilElapsed
}
