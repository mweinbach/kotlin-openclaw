package ai.openclaw.channels.matrix

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Matrix channel implementation using the Client-Server API.
 * Long-polls /sync for inbound events, sends via PUT rooms/{roomId}/send.
 */
class MatrixChannel(
    private val homeserverUrl: String,
    private val accessToken: String,
    private val userId: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val syncTimeoutMs: Int = 30000,
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "matrix"
    override val displayName: String = "Matrix"
    override val capabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        threads = true,
        groups = true,
        typing = true,
    )

    private var syncJob: Job? = null
    private var nextBatch: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json".toMediaType()
    private val baseUrl get() = homeserverUrl.trimEnd('/')

    // --- Lifecycle ---

    override suspend fun onStart() {
        syncJob = scope.launch { syncLoop() }
    }

    override suspend fun onStop() {
        syncJob?.cancel()
        syncJob = null
    }

    // --- Sync loop ---

    private suspend fun syncLoop() {
        // Initial sync to get the since token without processing old messages
        try {
            val token = initialSync()
            if (token != null) nextBatch = token
        } catch (_: Exception) {
            // Will retry in the main loop
        }

        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                val response = sync(nextBatch, syncTimeoutMs)
                backoffMs = 2000L
                val batch = response["next_batch"]?.jsonPrimitive?.contentOrNull
                if (batch != null) nextBatch = batch

                processSync(response)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    private fun initialSync(): String? {
        val url = "$baseUrl/_matrix/client/v3/sync?timeout=0&filter={\"room\":{\"timeline\":{\"limit\":0}}}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@use null
            if (!response.isSuccessful) return@use null
            val obj = json.parseToJsonElement(body).jsonObject
            obj["next_batch"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun sync(since: String?, timeout: Int): JsonObject {
        val urlBuilder = StringBuilder("$baseUrl/_matrix/client/v3/sync?timeout=$timeout")
        if (since != null) urlBuilder.append("&since=$since")

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Bearer $accessToken")
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@use JsonObject(emptyMap())
            if (!response.isSuccessful) return@use JsonObject(emptyMap())
            json.parseToJsonElement(body).jsonObject
        }
    }

    // --- Inbound processing ---

    private suspend fun processSync(syncResponse: JsonObject) {
        val rooms = syncResponse["rooms"]?.jsonObject ?: return
        val joinedRooms = rooms["join"]?.jsonObject ?: return

        for ((roomId, roomData) in joinedRooms) {
            val timeline = roomData.jsonObject["timeline"]?.jsonObject ?: continue
            val events = timeline["events"]?.jsonArray ?: continue

            for (event in events) {
                processEvent(roomId, event.jsonObject)
            }
        }
    }

    private suspend fun processEvent(roomId: String, event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type != "m.room.message") return

        val sender = event["sender"]?.jsonPrimitive?.contentOrNull ?: return
        // Skip own messages
        if (sender == userId) return

        val content = event["content"]?.jsonObject ?: return
        val msgType = content["msgtype"]?.jsonPrimitive?.contentOrNull ?: return
        if (msgType != "m.text") return

        val body = content["body"]?.jsonPrimitive?.contentOrNull ?: return
        val eventId = event["event_id"]?.jsonPrimitive?.contentOrNull

        // Thread support: check m.relates_to for threading
        val relatesTo = content["m.relates_to"]?.jsonObject
        val threadId = if (relatesTo?.get("rel_type")?.jsonPrimitive?.contentOrNull == "m.thread") {
            relatesTo["event_id"]?.jsonPrimitive?.contentOrNull
        } else null

        // Determine chat type based on room member count heuristic
        val chatType = ChatType.GROUP // Matrix rooms are typically group-like

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = sender,
            senderName = extractDisplayName(sender),
            targetId = roomId,
            text = body,
            messageId = eventId,
            threadId = threadId,
            metadata = buildMap {
                put("matrix_room_id", roomId)
                put("matrix_sender", sender)
                if (eventId != null) put("matrix_event_id", eventId)
            },
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val roomId = message.targetId
        val text = message.text
        val txnId = UUID.randomUUID().toString()

        val content = buildJsonObject {
            put("msgtype", "m.text")
            put("body", text)
            // Thread support
            val threadId = message.threadId
            if (threadId != null) {
                putJsonObject("m.relates_to") {
                    put("rel_type", "m.thread")
                    put("event_id", threadId)
                }
            }
        }

        val url = "$baseUrl/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(content.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Send a typing indicator to a room.
     */
    fun sendTyping(roomId: String, typing: Boolean, timeoutMs: Int = 5000): Boolean {
        val body = buildJsonObject {
            put("typing", typing)
            if (typing) put("timeout", timeoutMs)
        }
        val url = "$baseUrl/_matrix/client/v3/rooms/$roomId/typing/$userId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Helpers ---

    private fun extractDisplayName(matrixUserId: String): String {
        // @user:server.com -> user
        return matrixUserId.removePrefix("@").substringBefore(':')
    }
}
