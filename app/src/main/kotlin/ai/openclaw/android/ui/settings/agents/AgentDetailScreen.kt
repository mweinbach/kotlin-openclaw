package ai.openclaw.android.ui.settings.agents

import ai.openclaw.android.AgentEngine
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    engine: AgentEngine,
    agentId: String,
    onBack: () -> Unit,
) {
    val agent = engine.config.agents?.list?.firstOrNull { it.id == agentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent: ${agent?.name ?: agentId}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (agent == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Text(
                    text = "Agent not found: $agentId",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Basic Info
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Basic Info", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        DetailRow(label = "ID", value = agent.id)
                        DetailRow(label = "Name", value = agent.name ?: "-")
                        DetailRow(
                            label = "Default",
                            value = if (agent.default == true) "Yes" else "No",
                        )
                    }
                }

                // Identity
                agent.identity?.let { identity ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Identity", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            identity.name?.let { DetailRow(label = "Name", value = it) }
                            identity.theme?.let { DetailRow(label = "Theme", value = it) }
                        }
                    }
                }

                // Model
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Model", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        DetailRow(
                            label = "Primary",
                            value = agent.model?.primary ?: "(using defaults)",
                        )
                        val fallbacks = agent.model?.fallbacks.orEmpty()
                        if (fallbacks.isNotEmpty()) {
                            DetailRow(
                                label = "Fallbacks",
                                value = fallbacks.joinToString(", "),
                            )
                        }
                    }
                }

                // Tools
                val enabledTools = agent.tools?.enabled.orEmpty()
                val disabledTools = agent.tools?.disabled.orEmpty()
                if (enabledTools.isNotEmpty() || disabledTools.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Tools", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            if (enabledTools.isNotEmpty()) {
                                Text(
                                    text = "Enabled",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                enabledTools.forEach { tool ->
                                    Text(
                                        text = tool,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                                    )
                                }
                            }
                            if (disabledTools.isNotEmpty()) {
                                if (enabledTools.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Disabled",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                disabledTools.forEach { tool ->
                                    Text(
                                        text = tool,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
