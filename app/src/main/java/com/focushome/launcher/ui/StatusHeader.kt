package com.focushome.launcher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focushome.launcher.weather.WeatherCodes
import com.focushome.launcher.weather.WeatherSnapshot
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock, day and date, weather and battery. java.time is used directly, which
 * needs API 26 and no desugaring; that is exactly this app's minSdk.
 */
@Composable
fun StatusHeader(
    weather: WeatherSnapshot?,
    useFahrenheit: Boolean,
    weatherEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = rememberCurrentTime()
    val battery = rememberBatteryStatus()

    // Re-read every recomposition (roughly once a minute) rather than caching,
    // so flipping the system 12/24-hour setting is picked up without a restart.
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val locale = Locale.getDefault()
    val time = now.format(DateTimeFormatter.ofPattern(timePattern, locale))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = time,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = date,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val weatherText = weatherText(weather, useFahrenheit, weatherEnabled)
            if (weatherText != null) {
                StatusChipText(weatherText)
                StatusChipText("·")
            }
            StatusChipText(batteryText(battery))
        }
    }
}

@Composable
private fun StatusChipText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun weatherText(
    weather: WeatherSnapshot?,
    useFahrenheit: Boolean,
    weatherEnabled: Boolean,
): String? {
    if (!weatherEnabled) return null
    // No reading yet: say so once rather than leaving a hole in the row that
    // fills in a second later and shifts everything sideways.
    if (weather == null) return "Weather —"
    val temperature = "${weather.temperatureRounded(useFahrenheit)}${weather.unitSuffix(useFahrenheit)}"
    return "$temperature  ${WeatherCodes.describe(weather.weatherCode)}"
}

private fun batteryText(battery: BatteryStatus): String {
    val percent = battery.percent ?: return "Battery —"
    return if (battery.isCharging) "$percent% charging" else "$percent%"
}
