package ai.openclaw.android.ui.navigation

import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.chat.ChatDetailScreen
import ai.openclaw.android.ui.chat.ChatListScreen
import ai.openclaw.android.ui.channels.AddChannelScreen
import ai.openclaw.android.ui.channels.ChannelDetailScreen
import ai.openclaw.android.ui.channels.ChannelsScreen
import ai.openclaw.android.ui.dashboard.DashboardScreen
import ai.openclaw.android.ui.settings.*
import ai.openclaw.android.ui.settings.about.AboutScreen
import ai.openclaw.android.ui.settings.agents.AgentDetailScreen
import ai.openclaw.android.ui.settings.agents.AgentsScreen
import ai.openclaw.android.ui.settings.apikeys.ApiKeysScreen
import ai.openclaw.android.ui.settings.codexoauth.CodexOauthScreen
import ai.openclaw.android.ui.settings.gateway.GatewaySettingsScreen
import ai.openclaw.android.ui.settings.logs.LogsScreen
import ai.openclaw.android.ui.settings.models.ModelsScreen
import ai.openclaw.android.ui.settings.plugins.PluginsScreen
import ai.openclaw.android.ui.settings.security.SecurityScreen
import ai.openclaw.android.ui.settings.sessions.SessionSettingsScreen
import ai.openclaw.android.ui.tools.ToolsHubScreen
import ai.openclaw.android.ui.tools.approvals.ApprovalsScreen
import ai.openclaw.android.ui.tools.cron.CronEditSheet
import ai.openclaw.android.ui.tools.cron.CronScreen
import ai.openclaw.android.ui.tools.devices.DeviceToolsScreen
import ai.openclaw.android.ui.tools.memory.MemoryScreen
import ai.openclaw.android.ui.tools.skills.SkillsScreen
import ai.openclaw.android.ui.tools.terminal.TerminalScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(engine: AgentEngine) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            BottomNavItem.items.forEach { item ->
                val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                item(
                    icon = {
                        Icon(
                            if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                        )
                    },
                    label = { Text(item.label) },
                    selected = selected,
                    onClick = {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT_LIST,
        ) {
            // Chat tab
            composable(Routes.CHAT_LIST) {
                ChatListScreen(
                    engine = engine,
                    onSessionClick = { sessionId ->
                        navController.navigate(Routes.chatDetail(sessionId))
                    },
                    onNewChat = {
                        navController.navigate(Routes.chatDetail("new"))
                    },
                )
            }
            composable(
                Routes.CHAT_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: "new"
                ChatDetailScreen(
                    engine = engine,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }

            // Dashboard tab
            composable(Routes.DASHBOARD) {
                DashboardScreen(engine = engine)
            }

            // Channels tab
            composable(Routes.CHANNELS_LIST) {
                ChannelsScreen(
                    engine = engine,
                    onChannelClick = { key ->
                        navController.navigate(Routes.channelDetail(key))
                    },
                    onAddChannel = {
                        navController.navigate(Routes.ADD_CHANNEL)
                    },
                )
            }
            composable(
                Routes.CHANNEL_DETAIL,
                arguments = listOf(navArgument("channelKey") { type = NavType.StringType }),
            ) { backStackEntry ->
                val channelKey = backStackEntry.arguments?.getString("channelKey") ?: return@composable
                ChannelDetailScreen(
                    engine = engine,
                    channelKey = channelKey,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ADD_CHANNEL) {
                AddChannelScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            // Tools tab
            composable(Routes.TOOLS_HUB) {
                ToolsHubScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.TERMINAL) {
                TerminalScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SKILLS) {
                SkillsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CRON) {
                CronScreen(
                    engine = engine,
                    onEditJob = { jobId -> navController.navigate(Routes.cronEdit(jobId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.CRON_EDIT,
                arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val jobId = backStackEntry.arguments?.getString("jobId") ?: "new"
                CronEditSheet(
                    engine = engine,
                    jobId = jobId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.APPROVALS) {
                ApprovalsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.MEMORY) {
                MemoryScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.DEVICE_TOOLS) {
                DeviceToolsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }

            // Settings tab
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.API_KEYS) {
                ApiKeysScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CODEX_OAUTH) {
                CodexOauthScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.MODELS) {
                ModelsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.AGENTS_LIST) {
                AgentsScreen(
                    engine = engine,
                    onAgentClick = { agentId ->
                        navController.navigate(Routes.agentDetail(agentId))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.AGENT_DETAIL,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: return@composable
                AgentDetailScreen(
                    engine = engine,
                    agentId = agentId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PLUGINS) {
                PluginsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.GATEWAY_SETTINGS) {
                GatewaySettingsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SESSION_SETTINGS) {
                SessionSettingsScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SECURITY) {
                SecurityScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.LOGS) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ABOUT) {
                AboutScreen(
                    engine = engine,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
