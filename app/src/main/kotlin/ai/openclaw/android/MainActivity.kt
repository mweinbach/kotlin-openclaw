package ai.openclaw.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
)

class ChatViewModel(private val engine: AgentEngine) : ViewModel() {
    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val conversationHistory = mutableListOf<LlmMessage>()

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return

        messages = messages + ChatMessage("user", text)
        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val responseBuilder = StringBuilder()
                messages = messages + ChatMessage("assistant", "", isStreaming = true)

                engine.sendMessage(
                    userMessage = text,
                    conversationHistory = conversationHistory.toList(),
                ).catch { e ->
                    errorMessage = e.message
                    isLoading = false
                }.collect { event ->
                    when (event) {
                        is AcpRuntimeEvent.TextDelta -> {
                            responseBuilder.append(event.text)
                            messages = messages.dropLast(1) +
                                ChatMessage("assistant", responseBuilder.toString(), isStreaming = true)
                        }
                        is AcpRuntimeEvent.Done -> {
                            messages = messages.dropLast(1) +
                                ChatMessage("assistant", responseBuilder.toString())
                            conversationHistory.add(LlmMessage(
                                role = LlmMessage.Role.USER,
                                content = text,
                            ))
                            conversationHistory.add(LlmMessage(
                                role = LlmMessage.Role.ASSISTANT,
                                content = responseBuilder.toString(),
                            ))
                            isLoading = false
                        }
                        is AcpRuntimeEvent.Error -> {
                            errorMessage = event.message
                            messages = messages.dropLast(1)
                            isLoading = false
                        }
                        is AcpRuntimeEvent.ToolCall -> {
                            responseBuilder.append("\n[Tool: ${event.title}]\n")
                            messages = messages.dropLast(1) +
                                ChatMessage("assistant", responseBuilder.toString(), isStreaming = true)
                        }
                        is AcpRuntimeEvent.Status -> { /* ignore */ }
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
                if (messages.lastOrNull()?.isStreaming == true) {
                    messages = messages.dropLast(1)
                }
                isLoading = false
            }
        }
    }
}

class ChatViewModelFactory(private val engine: AgentEngine) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(engine) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OpenClawApp
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: ChatViewModel = viewModel(
                        factory = ChatViewModelFactory(app.engine)
                    )
                    OpenClawScreen(vm)
                }
            }
        }
    }
}

@Composable
fun OpenClawScreen(viewModel: ChatViewModel) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages change
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "OpenClaw",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Error banner
        val error = viewModel.errorMessage
        if (error != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(viewModel.messages) { msg ->
                ChatBubble(msg)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = !viewModel.isLoading,
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                enabled = inputText.isNotBlank() && !viewModel.isLoading,
            ) {
                if (viewModel.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "You" else "Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content.ifEmpty { if (message.isStreaming) "..." else "" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
