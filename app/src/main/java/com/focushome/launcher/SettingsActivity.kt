package com.focushome.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.focushome.launcher.data.AppRepository
import com.focushome.launcher.data.LaunchableApp
import com.focushome.launcher.data.Prefs
import com.focushome.launcher.ui.PasswordPromptScreen
import com.focushome.launcher.ui.SetPasswordScreen
import com.focushome.launcher.ui.SettingsScreen
import com.focushome.launcher.ui.theme.FocusHomeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Password gate plus the editor behind it.
 *
 * Declared exported=false and noHistory=true in the manifest: nothing else on
 * the device can start it, and it re-locks as soon as it leaves the foreground.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = Prefs(this)
        val appRepository = AppRepository(this)

        setContent {
            FocusHomeTheme {
                SettingsRoute(
                    prefs = prefs,
                    appRepository = appRepository,
                    onFinish = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SettingsRoute(
    prefs: Prefs,
    appRepository: AppRepository,
    onFinish: () -> Unit,
) {
    // rememberSaveable so a rotation does not throw you back to the prompt.
    // The activity is noHistory, so this state cannot outlive the visit.
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(prefs.isPasswordSet) }

    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(unlocked) {
        if (!unlocked) return@LaunchedEffect
        isLoading = true
        apps = withContext(Dispatchers.IO) {
            runCatching { appRepository.loadLaunchableApps() }.getOrDefault(emptyList())
        }
        isLoading = false
    }

    when {
        // Only reachable if prefs were cleared while this was open. Treated as
        // a fresh setup rather than an open door.
        !hasPassword -> SetPasswordScreen(
            title = "Set a password",
            subtitle = "No password is set yet. Choose one to protect these settings.",
            submitLabel = "Save password",
            onSubmit = { password ->
                prefs.setPassword(password)
                hasPassword = true
                unlocked = true
            },
            onCancel = onFinish,
        )

        !unlocked -> PasswordPromptScreen(
            onSubmit = { candidate ->
                val correct = prefs.verifyPassword(candidate)
                if (correct) unlocked = true
                correct
            },
            onCancel = onFinish,
        )

        else -> SettingsScreen(
            prefs = prefs,
            apps = apps,
            isLoading = isLoading,
            onDone = onFinish,
        )
    }
}
