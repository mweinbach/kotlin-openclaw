package ai.openclaw.android

import ai.openclaw.android.ui.navigation.Routes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class NavigationRoutesTest {

    @Test
    fun `chat detail route contains sessionId placeholder`() {
        assertEquals("chat/{sessionId}", Routes.CHAT_DETAIL)
    }

    @Test
    fun `chatDetail builder substitutes sessionId`() {
        assertEquals("chat/abc-123", Routes.chatDetail("abc-123"))
    }

    @Test
    fun `chatDetail builder handles special characters`() {
        val result = Routes.chatDetail("session with spaces")
        assertEquals("chat/session with spaces", result)
    }

    @Test
    fun `channel detail route contains channelKey placeholder`() {
        assertEquals("channels/{channelKey}", Routes.CHANNEL_DETAIL)
    }

    @Test
    fun `channelDetail builder substitutes channelKey`() {
        assertEquals("channels/telegram:default", Routes.channelDetail("telegram:default"))
    }

    @Test
    fun `cron edit route contains jobId placeholder`() {
        assertEquals("tools/cron/{jobId}", Routes.CRON_EDIT)
    }

    @Test
    fun `cronEdit builder substitutes jobId`() {
        assertEquals("tools/cron/job-42", Routes.cronEdit("job-42"))
    }

    @Test
    fun `agent detail route contains agentId placeholder`() {
        assertEquals("settings/agents/{agentId}", Routes.AGENT_DETAIL)
    }

    @Test
    fun `agentDetail builder substitutes agentId`() {
        assertEquals("settings/agents/my-agent", Routes.agentDetail("my-agent"))
    }

    @Test
    fun `all top-level routes are unique`() {
        val topRoutes = listOf(
            Routes.CHAT_LIST,
            Routes.DASHBOARD,
            Routes.CHANNELS_LIST,
            Routes.TOOLS_HUB,
            Routes.SETTINGS,
        )
        assertEquals(topRoutes.size, topRoutes.toSet().size, "Top-level routes must be unique")
    }

    @Test
    fun `all settings sub-routes start with settings prefix`() {
        val settingsRoutes = listOf(
            Routes.API_KEYS,
            Routes.MODELS,
            Routes.AGENTS_LIST,
            Routes.AGENT_DETAIL,
            Routes.PLUGINS,
            Routes.GATEWAY_SETTINGS,
            Routes.SESSION_SETTINGS,
            Routes.STORAGE_SETTINGS,
            Routes.SECURITY,
            Routes.LOGS,
            Routes.ABOUT,
        )
        for (route in settingsRoutes) {
            assertContains(route, "settings/", message = "Route $route should start with settings/")
        }
    }

    @Test
    fun `all tools sub-routes start with tools prefix`() {
        val toolsRoutes = listOf(
            Routes.TERMINAL,
            Routes.SKILLS,
            Routes.CRON,
            Routes.CRON_EDIT,
            Routes.APPROVALS,
            Routes.MEMORY,
            Routes.DEVICE_TOOLS,
        )
        for (route in toolsRoutes) {
            assertContains(route, "tools/", message = "Route $route should start with tools/")
        }
    }

    @Test
    fun `add channel route is under channels`() {
        assertContains(Routes.ADD_CHANNEL, "channels/")
    }
}
