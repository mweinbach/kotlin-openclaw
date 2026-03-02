package ai.openclaw.android.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.openclaw.android.AgentEngine
import ai.openclaw.core.model.*

private data class ChannelType(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val fields: List<FieldDef>,
)

private data class FieldDef(
    val key: String,
    val label: String,
    val isSecret: Boolean = false,
    val hint: String = "",
)

private val channelTypes = listOf(
    ChannelType(
        id = "telegram",
        name = "Telegram",
        icon = Icons.Default.Send,
        fields = listOf(
            FieldDef("botToken", "Bot Token", isSecret = true, hint = "From @BotFather"),
        ),
    ),
    ChannelType(
        id = "discord",
        name = "Discord",
        icon = Icons.Default.SportsEsports,
        fields = listOf(
            FieldDef("botToken", "Bot Token", isSecret = true, hint = "Discord bot token"),
        ),
    ),
    ChannelType(
        id = "slack",
        name = "Slack",
        icon = Icons.Default.Tag,
        fields = listOf(
            FieldDef("botToken", "Bot Token", isSecret = true),
            FieldDef("appToken", "App Token", isSecret = true),
            FieldDef("signingSecret", "Signing Secret", isSecret = true),
        ),
    ),
    ChannelType(
        id = "signal",
        name = "Signal",
        icon = Icons.Default.Lock,
        fields = listOf(
            FieldDef("apiUrl", "API URL", hint = "Signal CLI REST API URL"),
            FieldDef("number", "Phone Number", hint = "+1234567890"),
        ),
    ),
    ChannelType(
        id = "matrix",
        name = "Matrix",
        icon = Icons.Default.GridView,
        fields = listOf(
            FieldDef("homeserverUrl", "Homeserver URL", hint = "https://matrix.org"),
            FieldDef("accessToken", "Access Token", isSecret = true),
            FieldDef("userId", "User ID", hint = "@bot:matrix.org"),
        ),
    ),
    ChannelType(
        id = "irc",
        name = "IRC",
        icon = Icons.Default.Terminal,
        fields = listOf(
            FieldDef("server", "Server", hint = "irc.libera.chat"),
            FieldDef("port", "Port", hint = "6697"),
            FieldDef("nick", "Nickname"),
            FieldDef("password", "Password", isSecret = true),
            FieldDef("channels", "Channels", hint = "#channel1, #channel2"),
        ),
    ),
    ChannelType(
        id = "googlechat",
        name = "Google Chat",
        icon = Icons.Default.Chat,
        fields = listOf(
            FieldDef("serviceAccountKey", "Service Account Key", isSecret = true),
        ),
    ),
    ChannelType(
        id = "whatsapp",
        name = "WhatsApp",
        icon = Icons.Default.Phone,
        fields = listOf(
            FieldDef("allowFrom", "Allow From", hint = "Comma-separated phone numbers"),
            FieldDef("defaultTo", "Default To", hint = "Default recipient"),
        ),
    ),
    ChannelType(
        id = "msteams",
        name = "MS Teams",
        icon = Icons.Default.Groups,
        fields = listOf(
            FieldDef("appId", "App ID"),
            FieldDef("appPassword", "App Password", isSecret = true),
            FieldDef("tenantId", "Tenant ID"),
        ),
    ),
    ChannelType(
        id = "imessage",
        name = "iMessage",
        icon = Icons.Default.Message,
        fields = listOf(
            FieldDef("cliPath", "CLI Path", hint = "/usr/local/bin/imessage-cli"),
            FieldDef("dbPath", "DB Path", hint = "~/Library/Messages/chat.db"),
        ),
    ),
    ChannelType(
        id = "line",
        name = "LINE",
        icon = Icons.Default.ChatBubble,
        fields = listOf(
            FieldDef("channelAccessToken", "Channel Access Token", isSecret = true),
            FieldDef("channelSecret", "Channel Secret", isSecret = true),
        ),
    ),
    ChannelType(
        id = "mattermost",
        name = "Mattermost",
        icon = Icons.Default.Forum,
        fields = listOf(
            FieldDef("serverUrl", "Server URL", hint = "https://mattermost.example.com"),
            FieldDef("accessToken", "Access Token", isSecret = true),
        ),
    ),
    ChannelType(
        id = "nostr",
        name = "Nostr",
        icon = Icons.Default.Bolt,
        fields = listOf(
            FieldDef("privateKey", "Private Key (nsec)", isSecret = true),
            FieldDef("relayUrls", "Relay URLs", hint = "wss://relay1, wss://relay2"),
        ),
    ),
    ChannelType(
        id = "webchat",
        name = "WebChat",
        icon = Icons.Default.Language,
        fields = listOf(
            FieldDef("path", "Path", hint = "/webchat"),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelScreen(
    engine: AgentEngine,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var selectedType by remember { mutableStateOf<ChannelType?>(null) }
    var fieldValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentStep) {
                            0 -> "Add Channel"
                            1 -> "Configure ${selectedType?.name ?: ""}"
                            else -> "Add Channel"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentStep > 0) {
                            currentStep--
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(onClick = { currentStep-- }) {
                            Text("Back")
                        }
                    } else {
                        Spacer(Modifier)
                    }

                    when (currentStep) {
                        0 -> {
                            Button(
                                onClick = {
                                    currentStep = 1
                                    fieldValues = emptyMap()
                                },
                                enabled = selectedType != null,
                            ) {
                                Text("Next")
                            }
                        }
                        1 -> {
                            Button(
                                onClick = {
                                    saving = true
                                    saveChannel(engine, selectedType!!, fieldValues)
                                    saving = false
                                    onSaved()
                                },
                                enabled = !saving,
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (currentStep) {
            0 -> ChannelTypeSelector(
                selected = selectedType,
                onSelect = { selectedType = it },
                modifier = Modifier.padding(padding),
            )
            1 -> ChannelConfigForm(
                channelType = selectedType!!,
                values = fieldValues,
                onValuesChange = { fieldValues = it },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ChannelTypeSelector(
    selected: ChannelType?,
    onSelect: (ChannelType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(channelTypes) { type ->
            val isSelected = selected?.id == type.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(type) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                border = if (isSelected) {
                    CardDefaults.outlinedCardBorder()
                } else {
                    null
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = type.icon,
                        contentDescription = type.name,
                        modifier = Modifier.size(32.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = type.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelConfigForm(
    channelType: ChannelType,
    values: Map<String, String>,
    onValuesChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Enter credentials for ${channelType.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        channelType.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.key] ?: "",
                onValueChange = { newValue ->
                    onValuesChange(values + (field.key to newValue))
                },
                label = { Text(field.label) },
                placeholder = if (field.hint.isNotEmpty()) {
                    { Text(field.hint) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (field.isSecret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            )
        }
    }
}

private fun saveChannel(
    engine: AgentEngine,
    type: ChannelType,
    values: Map<String, String>,
) {
    val config = engine.config
    val channels = config.channels ?: ChannelsConfig()

    val updatedChannels = when (type.id) {
        "telegram" -> channels.copy(
            telegram = TelegramConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to TelegramAccountConfig(
                        botToken = secretFromValue(values["botToken"]),
                    ),
                ),
            ),
        )
        "discord" -> channels.copy(
            discord = DiscordConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to DiscordAccountConfig(
                        botToken = secretFromValue(values["botToken"]),
                    ),
                ),
            ),
        )
        "slack" -> channels.copy(
            slack = SlackConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to SlackAccountConfig(
                        botToken = secretFromValue(values["botToken"]),
                        appToken = secretFromValue(values["appToken"]),
                        signingSecret = secretFromValue(values["signingSecret"]),
                    ),
                ),
            ),
        )
        "signal" -> channels.copy(
            signal = SignalConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to SignalAccountConfig(
                        apiUrl = values["apiUrl"]?.ifBlank { null },
                        number = values["number"]?.ifBlank { null },
                    ),
                ),
            ),
        )
        "matrix" -> channels.copy(
            matrix = MatrixConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to MatrixAccountConfig(
                        homeserverUrl = values["homeserverUrl"]?.ifBlank { null },
                        accessToken = secretFromValue(values["accessToken"]),
                        userId = values["userId"]?.ifBlank { null },
                    ),
                ),
            ),
        )
        "irc" -> channels.copy(
            irc = IrcConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to IrcAccountConfig(
                        server = values["server"]?.ifBlank { null },
                        port = values["port"]?.toIntOrNull(),
                        nick = values["nick"]?.ifBlank { null },
                        password = secretFromValue(values["password"]),
                        channels = values["channels"]
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() },
                    ),
                ),
            ),
        )
        "googlechat" -> channels.copy(
            googlechat = GoogleChatConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to GoogleChatAccountConfig(
                        serviceAccountKey = secretFromValue(values["serviceAccountKey"]),
                    ),
                ),
            ),
        )
        "whatsapp" -> channels.copy(
            whatsapp = WhatsAppConfig(
                enabled = true,
                allowFrom = values["allowFrom"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() },
                defaultTo = values["defaultTo"]?.ifBlank { null },
            ),
        )
        "msteams" -> channels.copy(
            msteams = MSTeamsConfig(
                enabled = true,
                appId = values["appId"]?.ifBlank { null },
                appPassword = values["appPassword"]?.ifBlank { null },
                tenantId = values["tenantId"]?.ifBlank { null },
            ),
        )
        "imessage" -> channels.copy(
            imessage = IMessageConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to IMessageAccountConfig(
                        cliPath = values["cliPath"]?.ifBlank { null },
                        dbPath = values["dbPath"]?.ifBlank { null },
                    ),
                ),
            ),
        )
        "line" -> channels.copy(
            line = LineConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to LineAccountConfig(
                        channelAccessToken = secretFromValue(values["channelAccessToken"]),
                        channelSecret = secretFromValue(values["channelSecret"]),
                    ),
                ),
            ),
        )
        "mattermost" -> channels.copy(
            mattermost = MattermostConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to MattermostAccountConfig(
                        serverUrl = values["serverUrl"]?.ifBlank { null },
                        accessToken = secretFromValue(values["accessToken"]),
                    ),
                ),
            ),
        )
        "nostr" -> channels.copy(
            nostr = NostrConfig(
                enabled = true,
                accounts = mapOf(
                    "default" to NostrAccountConfig(
                        privateKey = secretFromValue(values["privateKey"]),
                        relayUrls = values["relayUrls"]
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() },
                    ),
                ),
            ),
        )
        "webchat" -> channels.copy(
            webchat = WebChatConfig(
                enabled = true,
                path = values["path"]?.ifBlank { null },
            ),
        )
        else -> channels
    }

    engine.saveConfig(config.copy(channels = updatedChannels))
    engine.reloadConfig()
}

private fun secretFromValue(value: String?): SecretInput? {
    if (value.isNullOrBlank()) return null
    return SecretInput(value = value)
}
