package ai.openclaw.channels.googlechat

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Chat channel implementation using the Google Chat API.
 * Uses Pub/Sub subscription polling for inbound messages,
 * and spaces.messages.create for outbound.
 *
 * Requires a service account with Chat API access and a configured
 * Pub/Sub subscription for receiving messages.
 */
class GoogleChatChannel(
    private val serviceAccountToken: () -> String,
    private val pubsubSubscription: String? = null,
    private val pubsubProjectId: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val pollIntervalMs: Long = 2000,
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "googlechat"
    override val displayName: String = "Google Chat"
    override val capabilities = ChannelCapabilities(
        text = true,
        images = true,
        threads = true,
        groups = true,
        typing = true,
    )

    private var pollingJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json".toMediaType()
    private val chatApiBase = "https://chat.googleapis.com/v1"
    private val pubsubApiBase = "https://pubsub.googleapis.com/v1"

    // --- Lifecycle ---

    override suspend fun onStart() {
        if (pubsubSubscription != null) {
            pollingJob = scope.launch { pollLoop() }
        }
    }

    override suspend fun onStop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // --- Pub/Sub polling for inbound ---

    private suspend fun pollLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                val messages = pullMessages()
                backoffMs = 2000L
                for (msg in messages) {
                    processMessage(msg)
                }
                delay(pollIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    private fun pullMessages(): List<PubSubMessage> {
        val sub = pubsubSubscription ?: return emptyList()
        val url = "$pubsubApiBase/$sub:pull"
        val body = buildJsonObject {
            put("maxMessages", 10)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${serviceAccountToken()}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val parsedResponse = client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: return emptyList()
            if (!response.isSuccessful) return emptyList()
            json.parseToJsonElement(responseBody).jsonObject
        }

        val obj = parsedResponse
        val receivedMessages = obj["receivedMessages"]?.jsonArray ?: return emptyList()

        val result = mutableListOf<PubSubMessage>()
        val ackIds = mutableListOf<String>()

        for (rm in receivedMessages) {
            val rmObj = rm.jsonObject
            val ackId = rmObj["ackId"]?.jsonPrimitive?.contentOrNull
            if (ackId != null) ackIds.add(ackId)

            val message = rmObj["message"]?.jsonObject ?: continue
            val data = message["data"]?.jsonPrimitive?.contentOrNull ?: continue

            // Data is base64-encoded JSON
            try {
                val decoded = String(java.util.Base64.getDecoder().decode(data))
                val eventObj = json.parseToJsonElement(decoded).jsonObject
                result.add(PubSubMessage(eventObj))
            } catch (_: Exception) {
                // Skip malformed messages
            }
        }

        // Acknowledge messages
        if (ackIds.isNotEmpty()) {
            acknowledgeMessages(ackIds)
        }

        return result
    }

    private fun acknowledgeMessages(ackIds: List<String>) {
        val sub = pubsubSubscription ?: return
        val url = "$pubsubApiBase/$sub:acknowledge"
        val body = buildJsonObject {
            putJsonArray("ackIds") {
                for (id in ackIds) add(id)
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${serviceAccountToken()}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { }
        } catch (_: Exception) {
            // Best effort
        }
    }

    private data class PubSubMessage(val event: JsonObject)

    // --- Inbound processing ---

    private suspend fun processMessage(msg: PubSubMessage) {
        val event = msg.event
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type != "MESSAGE" && type != "ADDED_TO_SPACE") return

        val message = event["message"]?.jsonObject ?: return
        val text = message["text"]?.jsonPrimitive?.contentOrNull
            ?: message["argumentText"]?.jsonPrimitive?.contentOrNull
            ?: return
        val sender = event["user"]?.jsonObject ?: message["sender"]?.jsonObject ?: return
        val senderName = sender["displayName"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
        val senderEmail = sender["email"]?.jsonPrimitive?.contentOrNull
        val senderType = sender["type"]?.jsonPrimitive?.contentOrNull

        // Skip bot messages
        if (senderType == "BOT") return

        val space = event["space"]?.jsonObject ?: message["space"]?.jsonObject
        val spaceName = space?.get("name")?.jsonPrimitive?.contentOrNull ?: return
        val spaceType = space["type"]?.jsonPrimitive?.contentOrNull
        val isGroup = spaceType == "ROOM" || spaceType == "SPACE"

        val messageName = message["name"]?.jsonPrimitive?.contentOrNull
        val threadName = message["thread"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = if (isGroup) ChatType.GROUP else ChatType.DIRECT,
            senderId = senderEmail ?: senderName,
            senderName = senderName,
            targetId = spaceName,
            text = text,
            messageId = messageName,
            threadId = threadName,
            metadata = buildMap {
                put("googlechat_space", spaceName)
                if (spaceType != null) put("googlechat_space_type", spaceType)
                if (messageName != null) put("googlechat_message_name", messageName)
                if (senderEmail != null) put("googlechat_sender_email", senderEmail)
            },
        )
        dispatchInbound(inbound)
    }

    /**
     * Handle an incoming webhook event directly (for HTTP push mode).
     */
    suspend fun handleWebhookEvent(eventJson: String) {
        try {
            val event = json.parseToJsonElement(eventJson).jsonObject
            processMessage(PubSubMessage(event))
        } catch (_: Exception) {
            // Skip malformed events
        }
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val spaceName = message.targetId
        val text = message.text

        val urlBuilder = StringBuilder("$chatApiBase/$spaceName/messages")
        val threadName = message.threadId
        if (threadName != null) {
            urlBuilder.append("?messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD")
        }

        val requestBody = buildJsonObject {
            put("text", text)
            if (threadName != null) {
                putJsonObject("thread") {
                    put("name", threadName)
                }
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Bearer ${serviceAccountToken()}")
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }
}
