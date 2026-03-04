package ai.openclaw.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.navigation.Routes

private data class SettingsItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
)

private data class SettingsGroup(
    val header: String,
    val items: List<SettingsItem>,
)

private val settingsGroups = listOf(
    SettingsGroup(
        header = "Configuration",
        items = listOf(
            SettingsItem("API Keys", "Manage LLM provider API keys", Icons.Default.Key, Routes.API_KEYS),
            SettingsItem("Codex OAuth", "Sign in to OpenAI/Codex with browser OAuth", Icons.Default.LockOpen, Routes.CODEX_OAUTH),
            SettingsItem("Models", "Configure default and available models", Icons.Default.ModelTraining, Routes.MODELS),
            SettingsItem("Agents", "View and configure agents", Icons.Default.SmartToy, Routes.AGENTS_LIST),
        ),
    ),
    SettingsGroup(
        header = "System",
        items = listOf(
            SettingsItem("Plugins", "Manage loaded plugins", Icons.Default.Extension, Routes.PLUGINS),
            SettingsItem("Gateway", "Gateway server configuration", Icons.Default.Router, Routes.GATEWAY_SETTINGS),
            SettingsItem("Sessions", "Session management settings", Icons.Default.Forum, Routes.SESSION_SETTINGS),
            SettingsItem("Storage", "Inspect local data and wipe app state", Icons.Default.Storage, Routes.STORAGE_SETTINGS),
        ),
    ),
    SettingsGroup(
        header = "Security & Monitoring",
        items = listOf(
            SettingsItem("Security", "Approval and tool policies", Icons.Default.Shield, Routes.SECURITY),
            SettingsItem("Logs", "View application logs", Icons.AutoMirrored.Filled.Article, Routes.LOGS),
        ),
    ),
    SettingsGroup(
        header = "Info",
        items = listOf(
            SettingsItem("About", "App version and build info", Icons.Default.Info, Routes.ABOUT),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            settingsGroups.forEach { group ->
                item {
                    Text(
                        text = group.header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
                items(group.items) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(item.subtitle) },
                        leadingContent = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Navigate",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.clickable { onNavigate(item.route) },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
