package ai.openclaw.channels.mattermost

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mattermost channel implementation using WebSocket for receiving events
 * and the REST API v4 for sending messages.
 *
 * Requires:
 * - serverUrl: The Mattermost server URL (e.g. https://mattermost.example.com)
 * - accessToken: A personal access token or bot token
 */
class MattermostChannel(
    private val serverUrl: String,
    private val accessToken: String,
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "mattermost"
    override val displayName: String = "Mattermost"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = true,
        threads = true, groups = true, typing = true,
        editing = true, deletion = true, richText = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null
    private var webSocket: WebSocket? = null
    private var botUserId: String = ""
    private val baseApiUrl get() = "${serverUrl.trimEnd('/')}/api/v4"

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

    // --- WebSocket connection ---

    private suspend fun connectLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                connectWebSocket()
                backoffMs = 2000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    private suspend fun connectWebSocket() {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        val wsScheme = if (serverUrl.startsWith("https")) "wss" else "ws"
        val wsHost = serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val wsUrl = "$wsScheme://$wsHost/api/v4/websocket"

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@MattermostChannel.webSocket = webSocket
                // Authenticate via WebSocket
                val authChallenge = buildJsonObject {
                    put("seq", 1)
                    put("action", "authentication_challenge")
                    putJsonObject("data") {
                        put("token", accessToken)
                    }
                }
                webSocket.send(authChallenge.toString())
                connected.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleWebSocketMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@MattermostChannel.webSocket = null
                connected.completeExceptionally(t)
                closed.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@MattermostChannel.webSocket = null
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

    // --- WebSocket event handling ---

    private suspend fun handleWebSocketMessage(raw: String) {
        val event = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }

        val eventType = event["event"]?.jsonPrimitive?.contentOrNull ?: return

        when (eventType) {
            "posted" -> handlePostedEvent(event)
            "post_edited" -> handlePostedEvent(event, edited = true)
            "reaction_added" -> handleReactionEvent(event)
        }
    }

    private suspend fun handlePostedEvent(event: JsonObject, edited: Boolean = false) {
        val data = event["data"]?.jsonObject ?: return
        val postJson = data["post"]?.jsonPrimitive?.contentOrNull ?: return

        val post = try {
            json.parseToJsonElement(postJson).jsonObject
        } catch (_: Exception) {
            return
        }

        val userId = post["user_id"]?.jsonPrimitive?.contentOrNull ?: return
        // Skip our own messages
        if (userId == botUserId) return

        val message = post["message"]?.jsonPrimitive?.contentOrNull ?: return
        val postId = post["id"]?.jsonPrimitive?.contentOrNull
        val mmChannelId = post["channel_id"]?.jsonPrimitive?.contentOrNull ?: return
        val rootId = post["root_id"]?.jsonPrimitive?.contentOrNull

        val channelType = data["channel_type"]?.jsonPrimitive?.contentOrNull
        val senderName = data["sender_name"]?.jsonPrimitive?.contentOrNull
            ?: resolveUserName(userId) ?: userId

        val chatType = when (channelType) {
            "D" -> ChatType.DIRECT
            "G" -> ChatType.GROUP
            "O", "P" -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val teamId = data["team_id"]?.jsonPrimitive?.contentOrNull

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = userId,
            senderName = senderName.removePrefix("@"),
            targetId = mmChannelId,
            text = message,
            messageId = postId,
            threadId = rootId?.takeIf { it.isNotEmpty() },
            metadata = buildMap {
                put("mattermost_channel_id", mmChannelId)
                put("mattermost_user_id", userId)
                if (postId != null) put("mattermost_post_id", postId)
                if (rootId != null && rootId.isNotEmpty()) put("mattermost_root_id", rootId)
                if (teamId != null) put("mattermost_team_id", teamId)
                if (channelType != null) put("mattermost_channel_type", channelType)
                if (edited) put("mattermost_edited", "true")
            },
        )

        dispatchInbound(inbound)
    }

    private suspend fun handleReactionEvent(event: JsonObject) {
        val data = event["data"]?.jsonObject ?: return
        val reactionJson = data["reaction"]?.jsonPrimitive?.contentOrNull ?: return
        val reaction = try {
            json.parseToJsonElement(reactionJson).jsonObject
        } catch (_: Exception) {
            return
        }

        val userId = reaction["user_id"]?.jsonPrimitive?.contentOrNull ?: return
        if (userId == botUserId) return
        val postId = reaction["post_id"]?.jsonPrimitive?.contentOrNull ?: return
        val emojiName = reaction["emoji_name"]?.jsonPrimitive?.contentOrNull ?: return

        // We need the channel_id from the original post
        val post = getPost(postId) ?: return
        val mmChannelId = post["channel_id"]?.jsonPrimitive?.contentOrNull ?: return

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = ChatType.GROUP,
            senderId = userId,
            senderName = resolveUserName(userId) ?: userId,
            targetId = mmChannelId,
            text = "[Reaction: :$emojiName:]",
            replyToMessageId = postId,
            metadata = buildMap {
                put("mattermost_channel_id", mmChannelId)
                put("mattermost_user_id", userId)
                put("mattermost_reaction", emojiName)
                put("mattermost_post_id", postId)
            },
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val mmChannelId = message.targetId
        val text = message.text

        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true

        for ((i, chunk) in chunks.withIndex()) {
            val params = buildJsonObject {
                put("channel_id", mmChannelId)
                put("message", chunk)
                // Thread support
                val rootId = message.threadId
                    ?: if (i > 0) null else message.replyToMessageId
                if (rootId != null) {
                    put("root_id", rootId)
                }
            }
            if (!callRestApi("POST", "/posts", params)) {
                success = false
            }
        }
        success
    }

    fun addReaction(postId: String, emojiName: String): Boolean {
        val params = buildJsonObject {
            put("user_id", botUserId)
            put("post_id", postId)
            put("emoji_name", emojiName)
        }
        return callRestApi("POST", "/reactions", params)
    }

    // --- API helpers ---

    private fun resolveBotUserId() {
        val request = Request.Builder()
            .url("$baseApiUrl/users/me")
            .header("Authorization", "Bearer $accessToken")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return
                if (!response.isSuccessful) return
                val result = json.parseToJsonElement(body).jsonObject
                botUserId = result["id"]?.jsonPrimitive?.contentOrNull ?: ""
            }
        } catch (_: Exception) { }
    }

    private fun resolveUserName(userId: String): String? {
        val request = Request.Builder()
            .url("$baseApiUrl/users/$userId")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val user = json.parseToJsonElement(body).jsonObject
                val first = user["first_name"]?.jsonPrimitive?.contentOrNull ?: ""
                val last = user["last_name"]?.jsonPrimitive?.contentOrNull ?: ""
                val full = "$first $last".trim()
                full.ifEmpty {
                    user["username"]?.jsonPrimitive?.contentOrNull
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getPost(postId: String): JsonObject? {
        val request = Request.Builder()
            .url("$baseApiUrl/posts/$postId")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.parseToJsonElement(body).jsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callRestApi(method: String, path: String, params: JsonObject? = null): Boolean {
        val builder = Request.Builder()
            .url("$baseApiUrl$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")

        when (method) {
            "POST" -> builder.post(
                (params?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
            )
            "PUT" -> builder.put(
                (params?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
            )
            "DELETE" -> builder.delete()
            else -> builder.get()
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val TEXT_CHUNK_LIMIT = 16383

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
