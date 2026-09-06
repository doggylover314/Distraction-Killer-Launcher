package com.distractionkiller.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `known codes have descriptions`() {
        assertEquals("Clear", WeatherCodes.describe(0))
        assertEquals("Partly cloudy", WeatherCodes.describe(2))
        assertEquals("Fog", WeatherCodes.describe(45))
        assertEquals("Thunderstorm", WeatherCodes.describe(95))
    }

    @Test
    fun `unknown codes fall back to a dash instead of throwing`() {
        assertEquals("—", WeatherCodes.describe(-1))
        assertEquals("—", WeatherCodes.describe(4))
        assertEquals("—", WeatherCodes.describe(9999))
    }

    @Test
    fun `every described code produces non blank text`() {
        (0..100).forEach { code ->
            assertFalse(WeatherCodes.describe(code).isBlank())
        }
    }
}
