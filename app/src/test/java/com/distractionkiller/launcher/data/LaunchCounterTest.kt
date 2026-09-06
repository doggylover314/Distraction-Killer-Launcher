package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchCounterTest {

    @Test
    fun `counts accumulate within a day`() {
        val counter = LaunchCounter("", emptyMap())
            .recorded("com.a", "2026-09-06")
            .recorded("com.a", "2026-09-06")
            .recorded("com.b", "2026-09-06")
        assertEquals(2, counter.countFor("com.a", "2026-09-06"))
        assertEquals(1, counter.countFor("com.b", "2026-09-06"))
        assertEquals(0, counter.countFor("com.c", "2026-09-06"))
    }

    @Test
    fun `a new day starts from zero`() {
        val yesterday = LaunchCounter("", emptyMap()).recorded("com.a", "2026-09-05")
        assertEquals(0, yesterday.countFor("com.a", "2026-09-06"))
        val today = yesterday.recorded("com.b", "2026-09-06")
        assertEquals("2026-09-06", today.dateKey)
        assertEquals(mapOf("com.b" to 1), today.counts)
    }

    @Test
    fun `round trips through the string set encoding`() {
        val original = LaunchCounter("2026-09-06", mapOf("com.a" to 3, "com.b.c" to 1))
        val decoded = LaunchCounter.decode(original.dateKey, original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `garbage entries are dropped rather than crashing`() {
        val decoded = LaunchCounter.decode("2026-09-06", setOf("com.a|2", "broken", "|", "com.b|x", "com.c|"))
        assertEquals(mapOf("com.a" to 2), decoded.counts)
    }

    @Test
    fun `missing storage decodes to an empty counter`() {
        val decoded = LaunchCounter.decode(null, null)
        assertEquals(emptyMap<String, Int>(), decoded.counts)
        assertEquals(0, decoded.countFor("com.a", "2026-09-06"))
    }
}
