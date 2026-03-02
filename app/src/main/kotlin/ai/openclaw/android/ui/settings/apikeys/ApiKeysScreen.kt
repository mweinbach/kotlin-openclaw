package ai.openclaw.android.ui.settings.apikeys

import ai.openclaw.android.AgentEngine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class ApiKeyProvider(
    val name: String,
    val providerId: String,
    val requiresKey: Boolean = true,
)

private val providers = listOf(
    ApiKeyProvider("Anthropic", "anthropic"),
    ApiKeyProvider("OpenAI", "openai"),
    ApiKeyProvider("Gemini", "gemini"),
    ApiKeyProvider("Ollama", "ollama", requiresKey = false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(engine: AgentEngine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keyStatus by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var editingProvider by remember { mutableStateOf<ApiKeyProvider?>(null) }
    var editValue by remember { mutableStateOf("") }

    suspend fun refreshStatus() {
        val status = mutableMapOf<String, Boolean>()
        providers.forEach { provider ->
            status[provider.providerId] = when (provider.providerId) {
                "gemini" -> {
                    engine.secretStore.hasSecret("api_key_gemini") || engine.secretStore.hasSecret("api_key_google")
                }
                else -> engine.secretStore.hasSecret("api_key_${provider.providerId}")
            }
        }
        keyStatus = status
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(providers) { provider ->
                val isSet = keyStatus[provider.providerId] == true

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!provider.requiresKey) {
                                Text(
                                    text = "Local - no key needed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Surface(
                                    color = if (isSet) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = if (isSet) "Set" else "Not Set",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSet) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        },
                                    )
                                }
                            }
                        }
                        if (provider.requiresKey) {
                            Row {
                                IconButton(onClick = {
                                    editingProvider = provider
                                    editValue = ""
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                if (isSet) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            engine.clearApiKey(provider.providerId)
                                            refreshStatus()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingProvider != null) {
        val provider = editingProvider!!
        AlertDialog(
            onDismissRequest = { editingProvider = null },
            title = { Text("${provider.name} API Key") },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            engine.setApiKey(provider.providerId, editValue)
                            refreshStatus()
                            editingProvider = null
                        }
                    },
                    enabled = editValue.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingProvider = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
