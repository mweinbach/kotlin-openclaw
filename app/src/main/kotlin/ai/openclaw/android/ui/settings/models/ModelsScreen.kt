package ai.openclaw.android.ui.settings.models

import ai.openclaw.android.AgentEngine
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val fallbackModel = engine.availableModelIdsForEnabledProviders().firstOrNull()
        ?: "openai/gpt-4o-mini"
    val currentModel = engine.config.agents?.defaults?.model?.primary?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: fallbackModel

    var selectedModel by remember { mutableStateOf(currentModel) }
    var manualModel by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    val availableModels = engine.availableModelIdsForEnabledProviders()
        .let { models ->
            if (selectedModel in models) models else listOf(selectedModel) + models
        }
        .distinct()

    fun applyModel(modelId: String) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return
        selectedModel = normalized
        engine.setDefaultModel(normalized)
    }

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
                        text = "Tap to select from enabled providers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Manual Model ID",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = manualModel,
                onValueChange = { manualModel = it },
                label = { Text("provider/model") },
                placeholder = { Text("openai/gpt-5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        applyModel(manualModel)
                        manualModel = ""
                    },
                    enabled = manualModel.trim().isNotEmpty(),
                ) {
                    Text("Use Manual Model")
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
                if (availableModels.isEmpty()) {
                    Text("No models found for enabled providers. Add provider auth, then try again.")
                } else {
                    LazyColumn {
                        items(availableModels) { model ->
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
                                    applyModel(model)
                                    showPicker = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Close")
                }
            },
        )
    }
}
