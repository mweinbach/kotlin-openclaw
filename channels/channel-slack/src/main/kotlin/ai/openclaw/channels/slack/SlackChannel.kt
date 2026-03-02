package ai.openclaw.channels.slack

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Slack channel implementation using Socket Mode (WebSocket) for receiving events
 * and the Slack Web API for sending messages.
 *
 * Requires two tokens:
 * - botToken (xoxb-*): used for Web API calls (chat.postMessage, reactions.add, etc.)
 * - appToken (xapp-*): used for Socket Mode connection (apps.connections.open)
 */
class SlackChannel(
    private val botToken: String,
    private val appToken: String,
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "slack"
    override val displayName: String = "Slack"

    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = true,
        threads = true, groups = true, typing = true,
        richText = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null
    private var webSocket: WebSocket? = null
    private var botUserId: String = ""
    private val userNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // --- Lifecycle ---

    override suspend fun onStart() {
        resolveBotUserId()
        socketJob = scope.launch { connectLoop() }
    }

    override suspend fun onStop() {
        socketJob?.cancel()
        socketJob = null
        webSocket?.close(1000, "shutdown")
        webSocket = null
    }

    // --- Socket Mode connection ---

    private suspend fun connectLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                val wssUrl = openSocketModeConnection()
                if (wssUrl == null) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
                    continue
                }
                backoffMs = 2000L
                connectWebSocket(wssUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    /**
     * Calls apps.connections.open with the app-level token to get a WSS URL.
     */
    private fun openSocketModeConnection(): String? {
        val request = Request.Builder()
            .url("https://slack.com/api/apps.connections.open")
            .header("Authorization", "Bearer $appToken")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@use null
            if (!response.isSuccessful) return@use null
            val result = json.parseToJsonElement(body).jsonObject
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return@use null
            result["url"]?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * Opens a WebSocket to the Socket Mode WSS URL and suspends until
     * the connection is closed or fails.
     */
    private suspend fun connectWebSocket(wssUrl: String) {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        val request = Request.Builder().url(wssUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@SlackChannel.webSocket = webSocket
                connected.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleSocketMessage(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@SlackChannel.webSocket = null
                connected.completeExceptionally(t)
                closed.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@SlackChannel.webSocket = null
                closed.complete(Unit)
            }
        })

        try {
            connected.await()
            closed.await()
        } catch (_: Exception) {
            ws.cancel()
        }
    }

    // --- Socket Mode envelope handling ---

    private suspend fun handleSocketMessage(ws: WebSocket, raw: String) {
        val envelope = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }

        val type = envelope["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "hello" -> { /* connection established, nothing to do */ }

            "disconnect" -> {
                // Slack asks us to reconnect
                ws.close(1000, "disconnect requested")
            }

            "events_api" -> {
                acknowledgeEnvelope(ws, envelope)
                handleEventsApi(envelope)
            }

            "slash_commands" -> {
                acknowledgeEnvelope(ws, envelope)
                handleSlashCommand(envelope)
            }

            "interactive" -> {
                acknowledgeEnvelope(ws, envelope)
                handleInteractive(envelope)
            }
        }
    }

    /**
     * Acknowledge the envelope within 3 seconds to prevent Slack retries.
     */
    private fun acknowledgeEnvelope(ws: WebSocket, envelope: JsonObject) {
        val envelopeId = envelope["envelope_id"]?.jsonPrimitive?.contentOrNull ?: return
        val ack = buildJsonObject { put("envelope_id", envelopeId) }
        ws.send(ack.toString())
    }

    // --- Events API handling ---

    private suspend fun handleEventsApi(envelope: JsonObject) {
        val payload = envelope["payload"]?.jsonObject ?: return
        val event = payload["event"]?.jsonObject ?: return
        val eventType = event["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (eventType) {
            "message" -> handleMessageEvent(event)
            "app_mention" -> handleMessageEvent(event, wasMention = true)
        }
    }

    private suspend fun handleMessageEvent(event: JsonObject, wasMention: Boolean = false) {
        // Skip bot's own messages and message subtypes like message_changed/deleted
        val subtype = event["subtype"]?.jsonPrimitive?.contentOrNull
        if (subtype != null && subtype != "file_share") return

        val user = event["user"]?.jsonPrimitive?.contentOrNull ?: return
        // Skip our own messages
        if (user == botUserId) return

        val text = event["text"]?.jsonPrimitive?.contentOrNull ?: return
        val channel = event["channel"]?.jsonPrimitive?.contentOrNull ?: return
        val ts = event["ts"]?.jsonPrimitive?.contentOrNull
        val threadTs = event["thread_ts"]?.jsonPrimitive?.contentOrNull
        val channelType = event["channel_type"]?.jsonPrimitive?.contentOrNull

        val chatType = when (channelType) {
            "im" -> ChatType.DIRECT
            "mpim" -> ChatType.GROUP
            "channel", "group" -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val senderName = resolveUserName(user) ?: user

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = user,
            senderName = senderName,
            targetId = channel,
            text = text,
            messageId = ts,
            threadId = threadTs ?: if (chatType != ChatType.DIRECT) ts else null,
            metadata = buildMap {
                put("slack_channel", channel)
                put("slack_user", user)
                if (ts != null) put("slack_ts", ts)
                if (threadTs != null) put("slack_thread_ts", threadTs)
                if (wasMention) put("slack_mention", "true")
                if (channelType != null) put("slack_channel_type", channelType)
            },
        )

        dispatchInbound(inbound)
    }

    // --- Slash commands ---

    private suspend fun handleSlashCommand(envelope: JsonObject) {
        val payload = envelope["payload"]?.jsonObject ?: return
        val command = payload["command"]?.jsonPrimitive?.contentOrNull ?: ""
        val text = payload["text"]?.jsonPrimitive?.contentOrNull ?: ""
        val userId = payload["user_id"]?.jsonPrimitive?.contentOrNull ?: return
        val userName = payload["user_name"]?.jsonPrimitive?.contentOrNull ?: userId
        val channelId = payload["channel_id"]?.jsonPrimitive?.contentOrNull ?: return

        val inbound = InboundMessage(
            channelId = this.channelId,
            chatType = ChatType.GROUP,
            senderId = userId,
            senderName = userName,
            targetId = channelId,
            text = "$command $text".trim(),
            metadata = buildMap {
                put("slack_channel", channelId)
                put("slack_user", userId)
                put("slack_slash_command", command)
            },
        )

        dispatchInbound(inbound)
    }

    // --- Interactive payloads (button clicks, modals, etc.) ---

    private suspend fun handleInteractive(envelope: JsonObject) {
        val payload = envelope["payload"]?.jsonObject ?: return
        val interactionType = payload["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (interactionType != "block_actions") return

        val user = payload["user"]?.jsonObject
        val userId = user?.get("id")?.jsonPrimitive?.contentOrNull ?: return
        val userName = user["name"]?.jsonPrimitive?.contentOrNull
            ?: user["username"]?.jsonPrimitive?.contentOrNull ?: userId
        val channel = payload["channel"]?.jsonObject
        val channelId = channel?.get("id")?.jsonPrimitive?.contentOrNull ?: return

        val actions = payload["actions"]?.jsonArray ?: return
        val firstAction = actions.firstOrNull()?.jsonObject ?: return
        val actionValue = firstAction["value"]?.jsonPrimitive?.contentOrNull
            ?: firstAction["selected_option"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            ?: return

        val inbound = InboundMessage(
            channelId = this.channelId,
            chatType = ChatType.GROUP,
            senderId = userId,
            senderName = userName,
            targetId = channelId,
            text = actionValue,
            metadata = buildMap {
                put("slack_channel", channelId)
                put("slack_user", userId)
                put("slack_interactive", "true")
                put("slack_action_id", firstAction["action_id"]?.jsonPrimitive?.contentOrNull ?: "")
            },
        )

        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val targetId = message.targetId
        val text = message.text

        // Send typing indicator
        sendTypingIndicator(targetId, message.threadId)

        // Split into chunks of 4000 chars for readability
        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true

        for ((i, chunk) in chunks.withIndex()) {
            val params = buildJsonObject {
                put("channel", targetId)
                put("text", chunk)
                // Thread support: reply in thread if threadId is specified
                val threadTs = message.threadId
                    ?: if (i > 0) null else message.replyToMessageId
                if (threadTs != null) {
                    put("thread_ts", threadTs)
                }
            }
            if (!callWebApi("chat.postMessage", params)) {
                success = false
            }
        }
        success
    }

    /**
     * Add a reaction emoji to a message.
     */
    fun addReaction(channel: String, timestamp: String, emoji: String): Boolean {
        val params = buildJsonObject {
            put("channel", channel)
            put("timestamp", timestamp)
            put("name", emoji.removePrefix(":").removeSuffix(":"))
        }
        return callWebApi("reactions.add", params)
    }

    /**
     * Send a typing indicator in a channel/thread.
     */
    private fun sendTypingIndicator(channel: String, threadTs: String? = null) {
        // Slack doesn't have a dedicated "typing" API for bots;
        // typing indicators only work via the RTM or Socket Mode client-side.
        // This is a no-op for Web API bots.
    }

    // --- API helpers ---

    private fun resolveBotUserId() {
        val request = Request.Builder()
            .url("https://slack.com/api/auth.test")
            .header("Authorization", "Bearer $botToken")
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return
                val result = json.parseToJsonElement(body).jsonObject
                if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                    botUserId = result["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
                }
            }
        } catch (_: Exception) {
            // Non-fatal; we will fall back to not filtering our own messages by user id
        }
    }

    private fun resolveUserName(userId: String): String? {
        userNameCache[userId]?.let { return it }

        val request = Request.Builder()
            .url("https://slack.com/api/users.info?user=$userId")
            .header("Authorization", "Bearer $botToken")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val result = json.parseToJsonElement(body).jsonObject
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return@use null
                val user = result["user"]?.jsonObject ?: return@use null
                val profile = user["profile"]?.jsonObject
                val realName = profile?.get("real_name")?.jsonPrimitive?.contentOrNull
                val displayName = profile?.get("display_name")?.jsonPrimitive?.contentOrNull
                val name = displayName?.takeIf { it.isNotBlank() }
                    ?: realName?.takeIf { it.isNotBlank() }
                    ?: user["name"]?.jsonPrimitive?.contentOrNull
                name?.also { userNameCache[userId] = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callWebApi(method: String, params: JsonObject): Boolean {
        val request = Request.Builder()
            .url("https://slack.com/api/$method")
            .header("Authorization", "Bearer $botToken")
            .header("Content-Type", "application/json")
            .post(params.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                response.isSuccessful && body?.let {
                    json.parseToJsonElement(it).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull
                } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Slack's hard limit is 40000, but we chunk at 4000 for readability. */
        const val TEXT_CHUNK_LIMIT = 4000

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
