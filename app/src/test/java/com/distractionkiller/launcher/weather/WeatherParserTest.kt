package com.distractionkiller.launcher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParserTest {

    /** Copied verbatim from a live api.open-meteo.com response. */
    private val realResponse = """
        {"latitude":37.763283,"longitude":-122.41286,"generationtime_ms":0.03,
         "utc_offset_seconds":-25200,"timezone":"America/Los_Angeles",
         "timezone_abbreviation":"GMT-7","elevation":14.0,
         "current_units":{"time":"iso8601","interval":"seconds",
           "temperature_2m":"°C","weather_code":"wmo code","is_day":""},
         "current":{"time":"2026-09-05T10:30","interval":900,
           "temperature_2m":19.0,"weather_code":0,"is_day":1}}
    """.trimIndent()

    @Test
    fun `parses a real response`() {
        val snapshot = WeatherParser.parse(realResponse, nowMillis = 1_000L)!!
        assertEquals(19.0, snapshot.temperatureCelsius, 0.001)
        assertEquals(0, snapshot.weatherCode)
        assertTrue(snapshot.isDay)
        assertEquals(1_000L, snapshot.fetchedAtMillis)
    }

    @Test
    fun `is_day zero means night`() {
        val body = """{"current":{"temperature_2m":4.5,"weather_code":3,"is_day":0}}"""
        val snapshot = WeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(false, snapshot.isDay)
        assertEquals(4.5, snapshot.temperatureCelsius, 0.001)
    }

    @Test
    fun `negative temperatures survive`() {
        val body = """{"current":{"temperature_2m":-12.3,"weather_code":73,"is_day":1}}"""
        assertEquals(-12.3, WeatherParser.parse(body, 0L)!!.temperatureCelsius, 0.001)
    }

    @Test
    fun `missing weather_code degrades instead of throwing`() {
        val body = """{"current":{"temperature_2m":10.0,"is_day":1}}"""
        assertEquals(-1, WeatherParser.parse(body, 0L)!!.weatherCode)
    }

    @Test
    fun `garbage input returns null rather than crashing the header`() {
        assertNull(WeatherParser.parse("", 0L))
        assertNull(WeatherParser.parse("not json at all", 0L))
        assertNull(WeatherParser.parse("{}", 0L))
        assertNull(WeatherParser.parse("""{"current":{}}""", 0L))
        assertNull(WeatherParser.parse("""{"error":true,"reason":"bad request"}""", 0L))
    }
}
