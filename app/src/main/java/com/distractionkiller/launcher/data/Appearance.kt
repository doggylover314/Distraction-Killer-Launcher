package com.distractionkiller.launcher.data

/**
 * Look-only settings. None of these change which apps are reachable, so they
 * are editable without the password.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class TextSize { SMALL, MEDIUM, LARGE }

enum class ClockFormat { SYSTEM, HOUR_12, HOUR_24 }

internal inline fun <reified T : Enum<T>> enumFromStored(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
