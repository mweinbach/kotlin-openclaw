package ai.openclaw.android.ui.channels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openclaw.android.AgentEngine
import ai.openclaw.android.ui.components.SectionCard
import ai.openclaw.android.ui.components.Status
import ai.openclaw.android.ui.components.StatusIndicator
import ai.openclaw.core.model.*
import ai.openclaw.runtime.gateway.ChannelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    engine: AgentEngine,
    channelKey: String,
    onBack: () -> Unit,
) {
    val vm: ChannelsViewModel = viewModel(factory = ChannelsViewModel.Factory(engine))
    val channels by vm.channels.collectAsState()
    val channel = channels.find { it.key == channelKey }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (channel == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val parts = channelKey.split(":", limit = 2)
    val type = parts[0]
    val accountId = parts.getOrElse(1) { "default" }
    val configFields = getConfigFields(engine.config, type, accountId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove channel",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status card
            SectionCard(title = "Status") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusIndicator(
                        status = when (channel.status) {
                            ChannelManager.ChannelStatus.RUNNING -> Status.Connected
                            ChannelManager.ChannelStatus.ERROR -> Status.Error
                            ChannelManager.ChannelStatus.STARTING,
                            ChannelManager.ChannelStatus.RESTARTING -> Status.Warning
                            ChannelManager.ChannelStatus.STOPPED,
                            null -> Status.Offline
                        },
                        size = 16.dp,
                    )
                    Text(
                        text = when (channel.status) {
                            ChannelManager.ChannelStatus.RUNNING -> "Running"
                            ChannelManager.ChannelStatus.ERROR -> "Error"
                            ChannelManager.ChannelStatus.STARTING -> "Starting"
                            ChannelManager.ChannelStatus.RESTARTING -> "Restarting"
                            ChannelManager.ChannelStatus.STOPPED -> "Stopped"
                            null -> "Not started"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (channel.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = channel.error,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val isRunning = channel.status == ChannelManager.ChannelStatus.RUNNING
                    if (isRunning) {
                        Button(
                            onClick = { vm.stopChannel(channelKey) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = { vm.startChannel(channelKey) }) {
                            Text("Start")
                        }
                    }
                }
            }

            // Channel info
            SectionCard(title = "Channel Info") {
                InfoRow("Type", channel.type)
                InfoRow("Account", accountId)
                InfoRow("Enabled", if (channel.enabled) "Yes" else "No")
            }

            // Config fields
            if (configFields.isNotEmpty()) {
                SectionCard(title = "Configuration") {
                    configFields.forEach { (label, value) ->
                        InfoRow(label, value)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove Channel") },
            text = { Text("Remove ${channel.name} from your configuration? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        vm.removeChannel(channelKey)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun getConfigFields(
    config: OpenClawConfig,
    type: String,
    accountId: String,
): List<Pair<String, String>> {
    val fields = mutableListOf<Pair<String, String>>()
    val ch = config.channels ?: return fields

    when (type) {
        "telegram" -> {
            val acct = ch.telegram?.accounts?.get(accountId)
            acct?.let {
                it.botToken?.let { t -> fields.add("Bot Token" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
                it.polling?.let { p -> fields.add("Polling" to p.toString()) }
            }
        }
        "discord" -> {
            val acct = ch.discord?.accounts?.get(accountId)
            acct?.let {
                it.botToken?.let { t -> fields.add("Bot Token" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
                it.intents?.let { i -> fields.add("Intents" to i.joinToString(", ")) }
            }
        }
        "slack" -> {
            val acct = ch.slack?.accounts?.get(accountId)
            acct?.let {
                it.botToken?.let { t -> fields.add("Bot Token" to maskSecret(t)) }
                it.appToken?.let { t -> fields.add("App Token" to maskSecret(t)) }
                it.signingSecret?.let { t -> fields.add("Signing Secret" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "signal" -> {
            val acct = ch.signal?.accounts?.get(accountId)
            acct?.let {
                it.apiUrl?.let { u -> fields.add("API URL" to u) }
                it.number?.let { n -> fields.add("Number" to n) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "matrix" -> {
            val acct = ch.matrix?.accounts?.get(accountId)
            acct?.let {
                it.homeserverUrl?.let { u -> fields.add("Homeserver URL" to u) }
                it.accessToken?.let { t -> fields.add("Access Token" to maskSecret(t)) }
                it.userId?.let { u -> fields.add("User ID" to u) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "irc" -> {
            val acct = ch.irc?.accounts?.get(accountId)
            acct?.let {
                it.server?.let { s -> fields.add("Server" to s) }
                it.port?.let { p -> fields.add("Port" to p.toString()) }
                it.nick?.let { n -> fields.add("Nick" to n) }
                it.channels?.let { c -> fields.add("Channels" to c.joinToString(", ")) }
                it.useTls?.let { t -> fields.add("Use TLS" to t.toString()) }
            }
        }
        "googlechat" -> {
            val acct = ch.googlechat?.accounts?.get(accountId)
            acct?.let {
                it.serviceAccountKey?.let { t -> fields.add("Service Account Key" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "whatsapp" -> {
            ch.whatsapp?.let {
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
                it.defaultTo?.let { d -> fields.add("Default To" to d) }
                it.selfChatMode?.let { s -> fields.add("Self Chat Mode" to s.toString()) }
            }
        }
        "msteams" -> {
            ch.msteams?.let {
                it.appId?.let { a -> fields.add("App ID" to a) }
                it.appPassword?.let { p -> fields.add("App Password" to "****") }
                it.tenantId?.let { t -> fields.add("Tenant ID" to t) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "imessage" -> {
            val acct = ch.imessage?.accounts?.get(accountId)
            acct?.let {
                it.cliPath?.let { p -> fields.add("CLI Path" to p) }
                it.dbPath?.let { p -> fields.add("DB Path" to p) }
                it.remoteHost?.let { h -> fields.add("Remote Host" to h) }
                it.service?.let { s -> fields.add("Service" to s) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "line" -> {
            val acct = ch.line?.accounts?.get(accountId)
            acct?.let {
                it.channelAccessToken?.let { t -> fields.add("Channel Access Token" to maskSecret(t)) }
                it.channelSecret?.let { t -> fields.add("Channel Secret" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "mattermost" -> {
            val acct = ch.mattermost?.accounts?.get(accountId)
            acct?.let {
                it.serverUrl?.let { u -> fields.add("Server URL" to u) }
                it.accessToken?.let { t -> fields.add("Access Token" to maskSecret(t)) }
                it.allowFrom?.let { a -> fields.add("Allow From" to a.joinToString(", ")) }
            }
        }
        "nostr" -> {
            val acct = ch.nostr?.accounts?.get(accountId)
            acct?.let {
                it.privateKey?.let { t -> fields.add("Private Key" to maskSecret(t)) }
                it.relayUrls?.let { r -> fields.add("Relay URLs" to r.joinToString(", ")) }
            }
        }
        "webchat" -> {
            ch.webchat?.let {
                it.path?.let { p -> fields.add("Path" to p) }
                it.requireAuth?.let { r -> fields.add("Require Auth" to r.toString()) }
            }
        }
    }

    return fields
}

private fun maskSecret(secret: SecretInput): String {
    val env = secret.env
    val value = secret.value
    return when {
        env != null -> "\${$env}"
        value != null -> {
            if (value.length > 8) "${value.take(4)}****${value.takeLast(4)}" else "****"
        }
        else -> "(configured)"
    }
}
