package com.distractionkiller.launcher.blocker

/**
 * The file formats under assets/presets/, kept free of Android types so the
 * unit tests can parse the real bundled files and catch a format drift
 * between tools/build_presets.py and the app.
 */
object PresetParser {

    /** Registrable-domain shape the generator promises; anything else is dropped. */
    val DOMAIN_LINE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

    /**
     * index.tsv: a header row, then one preset per line as
     * id, kind (block|allow), name, description, source, license.
     */
    fun parseCatalog(lines: Sequence<String>): List<WebsitePreset> =
        lines.drop(1)
            .map { it.split('\t') }
            .filter { it.size >= 6 && it[0].isNotBlank() }
            .map { cols ->
                WebsitePreset(
                    id = cols[0].trim(),
                    appliesTo = if (cols[1].trim().equals("allow", ignoreCase = true)) {
                        WebsiteMode.ALLOWLIST
                    } else {
                        WebsiteMode.BLOCKLIST
                    },
                    name = cols[2].trim(),
                    description = cols[3].trim(),
                    source = cols[4].trim(),
                    license = cols[5].trim(),
                )
            }
            // A repeated id would collide as a LazyColumn key and crash Settings.
            .distinctBy { it.id }
            .toList()

    /** One domain per line; '#' comments and blank lines are skipped. */
    fun parseDomains(lines: Sequence<String>): HashSet<String> =
        lines.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && DOMAIN_LINE.matches(it) }
            .toHashSet()
}
