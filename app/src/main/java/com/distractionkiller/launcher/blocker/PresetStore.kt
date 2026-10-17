package com.distractionkiller.launcher.blocker

import java.util.concurrent.atomic.AtomicInteger

/**
 * The bundled preset domains the service enforces with, kept together with the
 * website mode they were loaded for. Loading is asynchronous (the adult list is
 * about 42,000 lines), while the mode is read fresh from the preferences on
 * every inspection, so for the length of a reload after a mode switch the two
 * disagree. Reading the stale set under the new mode is not harmless: allowlist
 * treats "listed" as allowed, so the blocklist presets would all count as
 * allowed. Tagging the set with its mode lets [domainsFor] refuse it.
 *
 * Thread-safe: [beginLoad] runs on the main thread, [publish] on an IO thread,
 * [domainsFor] on every accessibility event.
 */
internal class PresetStore {

    private class Loaded(val mode: WebsiteMode, val domains: CompactDomainSet)

    @Volatile private var loaded = Loaded(WebsiteMode.BLOCKLIST, CompactDomainSet.EMPTY)

    /**
     * Counts reload requests. Two quick toggles start two loads, and without
     * this the slower, older one could finish last and put stale domains back.
     * Atomic because nothing but convention keeps every caller on one thread.
     */
    private val generation = AtomicInteger()

    /** Makes the check in [publish] and the assignment one step, so an older load cannot slip in between. */
    private val publishLock = Any()

    /** Starts a reload and returns the ticket its result must present to [publish]. */
    fun beginLoad(): Int = generation.incrementAndGet()

    /** Stores [domains], loaded for [mode], unless a newer reload has begun since [ticket] was issued. */
    fun publish(ticket: Int, mode: WebsiteMode, domains: CompactDomainSet): Boolean {
        synchronized(publishLock) {
            if (ticket != generation.get()) return false
            loaded = Loaded(mode, domains)
            return true
        }
    }

    /**
     * The presets to use under [mode]. A set loaded for the other mode yields
     * an empty one, so the rules fall back to the user's own list only. Failing
     * closed is the deliberate choice: under allowlist that blocks everything
     * the user did not list, for the few moments until the right set arrives,
     * instead of letting through every domain the wrong set happens to contain.
     */
    fun domainsFor(mode: WebsiteMode): CompactDomainSet {
        val snapshot = loaded
        return if (snapshot.mode == mode) snapshot.domains else CompactDomainSet.EMPTY
    }
}
