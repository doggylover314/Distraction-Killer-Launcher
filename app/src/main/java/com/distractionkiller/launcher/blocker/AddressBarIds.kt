package com.distractionkiller.launcher.blocker

/**
 * The view ids a browser's address bar may carry, tried with
 * findAccessibilityNodeInfosByViewId, which is one binder call per id. Chrome
 * and its forks answer to the first; Firefox is second and a few others are
 * further down, so a browser that is not Chromium would pay up to four failed
 * calls on every inspection. Remembering which id last matched for a package
 * and trying it first makes the steady state one call.
 */
internal class AddressBarIds {

    private val orders = HashMap<String, Array<String>>()

    /** Full resource ids for [packageName], the one that matched last first. */
    fun orderFor(packageName: String): Array<String> {
        orders[packageName]?.let { return it }
        // Browsers are few, but the key is whatever package raised the event;
        // bound the map so an odd device cannot grow it.
        if (orders.size >= MAX_PACKAGES) orders.clear()
        return Array(SUFFIXES.size) { "$packageName:id/${SUFFIXES[it]}" }.also { orders[packageName] = it }
    }

    fun remember(packageName: String, matchedId: String) {
        val order = orders[packageName] ?: return
        val at = order.indexOf(matchedId)
        if (at <= 0) return
        System.arraycopy(order, 0, order, 1, at)
        order[0] = matchedId
    }

    fun clear() = orders.clear()

    companion object {
        private const val MAX_PACKAGES = 64

        val SUFFIXES = listOf(
            "url_bar",                          // Chrome, Brave, Edge, Vivaldi, Kiwi and other Chromium forks
            "mozac_browser_toolbar_url_view",   // Firefox, Firefox Focus
            "location_bar_edit_text",           // Samsung Internet (unverified)
            "url_field",                        // Opera (unverified)
            "omnibarTextInput",                 // DuckDuckGo (unverified)
        )
    }
}
