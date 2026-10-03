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
    fun `search matches the label case-insensitively`() {
        val apps = listOf(phone, camera, games)
        assertEquals(listOf(phone), AppFilter.search(apps, "pho"))
        assertEquals(listOf(phone), AppFilter.search(apps, "PHONE"))
        assertEquals(listOf(games), AppFilter.search(apps, "sInK"))
    }

    @Test
    fun `search by package id works only when package matching is switched on`() {
        val apps = listOf(phone, camera, games)
        assertEquals(listOf(games), AppFilter.search(apps, "com.example.games", matchPackageNames = true))
    }

    @Test
    fun `search ignores package ids by default`() {
        val apps = listOf(phone, camera, games)
        assertTrue(AppFilter.search(apps, "com.example.games").isEmpty())
        // "example" is in every package id and in no label.
        assertTrue(AppFilter.search(apps, "example").isEmpty())
    }

    @Test
    fun `search ranks label prefixes before other label matches`() {
        val googleHome = LaunchableApp("com.google.home", "Google Home")
        val messages = LaunchableApp("com.example.messages", "Messages")
        val apps = listOf(googleHome, messages, phone).sortedWith(AppFilter.LABEL_ORDER)
        // Alphabetical order alone would put Google Home first.
        assertEquals(listOf(messages, googleHome), AppFilter.search(apps, "me"))
    }

    @Test
    fun `search keeps label order inside each rank`() {
        val zebra = LaunchableApp("com.z", "Zebra Mail")
        val mailbox = LaunchableApp("com.m", "Mailbox")
        val aMail = LaunchableApp("com.a", "A Mail")
        val webmail = LaunchableApp("com.w", "Webmail")
        val result = AppFilter.search(listOf(zebra, webmail, mailbox, aMail), "mail")
        assertEquals(listOf(mailbox, aMail, webmail, zebra), result)
    }

    @Test
    fun `search puts package-only matches last when package matching is on`() {
        val labelHit = LaunchableApp("com.zzz.other", "Zed Notes")
        val packageHit = LaunchableApp("com.notes.app", "Aardvark")
        val prefixHit = LaunchableApp("com.yyy.other", "Notes Plus")
        val result = AppFilter.search(
            listOf(packageHit, labelHit, prefixHit),
            "notes",
            matchPackageNames = true,
        )
        assertEquals(listOf(prefixHit, labelHit, packageHit), result)
    }

    @Test
    fun `search with a blank query returns the list unchanged`() {
        val apps = listOf(phone, camera, games)
        assertEquals(apps, AppFilter.search(apps, ""))
        assertEquals(apps, AppFilter.search(apps, "   "))
        assertEquals(apps, AppFilter.search(apps, "   ", matchPackageNames = true))
    }

    @Test
    fun `search with no match returns nothing`() {
        val apps = listOf(phone, camera, games)
        assertTrue(AppFilter.search(apps, "nothing here").isEmpty())
        assertTrue(AppFilter.search(apps, "nothing here", matchPackageNames = true).isEmpty())
    }

    private companion object {
        const val SELF = "com.distractionkiller.launcher"
    }
}
