package com.distractionkiller.launcher.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.DockList
import com.distractionkiller.launcher.data.DockStyle
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.LauncherMode
import com.distractionkiller.launcher.data.TextSize
import com.distractionkiller.launcher.weather.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home screen: header, a plain text list of app names, an optional dock
 * (names, or greyscale icons if chosen), and a way into Settings. No icons in
 * the list, by request, and no wallpaper-dependent chrome.
 *
 * [resetKey] changes whenever the launcher is resumed or Home is pressed again;
 * it throws away the half-typed search so the list is never left filtered.
 */
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>,
    mode: LauncherMode,
    resetKey: Int,
    isLoading: Boolean,
    weather: WeatherSnapshot?,
    config: HomeUiConfig,
    launchCounts: Map<String, Int>,
    onLaunch: (LaunchableApp) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember(resetKey) { mutableStateOf("") }

    // Emptying the text is not enough: the field would keep focus and leave the
    // keyboard hanging over the list the user just returned to.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(resetKey) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    // The search box only narrows what is already visible; it never reaches
    // hidden apps, so it is safe to leave unprotected.
    val shownApps = remember(apps, query, config.showSearchBar) {
        // Label only (the default): people type the name they see on screen.
        if (config.showSearchBar) AppFilter.search(apps, query) else apps
    }

    // Resolved against the visible, unsearched list rather than trusted from
    // Prefs: a hidden, blocked or uninstalled entry simply never renders, so
    // the dock can never reach an app the current mode and lists keep away.
    val dockApps = remember(apps, config.dockPackages) {
        DockList.resolve(config.dockPackages, apps)
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

            if (config.dockEnabled && dockApps.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                AppDock(apps = dockApps, config = config, onLaunch = onLaunch)
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

/**
 * Quick-access row, always exactly one line tall so it never eats into the list
 * above it. Text mode lays the names out with [DockFit]: names that fit keep
 * their full width and only the long ones are shortened, by as little as the
 * row allows. Icon mode draws each app's icon in greyscale, which keeps the
 * dock recognisable without the colour that makes icons pull at the eye.
 */
@Composable
private fun AppDock(
    apps: List<LaunchableApp>,
    config: HomeUiConfig,
    onLaunch: (LaunchableApp) -> Unit,
) {
    when (config.dockStyle) {
        DockStyle.TEXT -> TextDock(apps, config, onLaunch)
        DockStyle.ICONS -> IconDock(apps, config, onLaunch)
    }
}

@Composable
private fun TextDock(
    apps: List<LaunchableApp>,
    config: HomeUiConfig,
    onLaunch: (LaunchableApp) -> Unit,
) {
    val style = dockLabelStyle(config.textSize)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            for (app in apps) {
                Text(
                    text = app.label,
                    style = style,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable { onLaunch(app) }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val available = constraints.maxWidth
        val natural = IntArray(measurables.size) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) }
        val caps = DockFit.caps(natural, available)
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints(minWidth = 0, maxWidth = caps[i]))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        // Space evenly, like the old row: equal gaps at both ends and between.
        val free = (available - placeables.sumOf { it.width }).coerceAtLeast(0)
        val gap = free / (placeables.size + 1)
        layout(available, height) {
            var x = gap
            for (p in placeables) {
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

@Composable
private fun IconDock(
    apps: List<LaunchableApp>,
    config: HomeUiConfig,
    onLaunch: (LaunchableApp) -> Unit,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { DOCK_ICON_SIZE.roundToPx() }
    // Off the main thread: each icon is a PackageManager call plus a drawable
    // decode. Keyed on the resolved list, which is rebuilt whenever the app
    // list is, so an updated app's new icon shows up on the next resume.
    val icons by produceState<Map<String, ImageBitmap>>(emptyMap(), apps, sizePx) {
        value = withContext(Dispatchers.IO) { loadDockIcons(context, apps, sizePx) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (app in apps) {
            val icon = icons[app.packageName]
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clickable(onClickLabel = "Open ${app.label}") { onLaunch(app) }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .size(DOCK_ICON_SIZE),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = app.label,
                        colorFilter = GREYSCALE,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Still loading, or the app has no icon we could read: the
                    // first letter keeps the slot tappable and identifiable.
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

private val DOCK_ICON_SIZE = 40.dp

private val GREYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Icons that cannot be read are left out, and the dock shows a letter instead. */
private fun loadDockIcons(context: Context, apps: List<LaunchableApp>, sizePx: Int): Map<String, ImageBitmap> {
    val pm = context.packageManager
    val icons = HashMap<String, ImageBitmap>(apps.size)
    for (app in apps) {
        try {
            icons[app.packageName] = pm.getApplicationIcon(app.packageName)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            // Uninstalled since the list was built.
        } catch (_: RuntimeException) {
            // A broken or oversized icon resource in someone else's APK.
        }
    }
    return icons
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

/**
 * One step below [labelStyle]. The dock follows the App name size setting, so a
 * reader who sized the list up does not get a dock that stayed small, but it
 * trails the list by a step: five names across at the list's own size would
 * crowd five names into the one-line dock.
 */
@Composable
private fun dockLabelStyle(size: TextSize): TextStyle = when (size) {
    TextSize.SMALL -> MaterialTheme.typography.bodyLarge
    TextSize.MEDIUM -> MaterialTheme.typography.titleMedium
    TextSize.LARGE -> MaterialTheme.typography.titleLarge
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
