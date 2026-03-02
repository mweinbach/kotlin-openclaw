package ai.openclaw.android.ui.dashboard

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.SectionCard
import ai.openclaw.android.ui.components.Status
import ai.openclaw.android.ui.components.StatusIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen(engine: AgentEngine) {
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(engine),
    )
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    var toolbarExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(scrollState.isScrollInProgress) {
        toolbarExpanded = !scrollState.isScrollInProgress
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Gateway Card
                SectionCard(
                    title = "Gateway",
                    subtitle = "Port ${state.gatewayPort}",
                    trailing = {
                        StatusIndicator(
                            status = if (state.gatewayRunning) Status.Connected else Status.Offline,
                        )
                    },
                ) {
                    Text(
                        text = if (state.gatewayRunning) "Running on ${state.gatewayHost}:${state.gatewayPort}" else "Stopped",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Channels Card
                SectionCard(
                    title = "Channels",
                    subtitle = "${state.connectedChannels}/${state.channelCount} connected",
                    trailing = {
                        StatusIndicator(
                            status = when {
                                state.errorChannels > 0 -> Status.Error
                                state.connectedChannels == state.channelCount && state.channelCount > 0 -> Status.Connected
                                state.channelCount == 0 -> Status.Offline
                                else -> Status.Warning
                            },
                        )
                    },
                ) {
                    if (state.errorChannels > 0) {
                        Text(
                            text = "${state.errorChannels} channel(s) in error state",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Sessions Card
                SectionCard(
                    title = "Sessions",
                    subtitle = "${state.sessionCount} active",
                )

                // Memory Card
                SectionCard(
                    title = "Memory",
                    subtitle = if (state.memoryEntries > 0) "${state.memoryEntries} entries" else "No entries",
                )

                // Cron Card
                SectionCard(
                    title = "Cron Jobs",
                    subtitle = "${state.cronJobCount} scheduled",
                )

                // Plugins Card
                SectionCard(
                    title = "Plugins",
                    subtitle = "${state.pluginCount} loaded",
                )

                // Extra space for floating toolbar
                Spacer(modifier = Modifier.height(72.dp))
            }

            HorizontalFloatingToolbar(
                expanded = toolbarExpanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { viewModel.refresh() },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                floatingActionButtonPosition = FloatingToolbarHorizontalFabPosition.End,
            ) {
                IconButton(onClick = { viewModel.reloadConfig() }) {
                    Icon(Icons.Default.Sync, contentDescription = "Reload Config")
                }
                IconButton(onClick = { viewModel.clearSessions() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Sessions")
                }
            }
        }
    }
}
