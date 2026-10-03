package com.distractionkiller.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.ClockFormat
import com.distractionkiller.launcher.data.DockList
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.data.TextSize
import com.distractionkiller.launcher.data.ThemeMode

/**
 * The settings anyone can change: they alter how the home screen looks and
 * nothing about what it lets you reach. Every control writes straight through
 * to [Prefs]; the local state is only a mirror.
 *
 * [apps] is every launchable app on the device, loaded off the main thread by
 * the caller. This screen narrows it to what the home screen currently shows
 * before offering any of it as a dock candidate. [loadFailed] means the query
 * itself failed, which is not the same as an empty list: the dock editor then
 * offers [onRetryLoad] instead of presenting every saved entry as hidden.
 */
@Composable
fun AppearanceSettingsScreen(
    prefs: Prefs,
    apps: List<LaunchableApp>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetryLoad: () -> Unit,
    onOpenProtected: () -> Unit,
    onDone: () -> Unit,
    onThemeChanged: (ThemeMode) -> Unit = {},
) {
    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var textSize by remember { mutableStateOf(prefs.textSize) }
    var centerAlign by remember { mutableStateOf(prefs.centerAlign) }
    var showClock by remember { mutableStateOf(prefs.showClock) }
    var clockFormat by remember { mutableStateOf(prefs.clockFormat) }
    var showDate by remember { mutableStateOf(prefs.showDate) }
    var showBattery by remember { mutableStateOf(prefs.showBattery) }
    var weatherEnabled by remember { mutableStateOf(prefs.weatherEnabled) }
    var useFahrenheit by remember { mutableStateOf(prefs.useFahrenheit) }
    var showPackageNames by remember { mutableStateOf(prefs.showPackageNames) }
    var showSearchBar by remember { mutableStateOf(prefs.showSearchBar) }
    var showLaunchCounts by remember { mutableStateOf(prefs.showLaunchCounts) }
    var focusNoteEnabled by remember { mutableStateOf(prefs.focusNoteEnabled) }
    var focusNoteText by remember { mutableStateOf(prefs.focusNoteText) }
    var dockEnabled by remember { mutableStateOf(prefs.dockEnabled) }
    var dockPackages by remember { mutableStateOf(prefs.dockPackages) }

    val context = LocalContext.current
    // Filtered here, not by the caller: this screen is rebuilt on every return
    // from Protected settings, where the mode and lists may just have changed,
    // so the candidates always match what the home screen will show.
    val candidates = remember(apps) {
        AppFilter.visibleApps(
            installed = apps,
            mode = prefs.mode,
            allowlist = prefs.allowlist,
            blocklist = prefs.blocklist,
            selfPackage = context.packageName,
        )
    }
    val candidatesByPackage = remember(candidates) { candidates.associateBy { it.packageName } }
    // Only entries that still resolve to a visible app hold a slot. The rest
    // draw nothing on the home screen, so they must not lock the picker; they
    // are dropped the next time something is pinned.
    val dockUsed = dockPackages.count { it in candidatesByPackage }
    val dockFull = dockUsed >= DockList.MAX_ENTRIES

    fun saveDock(updated: List<String>) {
        dockPackages = updated
        prefs.dockPackages = updated
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Look only. Anything that changes what you can open is under Protected settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedButton(onClick = onOpenProtected, modifier = Modifier.fillMaxWidth()) {
                    Text("Protected settings (password)")
                }
            }

            // ----------------------------------------------------- theme
            item { SettingsDivider() }
            item { SectionHeader("Theme") }
            item {
                OptionGroup(
                    options = listOf(
                        Triple(ThemeMode.SYSTEM, "Follow system", null),
                        Triple(ThemeMode.LIGHT, "Light", null),
                        Triple(ThemeMode.DARK, "Dark", null),
                    ),
                    selected = themeMode,
                    onSelect = { themeMode = it; prefs.themeMode = it; onThemeChanged(it) },
                )
            }
            item { SectionHeader("App name size") }
            item {
                OptionGroup(
                    options = listOf(
                        Triple(TextSize.SMALL, "Small", null),
                        Triple(TextSize.MEDIUM, "Medium", null),
                        Triple(TextSize.LARGE, "Large", null),
                    ),
                    selected = textSize,
                    onSelect = { textSize = it; prefs.textSize = it },
                )
            }
            item {
                ToggleRow(
                    title = "Centre everything",
                    description = "Off keeps text left-aligned.",
                    checked = centerAlign,
                    onCheckedChange = { centerAlign = it; prefs.centerAlign = it },
                )
            }

            // ---------------------------------------------------- header
            item { SettingsDivider() }
            item { SectionHeader("Header") }
            item {
                ToggleRow(
                    title = "Clock",
                    description = null,
                    checked = showClock,
                    onCheckedChange = { showClock = it; prefs.showClock = it },
                )
            }
            item {
                OptionGroup(
                    options = listOf(
                        Triple(ClockFormat.SYSTEM, "Follow system clock format", null),
                        Triple(ClockFormat.HOUR_12, "12-hour", null),
                        Triple(ClockFormat.HOUR_24, "24-hour", null),
                    ),
                    selected = clockFormat,
                    onSelect = { clockFormat = it; prefs.clockFormat = it },
                )
            }
            item {
                ToggleRow(
                    title = "Day and date",
                    description = null,
                    checked = showDate,
                    onCheckedChange = { showDate = it; prefs.showDate = it },
                )
            }
            item {
                ToggleRow(
                    title = "Battery",
                    description = null,
                    checked = showBattery,
                    onCheckedChange = { showBattery = it; prefs.showBattery = it },
                )
            }
            item {
                ToggleRow(
                    title = "Weather",
                    description = "Uses approximate location and one call to " +
                        "open-meteo.com. Off means the app makes no network calls at all.",
                    checked = weatherEnabled,
                    onCheckedChange = { weatherEnabled = it; prefs.weatherEnabled = it },
                )
            }
            item {
                ToggleRow(
                    title = "Fahrenheit",
                    description = "Off shows Celsius.",
                    checked = useFahrenheit,
                    onCheckedChange = { useFahrenheit = it; prefs.useFahrenheit = it },
                )
            }

            // ------------------------------------------------ focus note
            item { SettingsDivider() }
            item { SectionHeader("Focus note", "One line under the date, for whatever you want to be reminded of.") }
            item {
                ToggleRow(
                    title = "Show focus note",
                    description = null,
                    checked = focusNoteEnabled,
                    onCheckedChange = { focusNoteEnabled = it; prefs.focusNoteEnabled = it },
                )
            }
            item {
                OutlinedTextField(
                    value = focusNoteText,
                    onValueChange = { focusNoteText = it; prefs.focusNoteText = it },
                    label = { Text("Note") },
                    singleLine = true,
                    enabled = focusNoteEnabled,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }

            // ------------------------------------------------- app list
            item { SettingsDivider() }
            item { SectionHeader("App list") }
            item {
                ToggleRow(
                    title = "Search box",
                    description = "Filters the apps already on the home screen. It cannot reach hidden ones.",
                    checked = showSearchBar,
                    onCheckedChange = { showSearchBar = it; prefs.showSearchBar = it },
                )
            }
            item {
                ToggleRow(
                    title = "Opens today",
                    description = "A small count next to each app, reset at midnight.",
                    checked = showLaunchCounts,
                    onCheckedChange = { showLaunchCounts = it; prefs.showLaunchCounts = it },
                )
            }
            item {
                ToggleRow(
                    title = "Package names",
                    description = "The technical id under each app name.",
                    checked = showPackageNames,
                    onCheckedChange = { showPackageNames = it; prefs.showPackageNames = it },
                )
            }

            // -------------------------------------------------- app dock
            // No password needed: the home screen resolves the dock against the
            // visible app list at render time, so it can only ever hold apps the
            // user could already tap in the list. It changes how the home screen
            // looks, never what it can reach. Last in the screen because the
            // picker is as long as the visible app list.
            item { SettingsDivider() }
            item {
                SectionHeader(
                    title = "App dock",
                    subtitle = "A row of up to ${DockList.MAX_ENTRIES} apps pinned at the bottom of the home screen.",
                )
            }
            item {
                ToggleRow(
                    title = "Show dock",
                    description = "Off hides it and keeps your choices.",
                    checked = dockEnabled,
                    onCheckedChange = { dockEnabled = it; prefs.dockEnabled = it },
                )
            }

            // The editor, and above all the picker (one row per visible app), is
            // only worth scrolling past for someone who turned the dock on.
            if (dockEnabled) {
                if (loadFailed) {
                    // Not "hidden": the query failed, so nothing is known about
                    // any entry, and nothing here may offer to delete one.
                    item {
                        InfoText(
                            "Could not read the installed apps, so the dock cannot be edited " +
                                "right now. Your pinned apps are kept.",
                        )
                    }
                    item { TextButton(onClick = onRetryLoad) { Text("Try again") } }
                } else if (isLoading && apps.isEmpty()) {
                    // Until the list arrives every saved entry would look hidden.
                    item { InfoText("Loading installed apps…") }
                } else {
                    if (dockPackages.isEmpty()) {
                        item { InfoText("Nothing pinned yet.") }
                    }
                    itemsIndexed(items = dockPackages, key = { _, pkg -> "dock-entry-$pkg" }) { index, pkg ->
                        val app = candidatesByPackage[pkg]
                        DockEntryRow(
                            position = index + 1,
                            // A hidden entry shows only the id already saved in the
                            // dock. This screen has no password, so it does not
                            // look up the name of an app the current list hides.
                            title = app?.label ?: pkg,
                            hidden = app == null,
                            canMoveLeft = index > 0,
                            canMoveRight = index < dockPackages.lastIndex,
                            onMoveLeft = { saveDock(DockList.moved(dockPackages, pkg, -1)) },
                            onMoveRight = { saveDock(DockList.moved(dockPackages, pkg, +1)) },
                            onRemove = { saveDock(DockList.toggled(dockPackages, pkg)) },
                        )
                    }

                    item {
                        InfoText(
                            text = if (dockFull) {
                                "$dockUsed of ${DockList.MAX_ENTRIES} dock slots in use. " +
                                    "Remove one to pin another."
                            } else {
                                "$dockUsed of ${DockList.MAX_ENTRIES} dock slots in use. " +
                                    "Tick apps below to pin them."
                            },
                            highlighted = dockFull,
                        )
                    }
                    if (candidates.isEmpty()) {
                        item { InfoText("No apps are on the home screen yet, so there is nothing to pin.") }
                    } else {
                        items(items = candidates, key = { "dock-pick-" + it.packageName }) { app ->
                            val pinned = app.packageName in dockPackages
                            DockPickerRow(
                                label = app.label,
                                checked = pinned,
                                // Disabled rather than ignoring the tap, so a full
                                // dock is visible instead of looking broken.
                                enabled = pinned || !dockFull,
                                onCheckedChange = {
                                    saveDock(
                                        DockList.toggledAmong(
                                            dockPackages,
                                            app.packageName,
                                            candidatesByPackage.keys,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * One saved dock entry. A hidden one (not on the home screen under the current
 * app list, or no longer installed) is greyed and can only be removed: moving
 * it would not change anything the user can see. A visible one can be removed
 * too, so unpinning never means hunting for the app in the picker below.
 */
@Composable
private fun DockEntryRow(
    position: Int,
    title: String,
    hidden: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "$position. $title",
            style = MaterialTheme.typography.bodyLarge,
            color = if (hidden) dimmed() else MaterialTheme.colorScheme.onSurface,
        )
        if (hidden) {
            Text(
                text = "Hidden by the current app list or not installed, so it is not shown on the home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            if (!hidden) {
                TextButton(
                    onClick = onMoveLeft,
                    enabled = canMoveLeft,
                    modifier = Modifier.semantics { contentDescription = "Move $title left" },
                ) { Text("Move left") }
                TextButton(
                    onClick = onMoveRight,
                    enabled = canMoveRight,
                    modifier = Modifier.semantics { contentDescription = "Move $title right" },
                ) { Text("Move right") }
            }
            // The visible text is the same for every row; the description says which.
            TextButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove $title from the dock" },
            ) { Text("Remove") }
        }
    }
}

/**
 * The whole row toggles, and the checkbox carries no click handler of its own,
 * so a screen reader sees one checkbox rather than a row plus a checkbox.
 */
@Composable
private fun DockPickerRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else dimmed(),
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Material's own disabled-content opacity, for text the components do not dim themselves. */
@Composable
private fun dimmed() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
