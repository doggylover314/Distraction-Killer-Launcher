package com.distractionkiller.launcher.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distractionkiller.launcher.data.ClockFormat
import com.distractionkiller.launcher.weather.WeatherCodes
import com.distractionkiller.launcher.weather.WeatherSnapshot
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock, day and date, focus note, weather and battery. Every line can be
 * switched off from Appearance settings. java.time is used directly, which
 * needs API 26 and no desugaring; that is exactly this app's minSdk.
 */
@Composable
fun StatusHeader(
    weather: WeatherSnapshot?,
    config: HomeUiConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = rememberCurrentTime()
    val battery = rememberBatteryStatus()
    val locale = Locale.getDefault()
    val align = if (config.centerAlign) TextAlign.Center else TextAlign.Start

    // Re-read every recomposition (roughly once a minute) rather than caching,
    // so flipping the system 12/24-hour setting is picked up without a restart.
    val use24h = when (config.clockFormat) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.HOUR_12 -> false
        ClockFormat.HOUR_24 -> true
    }
    val time = now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (config.centerAlign) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (config.showClock) {
            Text(
                text = time,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (config.showDate) {
            Text(
                text = date,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = align,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        config.focusNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = align,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        val chips = buildList {
            weatherText(weather, config)?.let(::add)
            if (config.showBattery) add(batteryText(battery))
        }
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chips.forEachIndexed { index, text ->
                    if (index > 0) StatusChipText("·")
                    StatusChipText(text)
                }
            }
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

private fun weatherText(weather: WeatherSnapshot?, config: HomeUiConfig): String? {
    if (!config.weatherEnabled) return null
    // No reading yet: say so once rather than leaving a hole in the row that
    // fills in a second later and shifts everything sideways.
    if (weather == null) return "Weather —"
    val temperature =
        "${weather.temperatureRounded(config.useFahrenheit)}${weather.unitSuffix(config.useFahrenheit)}"
    return "$temperature  ${WeatherCodes.describe(weather.weatherCode)}"
}

private fun batteryText(battery: BatteryStatus): String {
    val percent = battery.percent ?: return "Battery —"
    return if (battery.isCharging) "$percent% charging" else "$percent%"
}
