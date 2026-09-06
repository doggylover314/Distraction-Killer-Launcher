package com.distractionkiller.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.LauncherMode
import com.distractionkiller.launcher.data.TextSize
import com.distractionkiller.launcher.weather.WeatherSnapshot

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
    config: HomeUiConfig,
    launchCounts: Map<String, Int>,
    onLaunch: (LaunchableApp) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // The search box only narrows what is already visible; it never reaches
    // hidden apps, so it is safe to leave unprotected.
    val shownApps = remember(apps, query, config.showSearchBar) {
        if (config.showSearchBar) AppFilter.search(apps, query) else apps
    }

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
                config = config,
                modifier = Modifier.padding(top = 32.dp, bottom = 18.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (config.showSearchBar) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

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

                    shownApps.isEmpty() -> HintText("No apps match \"$query\".")

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = shownApps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                config = config,
                                launchCount = launchCounts[app.packageName] ?: 0,
                                onClick = { onLaunch(app) },
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .then(if (config.centerAlign) Modifier.align(Alignment.CenterHorizontally) else Modifier),
            ) {
                Text(text = "Settings")
            }
        }
    }
}

@Composable
private fun AppRow(
    app: LaunchableApp,
    config: HomeUiConfig,
    launchCount: Int,
    onClick: () -> Unit,
) {
    val align = if (config.centerAlign) TextAlign.Center else TextAlign.Start
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = labelStyle(config.textSize),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
            if (config.showPackageNames) {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = align,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (config.showLaunchCounts && launchCount > 0) {
            Text(
                text = "$launchCount×",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun labelStyle(size: TextSize): TextStyle = when (size) {
    TextSize.SMALL -> MaterialTheme.typography.titleLarge
    TextSize.MEDIUM -> MaterialTheme.typography.headlineSmall
    TextSize.LARGE -> MaterialTheme.typography.headlineLarge
}

@Composable
private fun HintText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
