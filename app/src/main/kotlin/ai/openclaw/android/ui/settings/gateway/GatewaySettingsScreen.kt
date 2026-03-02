package ai.openclaw.android.ui.settings.gateway

import ai.openclaw.android.AgentEngine
import ai.openclaw.core.model.GatewayConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewaySettingsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val gateway = engine.config.gateway
    var port by remember { mutableStateOf((gateway?.port ?: 18789).toString()) }
    var host by remember { mutableStateOf(gateway?.customBindHost ?: "127.0.0.1") }
    var hasChanges by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateway") },
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
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Server Configuration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it
                    hasChanges = true
                },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    hasChanges = true
                },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Auth mode display
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Authentication", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val authMode = gateway?.auth?.mode?.name ?: "None"
                    Text(
                        text = "Mode: $authMode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // TLS status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("TLS", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val tlsEnabled = gateway?.tls?.enabled == true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = if (tlsEnabled) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = if (tlsEnabled) "Enabled" else "Disabled",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (tlsEnabled) {
                        gateway?.tls?.certPath?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cert: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Save button
            Button(
                onClick = {
                    val portInt = port.toIntOrNull() ?: 18789
                    val currentGateway = engine.config.gateway ?: GatewayConfig()
                    val newConfig = engine.config.copy(
                        gateway = currentGateway.copy(
                            port = portInt,
                            customBindHost = host,
                        ),
                    )
                    engine.saveConfig(newConfig)
                    hasChanges = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasChanges,
            ) {
                Text("Save")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
