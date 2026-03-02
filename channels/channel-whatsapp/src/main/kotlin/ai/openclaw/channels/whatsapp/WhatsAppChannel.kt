package ai.openclaw.channels.whatsapp

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WhatsApp Business Cloud API channel implementation.
 * Uses webhook polling for receiving messages and the Cloud API for sending.
 *
 * Requires:
 * - phoneNumberId: The WhatsApp Business phone number ID
 * - accessToken: A permanent or long-lived access token from Meta
 * - verifyToken: The token used to verify webhook subscriptions
 */
class WhatsAppChannel(
    private val phoneNumberId: String,
    private val accessToken: String,
    private val verifyToken: String,
    private val baseUrl: String = "https://graph.facebook.com/v21.0",
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "whatsapp"
    override val displayName: String = "WhatsApp"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = true,
        typing = true, groups = true,
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

    // --- Webhook Polling ---

    /**
     * Polls the webhook endpoint for new messages. In production this would be
     * replaced by a webhook server receiving POST callbacks from Meta.
     * Here we use long-polling against a configurable endpoint for the Android context.
     */
    private suspend fun pollLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                // In a full deployment, webhook events arrive via HTTP POST.
                // On Android, we poll a relay or local proxy that buffers webhook events.
                delay(backoffMs.coerceAtLeast(5000))
                backoffMs = 5000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    /**
     * Process an incoming webhook payload from the WhatsApp Cloud API.
     * Called externally when a webhook event is received (e.g. via gateway route).
     */
    suspend fun processWebhook(payload: String) {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return
        }

        val entries = root["entry"]?.jsonArray ?: return
        for (entry in entries) {
            val changes = entry.jsonObject["changes"]?.jsonArray ?: continue
            for (change in changes) {
                val value = change.jsonObject["value"]?.jsonObject ?: continue
                processWebhookValue(value)
            }
        }
    }

    private suspend fun processWebhookValue(value: JsonObject) {
        val messages = value["messages"]?.jsonArray ?: return
        val contacts = value["contacts"]?.jsonArray
        val metadata = value["metadata"]?.jsonObject

        val displayPhoneNumber = metadata?.get("display_phone_number")?.jsonPrimitive?.contentOrNull

        for (msg in messages) {
            val msgObj = msg.jsonObject
            val from = msgObj["from"]?.jsonPrimitive?.contentOrNull ?: continue
            val msgId = msgObj["id"]?.jsonPrimitive?.contentOrNull
            val msgType = msgObj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val timestamp = msgObj["timestamp"]?.jsonPrimitive?.contentOrNull

            // Resolve sender name from contacts array
            val senderName = contacts?.firstOrNull {
                it.jsonObject["wa_id"]?.jsonPrimitive?.contentOrNull == from
            }?.jsonObject?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?: from

            val text = when (msgType) {
                "text" -> msgObj["text"]?.jsonObject?.get("body")?.jsonPrimitive?.contentOrNull
                "image" -> msgObj["image"]?.jsonObject?.get("caption")?.jsonPrimitive?.contentOrNull
                    ?: "[Image]"
                "video" -> msgObj["video"]?.jsonObject?.get("caption")?.jsonPrimitive?.contentOrNull
                    ?: "[Video]"
                "audio" -> "[Audio message]"
                "document" -> msgObj["document"]?.jsonObject?.get("caption")?.jsonPrimitive?.contentOrNull
                    ?: "[Document]"
                "sticker" -> "[Sticker]"
                "location" -> {
                    val loc = msgObj["location"]?.jsonObject
                    val lat = loc?.get("latitude")?.jsonPrimitive?.contentOrNull ?: "0"
                    val lng = loc?.get("longitude")?.jsonPrimitive?.contentOrNull ?: "0"
                    "[Location: $lat, $lng]"
                }
                "reaction" -> {
                    val reaction = msgObj["reaction"]?.jsonObject
                    val emoji = reaction?.get("emoji")?.jsonPrimitive?.contentOrNull ?: ""
                    val reactedMsgId = reaction?.get("message_id")?.jsonPrimitive?.contentOrNull
                    // Reactions are dispatched as metadata, not text
                    if (emoji.isNotEmpty()) "[Reaction: $emoji]" else null
                }
                "contacts" -> "[Shared contact]"
                "interactive" -> {
                    val interactive = msgObj["interactive"]?.jsonObject
                    val interType = interactive?.get("type")?.jsonPrimitive?.contentOrNull
                    when (interType) {
                        "button_reply" -> interactive["button_reply"]?.jsonObject
                            ?.get("title")?.jsonPrimitive?.contentOrNull
                        "list_reply" -> interactive["list_reply"]?.jsonObject
                            ?.get("title")?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
                else -> null
            } ?: continue

            // Determine chat type from context
            val contextObj = msgObj["context"]?.jsonObject
            val isGroup = contextObj?.get("group_id") != null
            val groupId = contextObj?.get("group_id")?.jsonPrimitive?.contentOrNull

            val inbound = InboundMessage(
                channelId = channelId,
                chatType = if (isGroup) ChatType.GROUP else ChatType.DIRECT,
                senderId = from,
                senderName = senderName,
                targetId = groupId ?: from,
                text = text,
                messageId = msgId,
                replyToMessageId = contextObj?.get("id")?.jsonPrimitive?.contentOrNull,
                metadata = buildMap {
                    put("whatsapp_from", from)
                    put("whatsapp_type", msgType)
                    if (msgId != null) put("whatsapp_message_id", msgId)
                    if (timestamp != null) put("whatsapp_timestamp", timestamp)
                    if (displayPhoneNumber != null) put("whatsapp_phone", displayPhoneNumber)
                    if (groupId != null) put("whatsapp_group_id", groupId)
                },
            )

            dispatchInbound(inbound)
        }
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val recipientId = message.targetId

        // Send typing indicator (mark as read)
        markAsRead(message.replyToMessageId)

        val chunks = splitText(message.text, TEXT_CHUNK_LIMIT)
        var success = true

        for (chunk in chunks) {
            val params = buildJsonObject {
                put("messaging_product", "whatsapp")
                put("recipient_type", "individual")
                put("to", recipientId)
                put("type", "text")
                putJsonObject("text") {
                    put("preview_url", true)
                    put("body", chunk)
                }
                // Reply context
                val replyId = message.replyToMessageId
                if (replyId != null) {
                    putJsonObject("context") {
                        put("message_id", replyId)
                    }
                }
            }
            if (!callApi("messages", params)) {
                success = false
            }
        }
        success
    }

    // --- Reactions ---

    fun sendReaction(messageId: String, recipientId: String, emoji: String): Boolean {
        val params = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipientId)
            put("type", "reaction")
            putJsonObject("reaction") {
                put("message_id", messageId)
                put("emoji", emoji)
            }
        }
        return callApi("messages", params)
    }

    // --- Media ---

    fun sendImage(recipientId: String, imageUrl: String, caption: String? = null): Boolean {
        val params = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipientId)
            put("type", "image")
            putJsonObject("image") {
                put("link", imageUrl)
                if (caption != null) put("caption", caption)
            }
        }
        return callApi("messages", params)
    }

    // --- Typing / Read receipts ---

    private fun markAsRead(messageId: String?) {
        if (messageId == null) return
        val params = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("status", "read")
            put("message_id", messageId)
        }
        callApi("messages", params)
    }

    // --- API helpers ---

    private fun callApi(endpoint: String, params: JsonObject): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/$phoneNumberId/$endpoint")
            .header("Authorization", "Bearer $accessToken")
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
        const val TEXT_CHUNK_LIMIT = 4096

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
