package ai.openclaw.android.ui.settings.storage

import ai.openclaw.android.AgentEngine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember {
        mutableStateOf(
            AgentEngine.InstallStorageSnapshot(
                configFileExists = false,
                sessionCount = 0,
                cronStoreExists = false,
                secretCount = 0,
            ),
        )
    }
    var showConfirm by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshSnapshot() {
        scope.launch {
            runCatching { engine.storageSnapshot() }
                .onSuccess { snapshot = it }
                .onFailure { err -> statusMessage = err.message ?: "Unable to read storage snapshot." }
        }
    }

    LaunchedEffect(Unit) {
        refreshSnapshot()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
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
                text = "Local Storage Snapshot",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StorageDetailRow("Config file", if (snapshot.configFileExists) "present" else "missing")
                    StorageDetailRow("Persisted sessions", snapshot.sessionCount.toString())
                    StorageDetailRow("Cron store", if (snapshot.cronStoreExists) "present" else "missing")
                    StorageDetailRow("Stored secrets", snapshot.secretCount.toString())
                }
            }

            Text(
                text = "Factory Reset",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Wipes config, sessions, cron data, OAuth/secrets, and local database. " +
                            "After wipe, the engine restarts with default install state.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { refreshSnapshot() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isWorking,
                    ) {
                        Text("Refresh Snapshot")
                    }
                    Button(
                        onClick = { showConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isWorking,
                    ) {
                        Text(if (isWorking) "Wiping..." else "Wipe Local Storage")
                    }
                }
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isWorking) showConfirm = false },
            title = { Text("Wipe Local Storage?") },
            text = {
                Text("This clears all local app data and restarts to a fresh install state.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isWorking) return@TextButton
                        isWorking = true
                        statusMessage = null
                        scope.launch {
                            runCatching { engine.wipeInstallStorage() }
                                .onSuccess {
                                    statusMessage = "Local storage wiped and startup state reset."
                                    refreshSnapshot()
                                }
                                .onFailure { err ->
                                    statusMessage = err.message ?: "Failed to wipe local storage."
                                }
                            isWorking = false
                            showConfirm = false
                        }
                    },
                ) {
                    Text("Wipe")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isWorking) showConfirm = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun StorageDetailRow(label: String, value: String) {
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
