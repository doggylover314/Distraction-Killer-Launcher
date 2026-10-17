package com.distractionkiller.launcher.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowScansTest {

    private val label = "Distraction Killer Launcher"

    private fun scan(root: FakeNode, limits: WalkLimits = InspectionPolicy.BROWSER_LIMITS): Pair<UrlScan, FakeAccess> {
        val access = FakeAccess()
        return UrlScanner.scan(root, access, limits) to access
    }

    private fun text(t: String, name: String = t) = FakeNode(name = name, text = t)

    // ----------------------------------------------------------- the URL scan

    @Test
    fun `one pass answers every question about the window`() {
        val tree = node(
            node(
                FakeNode(name = "bar", id = "app:id/url_bar", text = "https://allowed.example/x", editable = true),
                text("sender.example"),
                name = "toolbar",
            ),
            webView(true),
            name = "root",
        )
        val (scan, access) = scan(tree)

        assertEquals("allowed.example", scan.idMatch?.host)
        assertEquals("sender.example", scan.plain?.host)
        assertTrue(scan.webViewSeen)
        // The old code walked this tree two or three times; now every node is fetched once.
        assertEquals(tree.count() - 1, access.childCalls)
        assertEquals(tree.count() - 1, scan.stats!!.childCalls)
    }

    @Test
    fun `text inside a web view is never read`() {
        val tree = node(webView(true, text("reddit.com")), name = "root")
        val (scan, access) = scan(tree)
        assertNull(scan.plain)
        assertTrue(scan.webViewSeen)
        assertEquals(1, access.childCalls)
    }

    @Test
    fun `an ad banner web view does not mark the window as a page`() {
        val (scan, _) = scan(node(text("sender.example"), webView(false), name = "root"))
        assertFalse(scan.webViewSeen)
        assertNull(scan.best(isBrowser = false))
    }

    @Test
    fun `a window without a large web view is not judged outside a browser`() {
        val tree = node(text("Booking.com"), name = "root")
        val (scan, _) = scan(tree)
        assertEquals("booking.com", scan.plain?.host)
        assertNull(scan.best(isBrowser = false))
        assertNotNull(scan.best(isBrowser = true))
    }

    @Test
    fun `a url-like id without a host counts in a browser but not elsewhere`() {
        val tree = node(
            FakeNode(name = "bar", id = "app:id/url_bar", editable = true),
            FakeNode(name = "title", text = "tldr.example", editable = false),
            webView(true),
            name = "root",
        )
        val (scan, _) = scan(tree)
        assertNotNull(scan.idMatch)
        assertNull(scan.idMatch?.host)
        // In a browser an empty bar is the new-tab page and is the thing to judge.
        assertSame(scan.idMatch, scan.best(isBrowser = true))
        // In another app it is a search box, so the plain host is used.
        assertEquals("tldr.example", scan.best(isBrowser = false)?.host)
    }

    @Test
    fun `the first candidate in each category wins`() {
        val tree = node(
            text("first.example"),
            text("second.example"),
            FakeNode(name = "e1", text = "edit1.example", editable = true),
            FakeNode(name = "e2", text = "edit2.example", editable = true),
            FakeNode(name = "i1", id = "x:id/omnibox", text = "id1.example"),
            FakeNode(name = "i2", id = "x:id/omnibox", text = "id2.example"),
            name = "root",
        )
        val (scan, _) = scan(tree)
        assertEquals("first.example", scan.plain?.host)
        assertEquals("edit1.example", scan.editable?.host)
        assertEquals("id1.example", scan.idMatch?.host)
        // Priority: id, then editable, then plain.
        assertEquals("id1.example", scan.best(isBrowser = true)?.host)
    }

    @Test
    fun `only id and editable candidates are address bars`() {
        val tree = node(
            text("plain.example"),
            FakeNode(name = "e", text = "edit.example", editable = true),
            name = "root",
        )
        val (scan, _) = scan(tree)
        assertFalse(scan.plain!!.isAddressBar)
        assertTrue(scan.editable!!.isAddressBar)
    }

    @Test
    fun `candidates carry the focus and hint state read during the walk`() {
        val tree = node(
            FakeNode(name = "bar", id = "x:id/url_bar", text = "hint.example", editable = true, focused = true, showingHint = true),
            name = "root",
        )
        val c = scan(tree).first.idMatch!!
        assertTrue(c.focused)
        assertTrue(c.showingHint)
        assertEquals("hint.example", c.host)
    }

    @Test
    fun `a description stands in for blank text`() {
        val tree = node(FakeNode(name = "d", text = "  ", description = "desc.example"), name = "root")
        assertEquals("desc.example", scan(tree).first.plain?.host)
    }

    @Test
    fun `the generic budget reaches an in-app browser but not a candidate buried in a big native screen`() {
        // 300 list rows, then a web view and a host-like header: past the generic budget of 150.
        val rows = List(300) { FakeNode(name = "row$it", text = "Message $it") }
        val tree = FakeNode(name = "root", children = rows + listOf(text("late.example"), webView(true)))

        val generic = scan(tree, InspectionPolicy.GENERIC_LIMITS).first
        assertFalse(generic.webViewSeen)
        assertNull(generic.plain)
        assertTrue(generic.stats!!.truncated)
        assertEquals(149, generic.stats!!.childCalls)

        // The browser budget covers the same screen, which is why it is kept for browsers.
        val browser = scan(tree, InspectionPolicy.BROWSER_LIMITS).first
        assertTrue(browser.webViewSeen)
        assertEquals("late.example", browser.plain?.host)
        assertFalse(browser.stats!!.truncated)
    }

    @Test
    fun `an in-app browser screen is cheap under the generic limits`() {
        val tree = node(
            node(
                node(text("Done"), FakeNode(name = "title", id = "x:id/title", text = "news.example"), name = "toolbar"),
                webView(true, node(node(name = "deep"))),
                name = "content",
            ),
            name = "root",
        )
        val (scan, access) = scan(tree, InspectionPolicy.GENERIC_LIMITS)
        assertTrue(scan.webViewSeen)
        assertEquals("news.example", scan.best(isBrowser = false)?.host)
        assertTrue("fetched ${access.childCalls}", access.childCalls <= 8)
    }

    @Test
    fun `dotted text that overflows Int ahead of the url does not abandon the walk`() {
        // An order number with dots, on screen before the address bar in tree order.
        val tree = node(
            text("1.2.3.99999999999"),
            text("99999999999.1.1.1"),
            FakeNode(name = "bar", id = "app:id/url_bar", text = "https://found.example/x", editable = true),
            webView(true),
            name = "root",
        )
        val (scan, _) = scan(tree)
        assertNull(scan.plain)
        assertEquals("found.example", scan.idMatch?.host)
        assertTrue(scan.webViewSeen)
        assertFalse(scan.stats!!.truncated)
        // The walk finished, so the window gets a verdict instead of failing the same way forever.
        assertEquals(WindowVerdict.HAS_WEBVIEW, InspectionPolicy.verdictToRecord(scan))
    }

    // ------------------------------------------- how big a page-sized WebView is

    @Test
    fun `a full-screen window is measured as before`() {
        assertEquals(800, WebViewSize.minHeightPx(windowHeightPx = 2_000, displayHeightPx = 2_000))
    }

    @Test
    fun `in split-screen a WebView filling the window counts as a page`() {
        val display = 2_400
        val window = 1_200
        val fillsWindow = 900 // the window less a toolbar
        val byDisplay = display * 2 / 5
        assertTrue("old yardstick would have missed it", fillsWindow < byDisplay)
        assertTrue(fillsWindow >= WebViewSize.minHeightPx(window, display))
    }

    @Test
    fun `a half-height sheet counts but an ad banner still does not`() {
        val display = 2_400
        val minimum = WebViewSize.minHeightPx(windowHeightPx = 1_200, displayHeightPx = display)
        assertEquals(480, minimum)
        assertTrue(560 >= minimum) // a page in a half-height window
        assertTrue(150 < minimum) // a 50 dp banner on a 3x screen
    }

    @Test
    fun `unusable window bounds fall back to the display`() {
        assertEquals(800, WebViewSize.minHeightPx(windowHeightPx = 0, displayHeightPx = 2_000))
        assertEquals(800, WebViewSize.minHeightPx(windowHeightPx = -5, displayHeightPx = 2_000))
        // Taller than the display says nothing about the window; the display stands in.
        assertEquals(800, WebViewSize.minHeightPx(windowHeightPx = 5_000, displayHeightPx = 2_000))
        // Display unknown: the window is all there is.
        assertEquals(400, WebViewSize.minHeightPx(windowHeightPx = 1_000, displayHeightPx = 0))
        assertEquals(0, WebViewSize.minHeightPx(windowHeightPx = 0, displayHeightPx = 0))
    }

    @Test
    fun `the window yardstick is never stricter than the display one`() {
        for (display in listOf(800, 1_920, 2_400, 3_200)) {
            for (window in 1..display step 37) {
                assertTrue(WebViewSize.minHeightPx(window, display) <= WebViewSize.minHeightPx(display, display))
            }
        }
        // And it cannot overflow however large the inputs.
        assertTrue(WebViewSize.minHeightPx(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
    }

    @Test
    fun `url hints are recognised in any case`() {
        assertTrue(UrlScanner.hasUrlHint("com.x:id/UrlBar"))
        assertTrue(UrlScanner.hasUrlHint("com.x:id/toolbar_address_bar"))
        assertFalse(UrlScanner.hasUrlHint("com.x:id/search_box"))
    }

    // --------------------------------------------- the Settings lock pipeline

    private fun textsOf(root: FakeNode, limits: WalkLimits = InspectionPolicy.LOCK_LIMITS) =
        TextScanner.collect(root, FakeAccess(), limits)

    private fun locked(pkg: String, root: FakeNode): Boolean {
        val texts = textsOf(root).texts
        return SettingsLockDetector.isLockedScreen(pkg, texts, SettingsLockDetector.DEFAULT_KEYWORDS, label) ||
            SettingsLockDetector.isHomeRoleDialog(pkg, texts)
    }

    @Test
    fun `collected texts are trimmed, in tree order, and blanks are dropped`() {
        val tree = node(
            FakeNode(text = "  Title  ", description = "desc"),
            FakeNode(text = "   ", description = null),
            node(FakeNode(text = "Child")),
        )
        assertEquals(listOf("Title", "desc", "Child"), textsOf(tree).texts)
    }

    @Test
    fun `the lock text walk is bounded and counts its fetches`() {
        val scan = textsOf(wide(2_000) { FakeNode(text = "row $it") })
        assertEquals(500, scan.stats.visited)
        assertEquals(499, scan.stats.childCalls)
        // The root has no text; each of the 499 rows fetched has one.
        assertEquals(499, scan.texts.size)
        assertTrue(scan.stats.truncated)
    }

    @Test
    fun `the role-manager home picker is still locked through the real collection path`() {
        val picker = node(
            FakeNode(text = "Default apps"),
            node(FakeNode(text = "Home app"), FakeNode(text = "Pixel Launcher")),
        )
        assertTrue(locked("com.google.android.permissioncontroller", picker))
        assertTrue(locked("com.android.settings", picker))
    }

    @Test
    fun `the home-role request dialog is still caught`() {
        val dialog = node(FakeNode(text = "Set Other Launcher as your default home app?"), FakeNode(text = "Set as default"))
        assertTrue(locked("com.google.android.permissioncontroller", dialog))
        assertFalse(locked("com.android.chrome", dialog))
    }

    @Test
    fun `the uninstall confirmation that names the app is still locked`() {
        val dialog = node(FakeNode(text = "Do you want to uninstall this app?"), FakeNode(text = label), FakeNode(text = "OK"))
        assertTrue(locked("com.google.android.packageinstaller", dialog))
        val unrelated = node(FakeNode(text = "Do you want to uninstall this app?"), FakeNode(text = "Calculator"))
        assertFalse(locked("com.google.android.packageinstaller", unrelated))
    }

    @Test
    fun `the home chooser is recognised so the service can pick us`() {
        val chooser = node(
            FakeNode(text = "Select a Home app"),
            node(FakeNode(text = label), FakeNode(text = "Pixel Launcher")),
            FakeNode(text = "Always"),
        )
        val texts = textsOf(chooser).texts
        assertTrue(SettingsLockDetector.isLockedScreen("android", texts, SettingsLockDetector.DEFAULT_KEYWORDS, label))
        assertTrue(SettingsLockDetector.isHomeChooser("android", texts, label))
        assertFalse(SettingsLockDetector.isHomeChooser("com.android.settings", texts, label))
    }

    @Test
    fun `a harmless settings page stays unlocked`() {
        val wifi = node(FakeNode(text = "Wi-Fi"), FakeNode(text = "Bluetooth"), FakeNode(description = "Navigate up"))
        assertFalse(locked("com.android.settings", wifi))
    }
}
