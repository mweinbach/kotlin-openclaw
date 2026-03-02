package ai.openclaw.android.ui.settings.plugins

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val plugins = engine.pluginRegistry.allRecords()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (plugins.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Extension,
                message = "No plugins loaded",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(plugins) { plugin ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = plugin.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    plugin.version?.let { version ->
                                        Text(
                                            text = "v$version",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val statusColor = when {
                                    plugin.error != null -> MaterialTheme.colorScheme.errorContainer
                                    plugin.enabled -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                val statusTextColor = when {
                                    plugin.error != null -> MaterialTheme.colorScheme.onErrorContainer
                                    plugin.enabled -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    plugin.error != null -> "Error"
                                    plugin.enabled -> "Enabled"
                                    else -> "Disabled"
                                }
                                Surface(
                                    color = statusColor,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = statusText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusTextColor,
                                    )
                                }
                            }
                            plugin.description?.let { desc ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            plugin.error?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val capabilities = buildList {
                                if (plugin.toolNames.isNotEmpty()) add("${plugin.toolNames.size} tools")
                                if (plugin.hookNames.isNotEmpty()) add("${plugin.hookNames.size} hooks")
                                if (plugin.channelIds.isNotEmpty()) add("${plugin.channelIds.size} channels")
                            }
                            if (capabilities.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = capabilities.joinToString(" | "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
