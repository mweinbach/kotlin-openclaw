package ai.openclaw.android.ui.tools.cron

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.EmptyState
import ai.openclaw.runtime.cron.CronJob
import ai.openclaw.runtime.cron.CronSchedule
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    engine: AgentEngine,
    onEditJob: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf(emptyList<CronJob>()) }

    LaunchedEffect(Unit) {
        jobs = engine.cronScheduler.list(includeDisabled = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cron Jobs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditJob("new") }) {
                Icon(Icons.Default.Add, contentDescription = "New job")
            }
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Schedule,
                message = "No cron jobs scheduled",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                action = "Create Job",
                onAction = { onEditJob("new") },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    CronJobCard(
                        job = job,
                        onToggle = { enabled ->
                            scope.launch {
                                engine.cronScheduler.setEnabled(job.id, enabled)
                                jobs = engine.cronScheduler.list(includeDisabled = true)
                            }
                        },
                        onRunNow = {
                            scope.launch {
                                engine.cronScheduler.runNow(job.id)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                engine.cronScheduler.remove(job.id)
                                jobs = engine.cronScheduler.list(includeDisabled = true)
                            }
                        },
                        onClick = { onEditJob(job.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = scheduleDescription(job.schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = job.enabled,
                    onCheckedChange = onToggle,
                )
            }
            job.state.lastRunAtMs?.let { lastRun ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last run: ${formatTimestamp(lastRun)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onRunNow, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Run now",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun scheduleDescription(schedule: CronSchedule): String = when (schedule) {
    is CronSchedule.At -> "One-shot at ${schedule.at}"
    is CronSchedule.Every -> {
        val seconds = schedule.everyMs / 1000
        when {
            seconds < 60 -> "Every ${seconds}s"
            seconds < 3600 -> "Every ${seconds / 60}m"
            else -> "Every ${seconds / 3600}h"
        }
    }
    is CronSchedule.Cron -> schedule.expr
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}
