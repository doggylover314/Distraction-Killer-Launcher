package com.distractionkiller.launcher.blocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.distractionkiller.launcher.R
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.AppRepository
import com.distractionkiller.launcher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The part that makes the launcher hard to get around. Runs only while the user
 * has switched it on under Android Settings > Accessibility, which is the one
 * way a normal, non-root app is allowed to see what is on screen.
 *
 * Three jobs, each with its own protected toggle:
 *
 *  1. App enforcement. A hidden app that reaches the foreground by any route
 *     (notification, share sheet, Settings > Open, search) is sent home.
 *  2. Website blocking. The browser's address bar is read; a blocked host
 *     gets a Back press, or Home if Back did not get rid of it.
 *  3. Settings lock. The Android Settings screens that could switch the home
 *     app or remove this one are backed out of, unless the password was used
 *     to open a timed window first.
 *
 * Everything here runs on the main thread, per AccessibilityService's
 * contract, so the expensive PackageManager work is done in [reload] on IO and
 * cached. Per-event work is a handful of in-memory lookups plus, for browsers
 * and Settings only, a bounded walk of the window tree.
 */
class EnforcementService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var appRepository: AppRepository
    private lateinit var presets: WebsitePresets
    private lateinit var appLabel: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** PackageManager-derived state. Rebuilt on package changes, read on every event. */
    @Volatile private var launchable: Set<String> = emptySet()
    @Volatile private var exempt: Set<String> = emptySet()
    @Volatile private var browsers: Set<String> = emptySet()

    /** Derived from prefs + [launchable]; null means "recompute on next use". */
    @Volatile private var visibleCache: Set<String>? = null

    /** Union of every enabled preset for the current website mode. */
    @Volatile private var presetDomains: Set<String> = emptySet()

    private var lastHomeAt = 0L
    private var lastBackAt = 0L
    private var lastToastAt = 0L
    private var lastBlockedHost: String? = null
    private var lastBlockedHostAt = 0L
    private var lastSettingsBlockAt = 0L
    private var lastInspectionAt = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Prefs.ENFORCEMENT_KEYS) visibleCache = null
        if (key in Prefs.WEBSITE_KEYS) reloadPresets()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = reload()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        appRepository = AppRepository(this)
        presets = WebsitePresets(this)
        appLabel = getString(R.string.app_name)
        prefs.registerListener(prefsListener)
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        reload()
        reloadPresets()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { prefs.unregisterListener(prefsListener) }
        runCatching { unregisterReceiver(packageReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        // Defensive on purpose: an uncaught exception here kills the service,
        // and a dead service is a silent bypass.
        runCatching {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (enforceApp(packageName)) return
                    inspectWindow(packageName, force = true)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> inspectWindow(packageName, force = false)
                else -> Unit
            }
        }.onFailure { Log.w(TAG, "Event handling failed", it) }
    }

    // ---------------------------------------------------------- 1. apps

    private fun enforceApp(packageName: String): Boolean {
        val block = AppEnforcement.shouldSendHome(
            packageName = packageName,
            enforcementEnabled = prefs.enforceAppsSystemWide,
            launchablePackages = launchable,
            visiblePackages = visiblePackages(),
            exemptPackages = exempt,
        )
        if (!block) return false
        goHome()
        toast(getString(R.string.toast_app_blocked))
        return true
    }

    // ------------------------------------------- 2. sites and 3. settings

    /**
     * Only browsers and Settings windows are ever walked, and at most every
     * [INSPECT_INTERVAL_MS] unless the window itself just changed.
     */
    private fun inspectWindow(packageName: String, force: Boolean) {
        val isBrowser = packageName in browsers
        val isSettings = SettingsLockDetector.isSettingsPackage(packageName)
        if (!isBrowser && !isSettings) return

        val now = SystemClock.uptimeMillis()
        if (!force && now - lastInspectionAt < INSPECT_INTERVAL_MS) return
        lastInspectionAt = now

        val root = rootInActiveWindow ?: return
        if (isBrowser) checkAddressBar(packageName, root)
        if (isSettings) checkSettingsScreen(packageName, root)
    }

    private fun checkAddressBar(packageName: String, root: AccessibilityNodeInfo) {
        if (!prefs.siteBlockingEnabled) return
        val mode = prefs.websiteMode
        val custom = prefs.websiteListFor(mode)
        // Blocklist with nothing on it is a no-op. Allowlist with nothing on
        // it would block the whole web, which is only ever a mistake.
        if (custom.isEmpty() && presetDomains.isEmpty()) return

        val bar = addressBar(packageName, root) ?: return
        // While the user is typing, the bar holds half a URL. Judging that
        // would make every keystroke in allowlist mode a Back press. The page
        // is judged once it loads and the bar gives up focus.
        if (bar.isFocused) return
        val host = UrlMatcher.hostOf(bar.text) ?: return
        if (!SiteRules.shouldBlock(host, mode, custom, presetDomains)) return

        val now = SystemClock.uptimeMillis()
        // Back once. If the same host is still there a moment later (opened
        // from a new tab, or Back landed on another blocked page), go home.
        val stuck = host == lastBlockedHost && now - lastBlockedHostAt < STUCK_WINDOW_MS
        lastBlockedHost = host
        lastBlockedHostAt = now
        if (stuck) goHome() else goBack()
        toast(getString(R.string.toast_site_blocked, host))
    }

    /**
     * Address-bar text for a browser window.
     *
     * Known view ids first: every Chromium-based browser (Chrome, Brave, Edge,
     * Vivaldi, Kiwi, Samsung Internet's Chromium fork excepted) exposes
     * "<package>:id/url_bar". The others listed are from Firefox, Samsung
     * Internet, Opera and DuckDuckGo. I am confident about the Chromium and
     * Firefox ids and less so about the last three; if a browser is not being
     * caught, the generic fallback below still finds any editable field that
     * holds a URL, which is what an address bar is.
     */
    private fun addressBar(packageName: String, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (idSuffix in ADDRESS_BAR_IDS) {
            val node = root.findAccessibilityNodeInfosByViewId("$packageName:id/$idSuffix").firstOrNull()
            if (node != null && !node.text.isNullOrBlank()) return node
        }
        return findEditableUrl(root, budget = NODE_BUDGET)
    }

    private fun findEditableUrl(node: AccessibilityNodeInfo?, budget: Int): AccessibilityNodeInfo? {
        if (node == null || budget <= 0) return null
        if (node.isEditable || node.className?.endsWith("EditText") == true) {
            val text = node.text
            if (!text.isNullOrBlank() && UrlMatcher.hostOf(text) != null) return node
        }
        var remaining = budget - 1
        for (i in 0 until node.childCount) {
            val found = findEditableUrl(node.getChild(i), remaining)
            if (found != null) return found
            remaining -= 1
            if (remaining <= 0) break
        }
        return null
    }

    private fun checkSettingsScreen(packageName: String, root: AccessibilityNodeInfo) {
        if (!prefs.lockSystemSettings) return
        if (SettingsLockDetector.isUnlocked(prefs.systemSettingsUnlockedUntil, System.currentTimeMillis())) return

        val texts = ArrayList<CharSequence?>()
        collectTexts(root, texts, NODE_BUDGET)
        val locked = SettingsLockDetector.isLockedScreen(
            packageName = packageName,
            windowTexts = texts,
            keywords = prefs.settingsLockKeywords,
            appLabel = appLabel,
        )
        if (!locked) return

        val now = SystemClock.uptimeMillis()
        val stuck = now - lastSettingsBlockAt < STUCK_WINDOW_MS
        lastSettingsBlockAt = now
        if (stuck) goHome() else goBack()
        toast(getString(R.string.toast_settings_locked))
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, into: MutableList<CharSequence?>, budget: Int): Int {
        if (node == null || budget <= 0) return budget
        into.add(node.text)
        into.add(node.contentDescription)
        var remaining = budget - 1
        for (i in 0 until node.childCount) {
            if (remaining <= 0) break
            remaining = collectTexts(node.getChild(i), into, remaining)
        }
        return remaining
    }

    // ------------------------------------------------------------ helpers

    private fun visiblePackages(): Set<String> {
        visibleCache?.let { return it }
        val apps = launchable.map { com.distractionkiller.launcher.data.LaunchableApp(it, it) }
        val visible = AppFilter.visibleApps(
            installed = apps,
            mode = prefs.mode,
            allowlist = prefs.allowlist,
            blocklist = prefs.blocklist,
            selfPackage = packageName,
        ).mapTo(HashSet()) { it.packageName }
        visibleCache = visible
        return visible
    }

    private fun reloadPresets() {
        scope.launch {
            runCatching {
                presetDomains = if (prefs.siteBlockingEnabled) {
                    presets.loadEnabledDomains(prefs.websiteMode, prefs.enabledWebsitePresets)
                } else {
                    emptySet()
                }
            }.onFailure { Log.w(TAG, "Preset reload failed", it) }
        }
    }

    private fun reload() {
        scope.launch {
            runCatching {
                val apps = appRepository.loadLaunchableApps()
                launchable = apps.mapTo(HashSet()) { it.packageName }
                exempt = appRepository.systemExemptPackages()
                browsers = appRepository.browserPackages()
                visibleCache = null
            }.onFailure { Log.w(TAG, "Reload failed", it) }
        }
    }

    private fun goHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHomeAt < ACTION_INTERVAL_MS) return
        lastHomeAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun goBack() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < ACTION_INTERVAL_MS) return
        lastBackAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun toast(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "DKEnforcement"
        private const val ACTION_INTERVAL_MS = 600L
        private const val TOAST_INTERVAL_MS = 2_500L
        private const val STUCK_WINDOW_MS = 2_000L
        private const val INSPECT_INTERVAL_MS = 300L
        private const val NODE_BUDGET = 400

        /** Set by the service itself, so Settings can show whether it is live. */
        @Volatile var isRunning: Boolean = false
            private set

        val ADDRESS_BAR_IDS = listOf(
            "url_bar",                          // Chrome, Brave, Edge, Vivaldi, Kiwi and other Chromium forks
            "mozac_browser_toolbar_url_view",   // Firefox, Firefox Focus
            "location_bar_edit_text",           // Samsung Internet (unverified)
            "url_field",                        // Opera (unverified)
            "omnibarTextInput",                 // DuckDuckGo (unverified)
        )
    }
}
