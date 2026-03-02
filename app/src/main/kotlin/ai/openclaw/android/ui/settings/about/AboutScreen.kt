package ai.openclaw.android.ui.settings.about

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.Status
import ai.openclaw.android.ui.components.StatusIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(engine: AgentEngine, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // App name and version
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "OpenClaw",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Version 0.1.0",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Engine status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Engine Status", style = MaterialTheme.typography.titleMedium)
                        StatusIndicator(status = Status.Connected)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    val agentCount = engine.config.agents?.list?.size ?: 0
                    val pluginCount = engine.pluginRegistry.allRecords().size
                    val providerNames = buildList {
                        if (engine.providerRegistry.get("anthropic") != null) add("Anthropic")
                        if (engine.providerRegistry.get("openai") != null) add("OpenAI")
                        if (engine.providerRegistry.get("gemini") != null) add("Gemini")
                        if (engine.providerRegistry.get("ollama") != null) add("Ollama")
                    }

                    DetailRow(label = "Agents", value = "$agentCount configured")
                    DetailRow(label = "Plugins", value = "$pluginCount loaded")
                    DetailRow(label = "Providers", value = "${providerNames.size} registered")
                }
            }

            // Config info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow(
                        label = "Config Path",
                        value = "files/config/openclaw.json",
                    )
                    DetailRow(
                        label = "Sessions Path",
                        value = "files/sessions/",
                    )
                    engine.config.meta?.lastTouchedVersion?.let {
                        DetailRow(label = "Last Config Version", value = it)
                    }
                    engine.config.meta?.lastTouchedAt?.let {
                        DetailRow(label = "Last Modified", value = it)
                    }
                }
            }

            // Build info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Build Info", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow(label = "Platform", value = "Android")
                    DetailRow(label = "Runtime", value = "Kotlin/JVM")
                    DetailRow(label = "Compose", value = "Material3")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
