package com.distractionkiller.launcher.blocker

import java.util.Arrays

/**
 * The bundled preset domains, held as a sorted array of 64-bit hashes rather
 * than as strings. The adult preset alone is about 42,000 domains; as a
 * HashSet<String> that is several megabytes of String, byte[] and map-node
 * objects resident for as long as the accessibility service lives, which on a
 * 2 GB phone means GC pressure and a launcher process that is cheap to kill.
 * Eight bytes per domain in one flat array is about a tenth of that and puts
 * no objects on the heap to trace.
 *
 * Lookups walk up the host's parent domains and binary-search each one, so
 * "m.reddit.com" is judged by "m.reddit.com" and then "reddit.com" exactly as
 * the string set did.
 *
 * This is approximate in one direction. Two different domains can in theory
 * share a hash, and then a host that is not on the list would count as listed.
 * For a probed suffix that is not a member the chance of matching any of the
 * n stored hashes is about n / 2^64, so roughly 2e-15 per probe at 42,000
 * entries, and a host costs two to four probes. (The better-known birthday
 * figure of about 5e-11 is the chance that two entries collide with each
 * other, which only merges them; it is not the false-positive rate.) The hash
 * is not cryptographic, so this assumes nobody is crafting domains against it,
 * which is the right threat model for a tool that guards against impulse, not
 * against an engineer. The user's own custom list stays as exact strings (see
 * [SiteRules]) because it is tiny and exactness there is free.
 */
class CompactDomainSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    fun isEmpty(): Boolean = hashes.isEmpty()

    /** True if [domain] itself, with no parent-domain walk, is in the set. */
    fun containsExact(domain: String): Boolean = probe(hashOf(domain))

    /**
     * True if [host] or any parent domain of it is in the set. Hashes every
     * label-aligned suffix in a single right-to-left pass, so no substring is
     * allocated and the cost is one pass over the host plus one binary search
     * per label.
     */
    fun matchesHostOrParent(host: String): Boolean {
        if (hashes.isEmpty()) return false
        var h = SEED
        var i = host.length - 1
        while (i >= 0) {
            h = step(h, host[i].code)
            // A suffix is a candidate only where it starts at a label boundary.
            if ((i == 0 || host[i - 1] == '.') && probe(mix(h))) return true
            i--
        }
        return false
    }

    private fun probe(hash: Long): Boolean = Arrays.binarySearch(hashes, hash) >= 0

    /**
     * Collects hashes from any number of presets and sorts once at the end.
     * Streaming into one growing array (instead of a set per preset that is
     * then merged) keeps the peak close to the final size.
     */
    class Builder(initialCapacity: Int = 1024) {
        private var buf = LongArray(initialCapacity.coerceAtLeast(16))
        private var count = 0

        /** Entries added so far; pair with [truncate] to undo a half-read preset. */
        val size: Int get() = count

        fun addHash(hash: Long) {
            if (count == buf.size) buf = buf.copyOf(count + (count shr 1) + 16)
            buf[count++] = hash
        }

        fun addDomain(domain: String) = addHash(hashOf(domain))

        /** Drops everything added after the first [newSize] entries. */
        fun truncate(newSize: Int) {
            if (newSize in 0 until count) count = newSize
        }

        fun build(): CompactDomainSet {
            if (count == 0) return EMPTY
            Arrays.sort(buf, 0, count)
            var unique = 1
            for (i in 1 until count) {
                if (buf[i] != buf[unique - 1]) buf[unique++] = buf[i]
            }
            return CompactDomainSet(buf.copyOf(unique))
        }
    }

    companion object {
        val EMPTY = CompactDomainSet(LongArray(0))

        fun of(domains: Collection<String>): CompactDomainSet {
            val builder = Builder(domains.size)
            domains.forEach(builder::addDomain)
            return builder.build()
        }

        // FNV-1a over the characters, last to first, then a murmur3 finaliser.
        // Folding from the end is what lets one pass produce the hash of every
        // label-aligned suffix of a host. The finaliser spreads the weak low
        // bits of FNV so the sorted array is evenly populated.
        private const val SEED = -3750763034362895579L // 0xcbf29ce484222325, the FNV offset basis
        private const val PRIME = 1099511628211L

        private fun step(h: Long, c: Int): Long = (h xor c.toLong()) * PRIME

        private fun mix(value: Long): Long {
            var h = value
            h = h xor (h ushr 33)
            h *= -49064778989728563L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -4265267296055464877L // 0xc4ceb9fe1a85ec53
            h = h xor (h ushr 33)
            return h
        }

        fun hashOf(domain: CharSequence): Long = hashRange(domain, 0, domain.length, foldAsciiCase = false)

        /**
         * Hash of text[start, end). [foldAsciiCase] lower-cases A-Z on the fly,
         * which is what the preset parser needs so it can hash a line in place
         * instead of allocating a trimmed, lower-cased copy of each of 42,000
         * lines.
         */
        fun hashRange(text: CharSequence, start: Int, end: Int, foldAsciiCase: Boolean): Long {
            var h = SEED
            var i = end - 1
            while (i >= start) {
                var c = text[i].code
                if (foldAsciiCase && c in 'A'.code..'Z'.code) c += 'a'.code - 'A'.code
                h = step(h, c)
                i--
            }
            return mix(h)
        }
    }
}
