package ai.openclaw.android.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.runtime.engine.SessionPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatListScreen(
    engine: AgentEngine,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(engine))
    val sessions by viewModel.sessions.collectAsState()
    val headers by viewModel.sessionHeaders.collectAsState()
    var fabMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.refreshSessions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversations") },
                actions = {
                    IconButton(onClick = { viewModel.refreshSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
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
                            contentDescription = "New conversation",
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        fabMenuExpanded = false
                        onNewChat()
                    },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    text = { Text("New Conversation") },
                )
            }
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Chat,
                message = "No conversations yet",
                action = "Start a conversation",
                onAction = onNewChat,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it }) { sessionId ->
                    val header = headers[sessionId]
                    SessionCard(
                        sessionId = sessionId,
                        header = header,
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSessionClick(sessionId) 
                        },
                        onDelete = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteSession(sessionId) 
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    sessionId: String,
    header: SessionPersistence.SessionHeader?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = truncateId(sessionId),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Agent: ${header?.agentId ?: "default"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = header?.createdAt?.let { formatTimestamp(it) } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                header?.model?.let { model ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

private fun truncateId(id: String): String {
    return if (id.length > 24) "${id.take(8)}...${id.takeLast(8)}" else id
}

private fun formatTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return formatter.format(Date(millis))
}
