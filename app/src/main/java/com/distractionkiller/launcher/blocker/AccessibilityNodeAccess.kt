package com.distractionkiller.launcher.blocker

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

private const val WEB_VIEW = "android.webkit.WebView"

/**
 * Before API 33 a node or window info is a pooled object that its holder is
 * meant to hand back; skipping that makes every walked node a fresh allocation,
 * which an old phone pays for in garbage-collection pauses. From 33 recycle()
 * does nothing, so it is not called. A node must not be touched afterwards,
 * which is why the scans copy out what they need instead of keeping nodes.
 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { recycle() }
}

@Suppress("DEPRECATION")
internal fun AccessibilityWindowInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { recycle() }
}

/**
 * The real tree. Single-threaded by contract (accessibility callbacks run on
 * the main thread), which is what lets [bounds] be reused instead of allocating
 * a Rect per WebView.
 */
internal class AccessibilityNodeAccess : NodeAccess<AccessibilityNodeInfo> {

    /** A WebView at least this tall counts as a page rather than an ad banner. */
    var minWebViewHeightPx = 0

    private val bounds = Rect()

    override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

    override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)

    override fun release(node: AccessibilityNodeInfo) = node.recycleCompat()

    override fun isWebView(node: AccessibilityNodeInfo): Boolean = node.className?.toString() == WEB_VIEW

    override fun isLargeWebView(node: AccessibilityNodeInfo): Boolean {
        node.getBoundsInScreen(bounds)
        return bounds.height() >= minWebViewHeightPx
    }

    override fun viewId(node: AccessibilityNodeInfo): String? = node.viewIdResourceName
    override fun text(node: AccessibilityNodeInfo): CharSequence? = node.text
    override fun description(node: AccessibilityNodeInfo): CharSequence? = node.contentDescription
    override fun isEditable(node: AccessibilityNodeInfo): Boolean = node.isEditable
    override fun isFocused(node: AccessibilityNodeInfo): Boolean = node.isFocused
    override fun isShowingHint(node: AccessibilityNodeInfo): Boolean = node.isShowingHintText
}
