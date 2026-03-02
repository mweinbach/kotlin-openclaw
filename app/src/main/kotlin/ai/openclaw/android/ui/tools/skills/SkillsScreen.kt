package ai.openclaw.android.ui.tools.skills

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.android.ui.components.SearchTopBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(engine: AgentEngine, onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val allTools = remember { engine.toolRegistry.all() }
    val filteredTools = remember(searchQuery) {
        if (searchQuery.isBlank()) allTools
        else allTools.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                title = "Skills",
                onBack = onBack,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
            )
        },
    ) { padding ->
        if (filteredTools.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Psychology,
                message = if (searchQuery.isBlank()) "No tools registered" else "No matching tools",
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
                items(filteredTools, key = { it.name }) { tool ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (tool.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = tool.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
