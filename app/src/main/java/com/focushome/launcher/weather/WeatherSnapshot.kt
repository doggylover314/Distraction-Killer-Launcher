package com.focushome.launcher.weather

import kotlin.math.roundToInt

/** One reading, as shown in the home-screen header. */
data class WeatherSnapshot(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val fetchedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long, maxAgeMillis: Long): Boolean =
        nowMillis - fetchedAtMillis in 0 until maxAgeMillis

    fun temperatureRounded(useFahrenheit: Boolean): Int =
        if (useFahrenheit) {
            (temperatureCelsius * 9.0 / 5.0 + 32.0).roundToInt()
        } else {
            temperatureCelsius.roundToInt()
        }

    fun unitSuffix(useFahrenheit: Boolean): String = if (useFahrenheit) "°F" else "°C"
}
