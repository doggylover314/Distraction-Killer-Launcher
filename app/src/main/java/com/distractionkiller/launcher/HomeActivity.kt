package com.distractionkiller.launcher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import com.distractionkiller.launcher.blocker.EnforcementService
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.AppRepository
import com.distractionkiller.launcher.data.InstalledApps
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.ui.HomeScreen
import com.distractionkiller.launcher.ui.HomeUiConfig
import com.distractionkiller.launcher.ui.SetPasswordScreen
import com.distractionkiller.launcher.ui.theme.DistractionKillerTheme
import com.distractionkiller.launcher.ui.theme.windowThemeResId
import com.distractionkiller.launcher.weather.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The home screen itself. Registered with CATEGORY_HOME in the manifest, so the
 * system offers it as a launcher.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var appRepository: AppRepository

    /**
     * Bumped in onResume. Coming back from Settings re-reads preferences and
     * re-filters the app list. The list itself comes from [InstalledApps],
     * which only goes back to PackageManager after a package or language
     * change, so a resume with nothing new costs no binder calls.
     */
    private val resumeCounter = mutableIntStateOf(0)

    /**
     * Drives only the search reset. Kept apart from [resumeCounter] because a
     * Home press on a launcher that is already in front delivers onNewIntent,
     * which can happen as often as every 600 ms while enforcement bounces an
     * app that keeps relaunching. Sharing one counter would re-read the
     * preferences and re-filter the list for each of those presses.
     */
    private val searchResetCounter = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        setTheme(prefs.themeMode.windowThemeResId())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appRepository = AppRepository(this)

        setContent {
            val refreshKey by resumeCounter
            val themeMode = remember(refreshKey) { prefs.themeMode }

            DistractionKillerTheme(themeMode = themeMode) {
                var hasPassword by remember(refreshKey) { mutableStateOf(prefs.isPasswordSet) }

                // Back does nothing on the home screen, the same as a stock
                // launcher. Left off during first-run setup: nothing is enforced
                // yet, and if this was opened from another launcher's drawer the
                // user should be able to leave.
                BackHandler(enabled = hasPassword) { /* intentionally empty */ }

                if (!hasPassword) {
                    SetPasswordScreen(
                        title = "Welcome",
                        subtitle = "Pick a password. You will need it to change which apps show " +
                            "up here, so choose something you will remember. There is no " +
                            "recovery: if you forget it, the only way back in is to clear this " +
                            "app's data from Android Settings.",
                        submitLabel = "Save password",
                        onSubmit = { password ->
                            prefs.setPassword(password)
                            hasPassword = true
                        },
                    )
                } else {
                    HomeRoute(
                        prefs = prefs,
                        appRepository = appRepository,
                        refreshKey = refreshKey,
                        searchResetKey = searchResetCounter.intValue,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCounter.intValue++
        searchResetCounter.intValue++
    }

    /**
     * Pressing Home while the launcher is already in front. The activity is
     * singleTask, so the system re-delivers the intent here and onResume never
     * fires; without this bump the search box would survive the Home press.
     * onResume still covers the other route: coming back after an app launch.
     * Nothing a reload could pick up has changed, so only the search resets.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        searchResetCounter.intValue++
    }
}

@Composable
private fun HomeRoute(
    prefs: Prefs,
    appRepository: AppRepository,
    refreshKey: Int,
    searchResetKey: Int,
) {
    val context = LocalContext.current
    val weatherRepository = remember { WeatherRepository(context) }

    // Warm cache: the list is filtered right here, during composition, with
    // the current preferences. Not in the effect below, because an effect only
    // starts after the first frame, so a resumed screen would paint the old
    // list (or a loading hint) for a frame before correcting itself.
    val warmApps = remember(refreshKey) { warmVisibleApps(prefs, context.packageName) }

    // What the cold path last produced, or last saw warm. It stays on screen
    // while a stale cache reloads, and after a reload fails.
    var lastApps by remember { mutableStateOf(warmApps ?: emptyList<LaunchableApp>()) }
    var isLoading by remember { mutableStateOf(warmApps == null) }
    val apps = warmApps ?: lastApps
    var weather by remember { mutableStateOf(prefs.cachedWeather) }
    var launchCounter by remember(refreshKey) { mutableStateOf(prefs.launchCounter) }

    // Re-read on every resume so changes made in Settings show up immediately.
    val mode = remember(refreshKey) { prefs.mode }
    val config = remember(refreshKey) { HomeUiConfig.from(prefs) }

    var hasLocationPermission by remember(refreshKey) {
        mutableStateOf(weatherRepository.hasLocationPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The dialog is gone; the Settings lock can resume at once.
        EnforcementService.suppressSettingsLockUntilElapsed = 0L
        hasLocationPermission = granted
    }

    LaunchedEffect(refreshKey) {
        if (warmApps != null) {
            // The screen already shows warmApps; this only remembers them as
            // the fallback for a later failed reload.
            lastApps = warmApps
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        // runCatching, not because a failure is expected, but because this is
        // the home screen: a PackageManager call that throws (a large
        // transaction, a package being replaced mid-query) must not take the
        // only way out of the phone down with it. On failure the previous list
        // stays on screen and the next resume tries again.
        withContext(Dispatchers.IO) {
            runCatching {
                // First run only: work out sensible starter apps on this device.
                if (!prefs.hasSeededDefaults) {
                    prefs.allowlist = appRepository.defaultAllowlist()
                    prefs.hasSeededDefaults = true
                }
                InstalledApps.load(appRepository)
            }
        }
            .onSuccess { installed -> lastApps = visibleApps(installed, prefs, context.packageName) }
            .onFailure { error -> Log.w(TAG, "Could not read the installed app list", error) }
        isLoading = false
    }

    // Asked once, ever, and only if the weather line is actually switched on.
    // If you say no, the header just drops the weather and nothing nags you;
    // grant it later from Android's app info screen if you change your mind.
    LaunchedEffect(config.weatherEnabled, hasLocationPermission) {
        if (config.weatherEnabled && !hasLocationPermission && !prefs.hasRequestedLocationPermission) {
            prefs.hasRequestedLocationPermission = true
            // The permission dialog is drawn by PermissionController and names
            // this app, which is exactly what the Settings lock watches for.
            // Tell the service (same process) to stand down for a minute.
            EnforcementService.suppressSettingsLockUntilElapsed = SystemClock.elapsedRealtime() + 60_000L
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(refreshKey, config.weatherEnabled, hasLocationPermission) {
        weather = if (config.weatherEnabled) weatherRepository.currentWeather() else null
    }

    HomeScreen(
        apps = apps,
        mode = mode,
        resetKey = searchResetKey,
        // Never true on a warm resume, even if an earlier load was cancelled
        // before it could clear the flag.
        isLoading = isLoading && warmApps == null,
        weather = weather,
        config = config,
        launchCounts = if (config.showLaunchCounts) {
            launchCounter.counts.takeIf { launchCounter.dateKey == LocalDate.now().toString() } ?: emptyMap()
        } else {
            emptyMap()
        },
        onLaunch = { app ->
            val intent = appRepository.launchIntentFor(app.packageName)
            if (intent == null) {
                Toast.makeText(context, "${app.label} is no longer available", Toast.LENGTH_SHORT).show()
                return@HomeScreen
            }
            try {
                context.startActivity(intent)
                // Date taken now, not at resume: a screen left on past
                // midnight would otherwise credit taps to yesterday.
                launchCounter = launchCounter.recorded(app.packageName, LocalDate.now().toString())
                prefs.launchCounter = launchCounter
            } catch (e: ActivityNotFoundException) {
                // Uninstalled between the list being built and the tap.
                Toast.makeText(context, "Could not open ${app.label}", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                // e.g. an app that is visible but not startable by us.
                Toast.makeText(context, "Not allowed to open ${app.label}", Toast.LENGTH_SHORT).show()
            }
        },
        onOpenSettings = {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        },
    )
}

/**
 * The visible list built from the warm cache, or null to take the slow path.
 * Null covers a cold or stale cache, and also a device that has not been
 * seeded yet: seeding needs PackageManager lookups, and in allowlist mode an
 * unseeded list would show an empty home screen until the reload finished.
 * Main-thread safe: preference reads and a filter over an in-memory list.
 */
private fun warmVisibleApps(prefs: Prefs, selfPackage: String): List<LaunchableApp>? = runCatching {
    if (!prefs.hasSeededDefaults) return@runCatching null
    InstalledApps.cached()?.let { visibleApps(it, prefs, selfPackage) }
}.getOrNull()

private fun visibleApps(installed: List<LaunchableApp>, prefs: Prefs, selfPackage: String): List<LaunchableApp> =
    AppFilter.visibleApps(
        installed = installed,
        mode = prefs.mode,
        allowlist = prefs.allowlist,
        blocklist = prefs.blocklist,
        selfPackage = selfPackage,
    )

private const val TAG = "DistractionKiller"
