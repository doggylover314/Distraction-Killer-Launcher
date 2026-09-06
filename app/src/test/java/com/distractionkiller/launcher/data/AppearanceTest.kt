package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {

    @Test
    fun `stored enum names round trip`() {
        assertEquals(ThemeMode.DARK, enumFromStored("DARK", ThemeMode.SYSTEM))
        assertEquals(TextSize.LARGE, enumFromStored("LARGE", TextSize.MEDIUM))
        assertEquals(ClockFormat.HOUR_24, enumFromStored("HOUR_24", ClockFormat.SYSTEM))
    }

    @Test
    fun `unknown or missing values fall back to the default`() {
        assertEquals(ThemeMode.SYSTEM, enumFromStored(null, ThemeMode.SYSTEM))
        assertEquals(TextSize.MEDIUM, enumFromStored("huge", TextSize.MEDIUM))
        assertEquals(ClockFormat.SYSTEM, enumFromStored("", ClockFormat.SYSTEM))
    }
}
