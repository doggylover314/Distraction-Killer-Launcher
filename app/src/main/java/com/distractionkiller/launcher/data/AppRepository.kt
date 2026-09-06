package com.distractionkiller.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.distractionkiller.launcher.blocker.AppEnforcement

/** Everything that talks to PackageManager. */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    /**
     * Every installed app that has a launcher entry, alphabetical.
     *
     * Blocking and not instant (a few hundred milliseconds on a loaded phone),
     * so callers run it off the main thread.
     */
    fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(intent)
            .asSequence()
            .map { resolveInfo ->
                LaunchableApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                )
            }
            // A few apps publish more than one launcher activity. We launch by
            // package name, so collapse them to one row to match what tapping
            // actually does.
            .distinctBy { it.packageName }
            .sortedWith(AppFilter.LABEL_ORDER)
            .toList()
    }

    /**
     * Intent that opens an app's main entry point, or null if the package is
     * gone or has no launcher activity (uninstalled since the list was built,
     * work-profile app, disabled by the user, and so on).
     */
    fun launchIntentFor(packageName: String): Intent? =
        pm.getLaunchIntentForPackage(packageName)?.apply {
            // Required: we are starting the app from the launcher's own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun hasLaunchIntent(packageName: String): Boolean =
        pm.getLaunchIntentForPackage(packageName) != null

    /**
     * The starter allowlist, resolved on this device rather than hardcoded.
     *
     * Dialer / SMS / camera package names differ per manufacturer (Pixel,
     * Samsung and OnePlus all ship different ones), so each is looked up
     * through the intent that opens it. Anything that does not resolve is
     * simply left out; add it later from Settings.
     */
    fun defaultAllowlist(): Set<String> {
        val packages = LinkedHashSet<String>()

        // Phone. ACTION_DIAL is handled by whatever dialer is installed.
        resolvePackage(Intent(Intent.ACTION_DIAL))?.let(packages::add)

        // Messages. Telephony.Sms.getDefaultSmsPackage() is the documented way
        // to find the user's chosen SMS app (API 19+). Returns null on a device
        // with no telephony, e.g. a tablet.
        Telephony.Sms.getDefaultSmsPackage(appContext)?.let(packages::add)

        // Camera. MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA is the standard
        // "open the camera app" action.
        resolvePackage(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let(packages::add)

        // WhatsApp: the one package id you gave me directly.
        packages.add(WHATSAPP_PACKAGE)

        // No chess app here by your choice. If you want one preloaded, add its
        // package id to this list, e.g. Chess.com, which I believe is "com.chess"
        // but did not verify. Easier: just tick it in Settings on the device.

        // Only keep things that can actually be launched, so the home screen
        // never shows a dead row.
        return packages.filterTo(LinkedHashSet(), ::hasLaunchIntent)
    }

    /**
     * Installed web browsers: everything that offers to open an https URL.
     * Needs the BROWSABLE/https entry in the manifest's <queries> block, or
     * Android 11+ hides them. MATCH_ALL stops the current default browser from
     * being the only one returned.
     */
    fun browserPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val fromSystem = queryActivities(intent, PackageManager.MATCH_ALL.toLong())
            .mapTo(LinkedHashSet()) { it.activityInfo.packageName }
        return fromSystem + KNOWN_BROWSERS
    }

    /**
     * Packages the system-wide app enforcement must never send home. On top of
     * the fixed list: whatever handles Settings, dialling and calls, alarms,
     * and every enabled keyboard. Missing any of these would make a locked
     * phone unusable in a way the user did not ask for.
     */
    fun systemExemptPackages(): Set<String> {
        val packages = LinkedHashSet(AppEnforcement.FIXED_EXEMPTIONS)
        packages.add(appContext.packageName)
        listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Intent.ACTION_DIAL),
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
        ).forEach { intent -> resolvePackage(intent)?.let(packages::add) }

        runCatching {
            appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let(packages::add)

        runCatching {
            appContext.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
        }.getOrNull()?.let(packages::addAll)

        return packages
    }

    /**
     * Package that would handle [intent], or null.
     *
     * When several apps can handle it and none is the default, the system
     * returns its own chooser ("android"), which is not something we want on
     * the allowlist, so that case is filtered out.
     */
    private fun resolvePackage(intent: Intent): String? {
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        val packageName = resolved?.activityInfo?.packageName ?: return null
        return packageName.takeUnless { it == "android" || it.isEmpty() }
    }

    private fun queryActivities(intent: Intent, flags: Long = 0L) =
        // The ResolveInfoFlags overloads are API 33+; the int-flag versions are
        // deprecated there but are still the only option down at minSdk 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags.toInt())
        }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** Belt and braces for the query above; harmless if not installed. */
        val KNOWN_BROWSERS = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
            "org.chromium.chrome",
        )
    }
}
