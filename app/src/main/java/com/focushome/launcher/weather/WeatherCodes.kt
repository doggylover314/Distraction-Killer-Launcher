package com.focushome.launcher.weather

/**
 * WMO weather-interpretation codes to short English text.
 *
 * The mapping follows the table Open-Meteo publishes for its `weather_code`
 * field. I transcribed it from that documentation rather than an official WMO
 * publication, so treat the exact wording as approximate; the code numbers
 * themselves are the standard WMO 4677-derived set Open-Meteo returns. Worth a
 * glance at open-meteo.com/en/docs if a description ever looks wrong.
 */
object WeatherCodes {

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "—"
    }
}
