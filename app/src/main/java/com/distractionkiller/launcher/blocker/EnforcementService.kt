package com.distractionkiller.launcher.blocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.distractionkiller.launcher.R
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.AppRepository
import com.distractionkiller.launcher.data.LaunchableApp
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
 *  2. Website filtering. Wherever a URL is on screen (a browser's address bar,
 *     an in-app browser's header, a Custom Tab's title bar) it is judged
 *     against the blocklist or allowlist; a hit gets Back, then Home if Back
 *     did not get rid of it.
 *  3. Settings lock. The windows that could switch the home app or remove this
 *     one (Settings, the role-manager pickers, the uninstall dialog, the
 *     system chooser) are sent Home, unless the password was used to open a
 *     timed window first.
 *
 * Everything here runs on the main thread, per AccessibilityService's
 * contract, so the expensive PackageManager work is done in [reload] on IO and
 * cached. Per-event work is a handful of in-memory lookups plus, for windows
 * that might carry a URL or a locked screen, a bounded walk of the node tree.
 */
class EnforcementService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var appRepository: AppRepository
    private lateinit var presets: WebsitePresets
    private lateinit var appLabel: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

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
    private var lastBlockedKey: String? = null
    private var lastBlockedAt = 0L
    private var lastInspectionAt = 0L
    private var lastSlowInspectionAt = 0L
    private var retriedInspectionFor: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Prefs.ENFORCEMENT_KEYS) visibleCache = null
        if (key in Prefs.WEBSITE_KEYS) reloadPresets()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = reload()
    }

    /**
     * After a Home press, look again once the action throttle has passed. A
     * splash screen that hands over to its real activity a few hundred
     * milliseconds later would otherwise slip through the throttle and stay.
     */
    private val verifyForeground = Runnable {
        runCatching {
            val front = rootInActiveWindow?.packageName?.toString() ?: return@runCatching
            if (front != packageName && shouldSendHome(front)) {
                lastHomeAt = 0L
                goHome()
            }
        }
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
        handler.removeCallbacksAndMessages(null)
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
                    if (isApplicationWindow(event) && enforceApp(packageName)) return
                    inspectWindow(packageName, force = true)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> inspectWindow(packageName, force = false)
                else -> Unit
            }
        }.onFailure { Log.w(TAG, "Event handling failed", it) }
    }

    // ---------------------------------------------------------- 1. apps

    /**
     * Keyboards, picture-in-picture and other non-app windows also raise
     * WINDOW_STATE_CHANGED. Sending Home for those would make every text
     * field unusable (Gboard has a launcher icon) and cannot close a PiP
     * window anyway, so only real application windows count.
     */
    private fun isApplicationWindow(event: AccessibilityEvent): Boolean {
        if (event.className?.toString() == SOFT_INPUT_WINDOW) return false
        val window = runCatching { windows.firstOrNull { it.id == event.windowId } }.getOrNull()
            ?: return true
        return window.type == AccessibilityWindowInfo.TYPE_APPLICATION && !window.isInPictureInPictureMode
    }

    private fun shouldSendHome(packageName: String): Boolean = AppEnforcement.shouldSendHome(
        packageName = packageName,
        enforcementEnabled = prefs.enforceAppsSystemWide,
        launchablePackages = launchable,
        visiblePackages = visiblePackages(),
        exemptPackages = exempt,
    )

    private fun enforceApp(packageName: String): Boolean {
        if (!shouldSendHome(packageName)) return false
        goHome()
        handler.removeCallbacks(verifyForeground)
        handler.postDelayed(verifyForeground, ACTION_INTERVAL_MS + 150)
        toast(getString(R.string.toast_app_blocked))
        return true
    }

    // --------------------------------------------- 2. sites and 3. lock

    /**
     * Decides whether a window is worth walking and how often. Browsers and
     * lockable system windows are inspected every [INSPECT_INTERVAL_MS]; any
     * other app is walked only on a window change and every
     * [SLOW_INSPECT_INTERVAL_MS], which is enough to catch an in-app browser
     * without taxing a chat list that is being scrolled.
     */
    private fun inspectWindow(packageName: String, force: Boolean) {
        val isBrowser = packageName in browsers
        val isLockable = SettingsLockDetector.isLockablePackage(packageName)
        val webFiltering = prefs.siteBlockingEnabled
        if (!isBrowser && !isLockable && !webFiltering) return

        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (isBrowser || isLockable) {
                if (now - lastInspectionAt < INSPECT_INTERVAL_MS) return
                lastInspectionAt = now
            } else {
                if (now - lastSlowInspectionAt < SLOW_INSPECT_INTERVAL_MS) return
                lastSlowInspectionAt = now
            }
        }

        val roots = windowRootsFor(packageName)
        if (roots.isEmpty()) {
            // rootInActiveWindow is often null right after a window change.
            // One retry so a static screen with no follow-up content events
            // still gets judged.
            if (force && retriedInspectionFor != packageName) {
                retriedInspectionFor = packageName
                handler.postDelayed({ runCatching { inspectWindow(packageName, force = true) } }, 300)
            }
            return
        }
        retriedInspectionFor = null

        for (root in roots) {
            if (isLockable) {
                if (checkLockedScreen(packageName, root)) return
                continue
            }
            if (webFiltering && checkWebContent(packageName, root, isBrowser)) return
        }
    }

    /**
     * Every window on screen that belongs to [packageName]. Reading the
     * window list (rather than only the focused one) is what makes a browser
     * in the unfocused half of split-screen judgeable, and it also guards
     * against walking some other app's tree under this package's name.
     */
    private fun windowRootsFor(packageName: String): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(2)
        runCatching { windows }.getOrNull()?.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (root.packageName?.toString() == packageName) roots.add(root)
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }?.let(roots::add)
        }
        return roots
    }

    // ------------------------------------------------------------ sites

    private fun checkWebContent(packageName: String, root: AccessibilityNodeInfo, isBrowser: Boolean): Boolean {
        val mode = prefs.websiteMode
        val custom = prefs.websiteListFor(mode)
        // Blocklist with nothing on it is a no-op. Allowlist with nothing on
        // it would block the whole web, which is only ever a mistake.
        if (custom.isEmpty() && presetDomains.isEmpty()) return false

        val bar = findUrlView(packageName, root, isBrowser) ?: return false
        // While the user is typing, the bar holds half a URL. Judging that
        // would make every keystroke in allowlist mode a Back press. The page
        // is judged once it loads and the bar gives up focus.
        if (bar.isAddressBar && bar.focused) return false

        val text = if (bar.showingHint) "" else bar.text
        val host = UrlMatcher.hostOf(text)
        val block = when {
            host != null -> SiteRules.shouldBlock(host, mode, custom, presetDomains)
            // A real address bar showing something that is not a host (data:,
            // file:, view-source:, chrome://) is a page too. In allowlist
            // mode that page is not on the list, so it is blocked; only the
            // browser's own new-tab states pass.
            bar.isAddressBar && mode == WebsiteMode.ALLOWLIST -> !UrlMatcher.isBrowserInternal(text)
            else -> false
        }
        if (!block) return false

        escalate(key = host ?: text.toString())
        toast(getString(R.string.toast_site_blocked, host ?: "page"))
        return true
    }

    private class UrlView(
        val text: CharSequence,
        /** True for a proper address bar (known id, URL-ish id, or editable). */
        val isAddressBar: Boolean,
        val focused: Boolean,
        val showingHint: Boolean,
    )

    /**
     * Finds the on-screen URL, wherever this window keeps it.
     *
     * Order: known address-bar ids (every Chromium browser uses "url_bar";
     * Firefox "mozac_browser_toolbar_url_view"; the rest are best guesses),
     * then any node whose id looks like a URL field, then any editable field
     * holding a host, then any plain text holding a host outside the web
     * content itself. That last rule is what catches in-app browsers and
     * Custom Tabs, which show the domain in an ordinary TextView. Text inside
     * a WebView is never considered, so a page that merely mentions
     * "reddit.com" cannot trigger a block, and a non-browser window is only
     * judged at all if it actually contains a WebView.
     */
    private fun findUrlView(packageName: String, root: AccessibilityNodeInfo, isBrowser: Boolean): UrlView? {
        if (isBrowser) {
            for (idSuffix in ADDRESS_BAR_IDS) {
                val node = root.findAccessibilityNodeInfosByViewId("$packageName:id/$idSuffix").firstOrNull()
                    ?: continue
                return UrlView(
                    text = nodeText(node) ?: "",
                    isAddressBar = true,
                    focused = node.isFocused,
                    showingHint = node.isShowingHintText,
                )
            }
        }

        val walk = UrlWalk()
        walkForUrl(root, walk, NODE_BUDGET)
        if (!isBrowser && !walk.webViewSeen) return null
        val node = walk.idMatch ?: walk.editable ?: walk.plain ?: return null
        return UrlView(
            text = nodeText(node) ?: "",
            isAddressBar = node !== walk.plain,
            focused = node.isFocused,
            showingHint = node.isShowingHintText,
        )
    }

    private class UrlWalk {
        var webViewSeen = false
        var idMatch: AccessibilityNodeInfo? = null
        var editable: AccessibilityNodeInfo? = null
        var plain: AccessibilityNodeInfo? = null
    }

    private fun walkForUrl(node: AccessibilityNodeInfo?, walk: UrlWalk, budget: Int): Int {
        if (node == null || budget <= 0) return budget
        if (node.className?.toString() == WEB_VIEW) {
            walk.webViewSeen = true
            return budget - 1 // do not descend into page content
        }
        val id = node.viewIdResourceName
        val text = nodeText(node)
        val hostLike = text != null && UrlMatcher.hostOf(text) != null
        when {
            id != null && URL_VIEW_ID_HINTS.any { id.contains(it, ignoreCase = true) } ->
                if (walk.idMatch == null) walk.idMatch = node
            node.isEditable && hostLike -> if (walk.editable == null) walk.editable = node
            hostLike -> if (walk.plain == null) walk.plain = node
        }
        var remaining = budget - 1
        for (i in 0 until node.childCount) {
            if (remaining <= 0) break
            remaining = walkForUrl(node.getChild(i), walk, remaining)
        }
        return remaining
    }

    private fun nodeText(node: AccessibilityNodeInfo): CharSequence? =
        node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------- lock

    private fun checkLockedScreen(packageName: String, root: AccessibilityNodeInfo): Boolean {
        if (!prefs.lockSystemSettings) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        // Our own permission prompt is drawn by PermissionController and
        // names this app; HomeActivity announces it before asking. The grace
        // covers only the dialog packages, never the Settings app itself.
        if (nowElapsed < suppressSettingsLockUntilElapsed &&
            packageName in SettingsLockDetector.ROLE_DIALOG_PACKAGES
        ) {
            return false
        }
        if (prefs.isSettingsUnlocked(nowElapsed)) return false

        val texts = ArrayList<CharSequence?>()
        collectTexts(root, texts, NODE_BUDGET)
        val locked = SettingsLockDetector.isLockedScreen(
            packageName = packageName,
            windowTexts = texts,
            keywords = prefs.settingsLockKeywords,
            appLabel = appLabel,
        ) || SettingsLockDetector.isHomeRoleDialog(packageName, texts)
        if (!locked) return false

        // Home, not Back: on the home-app picker a quick tap on another
        // launcher would beat a Back press, and Home is final.
        goHome(immediate = true)
        toast(getString(R.string.toast_settings_locked))
        return true
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

    // ---------------------------------------------------------- helpers

    /** Back once; Home if the same thing is still there a moment later. */
    private fun escalate(key: String) {
        val now = SystemClock.uptimeMillis()
        val stuck = key == lastBlockedKey && now - lastBlockedAt < STUCK_WINDOW_MS
        lastBlockedKey = key
        lastBlockedAt = now
        if (stuck) goHome() else goBack()
    }

    private fun visiblePackages(): Set<String> {
        visibleCache?.let { return it }
        val apps = launchable.map { LaunchableApp(it, it) }
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
                val exemptions = appRepository.systemExemptPackages().toMutableSet()
                // If another launcher has become the default, "home" now means
                // that launcher. Sending it home would loop forever.
                appRepository.defaultHomePackage()
                    ?.takeIf { it != packageName }
                    ?.let { other ->
                        Log.w(TAG, "Another home app is the default: $other")
                        exemptions.add(other)
                    }
                launchable = apps.mapTo(HashSet()) { it.packageName }
                exempt = exemptions
                browsers = appRepository.browserPackages()
                visibleCache = null
            }.onFailure { Log.w(TAG, "Reload failed", it) }
        }
    }

    private fun goHome(immediate: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!immediate && now - lastHomeAt < ACTION_INTERVAL_MS) return
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
        private const val SLOW_INSPECT_INTERVAL_MS = 1_200L
        private const val NODE_BUDGET = 500
        private const val WEB_VIEW = "android.webkit.WebView"
        private const val SOFT_INPUT_WINDOW = "android.inputmethodservice.SoftInputWindow"

        /** Set by the service itself, so Settings can show whether it is live. */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * elapsedRealtime until which the Settings lock stands down. Set by
         * HomeActivity right before it asks for a runtime permission, whose
         * dialog would otherwise be treated as a locked screen.
         */
        @Volatile var suppressSettingsLockUntilElapsed: Long = 0L

        val ADDRESS_BAR_IDS = listOf(
            "url_bar",                          // Chrome, Brave, Edge, Vivaldi, Kiwi and other Chromium forks
            "mozac_browser_toolbar_url_view",   // Firefox, Firefox Focus
            "location_bar_edit_text",           // Samsung Internet (unverified)
            "url_field",                        // Opera (unverified)
            "omnibarTextInput",                 // DuckDuckGo (unverified)
        )

        /** Substrings of view ids that mark a URL display in any app. */
        val URL_VIEW_ID_HINTS = listOf(
            "url_bar", "urlbar", "url_view", "url_text", "url_field", "omnibox", "omnibar",
            "address_bar", "addressbar", "location_bar", "locationbar", "domain",
        )
    }
}
