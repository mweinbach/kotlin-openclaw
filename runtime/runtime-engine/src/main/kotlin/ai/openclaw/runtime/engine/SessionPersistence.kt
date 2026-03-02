package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Session persistence manager — saves/loads conversation history as JSONL files.
 * Ported from src/agents/pi-embedded-runner/session-manager-init.ts
 */
class SessionPersistence(
    private val sessionsDir: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Serializable
    data class SessionHeader(
        val sessionId: String,
        val agentId: String,
        val model: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val cwd: String? = null,
    )

    @Serializable
    data class SessionMessageEntry(
        val role: String,
        val content: String,
        val name: String? = null,
        val toolCallId: String? = null,
        val toolCalls: List<ToolCallEntry>? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class ToolCallEntry(
        val id: String,
        val name: String,
        val arguments: String,
    )

    /**
     * Load a session from its JSONL file. Returns header + messages.
     */
    fun load(sessionKey: String): Pair<SessionHeader?, List<LlmMessage>> {
        val file = sessionFile(sessionKey)
        if (!file.exists()) return null to emptyList()

        var header: SessionHeader? = null
        val messages = mutableListOf<LlmMessage>()

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    if (obj.containsKey("sessionId")) {
                        header = json.decodeFromJsonElement(SessionHeader.serializer(), obj)
                    } else {
                        val entry = json.decodeFromJsonElement(SessionMessageEntry.serializer(), obj)
                        messages.add(entryToMessage(entry))
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }

        return header to messages
    }

    /**
     * Append a message to the session file.
     */
    fun appendMessage(sessionKey: String, message: LlmMessage) {
        val file = sessionFile(sessionKey)
        file.parentFile?.mkdirs()
        val entry = messageToEntry(message)
        file.appendText(json.encodeToString(SessionMessageEntry.serializer(), entry) + "\n")
    }

    /**
     * Initialize a new session file with a header.
     */
    fun initSession(sessionKey: String, header: SessionHeader) {
        val file = sessionFile(sessionKey)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(SessionHeader.serializer(), header) + "\n")
    }

    /**
     * Check if a session file exists.
     */
    fun exists(sessionKey: String): Boolean = sessionFile(sessionKey).exists()

    /**
     * Delete a session file.
     */
    fun delete(sessionKey: String) {
        sessionFile(sessionKey).delete()
    }

    /**
     * List all session keys that have persisted files.
     */
    fun listSessionKeys(): List<String> {
        val dir = File(sessionsDir)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "jsonl" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Rewrite a session file with compacted messages.
     */
    fun compact(sessionKey: String, header: SessionHeader, messages: List<LlmMessage>) {
        val file = sessionFile(sessionKey)
        val tmpFile = File(file.parent, "${file.name}.tmp")
        tmpFile.bufferedWriter().use { writer ->
            writer.write(json.encodeToString(SessionHeader.serializer(), header))
            writer.newLine()
            for (msg in messages) {
                writer.write(json.encodeToString(SessionMessageEntry.serializer(), messageToEntry(msg)))
                writer.newLine()
            }
        }
        Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sessionFile(sessionKey: String): File {
        // Sanitize session key for filename
        val safeName = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(sessionsDir, "$safeName.jsonl")
    }

    private fun entryToMessage(entry: SessionMessageEntry): LlmMessage {
        val role = when (entry.role) {
            "system" -> LlmMessage.Role.SYSTEM
            "user" -> LlmMessage.Role.USER
            "assistant" -> LlmMessage.Role.ASSISTANT
            "tool" -> LlmMessage.Role.TOOL
            else -> LlmMessage.Role.USER
        }
        return LlmMessage(
            role = role,
            content = entry.content,
            name = entry.name,
            toolCallId = entry.toolCallId,
            toolCalls = entry.toolCalls?.map {
                ai.openclaw.core.agent.LlmToolCall(it.id, it.name, it.arguments)
            },
        )
    }

    private fun messageToEntry(message: LlmMessage): SessionMessageEntry {
        return SessionMessageEntry(
            role = message.role.name.lowercase(),
            content = message.content,
            name = message.name,
            toolCallId = message.toolCallId,
            toolCalls = message.toolCalls?.map {
                ToolCallEntry(it.id, it.name, it.arguments)
            },
        )
    }
}
