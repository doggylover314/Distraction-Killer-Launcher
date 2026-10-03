package com.distractionkiller.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.distractionkiller.launcher.data.AppRepository
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.data.ThemeMode
import com.distractionkiller.launcher.ui.AppearanceSettingsScreen
import com.distractionkiller.launcher.ui.PasswordPromptScreen
import com.distractionkiller.launcher.ui.ProtectedSettingsScreen
import com.distractionkiller.launcher.ui.SetPasswordScreen
import com.distractionkiller.launcher.ui.theme.DistractionKillerTheme
import com.distractionkiller.launcher.ui.theme.windowThemeResId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two tiers of settings behind one entry point.
 *
 * Appearance opens straight away. Protected sits behind the password prompt.
 * Declared exported=false and noHistory=true in the manifest: nothing else on
 * the device can start it, and it re-locks as soon as it leaves the foreground.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Prefs(this)
        setTheme(prefs.themeMode.windowThemeResId())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appRepository = AppRepository(this)

        setContent {
            var themeMode by remember { mutableStateOf(prefs.themeMode) }
            DistractionKillerTheme(themeMode = themeMode) {
                SettingsRoute(
                    prefs = prefs,
                    appRepository = appRepository,
                    onThemeChanged = { themeMode = it },
                    onFinish = { finish() },
                )
            }
        }
    }

    /**
     * noHistory finishes this activity when something covers it, but the
     * framework deliberately skips that when the screen simply turns off.
     * Finishing here makes "leaves the foreground, re-locks" hold in that
     * case too. Rotation is exempt so it does not bounce you to the prompt.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}

private enum class Screen { APPEARANCE, PROMPT, PROTECTED }

@Composable
private fun SettingsRoute(
    prefs: Prefs,
    appRepository: AppRepository,
    onThemeChanged: (ThemeMode) -> Unit,
    onFinish: () -> Unit,
) {
    // rememberSaveable so a rotation does not throw you back to the prompt.
    // The activity is noHistory, so this state cannot outlive the visit.
    var screen by rememberSaveable { mutableStateOf(Screen.APPEARANCE) }
    var hasPassword by remember { mutableStateOf(prefs.isPasswordSet) }

    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var isLoading by remember { mutableStateOf(true) }
    // Kept apart from an empty list: "the query failed" and "nothing to show"
    // look the same in [apps], and the Appearance screen must not call every
    // saved dock entry hidden on the strength of a failed query.
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }

    // Back from either inner screen returns to Appearance and re-locks.
    BackHandler(enabled = screen != Screen.APPEARANCE) { screen = Screen.APPEARANCE }

    // Both tiers need the list: Protected to edit it, Appearance to offer the
    // dock only apps the home screen shows. Keyed on the attempt, not on the
    // screen: the query is blocking and cannot be cancelled mid-flight, so
    // restarting it on every navigation would leave the discarded ones running.
    LaunchedEffect(loadAttempt) {
        isLoading = true
        loadFailed = false
        withContext(Dispatchers.IO) { runCatching { appRepository.loadLaunchableApps() } }
            .onSuccess { apps = it }
            .onFailure { loadFailed = true }
        isLoading = false
    }

    // A failed load is tried again on the next screen change, as before, but
    // never while one is still running.
    LaunchedEffect(screen) {
        if (loadFailed && !isLoading) loadAttempt++
    }

    when (screen) {
        Screen.APPEARANCE -> AppearanceSettingsScreen(
            prefs = prefs,
            apps = apps,
            isLoading = isLoading,
            loadFailed = loadFailed,
            onRetryLoad = { loadAttempt++ },
            onOpenProtected = { screen = Screen.PROMPT },
            onDone = onFinish,
            // Re-themes this activity immediately instead of on the next visit.
            onThemeChanged = onThemeChanged,
        )

        // Only reachable if prefs were cleared while this was open. Treated as
        // a fresh setup rather than an open door.
        Screen.PROMPT -> if (!hasPassword) {
            SetPasswordScreen(
                title = "Set a password",
                subtitle = "No password is set yet. Choose one to protect these settings.",
                submitLabel = "Save password",
                onSubmit = { password ->
                    prefs.setPassword(password)
                    hasPassword = true
                    screen = Screen.PROTECTED
                },
                onCancel = { screen = Screen.APPEARANCE },
            )
        } else {
            PasswordPromptScreen(
                onSubmit = { candidate ->
                    val correct = prefs.verifyPassword(candidate)
                    if (correct) screen = Screen.PROTECTED
                    correct
                },
                onCancel = { screen = Screen.APPEARANCE },
            )
        }

        Screen.PROTECTED -> ProtectedSettingsScreen(
            prefs = prefs,
            apps = apps,
            isLoading = isLoading,
            onDone = onFinish,
        )
    }
}
