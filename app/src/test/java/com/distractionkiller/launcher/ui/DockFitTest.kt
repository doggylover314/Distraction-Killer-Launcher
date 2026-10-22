package com.distractionkiller.launcher.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockFitTest {

    @Test
    fun `labels that fit keep their natural width`() {
        assertArrayEquals(intArrayOf(50, 60, 70), DockFit.caps(intArrayOf(50, 60, 70), 300))
    }

    @Test
    fun `an exact fit is left alone`() {
        assertArrayEquals(intArrayOf(100, 200), DockFit.caps(intArrayOf(100, 200), 300))
    }

    @Test
    fun `only the long label is shortened`() {
        // Equal share is 100. The two short ones keep 60 and 70; the long one
        // gets the 170 they leave.
        assertArrayEquals(intArrayOf(60, 170, 70), DockFit.caps(intArrayOf(60, 400, 70), 300))
    }

    @Test
    fun `two long labels split what is left evenly`() {
        assertArrayEquals(intArrayOf(250, 50, 250), DockFit.caps(intArrayOf(500, 50, 600), 550))
    }

    @Test
    fun `every label too long gets an equal slice and the row adds up`() {
        val caps = DockFit.caps(intArrayOf(300, 300, 300), 100)
        assertArrayEquals(intArrayOf(34, 33, 33), caps)
        assertTrue(caps.sum() == 100)
    }

    @Test
    fun `the row never exceeds the available width`() {
        val natural = intArrayOf(120, 45, 230, 80, 310)
        for (available in listOf(0, 1, 99, 200, 333, 500, 784, 785, 1000)) {
            val caps = DockFit.caps(natural, available)
            assertTrue("available=$available", caps.sum() <= maxOf(available, 0) || natural.sum() <= available)
            caps.forEachIndexed { i, c -> assertTrue(c <= natural[i]) }
        }
    }

    @Test
    fun `empty dock`() {
        assertArrayEquals(IntArray(0), DockFit.caps(IntArray(0), 300))
    }
}
