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
 * cached.
 *
 * The cost to watch is the tree walk. Every node fetched is a binder call
 * answered on the inspected app's own UI thread, and content-changed events
 * arrive continuously while any app scrolls. So most events end in a few
 * in-memory lookups (see [InspectionPolicy]); only windows that can carry a URL
 * or a locked screen are walked, each at most once per inspection, within a node
 * and depth budget.
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
    @Volatile private var homeApps: Set<String> = emptySet()

    /** Derived from prefs + [launchable]; null means "recompute on next use". */
    @Volatile private var visibleCache: Set<String>? = null

    /** Union of every enabled preset, for the website mode it was loaded under. */
    private val presetStore = PresetStore()

    private var lastHomeAt = 0L
    private var lastBackAt = 0L
    private var lastToastAt = 0L
    private var lastBlockedKey: String? = null
    private var lastBlockedAt = 0L
    private var lastFastInspectionAt = 0L
    private var lastSlowInspectionAt = 0L
    private var lastSweepAt = 0L
    private var retriedInspectionFor: String? = null

    /** What earlier inspections learned per window; see [WindowMemo]. */
    private val windowMemo = WindowMemo()
    private val addressBarIds = AddressBarIds()
    private val nodeAccess = AccessibilityNodeAccess()

    /** Set by [LazyScan] when it walked a window: what that walk found, and whether it read everything. */
    private var lastScan: UrlScan? = null

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
            val root = rootInActiveWindow
            val front = root?.packageName?.toString()
            root?.recycleCompat()
            if (front != null && front != packageName && shouldSendHome(front)) {
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
        // Window ids and the learned address-bar ids belong to the previous
        // connection; the system may have reused or dropped any of them.
        windowMemo.clear()
        addressBarIds.clear()
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
                    retriedInspectionFor = null
                    // The in-memory test goes first: isApplicationWindow reads the
                    // window list over binder, and nearly every window belongs to
                    // an app that is allowed.
                    if (shouldSendHome(packageName) && isApplicationWindow(event) && bounceHiddenApp(packageName)) return
                    inspectWindow(packageName, event.windowId, stateChange = true)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    inspectWindow(packageName, event.windowId, stateChange = false)
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
        val all = runCatching { windows }.getOrNull() ?: return true
        try {
            val window = all.firstOrNull { it.id == event.windowId } ?: return true
            return window.type == AccessibilityWindowInfo.TYPE_APPLICATION && !window.isInPictureInPictureMode
        } finally {
            all.forEach { it.recycleCompat() }
        }
    }

    private fun shouldSendHome(packageName: String): Boolean = AppEnforcement.shouldSendHome(
        packageName = packageName,
        enforcementEnabled = prefs.enforceAppsSystemWide,
        launchablePackages = launchable,
        visiblePackages = visiblePackages(),
        exemptPackages = exempt,
    )

    /** Caller has already established [shouldSendHome]. Returns false if the app turned out to be the new home. */
    private fun bounceHiddenApp(packageName: String): Boolean {
        // A launcher that has just won the home role is where Home now leads.
        // Bouncing it would loop; exempt it on the spot and log the takeover.
        if (packageName in homeApps) {
            val current = runCatching { appRepository.defaultHomePackage() }.getOrNull()
            if (current == packageName) {
                Log.w(TAG, "Another home app has become the default: $packageName")
                exempt = exempt + packageName
                return false
            }
        }
        goHome()
        handler.removeCallbacks(verifyForeground)
        handler.postDelayed(verifyForeground, ACTION_INTERVAL_MS + 150)
        toast(getString(R.string.toast_app_blocked))
        return true
    }

    // --------------------------------------------- 2. sites and 3. lock

    /** What the website rules are, read once per inspection that gets as far as needing them. */
    private class WebRules(val mode: WebsiteMode, val custom: Set<String>, val presets: CompactDomainSet)

    /** Null when there is nothing to enforce, in which case no node is ever fetched for it. */
    private fun currentWebRules(): WebRules? {
        if (!prefs.siteBlockingEnabled) return null
        val mode = prefs.websiteMode
        val custom = prefs.websiteListFor(mode)
        val preset = presetStore.domainsFor(mode)
        if (custom.isEmpty() && preset.isEmpty()) return null
        return WebRules(mode, custom, preset)
    }

    /**
     * Decides whether a window is worth walking and how often; the rules are in
     * [InspectionPolicy]. In short: browsers and lockable system windows are
     * checked every [InspectionPolicy.FAST_INTERVAL_MS] because the user is
     * navigating them; any other app is walked when its window opens, followed
     * if it was found to host a web page, and otherwise looked at again only
     * once per [InspectionPolicy.RECHECK_INTERVAL_MS], so scrolling a feed or a
     * chat costs a bounded walk every few seconds at most.
     *
     * Everything that can say "nothing to do" is tested before the first node is
     * requested, because the window root itself is already a binder call.
     */
    private fun inspectWindow(packageName: String, windowId: Int, stateChange: Boolean) {
        if (packageName in NEVER_INSPECT) return
        val kind = when {
            SettingsLockDetector.isLockablePackage(packageName) -> WindowKind.LOCKABLE
            packageName in browsers -> WindowKind.BROWSER
            else -> WindowKind.OTHER
        }

        val now = SystemClock.uptimeMillis()
        val known = windowMemo.find(windowId, packageName)
        if (!InspectionPolicy.shouldInspect(kind, stateChange, known, now, lastFastInspectionAt, lastSlowInspectionAt)) return

        val lockActive = kind == WindowKind.LOCKABLE && lockStandsGuard(packageName)
        val rules = if (kind == WindowKind.LOCKABLE) null else currentWebRules()
        if (!lockActive && rules == null) return

        if (!stateChange) {
            if (kind == WindowKind.OTHER) lastSlowInspectionAt = now else lastFastInspectionAt = now
        }

        val roots = rootsFor(packageName, windowId, now)
        if (roots.isEmpty()) {
            // rootInActiveWindow is often null right after a window change.
            // One retry so a static screen with no follow-up content events
            // still gets judged.
            if (stateChange && retriedInspectionFor != packageName) {
                retriedInspectionFor = packageName
                handler.postDelayed(
                    { runCatching { inspectWindow(packageName, windowId, stateChange = true) } },
                    300,
                )
            }
            return
        }
        retriedInspectionFor = null

        lastScan = null
        try {
            for (root in roots) {
                if (lockActive) {
                    if (checkLockedScreen(packageName, root)) return
                    continue
                }
                if (rules != null && checkWebContent(packageName, root, kind == WindowKind.BROWSER, rules, stateChange, now)) return
            }
            val scan = lastScan
            // The verdict describes one window. The focused-window fallback in
            // rootsFor can hand back a different window of the same package, and
            // what that tree holds must not be written against this event's id.
            if (kind == WindowKind.OTHER && scan != null && roots.size == 1 && roots[0].windowId == windowId) {
                InspectionPolicy.verdictToRecord(scan)?.let { windowMemo.record(windowId, packageName, it, now) }
            }
        } finally {
            roots.forEach { it.recycleCompat() }
        }
    }

    /**
     * The root of the window that raised the event. The focused window is one
     * binder call and is the answer almost every time. Only when the event came
     * from some other window (the unfocused half of split-screen, a dialog over
     * the activity, a keyboard) is the window list read, and then only that one
     * window's root is fetched. That keeps a browser in the unfocused pane
     * judgeable, and it also guards against walking some other app's tree under
     * this package's name.
     */
    private fun rootsFor(packageName: String, windowId: Int, now: Long): List<AccessibilityNodeInfo> {
        val active = rootInActiveWindow
        if (active != null &&
            InspectionPolicy.activeRootAnswers(windowId, active.windowId, packageName, active.packageName?.toString())
        ) {
            return listOf(active)
        }
        val roots = rootsFromWindowList(packageName, windowId, now)
        // Whenever the list gave nothing, whatever the event's window id, settle
        // for the focused window if it is at least this package's; see
        // InspectionPolicy.activeRootStandsIn for why the id must not gate this.
        if (active != null && InspectionPolicy.activeRootStandsIn(roots.size, packageName, active.packageName?.toString())) {
            roots.add(active)
        } else {
            active?.recycleCompat()
        }
        return roots
    }

    private fun rootsFromWindowList(packageName: String, windowId: Int, now: Long): ArrayList<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(2)
        val all = runCatching { windows }.getOrNull() ?: return roots
        try {
            for (window in all) {
                if (windowId >= 0 && window.id != windowId) continue
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    // A keyboard or overlay. Its type cannot change, so remember
                    // that and skip every later event from it without a lookup.
                    // Lockable packages are never skipped (see InspectionPolicy).
                    InspectionPolicy.rememberNotInspectable(windowMemo, windowId, packageName, now)
                    continue
                }
                val root = runCatching { window.root }.getOrNull() ?: continue
                if (root.packageName?.toString() == packageName) roots.add(root) else root.recycleCompat()
            }
        } finally {
            all.forEach { it.recycleCompat() }
        }
        return roots
    }

    // ------------------------------------------------------------ sites

    /** Walks [root] at most once, however many of the checks below want its contents. */
    private inner class LazyScan(private val root: AccessibilityNodeInfo, private val isBrowser: Boolean) {
        private var scan: UrlScan? = null

        val isReady: Boolean get() = scan != null

        fun get(): UrlScan = scan ?: run {
            nodeAccess.calibrateFor(root, resources.displayMetrics.heightPixels)
            val limits = if (isBrowser) InspectionPolicy.BROWSER_LIMITS else InspectionPolicy.GENERIC_LIMITS
            UrlScanner.scan(root, nodeAccess, limits).also {
                scan = it
                lastScan = it
            }
        }
    }

    private fun checkWebContent(
        packageName: String,
        root: AccessibilityNodeInfo,
        isBrowser: Boolean,
        rules: WebRules,
        stateChange: Boolean,
        now: Long,
    ): Boolean {
        val lazyScan = LazyScan(root, isBrowser)
        // A browser's address bar is found by id in one call; only if that fails
        // is the tree walked, and that walk then answers every other question too.
        val view = (if (isBrowser) addressBarById(packageName, root) else null)
            ?: lazyScan.get().best(isBrowser)

        val block = SiteVerdict.judge(
            view = view,
            isBrowser = isBrowser,
            mode = rules.mode,
            custom = rules.custom,
            presets = rules.presets,
            webViewShown = { lazyScan.get().webViewSeen },
            plainHost = {
                // The sweep needs the whole tree. Answer from a walk that has
                // already happened; otherwise do one only when it is due, since
                // the address bar (a single call) is what is checked every time.
                if (lazyScan.isReady || stateChange || InspectionPolicy.sweepDue(now, lastSweepAt)) {
                    if (!lazyScan.isReady) lastSweepAt = now
                    lazyScan.get().plain?.host
                } else {
                    null
                }
            },
        ) ?: return false

        act(root, key = block.key)
        toast(getString(R.string.toast_site_blocked, block.label))
        return true
    }

    /** One binder call per id tried, the id that matched last time first. */
    private fun addressBarById(packageName: String, root: AccessibilityNodeInfo): UrlCandidate? {
        for (id in addressBarIds.orderFor(packageName)) {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val node = matches.firstOrNull()
            val candidate = node?.let {
                val text = nodeAccess.displayText(it)
                UrlCandidate(
                    text = text ?: "",
                    host = text?.let { shown -> UrlMatcher.hostOf(shown) },
                    isAddressBar = true,
                    focused = it.isFocused,
                    showingHint = it.isShowingHintText,
                )
            }
            matches.forEach { it.recycleCompat() }
            if (candidate != null) {
                addressBarIds.remember(packageName, id)
                return candidate
            }
        }
        return null
    }

    /**
     * Back (then Home if stuck) is only right when this window has focus:
     * in split-screen a Back press lands on the focused pane, which may be a
     * form in some other app. An unfocused window goes straight Home.
     */
    private fun act(root: AccessibilityNodeInfo, key: String) {
        val window = runCatching { root.window }.getOrNull()
        val active = runCatching { window?.isActive }.getOrNull() ?: true
        window?.recycleCompat()
        if (active) escalate(key) else goHome()
    }

    // ------------------------------------------------------------- lock

    /** Whether the Settings lock has anything to enforce right now; see [SettingsLockDetector.shouldCheck]. */
    private fun lockStandsGuard(packageName: String): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        return SettingsLockDetector.shouldCheck(
            lockEnabled = prefs.lockSystemSettings,
            packageName = packageName,
            nowElapsed = nowElapsed,
            suppressUntilElapsed = suppressSettingsLockUntilElapsed,
            unlocked = { prefs.isSettingsUnlocked(nowElapsed) },
        )
    }

    private fun checkLockedScreen(packageName: String, root: AccessibilityNodeInfo): Boolean {
        val texts = TextScanner.collect(root, nodeAccess, InspectionPolicy.LOCK_LIMITS).texts
        val locked = SettingsLockDetector.isLockedScreen(
            packageName = packageName,
            windowTexts = texts,
            keywords = prefs.settingsLockKeywords,
            appLabel = appLabel,
        ) || SettingsLockDetector.isHomeRoleDialog(packageName, texts)
        if (!locked) return false

        // The system's own home chooser: Home would only reopen it, because
        // Home *is* the unresolved intent. Pick ourselves instead.
        if (SettingsLockDetector.isHomeChooser(packageName, texts, appLabel)) {
            if (chooseSelfInHomeChooser(root)) toast(getString(R.string.toast_settings_locked))
            return true
        }

        // Home, not Back: on the home-app picker a quick tap on another
        // launcher would beat a Back press, and Home is final.
        goHome(immediate = true)
        toast(getString(R.string.toast_settings_locked))
        return true
    }

    private fun chooseSelfInHomeChooser(root: AccessibilityNodeInfo): Boolean {
        val row = root.findAccessibilityNodeInfosByText(appLabel).firstOrNull() ?: return false
        var target: AccessibilityNodeInfo? = row
        while (target != null && !target.isClickable) target = target.parent
        val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            // Newer choosers confirm with "Always"; older ones apply at once.
            root.findAccessibilityNodeInfosByText("Always").firstOrNull { it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return clicked
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
        val ticket = presetStore.beginLoad()
        scope.launch {
            runCatching {
                // Read once, and stored with the result: the mode a set was loaded
                // for is what decides whether it may be used (see PresetStore).
                val mode = prefs.websiteMode
                val loaded = if (prefs.siteBlockingEnabled) {
                    presets.loadEnabledDomains(mode, prefs.enabledWebsitePresets)
                } else {
                    CompactDomainSet.EMPTY
                }
                presetStore.publish(ticket, mode, loaded)
            }.onFailure { Log.w(TAG, "Preset reload failed", it) }
        }
    }

    private fun reload() {
        scope.launch {
            runCatching {
                val allLaunchable = appRepository.launchablePackagesAllProfiles()
                val exemptions = appRepository.systemExemptPackages().toMutableSet()
                // If another launcher has become the default, "home" now means
                // that launcher. Sending it home would loop forever.
                appRepository.defaultHomePackage()
                    ?.takeIf { it != packageName }
                    ?.let { other ->
                        Log.w(TAG, "Another home app is the default: $other")
                        exemptions.add(other)
                    }
                launchable = allLaunchable
                homeApps = appRepository.homeAppPackages()
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
        private const val SOFT_INPUT_WINDOW = "android.inputmethodservice.SoftInputWindow"

        /** Windows that never carry a URL or a lockable screen; not worth a walk. */
        private val NEVER_INSPECT = setOf("com.android.systemui", "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard", "com.touchtype.swiftkey", "com.swiftkey.beta")

        /** Set by the service itself, so Settings can show whether it is live. */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * elapsedRealtime until which the Settings lock stands down. Set by
         * HomeActivity right before it asks for a runtime permission, whose
         * dialog would otherwise be treated as a locked screen.
         */
        @Volatile var suppressSettingsLockUntilElapsed: Long = 0L
    }
}
