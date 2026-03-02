package ai.openclaw.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.openclaw.android.AgentEngine
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.DEFAULT_AGENT_ID
import ai.openclaw.runtime.engine.SessionPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val toolCalls: List<String> = emptyList(),
)

class ChatViewModel(private val engine: AgentEngine) : ViewModel() {

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var currentSessionId: String? = null
        private set

    private var currentAgentId: String = DEFAULT_AGENT_ID
    private var currentModelId: String? = null

    private val conversationHistory = mutableListOf<LlmMessage>()

    private val _sessions = MutableStateFlow<List<String>>(emptyList())
    val sessions: StateFlow<List<String>> = _sessions.asStateFlow()

    private val _sessionHeaders = MutableStateFlow<Map<String, SessionPersistence.SessionHeader?>>(emptyMap())
    val sessionHeaders: StateFlow<Map<String, SessionPersistence.SessionHeader?>> = _sessionHeaders.asStateFlow()

    init {
        currentAgentId = engine.defaultAgentId()
        currentModelId = engine.preferredModelForAgent(currentAgentId)
        refreshSessions()
    }

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val keys = engine.sessionPersistence.listSessionKeys()
            _sessions.value = keys
            val headers = mutableMapOf<String, SessionPersistence.SessionHeader?>()
            for (key in keys) {
                val (header, _) = engine.sessionPersistence.load(key)
                headers[key] = header
            }
            _sessionHeaders.value = headers
        }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            currentSessionId = sessionId
            val (header, loadedMessages) = engine.sessionPersistence.load(sessionId)
            currentAgentId = header?.agentId ?: engine.defaultAgentId()
            currentModelId = header?.model ?: engine.preferredModelForAgent(currentAgentId)
            conversationHistory.clear()
            conversationHistory.addAll(loadedMessages)
            messages = loadedMessages.mapNotNull { msg ->
                when (msg.role) {
                    LlmMessage.Role.USER -> ChatMessage(role = "user", content = msg.content)
                    LlmMessage.Role.ASSISTANT -> ChatMessage(
                        role = "assistant",
                        content = msg.content,
                        toolCalls = msg.toolCalls?.map { it.name } ?: emptyList(),
                    )
                    else -> null
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return

        errorMessage = null

        // Create session if new
        if (currentSessionId == null) {
            val sessionId = UUID.randomUUID().toString()
            currentSessionId = sessionId
            currentAgentId = engine.defaultAgentId()
            currentModelId = engine.preferredModelForAgent(currentAgentId)
            engine.sessionPersistence.initSession(
                sessionId,
                SessionPersistence.SessionHeader(
                    sessionId = sessionId,
                    agentId = currentAgentId,
                    model = currentModelId,
                ),
            )
        }

        // Add user message
        val userMessage = LlmMessage(role = LlmMessage.Role.USER, content = text)
        conversationHistory.add(userMessage)
        messages = messages + ChatMessage(role = "user", content = text)

        // Persist user message
        currentSessionId?.let { sid ->
            engine.sessionPersistence.appendMessage(sid, userMessage)
        }

        // Stream assistant response
        isLoading = true
        val assistantToolCalls = mutableListOf<String>()
        val responseBuilder = StringBuilder()

        // Add a streaming placeholder
        messages = messages + ChatMessage(role = "assistant", content = "", isStreaming = true)

        viewModelScope.launch {
            try {
                val flow = engine.sendMessage(
                    userMessage = text,
                    conversationHistory = conversationHistory.dropLast(1),
                    model = currentModelId,
                    agentId = currentAgentId,
                    sessionKey = currentSessionId ?: "",
                )
                flow.collect { event ->
                    when (event) {
                        is AcpRuntimeEvent.TextDelta -> {
                            responseBuilder.append(event.text)
                            messages = messages.dropLast(1) + ChatMessage(
                                role = "assistant",
                                content = responseBuilder.toString(),
                                isStreaming = true,
                                toolCalls = assistantToolCalls.toList(),
                            )
                        }
                        is AcpRuntimeEvent.ToolCall -> {
                            val toolName = event.title ?: event.text
                            if (toolName.isNotBlank()) {
                                assistantToolCalls.add(toolName)
                                messages = messages.dropLast(1) + ChatMessage(
                                    role = "assistant",
                                    content = responseBuilder.toString(),
                                    isStreaming = true,
                                    toolCalls = assistantToolCalls.toList(),
                                )
                            }
                        }
                        is AcpRuntimeEvent.Status -> {
                            // Status updates are informational; no UI change needed
                        }
                        is AcpRuntimeEvent.Done -> {
                            val finalContent = responseBuilder.toString()
                            messages = messages.dropLast(1) + ChatMessage(
                                role = "assistant",
                                content = finalContent,
                                isStreaming = false,
                                toolCalls = assistantToolCalls.toList(),
                            )
                            val assistantMessage = LlmMessage(
                                role = LlmMessage.Role.ASSISTANT,
                                content = finalContent,
                            )
                            conversationHistory.add(assistantMessage)
                            currentSessionId?.let { sid ->
                                withContext(Dispatchers.IO) {
                                    engine.sessionPersistence.appendMessage(sid, assistantMessage)
                                }
                            }
                            isLoading = false
                        }
                        is AcpRuntimeEvent.Error -> {
                            errorMessage = event.message
                            // Remove the streaming placeholder if empty
                            if (responseBuilder.isEmpty()) {
                                messages = messages.dropLast(1)
                            } else {
                                messages = messages.dropLast(1) + ChatMessage(
                                    role = "assistant",
                                    content = responseBuilder.toString(),
                                    isStreaming = false,
                                    toolCalls = assistantToolCalls.toList(),
                                )
                            }
                            isLoading = false
                        }
                    }
                }
                // If flow completes without Done event
                if (isLoading) {
                    val finalContent = responseBuilder.toString()
                    if (finalContent.isNotEmpty()) {
                        messages = messages.dropLast(1) + ChatMessage(
                            role = "assistant",
                            content = finalContent,
                            isStreaming = false,
                            toolCalls = assistantToolCalls.toList(),
                        )
                        val assistantMessage = LlmMessage(
                            role = LlmMessage.Role.ASSISTANT,
                            content = finalContent,
                        )
                        conversationHistory.add(assistantMessage)
                        currentSessionId?.let { sid ->
                            withContext(Dispatchers.IO) {
                                engine.sessionPersistence.appendMessage(sid, assistantMessage)
                            }
                        }
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
                if (responseBuilder.isEmpty()) {
                    messages = messages.dropLast(1)
                }
                isLoading = false
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.sessionPersistence.delete(sessionId)
            if (currentSessionId == sessionId) {
                startNewSession()
            }
            refreshSessions()
        }
    }

    fun startNewSession() {
        currentSessionId = null
        currentAgentId = engine.defaultAgentId()
        currentModelId = engine.preferredModelForAgent(currentAgentId)
        conversationHistory.clear()
        messages = emptyList()
        errorMessage = null
        isLoading = false
    }
}

class ChatViewModelFactory(private val engine: AgentEngine) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(engine) as T
    }
}
