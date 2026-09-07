package com.distractionkiller.launcher.blocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast

/** Is the enforcement service switched on, and how does the user get there. */
object AccessibilityStatus {

    fun isServiceEnabled(context: Context): Boolean {
        if (EnforcementService.isRunning) return true
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    /**
     * Opens the system Accessibility list. There is no way to deep-link to
     * one service or to toggle it programmatically; the user has to tap
     * through, and on Android 13+ a sideloaded app first needs
     * "Allow restricted settings" from its App info page.
     */
    fun openAccessibilitySettings(context: Context) =
        context.openFirstAvailable(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

    fun openAppInfo(context: Context) =
        context.openFirstAvailable(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
            Intent(Settings.ACTION_SETTINGS),
        )

    /** Some OEM builds do not handle ACTION_HOME_SETTINGS; fall back down the chain. */
    fun openHomeAppSettings(context: Context) =
        context.openFirstAvailable(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

    /**
     * An unhandled ActivityNotFoundException here would take the whole
     * process down, launcher included, so every Settings jump is guarded.
     */
    private fun Context.openFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // same: some OEM screens are not exported
            }
        }
        Toast.makeText(this, "That Settings screen is not available on this phone", Toast.LENGTH_SHORT).show()
    }
}
