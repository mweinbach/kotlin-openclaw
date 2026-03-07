package ai.openclaw.android.ui.tools.approvals

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.core.security.ApprovalEvent
import ai.openclaw.core.security.ApprovalManager
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApprovalsScreen(engine: AgentEngine, onBack: () -> Unit) {
    ApprovalsScreen(
        approvalManager = engine.approvalManager,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalsScreen(
    approvalManager: ApprovalManager,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var requests by remember(approvalManager) { mutableStateOf(emptyList<ApprovalRequest>()) }

    LaunchedEffect(approvalManager) {
        val resolvedRequestIds = mutableSetOf<String>()

        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                approvalManager.events.collect { event ->
                    requests = when (event) {
                        is ApprovalEvent.ApprovalRequired -> {
                            resolvedRequestIds.remove(event.request.id)
                            requests.upsert(event.request)
                        }
                        is ApprovalEvent.ApprovalResolved -> {
                            resolvedRequestIds += event.requestId
                            requests.without(event.requestId)
                        }
                    }
                }
            }

            val snapshot = approvalManager.pendingRequests()
            requests = snapshot
                .filterNot { it.id in resolvedRequestIds }
                .fold(requests) { current, request -> current.upsert(request) }
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
                                if (approvalManager.approve(request.id)) {
                                    requests = requests.without(request.id)
                                }
                            }
                        },
                        onDeny = {
                            scope.launch {
                                if (approvalManager.deny(request.id)) {
                                    requests = requests.without(request.id)
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
private fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val remainingSec by produceState(
        initialValue = remainingSeconds(request.expiresAtMs),
        key1 = request.expiresAtMs,
    ) {
        while (value > 0) {
            delay(1000)
            value = remainingSeconds(request.expiresAtMs)
        }
    }

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

private fun List<ApprovalRequest>.upsert(request: ApprovalRequest): List<ApprovalRequest> {
    val index = indexOfFirst { it.id == request.id }
    if (index == -1) return this + request
    return toMutableList().also { it[index] = request }
}

private fun List<ApprovalRequest>.without(requestId: String): List<ApprovalRequest> =
    filterNot { it.id == requestId }

private fun remainingSeconds(expiresAtMs: Long): Long =
    ((expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)) / 1000
