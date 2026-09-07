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
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.ui.HomeScreen
import com.distractionkiller.launcher.ui.HomeUiConfig
import com.distractionkiller.launcher.ui.SetPasswordScreen
import com.distractionkiller.launcher.ui.theme.DistractionKillerTheme
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
     * Bumped in onResume. Coming back from Settings, or from another app after
     * installing or removing something, re-reads preferences and re-queries
     * PackageManager. Cheaper and easier to follow than a package-change
     * BroadcastReceiver, and a launcher is always resumed before it is seen.
     */
    private val resumeCounter = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = Prefs(this)
        appRepository = AppRepository(this)

        setContent {
            val refreshKey by resumeCounter
            val themeMode = remember(refreshKey) { prefs.themeMode }

            DistractionKillerTheme(themeMode = themeMode) {
                var hasPassword by remember(refreshKey) { mutableStateOf(prefs.isPasswordSet) }

                // Back does nothing on the home screen, the same as a stock
                // launcher. Also stops the first-run password screen from being
                // dismissed with a swipe.
                BackHandler(enabled = true) { /* intentionally empty */ }

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
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCounter.intValue++
    }
}

@Composable
private fun HomeRoute(
    prefs: Prefs,
    appRepository: AppRepository,
    refreshKey: Int,
) {
    val context = LocalContext.current
    val weatherRepository = remember { WeatherRepository(context) }

    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var isLoading by remember { mutableStateOf(true) }
    var weather by remember { mutableStateOf(prefs.cachedWeather) }
    var launchCounter by remember(refreshKey) { mutableStateOf(prefs.launchCounter) }

    // Re-read on every resume so changes made in Settings show up immediately.
    val mode = remember(refreshKey) { prefs.mode }
    val config = remember(refreshKey) { HomeUiConfig.from(prefs) }
    val today = remember(refreshKey) { LocalDate.now().toString() }

    var hasLocationPermission by remember(refreshKey) {
        mutableStateOf(weatherRepository.hasLocationPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    LaunchedEffect(refreshKey) {
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
                appRepository.loadLaunchableApps()
            }
        }
            .onSuccess { installed ->
                apps = AppFilter.visibleApps(
                    installed = installed,
                    mode = prefs.mode,
                    allowlist = prefs.allowlist,
                    blocklist = prefs.blocklist,
                    selfPackage = context.packageName,
                )
            }
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
        isLoading = isLoading,
        weather = weather,
        config = config,
        launchCounts = if (config.showLaunchCounts) {
            launchCounter.counts.takeIf { launchCounter.dateKey == today } ?: emptyMap()
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
                launchCounter = launchCounter.recorded(app.packageName, today)
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

private const val TAG = "DistractionKiller"
