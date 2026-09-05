package com.focushome.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focushome.launcher.data.LaunchableApp
import com.focushome.launcher.data.LauncherMode
import com.focushome.launcher.weather.WeatherSnapshot

/**
 * The home screen: header, a plain text list of app names, and a way into
 * Settings. No icons, by request, and no wallpaper-dependent chrome.
 */
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>,
    mode: LauncherMode,
    isLoading: Boolean,
    weather: WeatherSnapshot?,
    weatherEnabled: Boolean,
    useFahrenheit: Boolean,
    onLaunch: (LaunchableApp) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keeps content clear of the status bar and gesture area now
                // that Android 15 draws every app edge to edge.
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            StatusHeader(
                weather = weather,
                useFahrenheit = useFahrenheit,
                weatherEnabled = weatherEnabled,
                modifier = Modifier.padding(top = 32.dp, bottom = 18.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading && apps.isEmpty() -> HintText("Loading apps…")

                    apps.isEmpty() -> HintText(
                        when (mode) {
                            LauncherMode.ALLOWLIST ->
                                "Nothing on the allowlist yet.\nOpen Settings to add apps."
                            LauncherMode.BLOCKLIST ->
                                "Every app is blocked.\nOpen Settings to unblock some."
                        },
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = apps, key = { it.packageName }) { app ->
                            AppRow(app = app, onClick = { onLaunch(app) })
                        }
                    }
                }
            }

            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(text = "Settings")
            }
        }
    }
}

@Composable
private fun AppRow(app: LaunchableApp, onClick: () -> Unit) {
    Text(
        text = app.label,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
