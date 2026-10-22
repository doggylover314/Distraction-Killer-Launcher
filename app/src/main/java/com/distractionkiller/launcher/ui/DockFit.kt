package com.distractionkiller.launcher.ui

/**
 * Width budget for the one-line text dock.
 *
 * Every label that fits in an equal share of the row keeps its full width, and
 * whatever those short labels leave unused is split among the long ones, which
 * are the only ones that get shortened. "Phone, Maps, Google Calendar" on a
 * narrow phone therefore cuts only the calendar, and only by as much as it has
 * to, instead of cutting all three to the same stub.
 */
internal object DockFit {

    /**
     * Maximum width for each cell, in the same order as [natural].
     * [natural] is each cell's unconstrained width, [available] the row width.
     * When everything fits, every cap equals its natural width.
     */
    fun caps(natural: IntArray, available: Int): IntArray {
        val caps = natural.copyOf()
        if (natural.isEmpty() || natural.sum() <= available) return caps

        val order = natural.indices.sortedBy { natural[it] }
        var remaining = available.coerceAtLeast(0)
        var left = order.size
        for ((rank, index) in order.withIndex()) {
            val share = remaining / left
            if (natural[index] <= share) {
                // Fits in a fair share, so it keeps its own width.
                remaining -= natural[index]
                left--
                continue
            }
            // This one and every wider one after it get an equal slice of what
            // is left. The integer remainder goes one pixel at a time to the
            // first few so the row adds up to exactly [available].
            val wide = order.subList(rank, order.size)
            val extra = remaining - share * wide.size
            wide.forEachIndexed { i, idx -> caps[idx] = share + if (i < extra) 1 else 0 }
            break
        }
        return caps
    }
}
