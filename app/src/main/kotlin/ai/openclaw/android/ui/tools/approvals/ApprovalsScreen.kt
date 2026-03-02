package ai.openclaw.android.ui.tools.approvals

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.core.security.ApprovalRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(engine: AgentEngine, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf(emptyList<ApprovalRequest>()) }

    // Poll for pending requests
    LaunchedEffect(Unit) {
        while (isActive) {
            requests = engine.approvalManager.pendingRequests()
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approvals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (requests.isEmpty()) {
            EmptyState(
                icon = Icons.Default.CheckCircle,
                message = "No pending approvals",
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
                items(requests, key = { it.id }) { request ->
                    ApprovalCard(
                        request = request,
                        onApprove = {
                            scope.launch {
                                engine.approvalManager.approve(request.id)
                                requests = engine.approvalManager.pendingRequests()
                            }
                        },
                        onDeny = {
                            scope.launch {
                                engine.approvalManager.deny(request.id)
                                requests = engine.approvalManager.pendingRequests()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val remainingMs = (request.expiresAtMs - now).coerceAtLeast(0)
    val remainingSec = remainingMs / 1000

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${remainingSec}s left",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (remainingSec < 30) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = request.toolInput,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Agent: ${request.agentId} | ${formatTimestamp(request.createdAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Deny")
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}
