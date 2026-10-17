package com.distractionkiller.launcher.blocker

/**
 * What the tree walks need to know about a node. On Android [child] is a
 * binder call into the inspected app, answered on that app's UI thread, so it
 * is the one member that costs anything; every other member reads fields that
 * already travelled in the parceled node. The indirection exists so the limits
 * and the "one pass" guarantee can be unit-tested against a fake tree that
 * counts those calls, with no device.
 */
internal interface NodeAccess<N : Any> {
    fun childCount(node: N): Int
    fun child(node: N, index: Int): N?

    /**
     * Hands a node back to the platform's pool once nothing holds it. Before
     * API 33 that avoids an allocation per node walked; from 33 it is a no-op.
     */
    fun release(node: N)

    fun isWebView(node: N): Boolean

    /** Only asked of a WebView. An ad banner is a WebView too and must not count. */
    fun isLargeWebView(node: N): Boolean

    fun viewId(node: N): String?
    fun text(node: N): CharSequence?
    fun description(node: N): CharSequence?
    fun isEditable(node: N): Boolean
    fun isFocused(node: N): Boolean
    fun isShowingHint(node: N): Boolean
}

/** The text a node shows: its text, else its content description, ignoring blanks. */
internal fun <N : Any> NodeAccess<N>.displayText(node: N): CharSequence? =
    text(node)?.takeIf { it.isNotBlank() } ?: description(node)?.takeIf { it.isNotBlank() }

/**
 * Upper bounds on one walk. [maxNodes] caps the cross-process calls; [maxDepth]
 * is the deepest level that will be visited (the root is level 0), which keeps a
 * pathological or runaway hierarchy from eating the whole budget on one chain.
 */
internal class WalkLimits(val maxNodes: Int, val maxDepth: Int)

internal object TreeWalk {

    /** Returns true to descend into the node's children. */
    fun interface Visitor<N : Any> {
        fun visit(node: N, depth: Int): Boolean
    }

    class Stats(
        val visited: Int,
        /** Calls to [NodeAccess.child]; on a device, one binder transaction each at worst. */
        val childCalls: Int,
        /** True if a limit stopped the walk while there was still more tree to read. */
        val truncated: Boolean,
    )

    /**
     * Pre-order walk of [root] within [limits]. Iterative, so the stack is two
     * small arrays sized by the depth limit rather than a frame per level, and
     * every node it fetched is released as soon as its subtree is done. The root
     * belongs to the caller and is never released here.
     */
    @Suppress("UNCHECKED_CAST")
    fun <N : Any> walk(root: N, access: NodeAccess<N>, limits: WalkLimits, visitor: Visitor<N>): Stats {
        var visited = 1
        var childCalls = 0
        var truncated = false
        if (!visitor.visit(root, 0)) return Stats(visited, childCalls, false)
        if (limits.maxDepth < 1) return Stats(visited, childCalls, access.childCount(root) > 0)

        // path[level] is the node whose children are being read at that level.
        val path = arrayOfNulls<Any>(limits.maxDepth)
        val next = IntArray(limits.maxDepth)
        path[0] = root
        var top = 0
        try {
            while (top >= 0) {
                val parent = path[top] as N
                val index = next[top]
                if (index >= access.childCount(parent)) {
                    if (top > 0) access.release(parent)
                    path[top] = null
                    top--
                    continue
                }
                if (visited >= limits.maxNodes) {
                    truncated = true
                    break
                }
                next[top] = index + 1
                childCalls++
                // A child that cannot be fetched costs a call but no budget.
                val child = access.child(parent, index) ?: continue
                visited++
                if (!visitor.visit(child, top + 1)) {
                    access.release(child)
                    continue
                }
                if (top + 1 >= limits.maxDepth) {
                    if (access.childCount(child) > 0) truncated = true
                    access.release(child)
                    continue
                }
                top++
                path[top] = child
                next[top] = 0
            }
        } finally {
            // Only reached with levels still open on a budget stop or an exception.
            while (top > 0) {
                runCatching { access.release(path[top] as N) }
                top--
            }
        }
        return Stats(visited, childCalls, truncated)
    }
}
