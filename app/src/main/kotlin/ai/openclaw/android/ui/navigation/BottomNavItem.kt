package ai.openclaw.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    data object Chat : BottomNavItem("chat", "Chat", Icons.Outlined.Chat, Icons.Filled.Chat)
    data object Dashboard : BottomNavItem("dashboard", "Dashboard", Icons.Outlined.Dashboard, Icons.Filled.Dashboard)
    data object Channels : BottomNavItem("channels", "Channels", Icons.Outlined.Hub, Icons.Filled.Hub)
    data object Tools : BottomNavItem("tools", "Tools", Icons.Outlined.Terminal, Icons.Filled.Terminal)
    data object Settings : BottomNavItem("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)

    companion object {
        val items = listOf(Chat, Dashboard, Channels, Tools, Settings)
    }
}

/** Nested routes within each tab. */
object Routes {
    // Chat
    const val CHAT_LIST = "chat"
    const val CHAT_DETAIL = "chat/{sessionId}"
    fun chatDetail(sessionId: String) = "chat/$sessionId"

    // Dashboard
    const val DASHBOARD = "dashboard"

    // Channels
    const val CHANNELS_LIST = "channels"
    const val CHANNEL_DETAIL = "channels/{channelKey}"
    const val ADD_CHANNEL = "channels/add"
    fun channelDetail(channelKey: String) = "channels/$channelKey"

    // Tools
    const val TOOLS_HUB = "tools"
    const val TERMINAL = "tools/terminal"
    const val SKILLS = "tools/skills"
    const val CRON = "tools/cron"
    const val CRON_EDIT = "tools/cron/{jobId}"
    const val APPROVALS = "tools/approvals"
    const val MEMORY = "tools/memory"
    const val DEVICE_TOOLS = "tools/devices"
    fun cronEdit(jobId: String) = "tools/cron/$jobId"

    // Settings
    const val SETTINGS = "settings"
    const val API_KEYS = "settings/apikeys"
    const val CODEX_OAUTH = "settings/codex-oauth"
    const val MODELS = "settings/models"
    const val AGENTS_LIST = "settings/agents"
    const val AGENT_DETAIL = "settings/agents/{agentId}"
    const val PLUGINS = "settings/plugins"
    const val GATEWAY_SETTINGS = "settings/gateway"
    const val SESSION_SETTINGS = "settings/sessions"
    const val STORAGE_SETTINGS = "settings/storage"
    const val SECURITY = "settings/security"
    const val LOGS = "settings/logs"
    const val ABOUT = "settings/about"
    fun agentDetail(agentId: String) = "settings/agents/$agentId"
}
