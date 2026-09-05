package com.focushome.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.focushome.launcher.weather.WeatherSnapshot

/**
 * Every persisted setting lives here, in one private SharedPreferences file.
 * Nothing leaves the device.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- mode

    var mode: LauncherMode
        get() = LauncherMode.fromStoredValue(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    // ------------------------------------------------------------ app lists

    var allowlist: Set<String>
        get() = readSet(KEY_ALLOWLIST)
        set(value) = writeSet(KEY_ALLOWLIST, value)

    var blocklist: Set<String>
        get() = readSet(KEY_BLOCKLIST)
        set(value) = writeSet(KEY_BLOCKLIST, value)

    fun listFor(mode: LauncherMode): Set<String> = when (mode) {
        LauncherMode.ALLOWLIST -> allowlist
        LauncherMode.BLOCKLIST -> blocklist
    }

    fun setListFor(mode: LauncherMode, value: Set<String>) {
        when (mode) {
            LauncherMode.ALLOWLIST -> allowlist = value
            LauncherMode.BLOCKLIST -> blocklist = value
        }
    }

    /** Ticking a checkbox in Settings writes straight through to disk. */
    fun setPackageChecked(mode: LauncherMode, packageName: String, checked: Boolean) {
        val current = listFor(mode)
        val updated = if (checked) current + packageName else current - packageName
        if (updated != current) setListFor(mode, updated)
    }

    var hasSeededDefaults: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEY_SEEDED, value) }

    // ------------------------------------------------------------- password

    val isPasswordSet: Boolean
        get() = prefs.contains(KEY_PASSWORD_HASH) && prefs.contains(KEY_PASSWORD_SALT)

    fun setPassword(password: String) {
        val salt = PasswordHasher.newSalt()
        prefs.edit {
            putString(KEY_PASSWORD_SALT, salt)
            putString(KEY_PASSWORD_HASH, PasswordHasher.hash(password, salt))
        }
    }

    fun verifyPassword(candidate: String): Boolean {
        val salt = prefs.getString(KEY_PASSWORD_SALT, null) ?: return false
        val hash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return PasswordHasher.verify(candidate, salt, hash)
    }

    // -------------------------------------------------------------- weather

    var weatherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_WEATHER_ENABLED, value) }

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_FAHRENHEIT, false)
        set(value) = prefs.edit { putBoolean(KEY_FAHRENHEIT, value) }

    /**
     * Whether the location prompt has already been shown once.
     *
     * Persisted rather than kept in rememberSaveable: HomeActivity declares
     * stateNotNeeded (normal for a launcher), so saved instance state is not
     * dependable, and a rotation would otherwise re-prompt after a refusal.
     */
    var hasRequestedLocationPermission: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCATION_ASKED, value) }

    /**
     * Last successful reading, so a rotation or a quick trip to another app
     * redraws the header instantly instead of hitting the network again.
     */
    var cachedWeather: WeatherSnapshot?
        get() {
            if (!prefs.contains(KEY_WEATHER_TEMP)) return null
            return WeatherSnapshot(
                temperatureCelsius = prefs.getFloat(KEY_WEATHER_TEMP, 0f).toDouble(),
                weatherCode = prefs.getInt(KEY_WEATHER_CODE, -1),
                isDay = prefs.getBoolean(KEY_WEATHER_IS_DAY, true),
                fetchedAtMillis = prefs.getLong(KEY_WEATHER_FETCHED_AT, 0L),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_WEATHER_TEMP)
                remove(KEY_WEATHER_CODE)
                remove(KEY_WEATHER_IS_DAY)
                remove(KEY_WEATHER_FETCHED_AT)
            } else {
                putFloat(KEY_WEATHER_TEMP, value.temperatureCelsius.toFloat())
                putInt(KEY_WEATHER_CODE, value.weatherCode)
                putBoolean(KEY_WEATHER_IS_DAY, value.isDay)
                putLong(KEY_WEATHER_FETCHED_AT, value.fetchedAtMillis)
            }
        }

    // --------------------------------------------------------------- private

    /**
     * SharedPreferences hands back the *live* set instance and explicitly
     * documents that mutating it is undefined behaviour, so every read returns
     * a defensive copy.
     */
    private fun readSet(key: String): Set<String> =
        prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private fun writeSet(key: String, value: Set<String>) {
        prefs.edit { putStringSet(key, LinkedHashSet(value)) }
    }

    private companion object {
        const val FILE_NAME = "focus_home_prefs"
        const val KEY_MODE = "mode"
        const val KEY_ALLOWLIST = "allowlist"
        const val KEY_BLOCKLIST = "blocklist"
        const val KEY_SEEDED = "seeded_defaults"
        const val KEY_PASSWORD_HASH = "password_hash"
        const val KEY_PASSWORD_SALT = "password_salt"
        const val KEY_WEATHER_ENABLED = "weather_enabled"
        const val KEY_FAHRENHEIT = "weather_fahrenheit"
        const val KEY_LOCATION_ASKED = "location_permission_asked"
        const val KEY_WEATHER_TEMP = "weather_temp_c"
        const val KEY_WEATHER_CODE = "weather_code"
        const val KEY_WEATHER_IS_DAY = "weather_is_day"
        const val KEY_WEATHER_FETCHED_AT = "weather_fetched_at"
    }
}
