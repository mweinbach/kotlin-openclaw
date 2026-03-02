package ai.openclaw.android.ui.settings.security

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
fun SecurityScreen(engine: AgentEngine, onBack: () -> Unit) {
    val approvals = engine.config.approvals
    val execForwarding = approvals?.exec

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
                text = "Approval Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            // Exec approval forwarding
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Execution Approval Forwarding", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Enabled",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Surface(
                            color = if (execForwarding?.enabled == true) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = if (execForwarding?.enabled == true) "On" else "Off",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    execForwarding?.mode?.let { mode ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = mode.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    val targets = execForwarding?.targets.orEmpty()
                    if (targets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Forwarding Targets",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        targets.forEach { target ->
                            Text(
                                text = "${target.channel} -> ${target.to}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }

            // Tool profile
            Text(
                text = "Tool Security",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tool Profile", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val profile = engine.config.tools?.profile
                    val allowList = engine.config.tools?.allow.orEmpty()
                    val denyList = engine.config.tools?.deny.orEmpty()
                    Text(
                        text = when {
                            profile != null -> "${profile.name} profile"
                            denyList.isNotEmpty() -> "${denyList.size} tools denied"
                            allowList.isNotEmpty() -> "${allowList.size} tools allowed"
                            else -> "FULL - All tools enabled"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Secret store stats
            Text(
                text = "Secret Store",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Stored Secrets", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    var secretCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        secretCount = engine.secretStore.listSecretKeys().size
                    }
                    Text(
                        text = "$secretCount key(s) stored",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
