package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherModeTest {

    @Test
    fun `stored values round trip`() {
        LauncherMode.entries.forEach { mode ->
            assertEquals(mode, LauncherMode.fromStoredValue(mode.name))
        }
    }

    @Test
    fun `missing or unknown values fall back to allowlist`() {
        assertEquals(LauncherMode.ALLOWLIST, LauncherMode.fromStoredValue(null))
        assertEquals(LauncherMode.ALLOWLIST, LauncherMode.fromStoredValue(""))
        assertEquals(LauncherMode.ALLOWLIST, LauncherMode.fromStoredValue("SOMETHING_ELSE"))
        assertEquals(LauncherMode.ALLOWLIST, LauncherMode.fromStoredValue("allowlist"))
    }
}
