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
        assertTrue(locked("com.google.android.permissioncontroller", "App info", label))
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
    fun `the guest user gateway is locked`() {
        assertTrue(locked("com.android.settings", "Multiple users"))
        assertTrue(locked("com.android.settings", "System", "Users"))
        assertFalse(locked("com.android.settings", "Users & accounts settings for work"))
    }
}
