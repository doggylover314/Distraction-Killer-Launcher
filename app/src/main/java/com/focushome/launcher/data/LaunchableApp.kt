package com.focushome.launcher.data

/**
 * One installed app that has a launcher entry. No icon field on purpose: the
 * home screen is a text list, which also means nothing has to decode a few
 * hundred adaptive-icon drawables at startup.
 */
data class LaunchableApp(
    val packageName: String,
    val label: String,
)
