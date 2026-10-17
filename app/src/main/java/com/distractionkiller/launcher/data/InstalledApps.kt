package com.distractionkiller.launcher.data

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Process-wide cache of the launchable app list.
 *
 * Building the list costs one PackageManager query plus a label lookup per app,
 * which is a binder call and a resource read each: hundreds of milliseconds on
 * an old phone with a full app drawer. The result only changes when something
 * is installed, removed, updated or the language changes, so it is kept until
 * one of those events invalidates it (see [watchForChanges]) instead of being
 * rebuilt every time the home screen resumes.
 *
 * Reads happen on the main thread and writes on Dispatchers.IO.
 */
object InstalledApps {

    private const val TAG = "DistractionKiller"

    private val cache = AppListCache()
    private val watchLock = Any()

    /**
     * False until both receivers are registered. While it is false [cached]
     * reports a miss: a cache nothing can invalidate would show a stale app
     * list until the process dies, which is worse than being slow.
     */
    @Volatile
    private var watching = false

    /** The warm list, or null when it was never built or has been invalidated. Never blocks on a load. */
    fun cached(): List<LaunchableApp>? = if (watching) cache.cached() else null

    /**
     * The list, rebuilding it through [repository] only when it is cold or
     * stale. Blocking: call it off the main thread, as with
     * [AppRepository.loadLaunchableApps]. Throws what the query throws and
     * leaves the cache as it was.
     */
    fun load(repository: AppRepository): List<LaunchableApp> {
        // Same reason as in [cached]: without invalidation, a stored list could
        // only go stale, so every call goes to PackageManager.
        if (!watching) return repository.loadLaunchableApps()
        return cache.load { repository.loadLaunchableApps() }
    }

    /** Marks the list stale. Cheap, safe from any thread. */
    fun invalidate() = cache.invalidate()

    /**
     * Registers the broadcasts that invalidate the cache. Called once from
     * [com.distractionkiller.launcher.LauncherApp], so the process is covered
     * even while no activity exists (the enforcement service keeps it alive).
     *
     * Never throws: this runs on the main thread before the first frame, in the
     * only home screen the phone has. If registration fails the cache stays
     * disabled and every caller falls back to querying PackageManager.
     */
    fun watchForChanges(app: Application) {
        synchronized(watchLock) {
            if (watching) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    invalidate()
                }
            }
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    packageChangeFilter(),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(InvalidatingBroadcasts.LOCALE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            registered.exceptionOrNull()?.let { Log.w(TAG, "App list cache disabled: could not register receivers", it) }
            watching = registered.isSuccess
        }
    }

    private fun packageChangeFilter() = IntentFilter().apply {
        InvalidatingBroadcasts.PACKAGE_ACTIONS.forEach { addAction(it) }
        // Package broadcasts carry a "package:<name>" URI, and a filter without
        // the scheme does not match them.
        addDataScheme("package")
    }
}

/**
 * The broadcasts after which a cached app list can no longer be trusted.
 *
 * Kept as plain strings apart from [InstalledApps] so a unit test can pin them
 * down without touching Android: dropping the locale entry would compile and
 * run fine, and only show up as app names stuck in the old language.
 */
internal object InvalidatingBroadcasts {

    /** Install, uninstall, update, and component or enabled-state changes (a hidden launcher alias, say). */
    val PACKAGE_ACTIONS: List<String> = listOf(
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REMOVED,
        Intent.ACTION_PACKAGE_CHANGED,
        Intent.ACTION_PACKAGE_REPLACED,
    )

    /**
     * Every label is looked up in the current language, so a language change
     * makes the whole list stale even though no package changed. It has no data
     * URI, which is why it cannot share a filter with [PACKAGE_ACTIONS].
     */
    const val LOCALE_ACTION: String = Intent.ACTION_LOCALE_CHANGED
}

/**
 * The thread-safety and staleness rules, with no Android types so the JVM tests
 * can exercise them.
 *
 * One lock guards the state and is only ever held for a field read or write,
 * never across the slow query, so the main thread never waits on a load. A
 * second lock lets only one rebuild run at a time, so two callers that miss
 * together (Home and Settings, or two quick resumes on a slow phone) pay for
 * one query instead of two.
 */
internal class AppListCache {

    private val stateLock = Any()
    private val loadLock = Any()

    // Both guarded by stateLock. A null list means cold or stale; there is no
    // separate validity flag to drift out of step with it.
    private var apps: List<LaunchableApp>? = null
    private var generation = 0L

    fun cached(): List<LaunchableApp>? = synchronized(stateLock) { apps }

    fun invalidate() {
        synchronized(stateLock) {
            apps = null
            // Bumped so a rebuild already in flight cannot store its result:
            // it started before this event and may not reflect it.
            generation++
        }
    }

    /**
     * The warm list, or [compute]'s result stored as the new warm list.
     * A throw from [compute] propagates and leaves the cache untouched.
     */
    fun load(compute: () -> List<LaunchableApp>): List<LaunchableApp> {
        synchronized(loadLock) {
            val startedAt: Long
            synchronized(stateLock) {
                // Checked again now that we hold loadLock: whoever held it
                // before us has most likely just filled the cache.
                apps?.let { return it }
                startedAt = generation
            }
            val fresh = compute()
            synchronized(stateLock) {
                // An invalidation during the query means this result may be
                // missing the very change that caused it. Hand it to the caller
                // (better than nothing) but leave the cache cold so the next
                // reader rebuilds.
                if (generation == startedAt) apps = fresh
            }
            return fresh
        }
    }
}
