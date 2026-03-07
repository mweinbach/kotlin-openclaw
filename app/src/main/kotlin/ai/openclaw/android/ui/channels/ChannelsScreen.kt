package ai.openclaw.android.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.android.ui.components.SearchTopBar
import ai.openclaw.android.ui.components.Status
import ai.openclaw.android.ui.components.StatusIndicator
import ai.openclaw.runtime.gateway.ChannelManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    engine: AgentEngine,
    onChannelClick: (String) -> Unit,
    onAddChannel: () -> Unit,
) {
    val vm: ChannelsViewModel = viewModel(factory = ChannelsViewModel.Factory(engine))
    val channels by vm.channels.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val filtered = if (searchQuery.isBlank()) {
        channels
    } else {
        channels.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.type.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                title = "Channels",
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabMenuExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = !fabMenuExpanded },
                    ) {
                        Icon(
                            if (fabMenuExpanded) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Add channel",
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        fabMenuExpanded = false
                        onAddChannel()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Channel") },
                )
            }
        },
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Hub,
                message = "No channels configured.\nAdd a channel to connect your agent.",
                action = "Add Channel",
                onAction = onAddChannel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(filtered, key = { it.key }) { item ->
                    ChannelRow(
                        item = item,
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChannelClick(item.key) 
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    item: ChannelsViewModel.ChannelUiItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = statusLabel(item.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.error != null) {
                    Text(
                        text = item.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = channelIcon(item.type),
                contentDescription = item.type,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusIndicator(
                    status = channelStatusToStatus(item.status),
                    size = 10.dp,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = item.type,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        },
    )
}

private fun channelIcon(type: String) = when (type) {
    "telegram" -> Icons.Default.Send
    "discord" -> Icons.Default.SportsEsports
    "slack" -> Icons.Default.Tag
    "signal" -> Icons.Default.Lock
    "matrix" -> Icons.Default.GridView
    "irc" -> Icons.Default.Terminal
    "googlechat" -> Icons.Default.Chat
    "whatsapp" -> Icons.Default.Phone
    "msteams" -> Icons.Default.Groups
    "imessage" -> Icons.Default.Message
    "line" -> Icons.Default.ChatBubble
    "mattermost" -> Icons.Default.Forum
    "nostr" -> Icons.Default.Bolt
    "webchat" -> Icons.Default.Language
    else -> Icons.Default.Hub
}

private fun channelStatusToStatus(status: ChannelManager.ChannelStatus?): Status {
    return when (status) {
        ChannelManager.ChannelStatus.RUNNING -> Status.Connected
        ChannelManager.ChannelStatus.ERROR -> Status.Error
        ChannelManager.ChannelStatus.STARTING,
        ChannelManager.ChannelStatus.RESTARTING -> Status.Warning
        ChannelManager.ChannelStatus.STOPPED,
        null -> Status.Offline
    }
}

private fun statusLabel(status: ChannelManager.ChannelStatus?): String {
    return when (status) {
        ChannelManager.ChannelStatus.RUNNING -> "Running"
        ChannelManager.ChannelStatus.ERROR -> "Error"
        ChannelManager.ChannelStatus.STARTING -> "Starting"
        ChannelManager.ChannelStatus.RESTARTING -> "Restarting"
        ChannelManager.ChannelStatus.STOPPED -> "Stopped"
        null -> "Not started"
    }
}
