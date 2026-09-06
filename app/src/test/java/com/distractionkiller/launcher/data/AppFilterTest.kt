package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterTest {

    private val phone = LaunchableApp("com.example.phone", "Phone")
    private val camera = LaunchableApp("com.example.camera", "Camera")
    private val games = LaunchableApp("com.example.games", "Time Sink")
    private val self = LaunchableApp(SELF, "Distraction Killer Launcher")
    private val installed = listOf(phone, camera, games, self)

    @Test
    fun `allowlist mode shows only ticked apps`() {
        val visible = AppFilter.visibleApps(
            installed = installed,
            mode = LauncherMode.ALLOWLIST,
            allowlist = setOf(phone.packageName, camera.packageName),
            blocklist = setOf(camera.packageName),
            selfPackage = SELF,
        )
        assertEquals(listOf(camera, phone), visible)
    }

    @Test
    fun `blocklist mode shows everything except ticked apps`() {
        val visible = AppFilter.visibleApps(
            installed = installed,
            mode = LauncherMode.BLOCKLIST,
            allowlist = setOf(phone.packageName),
            blocklist = setOf(games.packageName),
            selfPackage = SELF,
        )
        assertEquals(listOf(camera, phone), visible)
    }

    @Test
    fun `the launcher never lists itself in either mode`() {
        val allow = AppFilter.visibleApps(
            installed = installed,
            mode = LauncherMode.ALLOWLIST,
            allowlist = setOf(SELF, phone.packageName),
            blocklist = emptySet(),
            selfPackage = SELF,
        )
        val block = AppFilter.visibleApps(
            installed = installed,
            mode = LauncherMode.BLOCKLIST,
            allowlist = emptySet(),
            blocklist = emptySet(),
            selfPackage = SELF,
        )
        assertTrue(allow.none { it.packageName == SELF })
        assertTrue(block.none { it.packageName == SELF })
    }

    @Test
    fun `empty allowlist hides everything`() {
        val visible = AppFilter.visibleApps(
            installed = installed,
            mode = LauncherMode.ALLOWLIST,
            allowlist = emptySet(),
            blocklist = emptySet(),
            selfPackage = SELF,
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `an allowlisted package that is not installed is simply absent`() {
        val visible = AppFilter.visibleApps(
            installed = listOf(phone),
            mode = LauncherMode.ALLOWLIST,
            allowlist = setOf(phone.packageName, "com.example.uninstalled"),
            blocklist = emptySet(),
            selfPackage = SELF,
        )
        assertEquals(listOf(phone), visible)
    }

    @Test
    fun `sorting ignores case and is stable on ties`() {
        val apps = listOf(
            LaunchableApp("com.b", "banana"),
            LaunchableApp("com.a", "Apple"),
            LaunchableApp("com.z", "apple"),
        )
        val visible = AppFilter.visibleApps(
            installed = apps,
            mode = LauncherMode.BLOCKLIST,
            allowlist = emptySet(),
            blocklist = emptySet(),
            selfPackage = SELF,
        )
        assertEquals(listOf("com.a", "com.z", "com.b"), visible.map { it.packageName })
    }

    @Test
    fun `search matches label and package, case insensitively`() {
        val apps = listOf(phone, camera, games)
        assertEquals(listOf(phone), AppFilter.search(apps, "pho"))
        assertEquals(listOf(phone), AppFilter.search(apps, "PHONE"))
        assertEquals(listOf(games), AppFilter.search(apps, "com.example.games"))
        assertEquals(apps, AppFilter.search(apps, "   "))
        assertTrue(AppFilter.search(apps, "nothing here").isEmpty())
    }

    private companion object {
        const val SELF = "com.distractionkiller.launcher"
    }
}
