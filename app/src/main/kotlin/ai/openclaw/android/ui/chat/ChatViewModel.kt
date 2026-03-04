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
    val toolCalls: List<ChatToolCall> = emptyList(),
)

data class ChatToolCall(
    val id: String,
    val title: String,
    val status: String = "in_progress",
    val detail: String? = null,
    val kind: String? = null,
    val rawInput: String? = null,
    val rawOutput: String? = null,
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
            val reconstructed = mutableListOf<ChatMessage>()
            for ((index, msg) in loadedMessages.withIndex()) {
                when (msg.role) {
                    LlmMessage.Role.USER -> {
                        reconstructed += ChatMessage(role = "user", content = msg.plainTextContent())
                    }
                    LlmMessage.Role.ASSISTANT -> {
                        reconstructed += ChatMessage(
                            role = "assistant",
                            content = msg.plainTextContent(),
                            toolCalls = msg.toolCalls?.mapIndexed { toolIndex, toolCall ->
                                val toolTitle = toolCall.name.trim().ifEmpty { "tool" }
                                val toolId = toolCall.id.trim().ifEmpty { "persisted:$toolIndex:$toolTitle" }
                                ChatToolCall(
                                    id = toolId,
                                    title = toolTitle,
                                    status = "in_progress",
                                )
                            } ?: emptyList(),
                        )
                    }
                    LlmMessage.Role.TOOL -> {
                        val toolCallId = msg.toolCallId?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: "persisted_tool_${index}_${msg.name ?: "tool"}"
                        val toolTitle = msg.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "tool"
                        val detail = msg.plainTextContent().trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { text -> if (text.length > 280) "${text.take(280)}..." else text }
                        val toolStatus = inferPersistedToolStatus(msg.plainTextContent())
                        val persistedToolCall = ChatToolCall(
                            id = toolCallId,
                            title = toolTitle,
                            status = toolStatus,
                            detail = detail,
                        )
                        if (!mergePersistedToolCall(reconstructed, persistedToolCall)) {
                            reconstructed += ChatMessage(
                                role = "assistant",
                                content = "",
                                toolCalls = listOf(persistedToolCall),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            messages = reconstructed
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
        val assistantToolCalls = linkedMapOf<String, ChatToolCall>()
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
                                toolCalls = assistantToolCalls.values.toList(),
                            )
                        }
                        is AcpRuntimeEvent.ToolCall -> {
                            val mergedToolCalls = applyToolCallEvent(assistantToolCalls, event)
                            messages = messages.dropLast(1) + ChatMessage(
                                role = "assistant",
                                content = responseBuilder.toString(),
                                isStreaming = true,
                                toolCalls = mergedToolCalls,
                            )
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
                                toolCalls = assistantToolCalls.values.toList(),
                            )
                            val assistantMessage = LlmMessage(
                                role = LlmMessage.Role.ASSISTANT,
                                content = finalContent,
                            )
                            conversationHistory.add(assistantMessage)
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
                                    toolCalls = assistantToolCalls.values.toList(),
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
                            toolCalls = assistantToolCalls.values.toList(),
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

internal fun applyToolCallEvent(
    toolCalls: LinkedHashMap<String, ChatToolCall>,
    event: AcpRuntimeEvent.ToolCall,
): List<ChatToolCall> {
    val title = resolveToolCallTitle(event)
    val id = resolveToolCallId(event, toolCalls, title)
    val normalizedStatus = normalizeToolCallStatus(
        rawStatus = event.status,
        rawTag = event.tag,
        eventText = event.text,
        rawOutput = event.rawOutput,
    )
    val detail = event.detail?.trim()?.takeIf { it.isNotEmpty() }
        ?: event.text.trim().takeIf { it.isNotEmpty() }
    val existing = toolCalls[id]
    val mergedStatus = when {
        normalizedStatus == null && existing != null -> existing.status
        normalizedStatus == null -> "in_progress"
        existing != null && existing.status != "in_progress" && normalizedStatus == "in_progress" -> existing.status
        else -> normalizedStatus
    }
    val merged = ChatToolCall(
        id = id,
        title = title.ifBlank { existing?.title ?: "tool" },
        status = mergedStatus,
        detail = detail ?: existing?.detail,
        kind = event.kind ?: existing?.kind,
        rawInput = event.rawInput ?: existing?.rawInput,
        rawOutput = event.rawOutput ?: existing?.rawOutput,
    )
    if (existing == merged) {
        return toolCalls.values.toList()
    }
    toolCalls[id] = merged
    return toolCalls.values.toList()
}

internal fun normalizeToolCallStatus(
    rawStatus: String?,
    rawTag: String?,
    eventText: String?,
    rawOutput: String?,
): String? {
    val status = rawStatus?.trim()?.lowercase().orEmpty()
    if (status.isNotEmpty()) {
        return when (status) {
            "in_progress", "running", "started", "start" -> "in_progress"
            "completed", "complete", "done", "success", "succeeded" -> "completed"
            "blocked", "denied", "timeout", "timed_out", "failed", "failure", "error" -> "failed"
            else -> status
        }
    }

    val tag = rawTag?.trim()?.lowercase().orEmpty()
    if (tag == "tool_call") {
        return "in_progress"
    }
    if (tag == "tool_call_update") {
        val output = rawOutput?.trim().orEmpty()
        if (output.isNotEmpty()) {
            return if (output.lowercase().contains("\"status\":\"error\"") || output.lowercase().contains("\"error\"")) {
                "failed"
            } else {
                "completed"
            }
        }
    }

    val text = eventText?.trim()?.lowercase().orEmpty()
    if (text.contains("error") || text.contains("fail")) {
        return "failed"
    }
    if (text.contains("denied") || text.contains("timed out") || text.contains("blocked")) {
        return "failed"
    }
    if (text.startsWith("completed ")) {
        return "completed"
    }
    return null
}

private fun resolveToolCallId(
    event: AcpRuntimeEvent.ToolCall,
    existing: LinkedHashMap<String, ChatToolCall>,
    title: String,
): String {
    event.toolCallId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val fallback = buildFallbackToolCallId(event, title)
    if (fallback in existing) return fallback
    return fallback
}

private fun resolveToolCallTitle(event: AcpRuntimeEvent.ToolCall): String {
    val explicitTitle = event.title?.trim().orEmpty()
    if (explicitTitle.isNotEmpty()) return explicitTitle

    val text = event.text.trim()
    if (text.isEmpty()) return "tool"
    val normalizedPrefixes = listOf("calling ", "completed ", "blocked ")
    for (prefix in normalizedPrefixes) {
        if (text.startsWith(prefix, ignoreCase = true)) {
            return text.substring(prefix.length).trim().ifEmpty { "tool" }
        }
    }
    return text
}

private fun buildFallbackToolCallId(event: AcpRuntimeEvent.ToolCall, title: String): String {
    val stableTitle = title.trim().lowercase().ifEmpty { "tool" }
    val key = listOfNotNull(
        stableTitle,
        event.detail?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString("|")
    if (key.isBlank()) return "tool_fallback"
    val digest = key.hashCode().toUInt().toString(16)
    return "tool_fallback_$digest"
}

private fun inferPersistedToolStatus(result: String): String {
    val normalized = result.trim().lowercase()
    if (normalized.isEmpty()) return "completed"
    if (normalized.contains("\"status\":\"error\"") || normalized.contains("\"error\"")) {
        return "failed"
    }
    if (normalized.startsWith("error:")) return "failed"
    return "completed"
}

private fun mergePersistedToolCall(
    messages: MutableList<ChatMessage>,
    toolCall: ChatToolCall,
): Boolean {
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (message.role != "assistant") continue
        val toolIndex = message.toolCalls.indexOfFirst { it.id == toolCall.id }
        if (toolIndex < 0) continue
        val mergedToolCalls = message.toolCalls.toMutableList()
        val existing = mergedToolCalls[toolIndex]
        mergedToolCalls[toolIndex] = existing.copy(
            title = existing.title.ifBlank { toolCall.title },
            status = toolCall.status,
            detail = toolCall.detail ?: existing.detail,
        )
        messages[index] = message.copy(toolCalls = mergedToolCalls)
        return true
    }
    return false
}
