package ai.openclaw.android.ui.tools.cron

import ai.openclaw.android.AgentEngine
import ai.openclaw.runtime.cron.CronJob
import ai.openclaw.runtime.cron.CronPayload
import ai.openclaw.runtime.cron.CronSchedule
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronEditSheet(
    engine: AgentEngine,
    jobId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = jobId == "new"

    var existingJob by remember { mutableStateOf<CronJob?>(null) }
    var name by remember { mutableStateOf("") }
    var scheduleExpr by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(isNew) }

    // Load existing job
    LaunchedEffect(jobId) {
        if (!isNew) {
            val job = engine.cronScheduler.getJob(jobId)
            existingJob = job
            if (job != null) {
                name = job.name
                scheduleExpr = when (val s = job.schedule) {
                    is CronSchedule.At -> s.at
                    is CronSchedule.Every -> "${s.everyMs}ms"
                    is CronSchedule.Cron -> s.expr
                }
                payload = when (val p = job.payload) {
                    is CronPayload.SystemEvent -> p.text
                    is CronPayload.AgentTurn -> p.message
                }
                enabled = job.enabled
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Job" else "Edit Job") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = scheduleExpr,
                    onValueChange = { scheduleExpr = it },
                    label = { Text("Schedule (cron expression or interval)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("e.g. */5 * * * * or 60000ms") },
                )

                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Payload message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val schedule = parseSchedule(scheduleExpr)
                            val cronPayload = CronPayload.AgentTurn(message = payload)

                            if (isNew) {
                                engine.cronScheduler.add(
                                    name = name,
                                    schedule = schedule,
                                    payload = cronPayload,
                                    enabled = enabled,
                                )
                            } else {
                                // Remove old and re-add with updated values
                                engine.cronScheduler.remove(jobId)
                                engine.cronScheduler.add(
                                    name = name,
                                    schedule = schedule,
                                    payload = cronPayload,
                                    enabled = enabled,
                                )
                            }
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && scheduleExpr.isNotBlank(),
                ) {
                    Text("Save")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun parseSchedule(expr: String): CronSchedule {
    val trimmed = expr.trim()
    return when {
        trimmed.endsWith("ms") -> {
            val ms = trimmed.removeSuffix("ms").toLongOrNull() ?: 60_000L
            CronSchedule.Every(everyMs = ms)
        }
        trimmed.endsWith("s") -> {
            val sec = trimmed.removeSuffix("s").toLongOrNull() ?: 60L
            CronSchedule.Every(everyMs = sec * 1000)
        }
        trimmed.endsWith("m") -> {
            val min = trimmed.removeSuffix("m").toLongOrNull() ?: 1L
            CronSchedule.Every(everyMs = min * 60_000)
        }
        trimmed.endsWith("h") -> {
            val hr = trimmed.removeSuffix("h").toLongOrNull() ?: 1L
            CronSchedule.Every(everyMs = hr * 3_600_000)
        }
        else -> CronSchedule.Cron(expr = trimmed)
    }
}
