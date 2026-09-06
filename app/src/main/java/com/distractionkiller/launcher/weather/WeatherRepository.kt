package com.distractionkiller.launcher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.distractionkiller.launcher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The only part of this app that touches the network, and only when the weather
 * line is switched on in Settings.
 *
 * Open-Meteo needs no API key, no account and no SDK, so this is a single
 * HttpURLConnection GET over HTTPS. Nothing is sent except a latitude and
 * longitude rounded to two decimal places (roughly a kilometre).
 */
class WeatherRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)

    /**
     * Cached reading if it is recent enough, otherwise a fresh fetch.
     * Returns null when weather is off, permission is missing, there is no
     * location fix yet, or the request fails. Callers just show nothing.
     */
    suspend fun currentWeather(forceRefresh: Boolean = false): WeatherSnapshot? {
        if (!prefs.weatherEnabled) return null

        val cached = prefs.cachedWeather
        if (!forceRefresh && cached != null && cached.isFresh(System.currentTimeMillis(), CACHE_MAX_AGE_MILLIS)) {
            return cached
        }
        if (!hasLocationPermission()) return cached

        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { currentLocation() } ?: return cached
        val fetched = withContext(Dispatchers.IO) { fetch(location.latitude, location.longitude) }
        if (fetched != null) prefs.cachedWeather = fetched
        return fetched ?: cached
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // --------------------------------------------------------------- network

    private fun fetch(latitude: Double, longitude: Double): WeatherSnapshot? {
        // Locale.US matters: in locales that use a decimal comma, the default
        // format would produce "47,5" and the request would be rejected.
        val url = String.format(
            Locale.US,
            "%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day&timezone=auto",
            ENDPOINT,
            latitude,
            longitude,
        )
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MILLIS
                readTimeout = HTTP_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            WeatherParser.parse(body, System.currentTimeMillis())
        } catch (e: IOException) {
            // Offline, DNS failure, timeout: not worth surfacing, the header
            // just keeps showing the last reading.
            null
        } finally {
            connection?.disconnect()
        }
    }

    // -------------------------------------------------------------- location

    /**
     * A coarse fix, cheaply. Tries the cached fixes the system already has
     * before asking for a new one, which on a phone that has been on for more
     * than a minute is almost always enough.
     */
    private suspend fun currentLocation(): Location? {
        val manager = ContextCompat.getSystemService(appContext, LocationManager::class.java)
            ?: return null

        withContext(Dispatchers.IO) { lastKnownLocation(manager) }?.let { return it }

        val provider = withContext(Dispatchers.IO) {
            PROVIDERS.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        } ?: return null

        return requestSingleFix(manager, provider)
    }

    private fun lastKnownLocation(manager: LocationManager): Location? = PROVIDERS
        .mapNotNull { provider ->
            // Wrapped: a provider can be missing on some devices, and the
            // permission check above can still race a revocation.
            runCatching {
                @Suppress("MissingPermission")
                manager.getLastKnownLocation(provider)
            }.getOrNull()
        }
        .filter { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MILLIS }
        .maxByOrNull { it.time }

    @Suppress("MissingPermission")
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // LocationManager.getCurrentLocation is API 30+; it delivers one
                // fix and cleans itself up, which is exactly what we want.
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(appContext),
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            } else {
                // API 26-29 fallback. requestSingleUpdate was deprecated in API
                // 30, so it is only used on the versions that predate the
                // replacement above.
                @Suppress("OVERRIDE_DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    // Empty overrides needed for API < 30, where these are abstract.
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }

    private companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val HTTP_TIMEOUT_MILLIS = 8_000
        const val CACHE_MAX_AGE_MILLIS = 30 * 60 * 1000L
        const val LOCATION_TIMEOUT_MILLIS = 12_000L
        const val LAST_KNOWN_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L

        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
