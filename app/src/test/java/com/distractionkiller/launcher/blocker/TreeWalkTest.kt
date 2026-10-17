package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TreeWalkTest {

    private class Run(val order: List<FakeNode>, val stats: TreeWalk.Stats, val access: FakeAccess)

    private fun walk(
        root: FakeNode,
        maxNodes: Int = 500,
        maxDepth: Int = 32,
        stopAt: (FakeNode) -> Boolean = { false },
    ): Run {
        val access = FakeAccess()
        val order = ArrayList<FakeNode>()
        val stats = TreeWalk.walk(root, access, WalkLimits(maxNodes, maxDepth)) { n, _ ->
            order.add(n)
            !stopAt(n)
        }
        return Run(order, stats, access)
    }

    private fun names(run: Run) = run.order.map { it.name }

    @Test
    fun `visits in pre-order and fetches each node exactly once`() {
        val tree = node(
            node(node(name = "d"), node(name = "e"), name = "b"),
            node(name = "c"),
            name = "a",
        )
        val run = walk(tree)
        assertEquals(listOf("a", "b", "d", "e", "c"), names(run))
        assertEquals(tree.count() - 1, run.access.childCalls)
        assertEquals(tree.count() - 1, run.stats.childCalls)
        assertFalse(run.stats.truncated)
    }

    @Test
    fun `the node budget caps both the visits and the child fetches`() {
        val run = walk(wide(1_000), maxNodes = 50)
        assertEquals(50, run.stats.visited)
        assertEquals(49, run.access.childCalls)
        assertTrue(run.stats.truncated)
    }

    @Test
    fun `a child that cannot be fetched costs a call but no budget`() {
        val root = FakeNode(name = "root", children = listOf(null, null, node(name = "a"), node(name = "b")))
        val run = walk(root, maxNodes = 3)
        assertEquals(listOf("root", "a", "b"), names(run))
        assertEquals(4, run.access.childCalls)
        assertFalse(run.stats.truncated)
    }

    @Test
    fun `the depth limit stops a long chain`() {
        val run = walk(chain(100), maxDepth = 5)
        assertEquals(6, run.stats.visited)
        assertEquals(5, run.access.childCalls)
        assertEquals("level5", run.order.last().name)
        assertTrue(run.stats.truncated)
    }

    @Test
    fun `a chain exactly as deep as the limit is not truncated`() {
        val run = walk(chain(5), maxDepth = 5)
        assertEquals(6, run.stats.visited)
        assertFalse(run.stats.truncated)
    }

    @Test
    fun `depth zero reads only the root`() {
        val run = walk(chain(3), maxDepth = 0)
        assertEquals(1, run.stats.visited)
        assertEquals(0, run.access.childCalls)
        assertTrue(run.stats.truncated)
    }

    @Test
    fun `a node the visitor declines is not descended into`() {
        val tree = node(
            node(name = "toolbar"),
            webView(true, node(name = "page-a"), node(name = "page-b")),
            node(name = "after"),
            name = "root",
        )
        val run = walk(tree, stopAt = { it.name == "webview" })
        assertEquals(listOf("root", "toolbar", "webview", "after"), names(run))
        // Three children of root; nothing under the web view.
        assertEquals(3, run.access.childCalls)
    }

    @Test
    fun `every fetched node is released once and the root never`() {
        val tree = node(node(node(name = "x"), name = "b"), node(name = "c"), name = "a")
        val run = walk(tree)
        assertEquals(setOf("b", "x", "c"), run.access.released.map { it.name }.toSet())
        assertEquals(3, run.access.released.size)
        assertFalse(run.access.released.any { it === tree })
    }

    @Test
    fun `nodes still open are released when a limit stops the walk`() {
        // Budget runs out while two levels are open.
        val tree = node(node(node(name = "x"), node(name = "y"), name = "b"), node(name = "c"), name = "a")
        val run = walk(tree, maxNodes = 3)
        assertEquals(listOf("a", "b", "x"), names(run))
        assertEquals(setOf("b", "x"), run.access.released.map { it.name }.toSet())
        assertEquals(2, run.access.released.size)
    }

    @Test
    fun `nodes are released even if the visitor throws`() {
        val tree = node(node(node(name = "x"), name = "b"), name = "a")
        val access = FakeAccess()
        try {
            TreeWalk.walk(tree, access, WalkLimits(100, 10)) { n, _ ->
                if (n.name == "x") error("boom")
                true
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("b"), access.released.map { it.name })
    }

    @Test
    fun `matches a plain recursive pre-order on random trees under random limits`() {
        val rng = Random(42)
        repeat(300) { round ->
            val tree = randomTree(rng, depth = 0)
            val maxNodes = 1 + rng.nextInt(60)
            val maxDepth = rng.nextInt(8)
            val stop = rng.nextInt(4) == 0
            val shouldStop: (FakeNode) -> Boolean = { stop && it.name.endsWith("7") }

            val expected = ArrayList<FakeNode>()
            reference(tree, 0, intArrayOf(maxNodes - 1), maxDepth, expected, shouldStop)
            val actual = walk(tree, maxNodes, maxDepth, shouldStop)
            assertEquals("round $round", expected.map { it.name }, names(actual))
        }
    }

    private var serial = 0

    private fun randomTree(rng: Random, depth: Int): FakeNode {
        val kids = if (depth >= 7) 0 else rng.nextInt(5)
        return FakeNode(
            name = "n${serial++}",
            children = List(kids) { if (rng.nextInt(10) == 0) null else randomTree(rng, depth + 1) },
        )
    }

    /** The old recursive shape, with a depth cap added. */
    private fun reference(
        node: FakeNode,
        depth: Int,
        budget: IntArray,
        maxDepth: Int,
        out: MutableList<FakeNode>,
        stop: (FakeNode) -> Boolean,
    ) {
        out.add(node)
        if (stop(node) || depth >= maxDepth) return
        for (child in node.children) {
            if (budget[0] <= 0) return
            if (child == null) continue
            budget[0]--
            reference(child, depth + 1, budget, maxDepth, out, stop)
        }
    }
}
