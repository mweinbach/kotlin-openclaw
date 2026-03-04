package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmContentBlock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Session persistence manager — saves/loads conversation history as JSONL files.
 * Ported from src/agents/pi-embedded-runner/session-manager-init.ts
 */
class SessionPersistence(
    private val sessionsDir: String,
    private var transcriptRepairPolicy: TranscriptRepairPolicy = TranscriptRepairPolicy(),
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
        val stopReason: String? = null,
        val toolCalls: List<ToolCallEntry>? = null,
        val contentBlocks: List<ContentBlockEntry>? = null,
        val idempotencyKey: String? = null,
        val openclawAbort: OpenclawAbortEntry? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class OpenclawAbortEntry(
        val aborted: Boolean = true,
        val origin: String? = null,
        val runId: String? = null,
    )

    @Serializable
    data class ToolCallEntry(
        val id: String,
        val name: String,
        val arguments: String,
    )

    @Serializable
    data class ContentBlockEntry(
        val type: String,
        val text: String? = null,
        val url: String? = null,
        val mimeType: String? = null,
    )

    data class SessionLoadResult(
        val header: SessionHeader?,
        val messages: List<LlmMessage>,
        val repairReport: SessionFileRepairReport?,
    )

    /**
     * Load a session from its JSONL file. Returns header + messages.
     */
    fun load(sessionKey: String): Pair<SessionHeader?, List<LlmMessage>> {
        val result = loadWithReport(sessionKey)
        return result.header to result.messages
    }

    /**
     * Load a session and include repair metadata.
     */
    fun loadWithReport(
        sessionKey: String,
        warn: (String) -> Unit = {},
    ): SessionLoadResult {
        val file = sessionFile(sessionKey)
        if (!file.exists()) {
            return SessionLoadResult(
                header = null,
                messages = emptyList(),
                repairReport = SessionFileRepairReport(
                    repaired = false,
                    droppedLines = 0,
                    reason = "missing session file",
                ),
            )
        }

        val repairReport = if (transcriptRepairPolicy.fileRepairEnabled) {
            repairSessionFileIfNeeded(file, warn)
        } else {
            null
        }

        var header: SessionHeader? = null
        val messages = mutableListOf<LlmMessage>()

        try {
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
                        // If repair is disabled, keep legacy behavior and skip malformed lines.
                    }
                }
            }
        } catch (err: IOException) {
            warn("session load failed: ${err.message ?: "unknown error"} (${file.name})")
        }

        return SessionLoadResult(
            header = header,
            messages = messages,
            repairReport = repairReport,
        )
    }

    /**
     * Append a message to the session file.
     */
    fun appendMessage(sessionKey: String, message: LlmMessage) {
        val file = sessionFile(sessionKey)
        file.parentFile?.mkdirs()
        val entry = messageToEntry(message)
        appendEntry(file, entry)
    }

    fun appendAbortAssistantPartial(
        sessionKey: String,
        runId: String,
        text: String,
        origin: String,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val key = "${runId.trim()}:assistant"
        if (key == ":assistant") return false

        val file = sessionFile(sessionKey)
        file.parentFile?.mkdirs()

        if (hasIdempotencyKey(file, key)) {
            return false
        }

        val entry = SessionMessageEntry(
            role = "assistant",
            content = trimmed,
            stopReason = "stop",
            idempotencyKey = key,
            openclawAbort = OpenclawAbortEntry(
                aborted = true,
                origin = origin,
                runId = runId.trim().takeIf { it.isNotEmpty() },
            ),
        )
        appendEntry(file, entry)
        return true
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

    fun setTranscriptRepairPolicy(policy: TranscriptRepairPolicy) {
        transcriptRepairPolicy = policy
    }

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
            stopReason = entry.stopReason,
            toolCalls = entry.toolCalls?.map {
                ai.openclaw.core.agent.LlmToolCall(it.id, it.name, it.arguments)
            },
            contentBlocks = entry.contentBlocks?.mapNotNull { block ->
                when (block.type) {
                    "text" -> block.text?.let { LlmContentBlock.Text(it) }
                    "image_url" -> block.url?.let { LlmContentBlock.ImageUrl(url = it, mimeType = block.mimeType) }
                    else -> null
                }
            }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun messageToEntry(message: LlmMessage): SessionMessageEntry {
        return SessionMessageEntry(
            role = message.role.name.lowercase(),
            content = message.plainTextContent(),
            name = message.name,
            toolCallId = message.toolCallId,
            stopReason = message.stopReason,
            toolCalls = message.toolCalls?.map {
                ToolCallEntry(it.id, it.name, it.arguments)
            },
            contentBlocks = message.contentBlocks?.mapNotNull { block ->
                when (block) {
                    is LlmContentBlock.Text -> ContentBlockEntry(
                        type = "text",
                        text = block.text,
                    )
                    is LlmContentBlock.ImageUrl -> ContentBlockEntry(
                        type = "image_url",
                        url = block.url,
                        mimeType = block.mimeType,
                    )
                }
            }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun appendEntry(file: File, entry: SessionMessageEntry) {
        file.appendText(json.encodeToString(SessionMessageEntry.serializer(), entry) + "\n")
    }

    private fun hasIdempotencyKey(file: File, key: String): Boolean {
        if (!file.exists()) return false
        return try {
            file.bufferedReader().useLines { lines ->
                lines.any { line ->
                    if (line.isBlank()) return@any false
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@any false
                    obj["idempotencyKey"]?.jsonPrimitive?.contentOrNull == key
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
