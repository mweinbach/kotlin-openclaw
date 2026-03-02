package ai.openclaw.android.ui.settings.models

import ai.openclaw.android.AgentEngine
import ai.openclaw.core.model.AgentDefaultsConfig
import ai.openclaw.core.model.AgentModelConfig
import ai.openclaw.core.model.AgentsConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val commonModels = listOf(
    "claude-sonnet-4-5-20250514",
    "claude-haiku-4-5-20251001",
    "gpt-4o",
    "gpt-4o-mini",
    "gemini-2.0-flash",
    "ollama/llama3",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val currentModel = engine.config.agents?.defaults?.model?.primary ?: "claude-sonnet-4-5-20250514"
    var selectedModel by remember { mutableStateOf(currentModel) }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Default Model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPicker = true },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Primary Model",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedModel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to change the default model for all agents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val fallbacks = engine.config.agents?.defaults?.model?.fallbacks.orEmpty()
            if (fallbacks.isNotEmpty()) {
                Text(
                    text = "Fallback Models",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                fallbacks.forEach { fallback ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = fallback,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Select Model") },
            text = {
                LazyColumn {
                    items(commonModels) { model ->
                        ListItem(
                            headlineContent = { Text(model) },
                            trailingContent = {
                                if (model == selectedModel) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                selectedModel = model
                                val currentAgents = engine.config.agents ?: AgentsConfig()
                                val currentDefaults = currentAgents.defaults ?: AgentDefaultsConfig()
                                val newConfig = engine.config.copy(
                                    agents = currentAgents.copy(
                                        defaults = currentDefaults.copy(
                                            model = AgentModelConfig(primary = model),
                                        ),
                                    ),
                                )
                                engine.saveConfig(newConfig)
                                showPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
