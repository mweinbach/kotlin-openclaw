package ai.openclaw.android.ui.tools.memory

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.android.ui.components.SearchTopBar
import ai.openclaw.runtime.memory.MemorySearchResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(engine: AgentEngine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MemorySearchResult>()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        totalCount = engine.memoryManager.size()
    }

    // Search when query changes
    LaunchedEffect(searchQuery) {
        results = if (searchQuery.isNotBlank()) {
            engine.memoryManager.search(searchQuery, topK = 20)
        } else {
            emptyList()
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onSave = { content, source ->
                scope.launch {
                    engine.memoryManager.store(content = content, source = source)
                    totalCount = engine.memoryManager.size()
                    showAddDialog = false
                }
            },
        )
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                title = "Memory",
                onBack = onBack,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                actions = {
                    Text(
                        text = "$totalCount entries",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add memory")
            }
        },
    ) { padding ->
        if (searchQuery.isBlank() && totalCount == 0) {
            EmptyState(
                icon = Icons.Default.Memory,
                message = "No memory entries stored",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                action = "Add Entry",
                onAction = { showAddDialog = true },
            )
        } else if (searchQuery.isBlank()) {
            EmptyState(
                icon = Icons.Default.Memory,
                message = "Search to find memories",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else if (results.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Memory,
                message = "No results for \"$searchQuery\"",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.entry.id }) { result ->
                    MemoryResultCard(
                        result = result,
                        onDelete = {
                            scope.launch {
                                engine.memoryManager.remove(result.entry.id)
                                totalCount = engine.memoryManager.size()
                                results = if (searchQuery.isNotBlank()) {
                                    engine.memoryManager.search(searchQuery, topK = 20)
                                } else {
                                    emptyList()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryResultCard(
    result: MemorySearchResult,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.entry.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "%.2f".format(result.score),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.entry.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(result.entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDelete) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (content: String, source: String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("manual") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Source") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content, source) },
                enabled = content.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}
