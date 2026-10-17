package com.distractionkiller.launcher.blocker

/**
 * The file formats under assets/presets/, kept free of Android types so the
 * unit tests can parse the real bundled files and catch a format drift
 * between tools/build_presets.py and the app.
 */
object PresetParser {

    /**
     * Registrable-domain shape the generator promises; anything else is dropped.
     * [addLine] enforces the same shape without a regex, and PresetParserTest
     * keeps the two in step.
     */
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

    /**
     * Reads a preset file's lines into [into] and returns how many were
     * accepted. One domain per line; '#' comments and blank lines are skipped.
     */
    fun addLines(lines: Sequence<String>, into: CompactDomainSet.Builder): Int {
        var accepted = 0
        for (line in lines) if (addLine(line, into)) accepted++
        return accepted
    }

    /**
     * Validates one line and adds its hash, without building the trimmed,
     * lower-cased copy that the string-set parser made per line. Loading the
     * adult preset is 42,000 lines on a slow phone's IO thread; the saving is
     * the garbage, which is what the collector then has to chase on a
     * 2 GB device.
     *
     * Case is folded for A-Z only. The bundled files are ASCII by contract, and
     * a stricter rule than String.lowercase() only matters for exotic
     * characters that lower-case into ASCII (the Kelvin sign), which a real
     * domain list never contains.
     */
    fun addLine(raw: String, into: CompactDomainSet.Builder): Boolean {
        var start = 0
        var end = raw.length
        while (start < end && raw[start].isWhitespace()) start++
        while (end > start && raw[end - 1].isWhitespace()) end--
        if (start == end || raw[start] == '#') return false
        if (!hasDomainShape(raw, start, end)) return false
        into.addHash(CompactDomainSet.hashRange(raw, start, end, foldAsciiCase = true))
        return true
    }

    /** [DOMAIN_LINE] over raw[start, end), accepting A-Z because lines are lower-cased first. */
    private fun hasDomainShape(raw: String, start: Int, end: Int): Boolean {
        var labelLength = 0
        var dots = 0
        for (i in start until end) {
            val c = raw[i]
            when {
                c == '.' -> {
                    if (labelLength == 0) return false
                    labelLength = 0
                    dots++
                }
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' -> labelLength++
                else -> return false
            }
        }
        return labelLength > 0 && dots > 0
    }
}
