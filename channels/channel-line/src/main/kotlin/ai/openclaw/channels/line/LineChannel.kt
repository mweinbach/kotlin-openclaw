package ai.openclaw.channels.line

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LINE Messaging API channel implementation.
 * Receives events via webhook and sends messages via the LINE API.
 *
 * Requires:
 * - channelAccessToken: Long-lived channel access token
 * - channelSecret: Channel secret for webhook signature verification
 */
class LineChannel(
    private val channelAccessToken: String,
    private val channelSecret: String,
    private val apiBaseUrl: String = "https://api.line.me/v2",
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "line"
    override val displayName: String = "LINE"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = false,
        typing = false, groups = true, richText = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    // --- Lifecycle ---

    override suspend fun onStart() {
        pollingJob = scope.launch { pollLoop() }
    }

    override suspend fun onStop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Poll loop placeholder. In production, LINE events arrive via webhook POST.
     * On Android, events can be relayed through a gateway proxy.
     */
    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            delay(5000)
        }
    }

    /**
     * Process an incoming webhook payload from the LINE Platform.
     * Called externally when a webhook event is received.
     */
    suspend fun processWebhook(payload: String) {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return
        }

        val events = root["events"]?.jsonArray ?: return
        for (event in events) {
            processEvent(event.jsonObject)
        }
    }

    private suspend fun processEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "message" -> processMessageEvent(event)
            "follow" -> processFollowEvent(event)
            "postback" -> processPostbackEvent(event)
        }
    }

    private suspend fun processMessageEvent(event: JsonObject) {
        val source = event["source"]?.jsonObject ?: return
        val message = event["message"]?.jsonObject ?: return
        val replyToken = event["replyToken"]?.jsonPrimitive?.contentOrNull

        val sourceType = source["type"]?.jsonPrimitive?.contentOrNull ?: "user"
        val userId = source["userId"]?.jsonPrimitive?.contentOrNull ?: return
        val groupId = source["groupId"]?.jsonPrimitive?.contentOrNull
        val roomId = source["roomId"]?.jsonPrimitive?.contentOrNull

        val msgType = message["type"]?.jsonPrimitive?.contentOrNull ?: return
        val msgId = message["id"]?.jsonPrimitive?.contentOrNull

        val text = when (msgType) {
            "text" -> message["text"]?.jsonPrimitive?.contentOrNull
            "image" -> "[Image]"
            "video" -> "[Video]"
            "audio" -> "[Audio]"
            "file" -> {
                val fileName = message["fileName"]?.jsonPrimitive?.contentOrNull ?: "file"
                "[File: $fileName]"
            }
            "location" -> {
                val title = message["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val address = message["address"]?.jsonPrimitive?.contentOrNull ?: ""
                val lat = message["latitude"]?.jsonPrimitive?.contentOrNull ?: "0"
                val lng = message["longitude"]?.jsonPrimitive?.contentOrNull ?: "0"
                "[Location: $title $address ($lat, $lng)]".trim()
            }
            "sticker" -> {
                val pkgId = message["packageId"]?.jsonPrimitive?.contentOrNull ?: ""
                val stkId = message["stickerId"]?.jsonPrimitive?.contentOrNull ?: ""
                "[Sticker: $pkgId/$stkId]"
            }
            else -> null
        } ?: return

        val chatType = when (sourceType) {
            "user" -> ChatType.DIRECT
            "group" -> ChatType.GROUP
            "room" -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val targetId = groupId ?: roomId ?: userId
        val senderName = resolveUserName(userId, groupId) ?: userId

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = userId,
            senderName = senderName,
            targetId = targetId,
            text = text,
            messageId = msgId,
            metadata = buildMap {
                put("line_source_type", sourceType)
                put("line_user_id", userId)
                if (groupId != null) put("line_group_id", groupId)
                if (roomId != null) put("line_room_id", roomId)
                if (msgId != null) put("line_message_id", msgId)
                if (replyToken != null) put("line_reply_token", replyToken)
                put("line_message_type", msgType)
            },
        )

        dispatchInbound(inbound)
    }

    private suspend fun processFollowEvent(event: JsonObject) {
        val source = event["source"]?.jsonObject ?: return
        val userId = source["userId"]?.jsonPrimitive?.contentOrNull ?: return
        val replyToken = event["replyToken"]?.jsonPrimitive?.contentOrNull

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = ChatType.DIRECT,
            senderId = userId,
            senderName = userId,
            targetId = userId,
            text = "/start",
            metadata = buildMap {
                put("line_event_type", "follow")
                put("line_user_id", userId)
                if (replyToken != null) put("line_reply_token", replyToken)
            },
        )

        dispatchInbound(inbound)
    }

    private suspend fun processPostbackEvent(event: JsonObject) {
        val source = event["source"]?.jsonObject ?: return
        val userId = source["userId"]?.jsonPrimitive?.contentOrNull ?: return
        val groupId = source["groupId"]?.jsonPrimitive?.contentOrNull
        val postback = event["postback"]?.jsonObject ?: return
        val data = postback["data"]?.jsonPrimitive?.contentOrNull ?: return

        val targetId = groupId ?: userId

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = if (groupId != null) ChatType.GROUP else ChatType.DIRECT,
            senderId = userId,
            senderName = userId,
            targetId = targetId,
            text = data,
            metadata = buildMap {
                put("line_event_type", "postback")
                put("line_user_id", userId)
                if (groupId != null) put("line_group_id", groupId)
                put("line_postback_data", data)
            },
        )

        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val targetId = message.targetId
        val text = message.text

        val replyToken = message.metadata["line_reply_token"]
        if (replyToken != null) {
            sendReply(replyToken, text)
        } else {
            sendPush(targetId, text)
        }
    }

    private fun sendReply(replyToken: String, text: String): Boolean {
        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        val messages = buildJsonArray {
            for (chunk in chunks.take(5)) { // LINE allows max 5 messages per reply
                addJsonObject {
                    put("type", "text")
                    put("text", chunk)
                }
            }
        }
        val params = buildJsonObject {
            put("replyToken", replyToken)
            put("messages", messages)
        }
        return callMessagingApi("bot/message/reply", params)
    }

    private fun sendPush(targetId: String, text: String): Boolean {
        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true
        // Push messages in batches of 5
        for (batch in chunks.chunked(5)) {
            val messages = buildJsonArray {
                for (chunk in batch) {
                    addJsonObject {
                        put("type", "text")
                        put("text", chunk)
                    }
                }
            }
            val params = buildJsonObject {
                put("to", targetId)
                put("messages", messages)
            }
            if (!callMessagingApi("bot/message/push", params)) {
                success = false
            }
        }
        return success
    }

    fun sendSticker(targetId: String, packageId: String, stickerId: String): Boolean {
        val params = buildJsonObject {
            put("to", targetId)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("type", "sticker")
                    put("packageId", packageId)
                    put("stickerId", stickerId)
                }
            })
        }
        return callMessagingApi("bot/message/push", params)
    }

    fun sendImage(targetId: String, imageUrl: String, previewUrl: String? = null): Boolean {
        val params = buildJsonObject {
            put("to", targetId)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("type", "image")
                    put("originalContentUrl", imageUrl)
                    put("previewImageUrl", previewUrl ?: imageUrl)
                }
            })
        }
        return callMessagingApi("bot/message/push", params)
    }

    // --- API helpers ---

    private fun resolveUserName(userId: String, groupId: String? = null): String? {
        val url = if (groupId != null) {
            "$apiBaseUrl/bot/group/$groupId/member/$userId"
        } else {
            "$apiBaseUrl/bot/profile/$userId"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $channelAccessToken")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val result = json.parseToJsonElement(body).jsonObject
                result["displayName"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callMessagingApi(endpoint: String, params: JsonObject): Boolean {
        val request = Request.Builder()
            .url("$apiBaseUrl/$endpoint")
            .header("Authorization", "Bearer $channelAccessToken")
            .header("Content-Type", "application/json")
            .post(params.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val TEXT_CHUNK_LIMIT = 5000

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
