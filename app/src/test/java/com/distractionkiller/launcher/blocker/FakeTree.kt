package com.distractionkiller.launcher.blocker

/** A stand-in for an accessibility node: plain data, children by index. */
internal class FakeNode(
    val name: String = "",
    val className: String = "android.view.View",
    val id: String? = null,
    val text: String? = null,
    val description: String? = null,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val showingHint: Boolean = false,
    val largeWebView: Boolean = false,
    val children: List<FakeNode?> = emptyList(),
) {
    /** Nodes in this subtree, ignoring null children. */
    fun count(): Int = 1 + children.sumOf { it?.count() ?: 0 }
}

/** Counts exactly what costs money on a device: child fetches. */
internal class FakeAccess : NodeAccess<FakeNode> {
    var childCalls = 0
    val released = ArrayList<FakeNode>()

    override fun childCount(node: FakeNode) = node.children.size

    override fun child(node: FakeNode, index: Int): FakeNode? {
        childCalls++
        return node.children[index]
    }

    override fun release(node: FakeNode) {
        released.add(node)
    }

    override fun isWebView(node: FakeNode) = node.className == "android.webkit.WebView"
    override fun isLargeWebView(node: FakeNode) = node.largeWebView
    override fun viewId(node: FakeNode) = node.id
    override fun text(node: FakeNode): CharSequence? = node.text
    override fun description(node: FakeNode): CharSequence? = node.description
    override fun isEditable(node: FakeNode) = node.editable
    override fun isFocused(node: FakeNode) = node.focused
    override fun isShowingHint(node: FakeNode) = node.showingHint
}

internal fun node(vararg children: FakeNode?, name: String = "") = FakeNode(name = name, children = children.toList())

internal fun webView(large: Boolean = true, vararg children: FakeNode?) = FakeNode(
    name = if (large) "webview" else "banner",
    className = "android.webkit.WebView",
    largeWebView = large,
    children = children.toList(),
)

/** A single chain [depth] levels deep: root is level 0. */
internal fun chain(depth: Int): FakeNode {
    var current = FakeNode(name = "level$depth")
    for (level in depth - 1 downTo 0) current = FakeNode(name = "level$level", children = listOf(current))
    return current
}

/** A root with [n] leaf children. */
internal fun wide(n: Int, make: (Int) -> FakeNode = { FakeNode(name = "leaf$it") }) =
    FakeNode(name = "root", children = List(n) { make(it) })
