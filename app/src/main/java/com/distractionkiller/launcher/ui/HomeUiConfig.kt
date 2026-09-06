package com.distractionkiller.launcher.ui

import com.distractionkiller.launcher.data.ClockFormat
import com.distractionkiller.launcher.data.Prefs
import com.distractionkiller.launcher.data.TextSize

/**
 * Snapshot of every appearance setting the home screen reads. Taken once per
 * resume so the screen is a pure function of it.
 */
data class HomeUiConfig(
    val textSize: TextSize,
    val centerAlign: Boolean,
    val showClock: Boolean,
    val clockFormat: ClockFormat,
    val showDate: Boolean,
    val showBattery: Boolean,
    val weatherEnabled: Boolean,
    val useFahrenheit: Boolean,
    val showPackageNames: Boolean,
    val showSearchBar: Boolean,
    val showLaunchCounts: Boolean,
    /** null when the focus note is switched off. */
    val focusNote: String?,
) {
    companion object {
        fun from(prefs: Prefs) = HomeUiConfig(
            textSize = prefs.textSize,
            centerAlign = prefs.centerAlign,
            showClock = prefs.showClock,
            clockFormat = prefs.clockFormat,
            showDate = prefs.showDate,
            showBattery = prefs.showBattery,
            weatherEnabled = prefs.weatherEnabled,
            useFahrenheit = prefs.useFahrenheit,
            showPackageNames = prefs.showPackageNames,
            showSearchBar = prefs.showSearchBar,
            showLaunchCounts = prefs.showLaunchCounts,
            focusNote = prefs.focusNoteText.trim().takeIf { prefs.focusNoteEnabled && it.isNotEmpty() },
        )
    }
}
