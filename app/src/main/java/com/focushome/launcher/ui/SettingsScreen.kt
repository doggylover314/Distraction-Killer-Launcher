package com.focushome.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.focushome.launcher.data.AppFilter
import com.focushome.launcher.data.LaunchableApp
import com.focushome.launcher.data.LauncherMode
import com.focushome.launcher.data.Prefs

/**
 * Everything behind the password gate.
 *
 * It takes [Prefs] directly instead of hoisting a dozen callbacks up to the
 * activity: every control here writes straight through to disk the moment it
 * changes, so there is no "unsaved state" to model. Local `remember` values are
 * only mirrors that keep the UI responsive.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    apps: List<LaunchableApp>,
    isLoading: Boolean,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf(prefs.mode) }
    var editingList by remember { mutableStateOf(prefs.mode) }
    // Both lists are held in state rather than re-read from Prefs during
    // composition, so the checkboxes and the two counters below stay in step
    // however Compose decides to skip recomposition.
    var allowlist by remember { mutableStateOf(prefs.allowlist) }
    var blocklist by remember { mutableStateOf(prefs.blocklist) }
    var query by remember { mutableStateOf("") }
    var weatherEnabled by remember { mutableStateOf(prefs.weatherEnabled) }
    var useFahrenheit by remember { mutableStateOf(prefs.useFahrenheit) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordChangedNotice by remember { mutableStateOf(false) }

    val checkedPackages = when (editingList) {
        LauncherMode.ALLOWLIST -> allowlist
        LauncherMode.BLOCKLIST -> blocklist
    }
    val visibleApps = remember(apps, query) { AppFilter.search(apps, query) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
            }

            // ------------------------------------------------------ mode
            item { SectionHeader("Home screen mode") }
            item {
                ModeOption(
                    title = "Allowlist",
                    description = "Show only the apps you tick.",
                    selected = mode == LauncherMode.ALLOWLIST,
                    onSelect = {
                        mode = LauncherMode.ALLOWLIST
                        prefs.mode = LauncherMode.ALLOWLIST
                        editingList = LauncherMode.ALLOWLIST
                    },
                )
            }
            item {
                ModeOption(
                    title = "Blocklist",
                    description = "Show every app except the ones you tick.",
                    selected = mode == LauncherMode.BLOCKLIST,
                    onSelect = {
                        mode = LauncherMode.BLOCKLIST
                        prefs.mode = LauncherMode.BLOCKLIST
                        editingList = LauncherMode.BLOCKLIST
                    },
                )
            }

            // --------------------------------------------------- weather
            item { Divider() }
            item { SectionHeader("Header") }
            item {
                ToggleRow(
                    title = "Show weather",
                    description = "Uses approximate location and one call to " +
                        "open-meteo.com. Off means the app makes no network calls at all.",
                    checked = weatherEnabled,
                    onCheckedChange = {
                        weatherEnabled = it
                        prefs.weatherEnabled = it
                    },
                )
            }
            item {
                ToggleRow(
                    title = "Fahrenheit",
                    description = "Off shows Celsius.",
                    checked = useFahrenheit,
                    onCheckedChange = {
                        useFahrenheit = it
                        prefs.useFahrenheit = it
                    },
                )
            }

            // -------------------------------------------------- password
            item { Divider() }
            item { SectionHeader("Security") }
            item {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = { showPasswordDialog = true }) {
                        Text("Change password")
                    }
                    if (passwordChangedNotice) {
                        Text(
                            text = "Password updated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // ------------------------------------------------- app lists
            item { Divider() }
            item {
                SectionHeader(
                    title = "Apps",
                    subtitle = "Both lists are kept, so you can edit either one " +
                        "without losing the other.",
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ListChoice(
                        label = "Allowlist",
                        count = allowlist.size,
                        active = mode == LauncherMode.ALLOWLIST,
                        selected = editingList == LauncherMode.ALLOWLIST,
                        onSelect = { editingList = LauncherMode.ALLOWLIST },
                        modifier = Modifier.weight(1f),
                    )
                    ListChoice(
                        label = "Blocklist",
                        count = blocklist.size,
                        active = mode == LauncherMode.BLOCKLIST,
                        selected = editingList == LauncherMode.BLOCKLIST,
                        onSelect = { editingList = LauncherMode.BLOCKLIST },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Text(
                    text = when (editingList) {
                        LauncherMode.ALLOWLIST -> "Ticked apps appear on the home screen."
                        LauncherMode.BLOCKLIST -> "Ticked apps are hidden from the home screen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            if (isLoading && apps.isEmpty()) {
                item { InfoText("Loading installed apps…") }
            } else if (visibleApps.isEmpty()) {
                item { InfoText("No apps match \"$query\".") }
            } else {
                items(items = visibleApps, key = { it.packageName }) { app ->
                    AppCheckRow(
                        app = app,
                        checked = app.packageName in checkedPackages,
                        onCheckedChange = { isChecked ->
                            prefs.setPackageChecked(editingList, app.packageName, isChecked)
                            when (editingList) {
                                LauncherMode.ALLOWLIST -> allowlist = prefs.allowlist
                                LauncherMode.BLOCKLIST -> blocklist = prefs.blocklist
                            }
                        },
                    )
                }
            }

            item {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onConfirm = { newPassword ->
                prefs.setPassword(newPassword)
                showPasswordDialog = false
                passwordChangedNotice = true
            },
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // You already unlocked this screen with the old password, so
                // only the new one is asked for.
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; error = null },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val problem = validateNewPassword(password, confirmation)
                    if (problem == null) onConfirm(password) else error = problem
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AppCheckRow(
    app: LaunchableApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListChoice(
    label: String,
    count: Int,
    active: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val suffix = if (active) " · in use" else ""
    if (selected) {
        Button(onClick = onSelect, modifier = modifier) {
            Text("$label ($count)$suffix", maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onSelect, modifier = modifier) {
            Text("$label ($count)$suffix", maxLines = 1)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}
