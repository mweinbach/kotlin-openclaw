package ai.openclaw.android.ui.settings.apikeys

import ai.openclaw.android.AgentEngine
import ai.openclaw.core.security.SecretCategory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class ApiKeyProvider(
    val name: String,
    val key: String,
    val requiresKey: Boolean = true,
)

private val providers = listOf(
    ApiKeyProvider("Anthropic", "api_key_anthropic"),
    ApiKeyProvider("OpenAI", "api_key_openai"),
    ApiKeyProvider("Gemini", "api_key_gemini"),
    ApiKeyProvider("Ollama", "api_key_ollama", requiresKey = false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(engine: AgentEngine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keyStatus by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var editingProvider by remember { mutableStateOf<ApiKeyProvider?>(null) }
    var editValue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val status = mutableMapOf<String, Boolean>()
        providers.forEach { provider ->
            status[provider.key] = engine.secretStore.hasSecret(provider.key)
        }
        keyStatus = status
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
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(providers) { provider ->
                val isSet = keyStatus[provider.key] == true

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
                                            engine.secretStore.deleteSecret(provider.key)
                                            keyStatus = keyStatus.toMutableMap().apply {
                                                put(provider.key, false)
                                            }
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

    // Edit dialog
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
                            engine.secretStore.storeSecret(
                                provider.key,
                                editValue,
                                SecretCategory.LLM_API_KEY,
                            )
                            keyStatus = keyStatus.toMutableMap().apply {
                                put(provider.key, true)
                            }
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
