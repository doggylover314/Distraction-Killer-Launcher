package com.distractionkiller.launcher

import android.app.Application
import com.distractionkiller.launcher.data.InstalledApps

/**
 * Exists only to register the app-list cache's invalidation receivers once per
 * process. This runs before the first frame, so it must stay a couple of
 * registerReceiver calls and nothing slower: no queries, no disk reads.
 */
class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        InstalledApps.watchForChanges(this)
    }
}
