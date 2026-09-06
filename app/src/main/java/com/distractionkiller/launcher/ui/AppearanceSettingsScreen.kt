package com.distractionkiller.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.distractionkiller.launcher.data.ClockFormat
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.data.TextSize
import com.distractionkiller.launcher.data.ThemeMode

/**
 * The settings anyone can change: they alter how the home screen looks and
 * nothing about what it lets you reach. Every control writes straight through
 * to [Prefs]; the local state is only a mirror.
 */
@Composable
fun AppearanceSettingsScreen(
    prefs: Prefs,
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
