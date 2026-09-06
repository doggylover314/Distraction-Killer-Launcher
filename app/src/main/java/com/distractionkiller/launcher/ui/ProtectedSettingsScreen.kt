package com.distractionkiller.launcher.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.distractionkiller.launcher.blocker.AccessibilityStatus
import com.distractionkiller.launcher.blocker.UrlMatcher
import com.distractionkiller.launcher.blocker.WebsiteMode
import com.distractionkiller.launcher.blocker.WebsitePresets
import com.distractionkiller.launcher.data.AppFilter
import com.distractionkiller.launcher.data.LaunchableApp
import com.distractionkiller.launcher.data.LauncherMode
import com.distractionkiller.launcher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How long the Android Settings lock stands down after the password is used. */
const val SETTINGS_UNLOCK_MINUTES = 10L

/**
 * Everything behind the password: what can be reached, what is blocked, and
 * how hard the phone makes it to get around that.
 *
 * Takes [Prefs] directly instead of hoisting a dozen callbacks up to the
 * activity: every control writes straight through to disk the moment it
 * changes, so there is no "unsaved state" to model.
 */
@Composable
fun ProtectedSettingsScreen(
    prefs: Prefs,
    apps: List<LaunchableApp>,
    isLoading: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(prefs.mode) }
    var editingList by remember { mutableStateOf(prefs.mode) }
    // Both lists are held in state rather than re-read from Prefs during
    // composition, so the checkboxes and the two counters stay in step
    // however Compose decides to skip recomposition.
    var allowlist by remember { mutableStateOf(prefs.allowlist) }
    var blocklist by remember { mutableStateOf(prefs.blocklist) }
    var query by remember { mutableStateOf("") }

    var siteBlocking by remember { mutableStateOf(prefs.siteBlockingEnabled) }
    var websiteMode by remember { mutableStateOf(prefs.websiteMode) }
    // Keyed on the mode: switching lists swaps in that list's own contents.
    var customSites by remember(websiteMode) { mutableStateOf(prefs.websiteListFor(websiteMode)) }
    var enabledPresets by remember { mutableStateOf(prefs.enabledWebsitePresets) }
    val presets = remember { WebsitePresets(context) }
    val catalog = remember { presets.catalog() }
    var presetCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(catalog) {
        presetCounts = withContext(Dispatchers.IO) {
            catalog.associate { it.id to presets.countDomains(it.id) }
        }
    }
    var enforceApps by remember { mutableStateOf(prefs.enforceAppsSystemWide) }
    var lockSettings by remember { mutableStateOf(prefs.lockSystemSettings) }
    var keywords by remember { mutableStateOf(prefs.settingsLockKeywords) }
    var unlockedUntil by remember { mutableLongStateOf(prefs.systemSettingsUnlockedUntil) }
    val serviceOn = remember { AccessibilityStatus.isServiceEnabled(context) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordChangedNotice by remember { mutableStateOf(false) }

    val checkedPackages = when (editingList) {
        LauncherMode.ALLOWLIST -> allowlist
        LauncherMode.BLOCKLIST -> blocklist
    }
    val visibleApps = remember(apps, query) { AppFilter.search(apps, query) }

    fun grantUnlockWindow() {
        unlockedUntil = System.currentTimeMillis() + SETTINGS_UNLOCK_MINUTES * 60_000L
        prefs.systemSettingsUnlockedUntil = unlockedUntil
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
                    Text("Protected settings", style = MaterialTheme.typography.headlineMedium)
                }
            }

            // ------------------------------------------------------ mode
            item { SectionHeader("Home screen mode") }
            item {
                OptionGroup(
                    options = listOf(
                        Triple(LauncherMode.ALLOWLIST, "Allowlist", "Show only the apps you tick."),
                        Triple(LauncherMode.BLOCKLIST, "Blocklist", "Show every app except the ones you tick."),
                    ),
                    selected = mode,
                    onSelect = {
                        mode = it
                        prefs.mode = it
                        editingList = it
                    },
                )
            }

            // ------------------------------------------------ enforcement
            item { SettingsDivider() }
            item {
                SectionHeader(
                    title = "Enforcement",
                    subtitle = "Hiding an app only removes the prompt to open it. The three switches " +
                        "below close the other routes, and all of them need the accessibility service.",
                )
            }
            item {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    InfoText(
                        text = if (serviceOn) "Accessibility service: on" else "Accessibility service: off",
                        highlighted = serviceOn,
                    )
                    if (!serviceOn) {
                        Text(
                            text = "Turn it on under Accessibility → Distraction Killer Launcher. " +
                                "On Android 13 and newer a sideloaded app is greyed out there until " +
                                "you open its App info page, tap the ⋮ menu, and choose " +
                                "\"Allow restricted settings\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            OutlinedButton(onClick = {
                                grantUnlockWindow()
                                AccessibilityStatus.openAppInfo(context)
                            }) { Text("App info") }
                            Button(onClick = {
                                grantUnlockWindow()
                                AccessibilityStatus.openAccessibilitySettings(context)
                            }) { Text("Accessibility settings") }
                        }
                    }
                }
            }
            item {
                ToggleRow(
                    title = "Send hidden apps home",
                    description = "A hidden app opened from a notification, search, the share sheet or " +
                        "Android Settings is bounced straight back here. Settings, the dialler, " +
                        "alarms and keyboards are never touched.",
                    checked = enforceApps,
                    onCheckedChange = { enforceApps = it; prefs.enforceAppsSystemWide = it },
                )
            }
            item {
                ToggleRow(
                    title = "Lock Android Settings",
                    description = "Backs out of the Settings screens that could switch the home app, " +
                        "uninstall this launcher or turn its accessibility service off, unless " +
                        "you first allow changes below.",
                    checked = lockSettings,
                    onCheckedChange = { lockSettings = it; prefs.lockSystemSettings = it },
                )
            }
            item {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    val now = System.currentTimeMillis()
                    if (unlockedUntil > now) {
                        val until = DateTimeFormatter.ofPattern("HH:mm")
                            .format(Instant.ofEpochMilli(unlockedUntil).atZone(ZoneId.systemDefault()))
                        InfoText("Android Settings changes allowed until $until.", highlighted = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::grantUnlockWindow) {
                            Text("Allow changes for $SETTINGS_UNLOCK_MINUTES min")
                        }
                        OutlinedButton(onClick = {
                            grantUnlockWindow()
                            AccessibilityStatus.openHomeAppSettings(context)
                        }) { Text("Switch home app") }
                    }
                }
            }
            item {
                SectionHeader(
                    title = "Locked Settings screens",
                    subtitle = "Any Settings screen whose title matches one of these, or that mentions " +
                        "this app by name, is treated as locked. Add your language's titles if " +
                        "your phone is not in English.",
                )
            }
            item {
                StringSetEditor(
                    values = keywords,
                    placeholder = "Screen title, e.g. Home app",
                    normalize = { it.trim().takeIf { t -> t.isNotEmpty() } },
                    onAdd = { keywords = keywords + it; prefs.settingsLockKeywords = keywords },
                    onRemove = { keywords = keywords - it; prefs.settingsLockKeywords = keywords },
                    keyboardType = KeyboardType.Text,
                )
            }

            // -------------------------------------------------- websites
            item { SettingsDivider() }
            item {
                SectionHeader(
                    title = "Websites",
                    subtitle = "Checked against the browser's address bar once a page loads. Works in " +
                        "Chrome, Brave, Firefox, Edge, Samsung Internet and most others. Needs the " +
                        "accessibility service.",
                )
            }
            item {
                ToggleRow(
                    title = "Website filtering",
                    description = null,
                    checked = siteBlocking,
                    onCheckedChange = { siteBlocking = it; prefs.siteBlockingEnabled = it },
                )
            }
            item {
                OptionGroup(
                    options = listOf(
                        Triple(WebsiteMode.BLOCKLIST, "Blocklist", "Listed sites are blocked; everything else opens."),
                        Triple(
                            WebsiteMode.ALLOWLIST,
                            "Allowlist",
                            "Only listed sites open. Everything else is sent back, so add your essentials first.",
                        ),
                    ),
                    selected = websiteMode,
                    onSelect = { websiteMode = it; prefs.websiteMode = it },
                )
            }
            item {
                SectionHeader(
                    title = "Presets",
                    subtitle = when (websiteMode) {
                        WebsiteMode.BLOCKLIST -> "Ready-made lists of sites to block. Switch on any you want."
                        WebsiteMode.ALLOWLIST -> "Ready-made lists of sites to allow. Switch on any you want."
                    },
                )
            }
            val modePresets = catalog.filter { it.appliesTo == websiteMode }
            if (modePresets.isEmpty()) {
                item { InfoText("No presets are bundled for this mode.") }
            }
            items(items = modePresets, key = { "preset-" + it.id }) { preset ->
                val count = presetCounts[preset.id]
                ToggleRow(
                    title = if (count == null) preset.name else "${preset.name} ($count)",
                    description = preset.description,
                    checked = preset.id in enabledPresets,
                    onCheckedChange = { on ->
                        enabledPresets = if (on) enabledPresets + preset.id else enabledPresets - preset.id
                        prefs.enabledWebsitePresets = enabledPresets
                    },
                )
            }
            val thirdParty = catalog.filter { it.source.startsWith("http") }
            if (thirdParty.isNotEmpty()) {
                item {
                    Text(
                        text = thirdParty.joinToString(", ") { it.name } +
                            " come from StevenBlack/hosts (" +
                            thirdParty.map { it.license }.distinct().joinToString("/") +
                            "). Everything else is hand-curated. See THIRD_PARTY_NOTICES.md in the source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
            item {
                SectionHeader(
                    title = when (websiteMode) {
                        WebsiteMode.BLOCKLIST -> "Your own blocked sites"
                        WebsiteMode.ALLOWLIST -> "Your own allowed sites"
                    },
                    subtitle = "\"reddit.com\" also covers www., old. and every other subdomain.",
                )
            }
            item {
                StringSetEditor(
                    values = customSites,
                    placeholder = "example.com",
                    normalize = UrlMatcher::normalizePattern,
                    onAdd = {
                        customSites = customSites + it
                        prefs.setWebsiteListFor(websiteMode, customSites)
                    },
                    onRemove = {
                        customSites = customSites - it
                        prefs.setWebsiteListFor(websiteMode, customSites)
                    },
                )
            }

            // -------------------------------------------------- password
            item { SettingsDivider() }
            item { SectionHeader("Security") }
            item {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = { showPasswordDialog = true }) { Text("Change password") }
                    if (passwordChangedNotice) InfoText("Password updated.", highlighted = true)
                }
            }

            // ------------------------------------------------- app lists
            item { SettingsDivider() }
            item {
                SectionHeader(
                    title = "Apps",
                    subtitle = "Both lists are kept, so you can edit either one without losing the other.",
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
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; error = null },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = validateNewPassword(password, confirmation)
                if (problem == null) onConfirm(password) else error = problem
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
private fun ListChoice(
    label: String,
    count: Int,
    active: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = "$label ($count)" + if (active) " · in use" else ""
    if (selected) {
        Button(onClick = onSelect, modifier = modifier) { Text(text, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onSelect, modifier = modifier) { Text(text, maxLines = 1) }
    }
}
