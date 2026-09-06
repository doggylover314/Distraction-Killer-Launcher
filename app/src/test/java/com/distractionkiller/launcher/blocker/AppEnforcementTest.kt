package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEnforcementTest {

    private val launchable = setOf("com.game", "com.phone", "com.android.settings", "com.browser")
    private val visible = setOf("com.phone", "com.browser")
    private val exempt = setOf("com.android.settings", "com.ime")

    private fun decide(pkg: String?, enabled: Boolean = true) = AppEnforcement.shouldSendHome(
        packageName = pkg,
        enforcementEnabled = enabled,
        launchablePackages = launchable,
        visiblePackages = visible,
        exemptPackages = exempt,
    )

    @Test
    fun `a hidden launchable app is sent home`() {
        assertTrue(decide("com.game"))
    }

    @Test
    fun `visible apps are left alone`() {
        assertFalse(decide("com.phone"))
        assertFalse(decide("com.browser"))
    }

    @Test
    fun `exempt apps are left alone even when hidden`() {
        assertFalse(decide("com.android.settings"))
    }

    @Test
    fun `apps without a launcher entry are never touched`() {
        // A file picker, a permission dialog, a share sheet.
        assertFalse(decide("com.android.documentsui.helper"))
        assertFalse(decide("com.ime"))
    }

    @Test
    fun `the switch turns everything off`() {
        assertFalse(decide("com.game", enabled = false))
    }

    @Test
    fun `null or empty package names are ignored`() {
        assertFalse(decide(null))
        assertFalse(decide(""))
    }

    @Test
    fun `fixed exemptions cover the system UI and settings`() {
        assertTrue("com.android.systemui" in AppEnforcement.FIXED_EXEMPTIONS)
        assertTrue("com.android.settings" in AppEnforcement.FIXED_EXEMPTIONS)
        assertTrue("android" in AppEnforcement.FIXED_EXEMPTIONS)
    }
}
