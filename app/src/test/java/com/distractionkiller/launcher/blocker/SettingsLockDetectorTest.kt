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
    fun `unlock window`() {
        assertTrue(SettingsLockDetector.isUnlocked(unlockedUntilMillis = 2_000, nowMillis = 1_000))
        assertFalse(SettingsLockDetector.isUnlocked(unlockedUntilMillis = 1_000, nowMillis = 1_000))
        assertFalse(SettingsLockDetector.isUnlocked(unlockedUntilMillis = 0, nowMillis = 1_000))
    }
}
