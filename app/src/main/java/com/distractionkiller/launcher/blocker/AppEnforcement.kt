package com.distractionkiller.launcher.blocker

/**
 * The rule for "should this foreground app be sent home".
 *
 * Only apps that appear in the launcher's own list are candidates: a helper
 * with no launcher icon (a file picker, a permission dialog, a share sheet)
 * is never touched, because that is how an allowed app breaks in confusing
 * ways. Everything system-critical is exempted explicitly on top of that.
 */
object AppEnforcement {

    fun shouldSendHome(
        packageName: String?,
        enforcementEnabled: Boolean,
        launchablePackages: Set<String>,
        visiblePackages: Set<String>,
        exemptPackages: Set<String>,
    ): Boolean {
        if (!enforcementEnabled || packageName.isNullOrEmpty()) return false
        if (packageName in exemptPackages) return false
        if (packageName !in launchablePackages) return false
        return packageName !in visiblePackages
    }

    /**
     * Packages that must never be blocked regardless of the lists. Resolved
     * ones (settings, dialer, alarm clock, keyboards) are added at runtime by
     * AppRepository.systemExemptPackages; these are the fixed names that do not need lookup.
     */
    val FIXED_EXEMPTIONS: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.emergency",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vpndialogs",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.shell",
        "com.android.documentsui",
        "com.android.intentresolver",
        "com.google.android.gms",
        // Emergency SOS, car-crash detection, and the stock clock apps: a
        // countdown or a ringing alarm must never be bounced.
        "com.google.android.apps.safetyhub",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
    )
}
