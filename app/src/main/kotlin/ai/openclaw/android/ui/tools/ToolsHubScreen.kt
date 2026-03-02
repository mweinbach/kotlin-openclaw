package ai.openclaw.android.ui.tools

import ai.openclaw.android.ui.navigation.Routes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class ToolCategory(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
)

private val categories = listOf(
    ToolCategory("Terminal", "Shell access", Icons.Default.Terminal, Routes.TERMINAL),
    ToolCategory("Skills", "Agent skills", Icons.Default.Psychology, Routes.SKILLS),
    ToolCategory("Cron Jobs", "Scheduled tasks", Icons.Default.Schedule, Routes.CRON),
    ToolCategory("Approvals", "Pending approvals", Icons.Default.CheckCircle, Routes.APPROVALS),
    ToolCategory("Memory", "Vector memory", Icons.Default.Memory, Routes.MEMORY),
    ToolCategory("Device Tools", "Device capabilities", Icons.Default.PhoneAndroid, Routes.DEVICE_TOOLS),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolsHubScreen(onNavigate: (String) -> Unit) {
    val gridState = rememberLazyGridState()
    var toolbarExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(gridState.isScrollInProgress) {
        toolbarExpanded = !gridState.isScrollInProgress
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tools") })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            ) {
                items(categories) { category ->
                    ToolCategoryCard(
                        category = category,
                        onClick = { onNavigate(category.route) },
                    )
                }
            }

            HorizontalFloatingToolbar(
                expanded = toolbarExpanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { onNavigate(Routes.TERMINAL) },
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                    }
                },
                floatingActionButtonPosition = FloatingToolbarHorizontalFabPosition.End,
            ) {
                IconButton(onClick = { onNavigate(Routes.APPROVALS) }) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Approvals")
                }
                IconButton(onClick = { onNavigate(Routes.CRON) }) {
                    Icon(Icons.Default.Schedule, contentDescription = "Cron Jobs")
                }
            }
        }
    }
}

@Composable
private fun ToolCategoryCard(
    category: ToolCategory,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = category.name,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
