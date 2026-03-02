package ai.openclaw.android

import ai.openclaw.android.ui.navigation.BottomNavItem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BottomNavItemTest {

    @Test
    fun `bottom nav has exactly 5 items`() {
        assertEquals(5, BottomNavItem.items.size)
    }

    @Test
    fun `first tab is Chat`() {
        val first = BottomNavItem.items[0]
        assertTrue(first is BottomNavItem.Chat)
        assertEquals("Chat", first.label)
        assertEquals("chat", first.route)
    }

    @Test
    fun `second tab is Dashboard`() {
        val second = BottomNavItem.items[1]
        assertTrue(second is BottomNavItem.Dashboard)
        assertEquals("Dashboard", second.label)
        assertEquals("dashboard", second.route)
    }

    @Test
    fun `third tab is Channels`() {
        val third = BottomNavItem.items[2]
        assertTrue(third is BottomNavItem.Channels)
        assertEquals("Channels", third.label)
        assertEquals("channels", third.route)
    }

    @Test
    fun `fourth tab is Tools`() {
        val fourth = BottomNavItem.items[3]
        assertTrue(fourth is BottomNavItem.Tools)
        assertEquals("Tools", fourth.label)
        assertEquals("tools", fourth.route)
    }

    @Test
    fun `fifth tab is Settings`() {
        val fifth = BottomNavItem.items[4]
        assertTrue(fifth is BottomNavItem.Settings)
        assertEquals("Settings", fifth.label)
        assertEquals("settings", fifth.route)
    }

    @Test
    fun `all routes are unique`() {
        val routes = BottomNavItem.items.map { it.route }
        assertEquals(routes.size, routes.toSet().size, "All tab routes must be unique")
    }

    @Test
    fun `all labels are unique`() {
        val labels = BottomNavItem.items.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "All tab labels must be unique")
    }

    @Test
    fun `all items have non-blank labels`() {
        for (item in BottomNavItem.items) {
            assertTrue(item.label.isNotBlank(), "Tab label must not be blank: ${item.route}")
        }
    }

    @Test
    fun `all items have non-blank routes`() {
        for (item in BottomNavItem.items) {
            assertTrue(item.route.isNotBlank(), "Tab route must not be blank: ${item.label}")
        }
    }

    @Test
    fun `all items have a selectedIcon`() {
        for (item in BottomNavItem.items) {
            assertNotEquals(item.icon, item.selectedIcon, "selectedIcon should differ from icon: ${item.label}")
        }
    }
}
