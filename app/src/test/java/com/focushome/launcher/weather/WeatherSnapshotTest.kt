package com.focushome.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSnapshotTest {

    private fun snapshot(celsius: Double, fetchedAt: Long = 0L) =
        WeatherSnapshot(celsius, weatherCode = 0, isDay = true, fetchedAtMillis = fetchedAt)

    @Test
    fun `celsius rounds to the nearest degree`() {
        assertEquals(19, snapshot(19.4).temperatureRounded(useFahrenheit = false))
        assertEquals(20, snapshot(19.5).temperatureRounded(useFahrenheit = false))
        assertEquals(-5, snapshot(-5.2).temperatureRounded(useFahrenheit = false))
    }

    @Test
    fun `fahrenheit conversion matches the known reference points`() {
        assertEquals(32, snapshot(0.0).temperatureRounded(useFahrenheit = true))
        assertEquals(212, snapshot(100.0).temperatureRounded(useFahrenheit = true))
        assertEquals(-40, snapshot(-40.0).temperatureRounded(useFahrenheit = true))
        assertEquals(72, snapshot(22.2).temperatureRounded(useFahrenheit = true))
    }

    @Test
    fun `unit suffix follows the setting`() {
        assertEquals("°C", snapshot(1.0).unitSuffix(useFahrenheit = false))
        assertEquals("°F", snapshot(1.0).unitSuffix(useFahrenheit = true))
    }

    @Test
    fun `freshness window`() {
        val maxAge = 30 * 60 * 1000L
        val reading = snapshot(10.0, fetchedAt = 1_000_000L)
        assertTrue(reading.isFresh(nowMillis = 1_000_000L, maxAgeMillis = maxAge))
        assertTrue(reading.isFresh(nowMillis = 1_000_000L + maxAge - 1, maxAgeMillis = maxAge))
        assertFalse(reading.isFresh(nowMillis = 1_000_000L + maxAge, maxAgeMillis = maxAge))
    }

    @Test
    fun `a reading from the future is treated as stale, not fresh forever`() {
        // Guards against a clock change leaving a cached value pinned in place.
        val reading = snapshot(10.0, fetchedAt = 5_000L)
        assertFalse(reading.isFresh(nowMillis = 1_000L, maxAgeMillis = 30 * 60 * 1000L))
    }
}
