package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLockDetectorTest {

    private val label = "Distraction Killer Launcher"
    private val keywords = SettingsLockDetector.DEFAULT_KEYWORDS

    private fun locked(pkg: String, vararg texts: String?) =
        SettingsLockDetector.isLockedScreen(pkg, texts.toList(), keywords, label)

    @Test
    fun `the home app picker is locked`() {
        assertTrue(locked("com.android.settings", "Default apps", "Home app", "Pixel Launcher", label))
        assertTrue(locked("com.android.settings", "Home app"))
        assertTrue(locked("com.samsung.android.settings", "Choose default apps"))
    }

    @Test
    fun `our own app info page is locked because it names us`() {
        assertTrue(locked("com.android.settings", "App info", label, "Uninstall", "Force stop"))
        assertTrue(locked("com.android.settings", "Use $label?"))
    }

    @Test
    fun `the system chooser counts as settings`() {
        assertTrue(locked("android", "Select a Home app"))
        assertTrue(locked("android", "Launcher", "Just once", "Always"))
        // Even with an unknown title the chooser lists us by name.
        assertTrue(locked("android", "Pick an app", "Pixel Launcher", label))
    }

    @Test
    fun `unrelated settings screens are not locked`() {
        assertFalse(locked("com.android.settings", "Wi-Fi", "Bluetooth", "Battery"))
        assertFalse(locked("com.android.settings", "Apps", "See all 143 apps"))
    }

    @Test
    fun `keyword matching is whole-title, so ordinary words do not trigger`() {
        // "Launcher" as a title is locked; a sentence containing it is not.
        assertFalse(locked("com.android.settings", "Open the launcher to continue"))
        assertTrue(locked("com.android.settings", "launcher"))
    }

    @Test
    fun `non settings apps are never locked whatever they show`() {
        assertFalse(locked("com.android.chrome", "Home app", label))
        assertFalse(locked("com.whatsapp", "Default apps"))
    }

    @Test
    fun `empty or null texts do not lock`() {
        assertFalse(locked("com.android.settings"))
        assertFalse(locked("com.android.settings", null, "", "  "))
    }

    @Test
    fun `unlock window is a forward-only interval in elapsed time`() {
        assertTrue(SettingsLockDetector.isUnlocked(grantedAtElapsed = 1_000, untilElapsed = 2_000, nowElapsed = 1_500))
        assertFalse(SettingsLockDetector.isUnlocked(grantedAtElapsed = 1_000, untilElapsed = 2_000, nowElapsed = 2_000))
        assertFalse(SettingsLockDetector.isUnlocked(grantedAtElapsed = 0, untilElapsed = 0, nowElapsed = 1_000))
        // A reboot restarts elapsed time near zero: "now" before the grant means the window is over.
        assertFalse(SettingsLockDetector.isUnlocked(grantedAtElapsed = 900_000, untilElapsed = 1_500_000, nowElapsed = 5_000))
    }

    @Test
    fun `the role manager pickers and installers are lockable`() {
        assertTrue(locked("com.google.android.permissioncontroller", "Default apps", "Home app"))
        assertTrue(locked("com.google.android.permissioncontroller", "App info", label, "Uninstall"))
        assertTrue(locked("com.google.android.packageinstaller", "Do you want to uninstall this app?", label))
        assertTrue(locked("com.android.intentresolver", "Select a Home app"))
        assertFalse(locked("com.google.android.packageinstaller", "Do you want to install this application?", "Other App"))
    }

    @Test
    fun `a launcher asking for the home role is caught by phrase`() {
        val texts = listOf("Set Nova Launcher as your default home app?", "Cancel", "Set as default")
        assertTrue(SettingsLockDetector.isHomeRoleDialog("com.google.android.permissioncontroller", texts))
        assertTrue(SettingsLockDetector.isHomeRoleDialog("android", listOf("Use Lawnchair as Home app")))
        assertFalse(SettingsLockDetector.isHomeRoleDialog("com.google.android.permissioncontroller",
            listOf("Allow Maps to access this device's location?")))
        assertFalse(SettingsLockDetector.isHomeRoleDialog("com.android.chrome", texts))
    }

    @Test
    fun `the guest user page is locked but the System page that lists it is not`() {
        assertTrue(locked("com.android.settings", "Multiple users", "You", "Add user", "Add guest"))
        assertTrue(locked("com.android.settings", "Users", "Switch to Guest"))
        // Pixel's System page has a "Multiple users" row next to updates, languages, date & time.
        assertFalse(locked("com.android.settings", "System", "Languages", "Multiple users", "Date & time"))
        assertFalse(locked("com.android.settings", "Users & accounts settings for work"))
    }

    @Test
    fun `permission and store pages that merely list the app are not locked`() {
        // Permission manager > Location lists every app that holds the permission.
        assertFalse(locked("com.google.android.permissioncontroller", "Location", "Maps", label, "Weather"))
        assertFalse(locked("com.android.vending", "Manage apps & device", label, "Update"))
        // With an action word on screen it is a page that can act on the app.
        assertTrue(locked("com.google.android.permissioncontroller", "App info", label, "Uninstall"))
        assertTrue(locked("com.android.vending", label, "Uninstall"))
        // The Settings app keeps the bare-label rule: its App info page and the
        // accessibility toggle both name the app.
        assertTrue(locked("com.android.settings", "Accessibility", label))
    }

    @Test
    fun `the home chooser is recognised only when it lists us`() {
        assertTrue(SettingsLockDetector.isHomeChooser("android", listOf("Select a Home app", "Pixel Launcher", label, "Just once", "Always"), label))
        assertTrue(SettingsLockDetector.isHomeChooser("com.android.intentresolver", listOf("Use as your home app", label), label))
        assertFalse(SettingsLockDetector.isHomeChooser("android", listOf("Select a Home app", "Pixel Launcher", "Nova"), label))
        assertFalse(SettingsLockDetector.isHomeChooser("android", listOf("Share with", label), label))
        assertFalse(SettingsLockDetector.isHomeChooser("com.android.settings", listOf("Home app", label), label))
    }
}
