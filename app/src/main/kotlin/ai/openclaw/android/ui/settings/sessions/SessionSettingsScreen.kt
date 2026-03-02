package ai.openclaw.android.ui.settings.sessions

import ai.openclaw.android.AgentEngine
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
fun SessionSettingsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val session = engine.config.session
    val sessionCount = remember {
        try {
            engine.sessionPersistence.listSessionKeys().size
        } catch (_: Exception) {
            0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
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

            // Active Sessions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Active Sessions", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$sessionCount session(s)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Configuration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            // Session config
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailRow(
                        label = "Scope",
                        value = session?.scope?.name ?: "per-sender",
                    )
                    DetailRow(
                        label = "Idle Timeout",
                        value = "${session?.idleMinutes ?: 30} minutes",
                    )
                    DetailRow(
                        label = "Store",
                        value = session?.store ?: "file",
                    )
                    DetailRow(
                        label = "Typing Mode",
                        value = session?.typingMode?.name ?: "thinking",
                    )
                }
            }

            // Reset config
            session?.reset?.let { reset ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Reset Policy", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(
                            label = "Mode",
                            value = reset.mode?.name ?: "idle",
                        )
                        reset.idleMinutes?.let {
                            DetailRow(label = "Idle Minutes", value = it.toString())
                        }
                        reset.atHour?.let {
                            DetailRow(label = "Reset At Hour", value = "$it:00")
                        }
                    }
                }
            }

            // Maintenance config
            session?.maintenance?.let { maintenance ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(
                            label = "Mode",
                            value = maintenance.mode?.name ?: "warn",
                        )
                        maintenance.pruneDays?.let {
                            DetailRow(label = "Prune After", value = "$it days")
                        }
                        maintenance.maxEntries?.let {
                            DetailRow(label = "Max Entries", value = it.toString())
                        }
                    }
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
