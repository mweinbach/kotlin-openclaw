package ai.openclaw.channels.signal

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Signal channel implementation using signal-cli-rest-api.
 * Polls /v1/receive for inbound messages, sends via /v2/send.
 */
class SignalChannel(
    private val apiUrl: String,
    private val number: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val pollIntervalMs: Long = 1000,
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "signal"
    override val displayName: String = "Signal"
    override val capabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        groups = true,
    )

    private var pollingJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json".toMediaType()

    // --- Lifecycle ---

    override suspend fun onStart() {
        pollingJob = scope.launch { pollLoop() }
    }

    override suspend fun onStop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // --- Polling ---

    private suspend fun pollLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                val messages = receive()
                backoffMs = 2000L
                for (envelope in messages) {
                    processEnvelope(envelope)
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

    private fun receive(): List<JsonObject> {
        val url = "${apiUrl.trimEnd('/')}/v1/receive/$number"
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@use emptyList()
            if (!response.isSuccessful) return@use emptyList()
            try {
                json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // --- Inbound processing ---

    private suspend fun processEnvelope(envelope: JsonObject) {
        val dataMessage = envelope["envelope"]?.jsonObject?.get("dataMessage")?.jsonObject
            ?: envelope["dataMessage"]?.jsonObject
            ?: return

        val text = dataMessage["message"]?.jsonPrimitive?.contentOrNull ?: return
        val source = envelope["envelope"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
            ?: envelope["source"]?.jsonPrimitive?.contentOrNull
            ?: return
        val sourceName = envelope["envelope"]?.jsonObject?.get("sourceName")?.jsonPrimitive?.contentOrNull
            ?: envelope["sourceName"]?.jsonPrimitive?.contentOrNull
            ?: source
        val timestamp = dataMessage["timestamp"]?.jsonPrimitive?.longOrNull

        // Group info
        val groupInfo = dataMessage["groupInfo"]?.jsonObject
        val groupId = groupInfo?.get("groupId")?.jsonPrimitive?.contentOrNull

        val isGroup = groupId != null
        val targetId = if (isGroup) "group:$groupId" else source

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = if (isGroup) ChatType.GROUP else ChatType.DIRECT,
            senderId = source,
            senderName = sourceName,
            targetId = targetId,
            text = text,
            messageId = timestamp?.toString(),
            metadata = buildMap {
                put("signal_source", source)
                if (groupId != null) put("signal_group_id", groupId)
                if (timestamp != null) put("signal_timestamp", timestamp.toString())
            },
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val targetId = message.targetId
        val text = message.text

        val isGroup = targetId.startsWith("group:")

        val params = buildJsonObject {
            put("message", text)
            put("number", number)
            if (isGroup) {
                putJsonArray("recipients") { }
                put("group", targetId.removePrefix("group:"))
            } else {
                putJsonArray("recipients") {
                    add(targetId)
                }
            }
        }

        val url = "${apiUrl.trimEnd('/')}/v2/send"
        val request = Request.Builder()
            .url(url)
            .post(params.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
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
