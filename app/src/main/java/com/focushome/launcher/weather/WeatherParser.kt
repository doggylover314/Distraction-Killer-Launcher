package com.focushome.launcher.weather

import org.json.JSONObject

/**
 * Parses the Open-Meteo current-conditions response.
 *
 * Shape confirmed against a live call to
 * api.open-meteo.com/v1/forecast?...&current=temperature_2m,weather_code,is_day
 * which answers with:
 *   {"current":{"time":"...","temperature_2m":19.0,"weather_code":0,"is_day":1}}
 *
 * Uses org.json, which is part of the Android platform, so no JSON library is
 * pulled into the app.
 */
object WeatherParser {

    fun parse(body: String, nowMillis: Long): WeatherSnapshot? = try {
        val current = JSONObject(body).optJSONObject("current")
        if (current == null || !current.has("temperature_2m")) {
            null
        } else {
            WeatherSnapshot(
                temperatureCelsius = current.getDouble("temperature_2m"),
                weatherCode = current.optInt("weather_code", -1),
                // is_day comes back as 1/0 rather than a JSON boolean.
                isDay = current.optInt("is_day", 1) == 1,
                fetchedAtMillis = nowMillis,
            )
        }
    } catch (e: org.json.JSONException) {
        null
    }
}
