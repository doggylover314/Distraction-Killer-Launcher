package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockListTest {

    @Test
    fun `the dock holds five apps`() {
        // Every other limit test is derived from the constant; this one pins the number people were promised.
        assertEquals(5, DockList.MAX_ENTRIES)
    }

    @Test
    fun `round trips and keeps order`() {
        val original = listOf("com.c", "com.a", "com.b")
        assertEquals(original, DockList.decode(DockList.encode(original)))
    }

    @Test
    fun `an empty list round trips to an empty list`() {
        assertEquals("", DockList.encode(emptyList()))
        assertTrue(DockList.decode(DockList.encode(emptyList())).isEmpty())
    }

    @Test
    fun `null and blank input decode to an empty list`() {
        assertTrue(DockList.decode(null).isEmpty())
        assertTrue(DockList.decode("").isEmpty())
        assertTrue(DockList.decode("   \n \n\t").isEmpty())
    }

    @Test
    fun `blank lines are dropped and entries are trimmed`() {
        assertEquals(listOf("com.a", "com.b"), DockList.decode("\n  com.a \n\n\tcom.b\n\n"))
    }

    @Test
    fun `duplicates are removed keeping the first occurrence`() {
        assertEquals(listOf("com.b", "com.a", "com.c"), DockList.decode("com.b\ncom.a\ncom.b\ncom.c\ncom.a"))
    }

    @Test
    fun `decode truncates to the maximum`() {
        val stored = (1..DockList.MAX_ENTRIES + 3).joinToString("\n") { "com.app$it" }
        val decoded = DockList.decode(stored)
        assertEquals(DockList.MAX_ENTRIES, decoded.size)
        assertEquals("com.app1", decoded.first())
    }

    @Test
    fun `duplicates do not use up room before truncation`() {
        val stored = "com.a\ncom.a\ncom.b\ncom.c\ncom.d\ncom.e\ncom.f"
        assertEquals(listOf("com.a", "com.b", "com.c", "com.d", "com.e"), DockList.decode(stored))
    }

    @Test
    fun `encode of decode is stable`() {
        val messy = " com.b \n\ncom.a\ncom.b\n"
        val once = DockList.encode(DockList.decode(messy))
        assertEquals(once, DockList.encode(DockList.decode(once)))
        assertEquals("com.b\ncom.a", once)
    }

    @Test
    fun `encode applies the same normalisation as decode`() {
        val tooMany = (1..DockList.MAX_ENTRIES + 2).map { "com.app$it" }
        assertEquals(tooMany.take(DockList.MAX_ENTRIES), DockList.decode(DockList.encode(tooMany)))
        assertEquals("com.a\ncom.b", DockList.encode(listOf(" com.a ", "", "com.b", "com.a")))
    }

    @Test
    fun `toggled appends a package that is not docked`() {
        assertEquals(listOf("com.a", "com.b"), DockList.toggled(listOf("com.a"), "com.b"))
    }

    @Test
    fun `toggled removes a package that is docked`() {
        assertEquals(listOf("com.a", "com.c"), DockList.toggled(listOf("com.a", "com.b", "com.c"), "com.b"))
    }

    @Test
    fun `toggled refuses to grow past the maximum`() {
        val full = (1..DockList.MAX_ENTRIES).map { "com.app$it" }
        assertEquals(full, DockList.toggled(full, "com.extra"))
    }

    @Test
    fun `toggled can still remove from a full dock`() {
        val full = (1..DockList.MAX_ENTRIES).map { "com.app$it" }
        val shrunk = DockList.toggled(full, "com.app1")
        assertEquals(DockList.MAX_ENTRIES - 1, shrunk.size)
        assertEquals(full.drop(1), shrunk)
    }

    @Test
    fun `moved shifts an entry in either direction`() {
        val list = listOf("com.a", "com.b", "com.c")
        assertEquals(listOf("com.a", "com.c", "com.b"), DockList.moved(list, "com.b", 1))
        assertEquals(listOf("com.b", "com.a", "com.c"), DockList.moved(list, "com.b", -1))
    }

    @Test
    fun `moved clamps at the start`() {
        val list = listOf("com.a", "com.b", "com.c")
        assertEquals(listOf("com.c", "com.a", "com.b"), DockList.moved(list, "com.c", -10))
        assertEquals(list, DockList.moved(list, "com.a", -1))
    }

    @Test
    fun `moved clamps at the end`() {
        val list = listOf("com.a", "com.b", "com.c")
        assertEquals(listOf("com.b", "com.c", "com.a"), DockList.moved(list, "com.a", 10))
        assertEquals(list, DockList.moved(list, "com.c", 1))
    }

    @Test
    fun `moved by zero or for an absent package changes nothing`() {
        val list = listOf("com.a", "com.b")
        assertEquals(list, DockList.moved(list, "com.a", 0))
        assertEquals(list, DockList.moved(list, "com.missing", 1))
        assertTrue(DockList.moved(emptyList(), "com.a", 1).isEmpty())
    }

    @Test
    fun `moved survives an extreme delta`() {
        val list = listOf("com.a", "com.b", "com.c")
        assertEquals(listOf("com.b", "com.c", "com.a"), DockList.moved(list, "com.a", Int.MAX_VALUE))
        assertEquals(listOf("com.c", "com.a", "com.b"), DockList.moved(list, "com.c", Int.MIN_VALUE))
    }

    @Test
    fun `toggledAmong drops entries that no longer resolve when it pins`() {
        // Five saved, three of them since hidden: the two that still show must not leave the dock "full".
        val saved = listOf("com.a", "com.hidden1", "com.b", "com.hidden2", "com.hidden3")
        val result = DockList.toggledAmong(saved, "com.c", resolvable = setOf("com.a", "com.b", "com.c"))
        assertEquals(listOf("com.a", "com.b", "com.c"), result)
    }

    @Test
    fun `toggledAmong keeps unresolved entries when it unpins`() {
        val saved = listOf("com.a", "com.hidden", "com.b")
        val result = DockList.toggledAmong(saved, "com.a", resolvable = setOf("com.a", "com.b"))
        assertEquals(listOf("com.hidden", "com.b"), result)
    }

    @Test
    fun `toggledAmong with an unknown visible list never prunes`() {
        val full = (1..DockList.MAX_ENTRIES).map { "com.app$it" }
        // A failed or pending app-list load looks like nothing resolves; the dock must survive it.
        assertEquals(full, DockList.toggledAmong(full, "com.extra", resolvable = emptySet()))
        assertEquals(listOf("com.a", "com.b"), DockList.toggledAmong(listOf("com.a"), "com.b", emptySet()))
    }

    @Test
    fun `toggledAmong still refuses to grow past the maximum when every entry resolves`() {
        val full = (1..DockList.MAX_ENTRIES).map { "com.app$it" }
        assertEquals(full, DockList.toggledAmong(full, "com.extra", resolvable = full.toSet() + "com.extra"))
    }

    @Test
    fun `resolve keeps the saved order, not the list order`() {
        val a = LaunchableApp("com.a", "Alpha")
        val b = LaunchableApp("com.b", "Beta")
        val c = LaunchableApp("com.c", "Gamma")
        assertEquals(listOf(c, a, b), DockList.resolve(listOf("com.c", "com.a", "com.b"), listOf(a, b, c)))
    }

    @Test
    fun `resolve drops a package the visible list does not contain`() {
        // Hidden by the protected tier, or uninstalled: the dock never reaches it.
        val a = LaunchableApp("com.a", "Alpha")
        val c = LaunchableApp("com.c", "Gamma")
        val visible = listOf(a, c)
        assertEquals(listOf(a, c), DockList.resolve(listOf("com.a", "com.hidden", "com.c", "com.gone"), visible))
    }

    @Test
    fun `resolve against an empty visible list is empty`() {
        assertTrue(DockList.resolve(listOf("com.a", "com.b"), emptyList()).isEmpty())
    }

    @Test
    fun `resolve of an empty order is empty`() {
        assertTrue(DockList.resolve(emptyList(), listOf(LaunchableApp("com.a", "Alpha"))).isEmpty())
    }

    @Test
    fun `resolve shows a repeated package once`() {
        val a = LaunchableApp("com.a", "Alpha")
        assertEquals(listOf(a), DockList.resolve(listOf("com.a", "com.a"), listOf(a)))
    }

    @Test
    fun `resolve returns the visible list's own app, not a rebuilt one`() {
        val a = LaunchableApp("com.a", "Alpha")
        val resolved = DockList.resolve(listOf("com.a"), listOf(a))
        assertEquals(1, resolved.size)
        assertTrue(resolved.single() === a)
    }
}
