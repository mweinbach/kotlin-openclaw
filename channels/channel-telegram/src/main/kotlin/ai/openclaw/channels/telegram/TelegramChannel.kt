package ai.openclaw.channels.telegram

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Telegram Bot API channel implementation.
 * Uses long-polling for receiving updates and REST API for sending messages.
 */
class TelegramChannel(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val client: OkHttpClient = OkHttpClient(),
    private val pollingTimeoutSec: Int = 30,
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "telegram"
    override val displayName: String = "Telegram"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = true,
        threads = true, groups = true, typing = true,
    )

    private var pollingJob: Job? = null
    private var lastUpdateId: Long = 0
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val updates = getUpdates(lastUpdateId + 1, pollingTimeoutSec)
                backoffMs = 2000L // reset on success
                for (update in updates) {
                    val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (updateId > lastUpdateId) lastUpdateId = updateId
                    processUpdate(update)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    private fun getUpdates(offset: Long, timeout: Int): List<JsonObject> {
        val url = "$baseUrl/bot$botToken/getUpdates?offset=$offset&timeout=$timeout" +
            "&allowed_updates=${allowedUpdates()}"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val result = json.parseToJsonElement(body).jsonObject
        return result["result"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    private fun allowedUpdates(): String =
        "[\"message\",\"edited_message\",\"channel_post\",\"callback_query\",\"message_reaction\"]"

    // --- Inbound processing ---

    private suspend fun processUpdate(update: JsonObject) {
        val message = update["message"]?.jsonObject
            ?: update["edited_message"]?.jsonObject
            ?: update["channel_post"]?.jsonObject

        if (message != null) {
            processMessage(message)
            return
        }

        val callbackQuery = update["callback_query"]?.jsonObject
        if (callbackQuery != null) {
            processCallbackQuery(callbackQuery)
        }
    }

    private suspend fun processMessage(message: JsonObject) {
        val chat = message["chat"]?.jsonObject ?: return
        val from = message["from"]?.jsonObject
        val chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: return
        val chatType = chat["type"]?.jsonPrimitive?.contentOrNull ?: "private"
        val messageId = message["message_id"]?.jsonPrimitive?.longOrNull
        val text = message["text"]?.jsonPrimitive?.contentOrNull
            ?: message["caption"]?.jsonPrimitive?.contentOrNull
            ?: return

        val senderId = from?.get("id")?.jsonPrimitive?.longOrNull?.toString() ?: chatId.toString()
        val senderName = buildSenderName(from)
        val threadId = message["message_thread_id"]?.jsonPrimitive?.longOrNull

        val resolvedChatType = when (chatType) {
            "private" -> ChatType.DIRECT
            "group", "supergroup" -> ChatType.GROUP
            "channel" -> ChatType.CHANNEL
            else -> ChatType.GROUP
        }

        // Build reply context
        val replyTo = message["reply_to_message"]?.jsonObject
        val replyText = replyTo?.let {
            val rt = it["text"]?.jsonPrimitive?.contentOrNull ?: ""
            if (rt.isNotEmpty()) "[Reply to: ${rt.take(100)}] " else ""
        } ?: ""

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = resolvedChatType,
            senderId = senderId,
            senderName = senderName,
            targetId = chatId.toString(),
            text = replyText + text,
            messageId = messageId?.toString(),
            threadId = threadId?.toString(),
            metadata = buildMap {
                put("telegram_chat_id", chatId.toString())
                put("telegram_chat_type", chatType)
                if (messageId != null) put("telegram_message_id", messageId.toString())
                from?.get("username")?.jsonPrimitive?.contentOrNull?.let { put("telegram_username", it) }
            },
        )

        dispatchInbound(inbound)
    }

    private suspend fun processCallbackQuery(query: JsonObject) {
        val from = query["from"]?.jsonObject
        val data = query["data"]?.jsonPrimitive?.contentOrNull ?: return
        val message = query["message"]?.jsonObject
        val chatId = message?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return
        val senderId = from?.get("id")?.jsonPrimitive?.longOrNull?.toString() ?: return

        // Answer callback to dismiss the loading indicator
        val queryId = query["id"]?.jsonPrimitive?.contentOrNull
        if (queryId != null) {
            answerCallbackQuery(queryId)
        }

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = ChatType.GROUP,
            senderId = senderId,
            senderName = buildSenderName(from),
            targetId = chatId.toString(),
            text = data,
            metadata = mapOf(
                "telegram_chat_id" to chatId.toString(),
                "telegram_callback" to "true",
            ),
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val chatId = message.targetId
        val text = message.text

        // Send typing indicator
        sendChatAction(chatId, "typing")

        // Split into chunks of 4000 chars (Telegram limit)
        val chunks = splitText(text, 4000)
        var success = true
        for ((i, chunk) in chunks.withIndex()) {
            val params = buildJsonObject {
                put("chat_id", chatId)
                put("text", chunk)
                put("parse_mode", "HTML")
                // Reply to original message on first chunk
                if (i == 0 && message.replyToMessageId != null) {
                    putJsonObject("reply_parameters") {
                        put("message_id", message.replyToMessageId!!.toLongOrNull() ?: 0)
                    }
                }
                // Thread support
                val threadId = message.threadId
                if (threadId != null) {
                    put("message_thread_id", threadId.toLongOrNull() ?: 0)
                }
            }
            if (!callApi("sendMessage", params)) {
                // Retry without HTML parse mode on format error
                val plainParams = buildJsonObject {
                    put("chat_id", chatId)
                    put("text", chunk)
                    if (i == 0 && message.replyToMessageId != null) {
                        putJsonObject("reply_parameters") {
                            put("message_id", message.replyToMessageId!!.toLongOrNull() ?: 0)
                        }
                    }
                    val threadId = message.threadId
                    if (threadId != null) {
                        put("message_thread_id", threadId.toLongOrNull() ?: 0)
                    }
                }
                if (!callApi("sendMessage", plainParams)) {
                    success = false
                }
            }
        }
        success
    }

    // --- Helpers ---

    fun sendChatAction(chatId: String, action: String) {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("action", action)
        }
        callApi("sendChatAction", params)
    }

    fun answerCallbackQuery(queryId: String, text: String? = null) {
        val params = buildJsonObject {
            put("callback_query_id", queryId)
            if (text != null) put("text", text)
        }
        callApi("answerCallbackQuery", params)
    }

    fun setMessageReaction(chatId: String, messageId: Long, emoji: String): Boolean {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            putJsonArray("reaction") {
                addJsonObject {
                    put("type", "emoji")
                    put("emoji", emoji)
                }
            }
        }
        return callApi("setMessageReaction", params)
    }

    private fun callApi(method: String, params: JsonObject): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/bot$botToken/$method")
            .header("Content-Type", "application/json")
            .post(params.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.isSuccessful && body?.contains("\"ok\":true") == true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSenderName(from: JsonObject?): String {
        if (from == null) return "Unknown"
        val first = from["first_name"]?.jsonPrimitive?.contentOrNull ?: ""
        val last = from["last_name"]?.jsonPrimitive?.contentOrNull ?: ""
        return "$first $last".trim().ifEmpty {
            from["username"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
        }
    }

    companion object {
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
