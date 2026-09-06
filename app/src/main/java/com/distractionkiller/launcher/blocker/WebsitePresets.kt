package com.distractionkiller.launcher.blocker

import android.content.Context
import android.util.Log

/**
 * One bundled list of domains: a category the user can switch on with one
 * tap instead of typing hundreds of sites.
 */
data class WebsitePreset(
    val id: String,
    /** Which website mode this preset belongs to. */
    val appliesTo: WebsiteMode,
    val name: String,
    val description: String,
    val source: String,
    val license: String,
)

/**
 * Reads the presets shipped under assets/presets/. The catalog is a small
 * TSV; each preset is a text file of one registrable domain per line, built
 * by tools/build_presets.py. Everything is parsed leniently: a bad line is
 * skipped, a missing file is an empty preset, and neither can crash the
 * service or the settings screen.
 */
class WebsitePresets(context: Context) {

    private val assets = context.applicationContext.assets

    @Volatile private var catalogCache: List<WebsitePreset>? = null

    fun catalog(): List<WebsitePreset> {
        catalogCache?.let { return it }
        val parsed = runCatching {
            assets.open("$DIR/index.tsv").bufferedReader().useLines(PresetParser::parseCatalog)
        }.getOrElse {
            Log.w(TAG, "Preset catalog unreadable", it)
            emptyList()
        }
        catalogCache = parsed
        return parsed
    }

    fun presetsFor(mode: WebsiteMode): List<WebsitePreset> = catalog().filter { it.appliesTo == mode }

    /** Domains in one preset. Blocking read; call off the main thread. */
    fun loadDomains(id: String): Set<String> = runCatching {
        assets.open("$DIR/$id.txt").bufferedReader().useLines(PresetParser::parseDomains)
    }.getOrElse {
        Log.w(TAG, "Preset $id unreadable", it)
        emptySet()
    }

    /** Union of every enabled preset that applies to [mode]. Off the main thread. */
    fun loadEnabledDomains(mode: WebsiteMode, enabledIds: Set<String>): Set<String> {
        val result = HashSet<String>()
        presetsFor(mode)
            .filter { it.id in enabledIds }
            .forEach { result.addAll(loadDomains(it.id)) }
        return result
    }

    fun countDomains(id: String): Int = loadDomains(id).size

    private companion object {
        const val TAG = "DKPresets"
        const val DIR = "presets"
    }
}
