package ai.openclaw.channels.webchat

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in WebSocket-based chat channel for direct client connections.
 * Manages WebSocket sessions and dispatches messages between clients and
 * the agent engine. Designed to be mounted on the Ktor gateway.
 *
 * Each connected WebSocket client gets a unique session ID.
 * Messages are JSON objects with the following format:
 *
 * Inbound (client -> server):
 *   { "type": "message", "text": "...", "sessionId": "..." }
 *
 * Outbound (server -> client):
 *   { "type": "message", "text": "...", "messageId": "..." }
 *   { "type": "typing", "active": true }
 */
class WebChatChannel(
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "webchat"
    override val displayName: String = "Web Chat"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, typing = true,
        richText = true, editing = true, deletion = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, WebSocket>()
    private val sessionMetadata = ConcurrentHashMap<String, SessionInfo>()

    data class SessionInfo(
        val sessionId: String,
        val connectedAt: Long = System.currentTimeMillis(),
        val remoteAddress: String? = null,
        val userAgent: String? = null,
    )

    // --- Lifecycle ---

    override suspend fun onStart() {
        // WebChat doesn't need to connect to anything externally;
        // it waits for clients to connect via WebSocket.
    }

    override suspend fun onStop() {
        sessions.values.forEach { ws ->
            ws.close(1000, "server shutdown")
        }
        sessions.clear()
        sessionMetadata.clear()
    }

    // --- WebSocket management ---

    /**
     * Create a WebSocketListener to be used with the Ktor gateway.
     * Call this when setting up the WebSocket route.
     */
    fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            private var sessionId: String = ""

            override fun onOpen(webSocket: WebSocket, response: Response) {
                sessionId = UUID.randomUUID().toString()
                sessions[sessionId] = webSocket
                sessionMetadata[sessionId] = SessionInfo(
                    sessionId = sessionId,
                    remoteAddress = null,
                    userAgent = response.request.header("User-Agent"),
                )

                // Send session init message
                val initMsg = buildJsonObject {
                    put("type", "session_init")
                    put("sessionId", sessionId)
                }
                webSocket.send(initMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleClientMessage(sessionId, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cleanupSession(sessionId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cleanupSession(sessionId)
            }
        }
    }

    /**
     * Register an externally-managed WebSocket connection.
     * Useful when the WebSocket is managed by a Ktor WebSocket handler
     * rather than OkHttp directly.
     */
    fun registerSession(sessionId: String, webSocket: WebSocket, metadata: SessionInfo? = null) {
        sessions[sessionId] = webSocket
        sessionMetadata[sessionId] = metadata ?: SessionInfo(sessionId = sessionId)
    }

    fun unregisterSession(sessionId: String) {
        cleanupSession(sessionId)
    }

    /**
     * Process an incoming text message from a WebSocket client.
     * Can be called externally by gateway WebSocket handlers.
     */
    suspend fun handleClientMessage(sessionId: String, raw: String) {
        val msg = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }

        val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: "message"

        when (type) {
            "message" -> handleTextMessage(sessionId, msg)
            "typing" -> { /* Client typing indicator - can forward to agent if needed */ }
            "ping" -> handlePing(sessionId)
        }
    }

    private suspend fun handleTextMessage(sessionId: String, msg: JsonObject) {
        val text = msg["text"]?.jsonPrimitive?.contentOrNull ?: return
        val clientMsgId = msg["messageId"]?.jsonPrimitive?.contentOrNull
        val userName = msg["userName"]?.jsonPrimitive?.contentOrNull ?: "User"
        val userId = msg["userId"]?.jsonPrimitive?.contentOrNull ?: sessionId

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = ChatType.DIRECT,
            senderId = userId,
            senderName = userName,
            targetId = sessionId,
            text = text,
            messageId = clientMsgId ?: UUID.randomUUID().toString(),
            metadata = buildMap {
                put("webchat_session_id", sessionId)
                put("webchat_user_id", userId)
                if (clientMsgId != null) put("webchat_client_message_id", clientMsgId)
            },
        )

        dispatchInbound(inbound)
    }

    private fun handlePing(sessionId: String) {
        val ws = sessions[sessionId] ?: return
        val pong = buildJsonObject {
            put("type", "pong")
            put("timestamp", System.currentTimeMillis())
        }
        ws.send(pong.toString())
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val sessionId = message.targetId
        val ws = sessions[sessionId] ?: return@withContext false
        val text = message.text

        // Send typing indicator first
        sendTypingIndicator(sessionId, active = true)
        delay(100) // Brief delay for UX

        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true

        for (chunk in chunks) {
            val outMsg = buildJsonObject {
                put("type", "message")
                put("text", chunk)
                put("messageId", UUID.randomUUID().toString())
                put("timestamp", System.currentTimeMillis())
            }
            if (!ws.send(outMsg.toString())) {
                success = false
            }
        }

        // Clear typing indicator
        sendTypingIndicator(sessionId, active = false)

        success
    }

    fun sendTypingIndicator(sessionId: String, active: Boolean) {
        val ws = sessions[sessionId] ?: return
        val msg = buildJsonObject {
            put("type", "typing")
            put("active", active)
        }
        ws.send(msg.toString())
    }

    /**
     * Send a system message (not from the agent) to a session.
     */
    fun sendSystemMessage(sessionId: String, text: String) {
        val ws = sessions[sessionId] ?: return
        val msg = buildJsonObject {
            put("type", "system")
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }
        ws.send(msg.toString())
    }

    // --- Session management ---

    fun getActiveSessions(): List<SessionInfo> {
        return sessionMetadata.values.toList()
    }

    fun getSessionCount(): Int = sessions.size

    private fun cleanupSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionMetadata.remove(sessionId)
    }

    companion object {
        const val TEXT_CHUNK_LIMIT = 8000

        fun splitText(text: String, maxLen: Int): List<String> {
            if (text.length <= maxLen) return listOf(text)
            val chunks = mutableListOf<String>()
            var remaining = text
            while (remaining.length > maxLen) {
                var splitIdx = remaining.lastIndexOf('\n', maxLen)
                if (splitIdx <= 0) splitIdx = remaining.lastIndexOf(' ', maxLen)
                if (splitIdx <= 0) splitIdx = maxLen
                chunks.add(remaining.substring(0, splitIdx))
                remaining = remaining.substring(splitIdx).trimStart()
            }
            if (remaining.isNotEmpty()) chunks.add(remaining)
            return chunks
        }
    }
}
