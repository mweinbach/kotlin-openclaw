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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
    var contentVisible by remember { mutableStateOf(false) }
    var showToolchainSetupDetails by remember { mutableStateOf(false) }
    var customBundleUrl by remember(state.customNodeDownloadUrl) {
        mutableStateOf(state.customNodeDownloadUrl.orEmpty())
    }
    var customBundleSha by remember(state.customNodeSha256) {
        mutableStateOf(state.customNodeSha256.orEmpty())
    }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        contentVisible = true
    }

    LaunchedEffect(scrollState.isScrollInProgress) {
        toolbarExpanded = !scrollState.isScrollInProgress
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.refresh() 
                    }) {
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
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400), initialOffsetY = { it / 4 }),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                SectionCard(
                    title = "Background Runtime",
                    subtitle = if (state.backgroundRuntimeActive) "Active" else "Stopped",
                    trailing = {
                        Switch(
                            checked = state.keepAliveInBackground,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setKeepAliveInBackground(context, enabled)
                            },
                        )
                    },
                ) {
                    Text(
                        text = if (state.keepAliveInBackground) {
                            "Foreground service will restart on app launch and boot."
                        } else {
                            "Background work is opt-in. Start it explicitly when needed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.backgroundRuntimeActive) {
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.stopBackgroundRuntime(context)
                                },
                            ) {
                                Text("Stop")
                            }
                        } else {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.startBackgroundRuntime(context)
                                },
                            ) {
                                Text("Start")
                            }
                        }
                    }
                }

                SectionCard(
                    title = "Toolchains",
                    subtitle = when {
                        state.nodeActive && !state.nodeVersion.isNullOrBlank() -> "Node ${state.nodeVersion}"
                        state.nodeActive -> "Node available"
                        state.nodeSupported -> "Node missing"
                        else -> "Setup required"
                    },
                    trailing = {
                        StatusIndicator(
                            status = when {
                                state.nodeActive && state.missingEssentialBins.isEmpty() -> Status.Connected
                                state.nodeSupported -> Status.Warning
                                else -> Status.Offline
                            },
                        )
                    },
                ) {
                    val nodeSource = when {
                        state.nodeManaged -> "Managed runtime active."
                        state.nodeActive -> "Shell/runtime Node active."
                        else -> "Node is not available in the current exec environment."
                    }
                    Text(
                        text = nodeSource,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val nodeMessage = state.nodeMessage
                    if (nodeMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = nodeMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.availableBins.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Available: ${state.availableBins.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.missingEssentialBins.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Missing JS bins: ${state.missingEssentialBins.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.missingRecommendedBins.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Recommended host bins missing: ${state.missingRecommendedBins.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!state.customNodeDownloadUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Custom bundle configured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val customBundleUrlTrimmed = customBundleUrl.trim()
                    val customBundleShaTrimmed = customBundleSha.trim().lowercase()
                    val customBundleValid = customBundleUrlTrimmed.startsWith("https://") ||
                        customBundleUrlTrimmed.startsWith("http://")
                    val customBundleShaValid = customBundleShaTrimmed.matches(Regex("^[0-9a-f]{64}$"))
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        enabled = !state.toolchainActionInProgress,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.nodeSupported) {
                                viewModel.installManagedNode()
                            } else {
                                showToolchainSetupDetails = true
                            }
                        },
                    ) {
                        Text(
                            if (state.toolchainActionInProgress) {
                                "Working..."
                            } else if (state.nodeInstalled) {
                                "Reinstall Node"
                            } else if (state.nodeSupported) {
                                "Install Node"
                            } else {
                                "Setup Node"
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !state.toolchainActionInProgress,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showToolchainSetupDetails = !showToolchainSetupDetails
                        },
                    ) {
                        Text(
                            if (showToolchainSetupDetails) {
                                "Hide Custom Bundle"
                            } else if (state.customNodeDownloadUrl.isNullOrBlank()) {
                                "Configure Custom Bundle"
                            } else {
                                "Edit Custom Bundle"
                            },
                        )
                    }
                    if (showToolchainSetupDetails) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Custom Android Bundle",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Save a verified Node bundle URL and SHA-256 here. Once saved, this device can use that bundle for managed installs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "nvm is shell-based and installs desktop/server Node distributions, so it is not a direct on-device Android solution inside the app sandbox.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customBundleUrl,
                            onValueChange = { customBundleUrl = it },
                            label = { Text("Bundle URL") },
                            placeholder = { Text("https://example.com/node-android-arm64.tar.xz") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.toolchainActionInProgress,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customBundleSha,
                            onValueChange = { customBundleSha = it },
                            label = { Text("SHA-256") },
                            placeholder = { Text("64 hex characters") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.toolchainActionInProgress,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Supported archive types: .zip, .tar.gz, and .tar.xz. The bundle must contain node plus npm/npx/corepack or the standard Node companion scripts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if ((customBundleUrlTrimmed.isNotEmpty() && !customBundleValid) ||
                            (customBundleShaTrimmed.isNotEmpty() && !customBundleShaValid)
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Enter an http(s) URL and a 64-character SHA-256 hash.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !state.toolchainActionInProgress &&
                                    !state.customNodeDownloadUrl.isNullOrBlank(),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    customBundleUrl = ""
                                    customBundleSha = ""
                                    viewModel.clearCustomManagedNodeBundle()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Clear")
                            }
                            OutlinedButton(
                                enabled = !state.toolchainActionInProgress &&
                                    customBundleValid &&
                                    customBundleShaValid,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.saveCustomManagedNodeBundle(
                                        downloadUrl = customBundleUrlTrimmed,
                                        sha256 = customBundleShaTrimmed,
                                        installAfterSave = false,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Save Setup")
                            }
                            Button(
                                enabled = !state.toolchainActionInProgress &&
                                    customBundleValid &&
                                    customBundleShaValid,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.saveCustomManagedNodeBundle(
                                        downloadUrl = customBundleUrlTrimmed,
                                        sha256 = customBundleShaTrimmed,
                                        installAfterSave = true,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Save + Install")
                            }
                        }
                    }
                }

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

                if (state.lastError != null) {
                    SectionCard(
                        title = "Last Error",
                        subtitle = state.lastError ?: "",
                    ) {
                        Text(
                            text = "Dashboard metrics may be incomplete until the next successful refresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            }

            HorizontalFloatingToolbar(
                expanded = toolbarExpanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.refresh() 
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                floatingActionButtonPosition = FloatingToolbarHorizontalFabPosition.End,
            ) {
                IconButton(onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.reloadConfig() 
                }) {
                    Icon(Icons.Default.Sync, contentDescription = "Reload Config")
                }
                IconButton(onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.clearSessions() 
                }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Sessions")
                }
            }
        }
    }

}
