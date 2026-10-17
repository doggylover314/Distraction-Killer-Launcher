package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AddressBarIdsTest {

    @Test
    fun `ids are qualified with the package, chromium first`() {
        val ids = AddressBarIds().orderFor("org.mozilla.firefox")
        assertEquals("org.mozilla.firefox:id/url_bar", ids.first())
        assertEquals(AddressBarIds.SUFFIXES.size, ids.size)
        assertEquals("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", ids[1])
    }

    @Test
    fun `the id that matched last time is tried first next time`() {
        val ids = AddressBarIds()
        val before = ids.orderFor("org.mozilla.firefox").toList()
        ids.remember("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        val after = ids.orderFor("org.mozilla.firefox")
        assertEquals("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", after.first())
        assertEquals("org.mozilla.firefox:id/url_bar", after[1])
        // Nothing is lost or duplicated by the move.
        assertEquals(before.toSet(), after.toSet())
        assertEquals(before.size, after.size)
    }

    @Test
    fun `remembering the first id changes nothing, and packages are independent`() {
        val ids = AddressBarIds()
        val a = ids.orderFor("a")
        ids.remember("a", "a:id/url_bar")
        assertEquals("a:id/url_bar", a.first())
        ids.remember("a", "a:id/omnibarTextInput")
        assertEquals("b:id/url_bar", ids.orderFor("b").first())
    }

    @Test
    fun `the lookup is cached and clearing resets the learned order`() {
        val ids = AddressBarIds()
        assertSame(ids.orderFor("a"), ids.orderFor("a"))
        ids.remember("a", "a:id/omnibarTextInput")
        ids.clear()
        assertEquals("a:id/url_bar", ids.orderFor("a").first())
    }

    @Test
    fun `the map stays bounded`() {
        val ids = AddressBarIds()
        repeat(1_000) { ids.orderFor("pkg$it") }
        assertEquals("pkg999:id/url_bar", ids.orderFor("pkg999").first())
    }
}
