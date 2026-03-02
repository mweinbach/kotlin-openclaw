package ai.openclaw.android.ui.settings.codexoauth

import ai.openclaw.android.AgentEngine
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexOauthScreen(
    engine: AgentEngine,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AgentEngine.CodexOauthStatus?>(null) }
    var launchingLogin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refreshStatus() {
        status = engine.getCodexOauthStatus()
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        engine.codexOauthEvents.collectLatest { event ->
            message = event
            launchingLogin = false
            refreshStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex OAuth") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (status?.tokenSet == true) "Connected" else "Not connected",
                        color = if (status?.tokenSet == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (!status?.accountId.isNullOrBlank()) {
                        Text(
                            "Account ID: ${status?.accountId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!status?.email.isNullOrBlank()) {
                        Text(
                            "Email: ${status?.email}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    status?.expiresAtMs?.let { expiresAt ->
                        Text(
                            "Expires: ${Instant.ofEpochMilli(expiresAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Sign in opens auth.openai.com in your browser and returns to the app automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        launchingLogin = true
                        runCatching {
                            val authUrl = engine.beginCodexOauthLogin()
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                            message = "Browser opened. Complete sign-in to continue."
                        }.onFailure { error ->
                            launchingLogin = false
                            message = error.message ?: "Failed to start Codex OAuth"
                        }
                    }
                },
                enabled = !launchingLogin,
            ) {
                Text(if (launchingLogin) "Waiting for callback..." else "Sign In With Codex")
            }

            TextButton(
                onClick = {
                    scope.launch {
                        engine.clearCodexOauth()
                        message = "Codex OAuth cleared"
                        refreshStatus()
                    }
                },
            ) {
                Text("Clear Codex OAuth")
            }

            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
