package com.distractionkiller.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.distractionkiller.launcher.blocker.SettingsLockDetector
import com.distractionkiller.launcher.blocker.WebsiteMode
import com.distractionkiller.launcher.weather.WeatherSnapshot

/**
 * Every persisted setting lives here, in one private SharedPreferences file.
 * Nothing leaves the device.
 *
 * Two tiers, mirrored by the two settings screens:
 *  - Appearance: look only, no password.
 *  - Protected: anything that changes what can be reached, password required.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ======================================================== protected tier

    var mode: LauncherMode
        get() = LauncherMode.fromStoredValue(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

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

    /** Whether the website list blocks what is on it or allows only what is on it. */
    var websiteMode: WebsiteMode
        get() = WebsiteMode.fromStoredValue(prefs.getString(KEY_WEBSITE_MODE, null))
        set(value) = prefs.edit { putString(KEY_WEBSITE_MODE, value.name) }

    /** The user's own blocked domains. Stored normalised (see UrlMatcher.normalizePattern). */
    var websiteBlocklist: Set<String>
        get() = readSet(KEY_WEBSITE_BLOCKLIST)
        set(value) = writeSet(KEY_WEBSITE_BLOCKLIST, value)

    /** The user's own allowed domains, for allowlist mode. */
    var websiteAllowlist: Set<String>
        get() = readSet(KEY_WEBSITE_ALLOWLIST)
        set(value) = writeSet(KEY_WEBSITE_ALLOWLIST, value)

    fun websiteListFor(mode: WebsiteMode): Set<String> = when (mode) {
        WebsiteMode.BLOCKLIST -> websiteBlocklist
        WebsiteMode.ALLOWLIST -> websiteAllowlist
    }

    fun setWebsiteListFor(mode: WebsiteMode, value: Set<String>) {
        when (mode) {
            WebsiteMode.BLOCKLIST -> websiteBlocklist = value
            WebsiteMode.ALLOWLIST -> websiteAllowlist = value
        }
    }

    /**
     * Ids of the bundled presets that are switched on (both kinds share one
     * set). The proxies preset starts on: without it, "reddit.com" is one
     * Google Translate link away from being readable.
     */
    var enabledWebsitePresets: Set<String>
        get() = prefs.getStringSet(KEY_WEBSITE_PRESETS, null)?.toSet() ?: DEFAULT_PRESETS
        set(value) = writeSet(KEY_WEBSITE_PRESETS, value)

    var siteBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SITE_BLOCKING, true)
        set(value) = prefs.edit { putBoolean(KEY_SITE_BLOCKING, value) }

    /** Send hidden apps home when they reach the foreground by any other route. */
    var enforceAppsSystemWide: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE_APPS, true)
        set(value) = prefs.edit { putBoolean(KEY_ENFORCE_APPS, value) }

    /** Block the Settings screens that could switch launcher or remove this app. */
    var lockSystemSettings: Boolean
        get() = prefs.getBoolean(KEY_LOCK_SETTINGS, true)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_SETTINGS, value) }

    var settingsLockKeywords: Set<String>
        get() = prefs.getStringSet(KEY_LOCK_KEYWORDS, null)?.toSet()
            ?: SettingsLockDetector.DEFAULT_KEYWORDS
        set(value) = writeSet(KEY_LOCK_KEYWORDS, value)

    /**
     * Window during which the locked Settings screens may be used, as a pair of
     * SystemClock.elapsedRealtime() values. Elapsed time rather than wall-clock
     * so changing the date cannot stretch it; see SettingsLockDetector.isUnlocked.
     */
    var settingsUnlockGrantedAtElapsed: Long
        get() = prefs.getLong(KEY_SETTINGS_UNLOCK_GRANTED, 0L)
        set(value) = prefs.edit { putLong(KEY_SETTINGS_UNLOCK_GRANTED, value) }

    var settingsUnlockUntilElapsed: Long
        get() = prefs.getLong(KEY_SETTINGS_UNLOCKED_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_SETTINGS_UNLOCKED_UNTIL, value) }

    fun grantSettingsUnlock(nowElapsed: Long, durationMillis: Long) {
        prefs.edit {
            putLong(KEY_SETTINGS_UNLOCK_GRANTED, nowElapsed)
            putLong(KEY_SETTINGS_UNLOCKED_UNTIL, nowElapsed + durationMillis)
        }
    }

    fun isSettingsUnlocked(nowElapsed: Long): Boolean =
        SettingsLockDetector.isUnlocked(settingsUnlockGrantedAtElapsed, settingsUnlockUntilElapsed, nowElapsed)

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

    // ======================================================= appearance tier

    var themeMode: ThemeMode
        get() = enumFromStored(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM)
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    var textSize: TextSize
        get() = enumFromStored(prefs.getString(KEY_TEXT_SIZE, null), TextSize.MEDIUM)
        set(value) = prefs.edit { putString(KEY_TEXT_SIZE, value.name) }

    var clockFormat: ClockFormat
        get() = enumFromStored(prefs.getString(KEY_CLOCK_FORMAT, null), ClockFormat.SYSTEM)
        set(value) = prefs.edit { putString(KEY_CLOCK_FORMAT, value.name) }

    var centerAlign: Boolean
        get() = prefs.getBoolean(KEY_CENTER, false)
        set(value) = prefs.edit { putBoolean(KEY_CENTER, value) }

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CLOCK, value) }

    var showDate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DATE, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_DATE, value) }

    var showBattery: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BATTERY, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_BATTERY, value) }

    var weatherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_WEATHER_ENABLED, value) }

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_FAHRENHEIT, false)
        set(value) = prefs.edit { putBoolean(KEY_FAHRENHEIT, value) }

    var showPackageNames: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PACKAGES, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_PACKAGES, value) }

    var showSearchBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SEARCH, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SEARCH, value) }

    var showLaunchCounts: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COUNTS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_COUNTS, value) }

    var focusNoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTE_ENABLED, value) }

    var focusNoteText: String
        get() = prefs.getString(KEY_NOTE_TEXT, null) ?: DEFAULT_NOTE
        set(value) = prefs.edit { putString(KEY_NOTE_TEXT, value) }

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

    // --------------------------------------------------------- launch counts

    var launchCounter: LaunchCounter
        get() = LaunchCounter.decode(
            prefs.getString(KEY_COUNTS_DATE, null),
            prefs.getStringSet(KEY_COUNTS, null),
        )
        set(value) = prefs.edit {
            putString(KEY_COUNTS_DATE, value.dateKey)
            putStringSet(KEY_COUNTS, LinkedHashSet(value.encode()))
        }

    // -------------------------------------------------------------- weather

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

    // ---------------------------------------------------------- listeners

    /**
     * The accessibility service keeps its own cached copy of the lists and
     * needs to know when Settings changes them. The listener must be held
     * strongly by the caller; SharedPreferences only keeps a weak reference.
     */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

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

    companion object {
        const val DEFAULT_NOTE = "Do the thing you opened the phone for."
        val DEFAULT_PRESETS: Set<String> = setOf("proxies")

        /** Keys that change which websites are listed; the service reloads presets on these. */
        val WEBSITE_KEYS: Set<String>
            get() = setOf(KEY_WEBSITE_MODE, KEY_WEBSITE_PRESETS, KEY_SITE_BLOCKING)

        /** Keys the enforcement service cares about; anything else it ignores. */
        val ENFORCEMENT_KEYS: Set<String>
            get() = setOf(
                KEY_MODE, KEY_ALLOWLIST, KEY_BLOCKLIST,
                KEY_WEBSITE_MODE, KEY_WEBSITE_BLOCKLIST, KEY_WEBSITE_ALLOWLIST, KEY_WEBSITE_PRESETS,
                KEY_SITE_BLOCKING, KEY_ENFORCE_APPS, KEY_LOCK_SETTINGS,
                KEY_LOCK_KEYWORDS, KEY_SETTINGS_UNLOCKED_UNTIL, KEY_SETTINGS_UNLOCK_GRANTED,
            )

        private const val FILE_NAME = "distraction_killer_prefs"
        private const val KEY_MODE = "mode"
        private const val KEY_ALLOWLIST = "allowlist"
        private const val KEY_BLOCKLIST = "blocklist"
        private const val KEY_SEEDED = "seeded_defaults"
        private const val KEY_WEBSITE_MODE = "website_mode"
        private const val KEY_WEBSITE_BLOCKLIST = "website_blocklist"
        private const val KEY_WEBSITE_ALLOWLIST = "website_allowlist"
        private const val KEY_WEBSITE_PRESETS = "website_presets"
        private const val KEY_SITE_BLOCKING = "site_blocking_enabled"
        private const val KEY_ENFORCE_APPS = "enforce_apps_system_wide"
        private const val KEY_LOCK_SETTINGS = "lock_system_settings"
        private const val KEY_LOCK_KEYWORDS = "settings_lock_keywords"
        private const val KEY_SETTINGS_UNLOCKED_UNTIL = "settings_unlocked_until_elapsed"
        private const val KEY_SETTINGS_UNLOCK_GRANTED = "settings_unlock_granted_elapsed"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_CLOCK_FORMAT = "clock_format"
        private const val KEY_CENTER = "center_align"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_DATE = "show_date"
        private const val KEY_SHOW_BATTERY = "show_battery"
        private const val KEY_WEATHER_ENABLED = "weather_enabled"
        private const val KEY_FAHRENHEIT = "weather_fahrenheit"
        private const val KEY_SHOW_PACKAGES = "show_package_names"
        private const val KEY_SHOW_SEARCH = "show_search_bar"
        private const val KEY_SHOW_COUNTS = "show_launch_counts"
        private const val KEY_NOTE_ENABLED = "focus_note_enabled"
        private const val KEY_NOTE_TEXT = "focus_note_text"
        private const val KEY_LOCATION_ASKED = "location_permission_asked"
        private const val KEY_COUNTS_DATE = "launch_counts_date"
        private const val KEY_COUNTS = "launch_counts"
        private const val KEY_WEATHER_TEMP = "weather_temp_c"
        private const val KEY_WEATHER_CODE = "weather_code"
        private const val KEY_WEATHER_IS_DAY = "weather_is_day"
        private const val KEY_WEATHER_FETCHED_AT = "weather_fetched_at"
    }
}
