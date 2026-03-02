package ai.openclaw.channels.discord

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Discord channel implementation using Gateway v10 WebSocket for receiving
 * events and REST API v10 for sending messages.
 */
class DiscordChannel(
    private val botToken: String,
    private val gatewayUrl: String = "wss://gateway.discord.gg/?v=10&encoding=json",
    private val apiBase: String = "https://discord.com/api/v10",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for websocket
        .build(),
    private val intents: Int = DEFAULT_INTENTS,
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "discord"
    override val displayName: String = "Discord"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        threads = true,
        groups = true,
        typing = true,
        richText = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Gateway state
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var gatewayJob: Job? = null
    private var sequenceNumber: Long? = null
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var selfBotId: String? = null

    // Reconnect state
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 50

    // --- Lifecycle ---

    override suspend fun onStart() {
        gatewayJob = scope.launch { connectGateway() }
    }

    override suspend fun onStop() {
        connected = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, "Shutting down")
        webSocket = null
        gatewayJob?.cancel()
        gatewayJob = null
        sessionId = null
        sequenceNumber = null
        resumeGatewayUrl = null
    }

    // --- Gateway Connection ---

    private suspend fun connectGateway() {
        while (currentCoroutineContext().isActive && reconnectAttempts < maxReconnectAttempts) {
            try {
                val url = resumeGatewayUrl ?: gatewayUrl
                openWebSocket(url)
                // After websocket is closed, we loop back to reconnect
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to reconnect with backoff
            }

            if (!currentCoroutineContext().isActive) break

            val backoffMs = calculateBackoff(reconnectAttempts)
            reconnectAttempts++
            delay(backoffMs)
        }
    }

    private suspend fun openWebSocket(url: String) {
        val latch = CompletableDeferred<Unit>()

        val request = Request.Builder().url(url).build()
        val listener = GatewayWebSocketListener(latch)
        webSocket = client.newWebSocket(request, listener)

        // Wait until the WebSocket disconnects
        latch.await()
    }

    // --- Gateway WebSocket Listener ---

    private inner class GatewayWebSocketListener(
        private val closeLatch: CompletableDeferred<Unit>,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Wait for HELLO from Discord
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val payload = json.parseToJsonElement(text).jsonObject
                handleGatewayPayload(webSocket, payload)
            } catch (_: Exception) {
                // Ignore malformed payloads
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@DiscordChannel.webSocket = null

            // Non-resumable close codes: reset session
            if (code in NON_RESUMABLE_CLOSE_CODES) {
                sessionId = null
                sequenceNumber = null
                resumeGatewayUrl = null
            }

            closeLatch.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@DiscordChannel.webSocket = null
            closeLatch.complete(Unit)
        }
    }

    // --- Gateway Payload Handling ---

    private fun handleGatewayPayload(ws: WebSocket, payload: JsonObject) {
        val op = payload["op"]?.jsonPrimitive?.intOrNull ?: return
        val s = payload["s"]?.jsonPrimitive?.longOrNull
        if (s != null) sequenceNumber = s

        val d = payload["d"]

        when (op) {
            OP_DISPATCH -> {
                val t = payload["t"]?.jsonPrimitive?.contentOrNull ?: return
                handleDispatch(t, d?.jsonObject)
            }

            OP_HELLO -> {
                val heartbeatInterval = d?.jsonObject
                    ?.get("heartbeat_interval")
                    ?.jsonPrimitive?.longOrNull ?: 41250L
                startHeartbeat(ws, heartbeatInterval)

                // Send IDENTIFY or RESUME
                if (sessionId != null && sequenceNumber != null) {
                    sendResume(ws)
                } else {
                    sendIdentify(ws)
                }
            }

            OP_HEARTBEAT_ACK -> {
                // Heartbeat acknowledged - connection healthy
            }

            OP_HEARTBEAT -> {
                // Server-requested heartbeat
                sendHeartbeat(ws)
            }

            OP_RECONNECT -> {
                // Discord requested reconnect
                ws.close(4000, "Reconnect requested")
            }

            OP_INVALID_SESSION -> {
                val resumable = d?.jsonPrimitive?.booleanOrNull ?: false
                if (!resumable) {
                    sessionId = null
                    sequenceNumber = null
                    resumeGatewayUrl = null
                }
                // Close and reconnect
                scope.launch {
                    delay(if (resumable) 1000L else 5000L)
                    ws.close(4000, "Invalid session")
                }
            }
        }
    }

    // --- Gateway Dispatch Events ---

    private fun handleDispatch(eventType: String, data: JsonObject?) {
        if (data == null) return

        when (eventType) {
            "READY" -> handleReady(data)
            "RESUMED" -> handleResumed()
            "MESSAGE_CREATE" -> scope.launch { handleMessageCreate(data) }
            "THREAD_CREATE" -> handleThreadCreate(data)
        }
    }

    private fun handleReady(data: JsonObject) {
        sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull
        resumeGatewayUrl = data["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
        selfBotId = data["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        reconnectAttempts = 0
        connected = true
    }

    private fun handleResumed() {
        reconnectAttempts = 0
        connected = true
    }

    private suspend fun handleMessageCreate(data: JsonObject) {
        val author = data["author"]?.jsonObject ?: return
        val authorId = author["id"]?.jsonPrimitive?.contentOrNull ?: return

        // Ignore messages from self
        if (authorId == selfBotId) return
        // Ignore messages from bots
        if (author["bot"]?.jsonPrimitive?.booleanOrNull == true) return

        val content = data["content"]?.jsonPrimitive?.contentOrNull
        if (content.isNullOrBlank()) return

        val discordChannelId = data["channel_id"]?.jsonPrimitive?.contentOrNull ?: return
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull
        val guildId = data["guild_id"]?.jsonPrimitive?.contentOrNull

        val senderName = buildSenderName(author, data["member"]?.jsonObject)

        // Determine chat type
        val chatType = if (guildId != null) ChatType.GROUP else ChatType.DIRECT

        // Extract thread ID if in a thread
        val threadId = data["thread"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull

        // Extract roles from member data
        val roles = data["member"]?.jsonObject
            ?.get("roles")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }

        // Build reply context
        val referencedMessage = data["referenced_message"]?.jsonObject
        val replyText = referencedMessage?.let {
            val refContent = it["content"]?.jsonPrimitive?.contentOrNull ?: ""
            if (refContent.isNotEmpty()) "[Reply to: ${refContent.take(100)}] " else ""
        } ?: ""

        val replyToMessageId = data["message_reference"]?.jsonObject
            ?.get("message_id")?.jsonPrimitive?.contentOrNull

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = authorId,
            senderName = senderName,
            targetId = discordChannelId,
            text = replyText + content,
            messageId = messageId,
            threadId = threadId,
            guildId = guildId,
            roles = roles,
            replyToMessageId = replyToMessageId,
            metadata = buildMap {
                put("discord_channel_id", discordChannelId)
                if (guildId != null) put("discord_guild_id", guildId)
                if (messageId != null) put("discord_message_id", messageId)
                author["username"]?.jsonPrimitive?.contentOrNull?.let {
                    put("discord_username", it)
                }
                author["discriminator"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it != "0") put("discord_discriminator", it)
                }
            },
        )

        dispatchInbound(inbound)
    }

    private fun handleThreadCreate(data: JsonObject) {
        // Thread creation events can be used for thread tracking.
        // The thread itself will send MESSAGE_CREATE events that we handle.
    }

    // --- Gateway Control Messages ---

    private fun sendIdentify(ws: WebSocket) {
        val identify = buildJsonObject {
            put("op", OP_IDENTIFY)
            putJsonObject("d") {
                put("token", botToken)
                put("intents", intents)
                putJsonObject("properties") {
                    put("os", "android")
                    put("browser", "openclaw")
                    put("device", "openclaw")
                }
            }
        }
        ws.send(identify.toString())
    }

    private fun sendResume(ws: WebSocket) {
        val resume = buildJsonObject {
            put("op", OP_RESUME)
            putJsonObject("d") {
                put("token", botToken)
                put("session_id", sessionId)
                put("seq", sequenceNumber)
            }
        }
        ws.send(resume.toString())
    }

    private fun sendHeartbeat(ws: WebSocket) {
        val heartbeat = buildJsonObject {
            put("op", OP_HEARTBEAT)
            if (sequenceNumber != null) {
                put("d", sequenceNumber)
            } else {
                put("d", JsonNull)
            }
        }
        ws.send(heartbeat.toString())
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Initial jitter: wait a random fraction of the interval before first heartbeat
            delay((Math.random() * intervalMs).toLong())
            while (isActive) {
                sendHeartbeat(ws)
                delay(intervalMs)
            }
        }
    }

    // --- Outbound: Send Message via REST API ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val targetChannelId = message.targetId
        val text = message.text

        // Send typing indicator
        sendTypingIndicator(targetChannelId)

        // Split into chunks of 2000 chars (Discord limit)
        val chunks = splitText(text, DISCORD_MAX_MESSAGE_LENGTH)
        var success = true

        for ((i, chunk) in chunks.withIndex()) {
            val body = buildJsonObject {
                put("content", chunk)
                // Reply to original message on first chunk
                if (i == 0 && message.replyToMessageId != null) {
                    putJsonObject("message_reference") {
                        put("message_id", message.replyToMessageId)
                        put("fail_if_not_exists", false)
                    }
                }
            }
            if (!postRest("/channels/$targetChannelId/messages", body)) {
                success = false
            }
        }
        success
    }

    // --- REST API Helpers ---

    fun sendTypingIndicator(targetChannelId: String) {
        val request = Request.Builder()
            .url("$apiBase/channels/$targetChannelId/typing")
            .header("Authorization", "Bot $botToken")
            .post("".toRequestBody(null))
            .build()
        try {
            client.newCall(request).execute().use { }
        } catch (_: Exception) {
            // Best-effort
        }
    }

    fun addReaction(channelId: String, messageId: String, emoji: String): Boolean {
        val encoded = emoji.encodeForDiscordReaction()
        val request = Request.Builder()
            .url("$apiBase/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            .header("Authorization", "Bot $botToken")
            .put("".toRequestBody(null))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    fun createThread(
        channelId: String,
        name: String,
        messageId: String? = null,
        autoArchiveDurationMinutes: Int = 1440,
    ): String? {
        val url = if (messageId != null) {
            "$apiBase/channels/$channelId/messages/$messageId/threads"
        } else {
            "$apiBase/channels/$channelId/threads"
        }
        val body = buildJsonObject {
            put("name", name.take(DISCORD_THREAD_NAME_LIMIT))
            put("auto_archive_duration", autoArchiveDurationMinutes)
            if (messageId == null) {
                // Public thread when not attached to a message
                put("type", 11)
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bot $botToken")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    json.parseToJsonElement(responseBody).jsonObject["id"]
                        ?.jsonPrimitive?.contentOrNull
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun postRest(path: String, body: JsonObject): Boolean {
        val request = Request.Builder()
            .url("$apiBase$path")
            .header("Authorization", "Bot $botToken")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.body?.string() // consume body
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Helpers ---

    private fun buildSenderName(author: JsonObject, member: JsonObject?): String {
        // Prefer guild nickname, fall back to global display name, then username
        val nick = member?.get("nick")?.jsonPrimitive?.contentOrNull
        if (!nick.isNullOrBlank()) return nick

        val globalName = author["global_name"]?.jsonPrimitive?.contentOrNull
        if (!globalName.isNullOrBlank()) return globalName

        val username = author["username"]?.jsonPrimitive?.contentOrNull
        return username ?: "Unknown"
    }

    companion object {
        // Gateway opcodes
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11

        // Discord Gateway Intents
        const val INTENT_GUILDS = 1 shl 0
        const val INTENT_GUILD_MEMBERS = 1 shl 1
        const val INTENT_GUILD_MESSAGES = 1 shl 9
        const val INTENT_GUILD_MESSAGE_REACTIONS = 1 shl 10
        const val INTENT_DIRECT_MESSAGES = 1 shl 12
        const val INTENT_DIRECT_MESSAGE_REACTIONS = 1 shl 13
        const val INTENT_MESSAGE_CONTENT = 1 shl 15
        const val INTENT_GUILD_VOICE_STATES = 1 shl 7

        val DEFAULT_INTENTS = INTENT_GUILDS or
            INTENT_GUILD_MESSAGES or
            INTENT_MESSAGE_CONTENT or
            INTENT_DIRECT_MESSAGES or
            INTENT_GUILD_MESSAGE_REACTIONS or
            INTENT_DIRECT_MESSAGE_REACTIONS or
            INTENT_GUILD_VOICE_STATES

        // Close codes that indicate a non-resumable session
        private val NON_RESUMABLE_CLOSE_CODES = setOf(
            4004, // Authentication failed
            4010, // Invalid shard
            4011, // Sharding required
            4012, // Invalid API version
            4013, // Invalid intents
            4014, // Disallowed intents
        )

        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val DISCORD_THREAD_NAME_LIMIT = 100

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

        fun calculateBackoff(attempt: Int): Long {
            // Exponential backoff: 2s, 3.6s, 6.5s, ... capped at 60s
            val base = 2000L
            val backoff = (base * Math.pow(1.8, attempt.toDouble())).toLong()
            return backoff.coerceAtMost(60_000L)
        }

        /** URL-encode an emoji for Discord reaction endpoints. */
        private fun String.encodeForDiscordReaction(): String {
            // Custom emoji format: name:id
            if (contains(":") && matches(Regex("\\w+:\\d+"))) {
                return this
            }
            // Unicode emoji - URL encode
            return java.net.URLEncoder.encode(this, "UTF-8")
        }
    }
}
