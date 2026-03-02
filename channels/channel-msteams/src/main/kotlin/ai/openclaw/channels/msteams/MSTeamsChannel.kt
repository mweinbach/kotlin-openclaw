package ai.openclaw.channels.msteams

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Microsoft Teams channel implementation using the Bot Framework / Graph API.
 * Receives activities via webhook and sends messages via the Bot Framework REST API.
 *
 * Requires:
 * - appId: Microsoft App ID (from Azure Bot registration)
 * - appPassword: Microsoft App Password (client secret)
 * - tenantId: Azure AD tenant ID (optional, for single-tenant apps)
 */
class MSTeamsChannel(
    private val appId: String,
    private val appPassword: String,
    private val tenantId: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "msteams"
    override val displayName: String = "Microsoft Teams"
    override val capabilities = ChannelCapabilities(
        text = true, images = true, reactions = true,
        threads = true, groups = true, typing = true,
        richText = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0
    private var pollingJob: Job? = null

    // --- Lifecycle ---

    override suspend fun onStart() {
        refreshAccessToken()
        pollingJob = scope.launch { tokenRefreshLoop() }
    }

    override suspend fun onStop() {
        pollingJob?.cancel()
        pollingJob = null
        accessToken = null
    }

    /**
     * Periodically refresh the OAuth2 access token before it expires.
     */
    private suspend fun tokenRefreshLoop() {
        while (currentCoroutineContext().isActive) {
            val sleepMs = (tokenExpiresAt - System.currentTimeMillis() - 300_000)
                .coerceAtLeast(60_000)
            delay(sleepMs)
            try {
                refreshAccessToken()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(30_000)
            }
        }
    }

    /**
     * Process an incoming Bot Framework activity (webhook POST body).
     * Called externally when a webhook event is received via the gateway.
     */
    suspend fun processActivity(payload: String) {
        val activity = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return
        }

        val type = activity["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "message" -> processMessageActivity(activity)
            "conversationUpdate" -> processConversationUpdate(activity)
            "messageReaction" -> processReaction(activity)
        }
    }

    private suspend fun processMessageActivity(activity: JsonObject) {
        val text = activity["text"]?.jsonPrimitive?.contentOrNull ?: return
        val from = activity["from"]?.jsonObject ?: return
        val fromId = from["id"]?.jsonPrimitive?.contentOrNull ?: return
        val fromName = from["name"]?.jsonPrimitive?.contentOrNull ?: fromId
        val conversation = activity["conversation"]?.jsonObject ?: return
        val conversationId = conversation["id"]?.jsonPrimitive?.contentOrNull ?: return
        val conversationType = conversation["conversationType"]?.jsonPrimitive?.contentOrNull
        val activityId = activity["id"]?.jsonPrimitive?.contentOrNull
        val serviceUrl = activity["serviceUrl"]?.jsonPrimitive?.contentOrNull ?: return
        val replyToId = activity["replyToId"]?.jsonPrimitive?.contentOrNull

        // Determine if this is a group/channel or 1:1 conversation
        val chatType = when (conversationType) {
            "personal" -> ChatType.DIRECT
            "groupChat" -> ChatType.GROUP
            "channel" -> ChatType.CHANNEL
            else -> if (conversationId.contains("@thread")) ChatType.GROUP else ChatType.DIRECT
        }

        // Strip bot mention from text
        val cleanText = stripBotMention(text, activity)

        val channelData = activity["channelData"]?.jsonObject
        val teamsChannelId = channelData?.get("channel")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull
        val teamId = channelData?.get("team")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = fromId,
            senderName = fromName,
            targetId = conversationId,
            text = cleanText,
            messageId = activityId,
            threadId = replyToId,
            metadata = buildMap {
                put("msteams_conversation_id", conversationId)
                put("msteams_service_url", serviceUrl)
                put("msteams_from_id", fromId)
                if (activityId != null) put("msteams_activity_id", activityId)
                if (conversationType != null) put("msteams_conversation_type", conversationType)
                if (teamsChannelId != null) put("msteams_channel_id", teamsChannelId)
                if (teamId != null) put("msteams_team_id", teamId)
            },
        )

        dispatchInbound(inbound)
    }

    private suspend fun processConversationUpdate(activity: JsonObject) {
        val membersAdded = activity["membersAdded"]?.jsonArray ?: return
        val conversation = activity["conversation"]?.jsonObject ?: return
        val conversationId = conversation["id"]?.jsonPrimitive?.contentOrNull ?: return
        val serviceUrl = activity["serviceUrl"]?.jsonPrimitive?.contentOrNull ?: return

        // Check if the bot was added to the conversation
        val botWasAdded = membersAdded.any { member ->
            member.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.contains(appId) == true
        }

        if (botWasAdded) {
            val inbound = InboundMessage(
                channelId = channelId,
                chatType = ChatType.GROUP,
                senderId = "system",
                senderName = "System",
                targetId = conversationId,
                text = "/start",
                metadata = mapOf(
                    "msteams_conversation_id" to conversationId,
                    "msteams_service_url" to serviceUrl,
                    "msteams_event" to "bot_added",
                ),
            )
            dispatchInbound(inbound)
        }
    }

    private suspend fun processReaction(activity: JsonObject) {
        val from = activity["from"]?.jsonObject ?: return
        val fromId = from["id"]?.jsonPrimitive?.contentOrNull ?: return
        val fromName = from["name"]?.jsonPrimitive?.contentOrNull ?: fromId
        val conversation = activity["conversation"]?.jsonObject ?: return
        val conversationId = conversation["id"]?.jsonPrimitive?.contentOrNull ?: return
        val reactionsAdded = activity["reactionsAdded"]?.jsonArray ?: return
        val replyToId = activity["replyToId"]?.jsonPrimitive?.contentOrNull

        for (reaction in reactionsAdded) {
            val reactionType = reaction.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: continue

            val inbound = InboundMessage(
                channelId = channelId,
                chatType = ChatType.GROUP,
                senderId = fromId,
                senderName = fromName,
                targetId = conversationId,
                text = "[Reaction: $reactionType]",
                replyToMessageId = replyToId,
                metadata = buildMap {
                    put("msteams_conversation_id", conversationId)
                    put("msteams_from_id", fromId)
                    put("msteams_reaction", reactionType)
                    if (replyToId != null) put("msteams_reply_to_id", replyToId)
                },
            )
            dispatchInbound(inbound)
        }
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val serviceUrl = message.metadata["msteams_service_url"] ?: return@withContext false
        val conversationId = message.targetId
        val text = message.text

        // Send typing indicator
        sendTypingActivity(serviceUrl, conversationId)

        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true

        for ((i, chunk) in chunks.withIndex()) {
            val activityPayload = buildJsonObject {
                put("type", "message")
                put("text", chunk)
                put("textFormat", "markdown")
                // Reply in thread if threadId is provided
                val replyTo = if (i == 0) message.replyToMessageId ?: message.threadId else null
                if (replyTo != null) {
                    put("replyToId", replyTo)
                }
            }

            val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities"
            if (!callBotFrameworkApi(url, activityPayload)) {
                success = false
            }
        }
        success
    }

    private fun sendTypingActivity(serviceUrl: String, conversationId: String) {
        val activity = buildJsonObject {
            put("type", "typing")
        }
        val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities"
        callBotFrameworkApi(url, activity)
    }

    // --- Auth ---

    private fun refreshAccessToken() {
        val tokenUrl = if (tenantId != null) {
            "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token"
        } else {
            "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token"
        }

        val formBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", appId)
            .add("client_secret", appPassword)
            .add("scope", "https://api.botframework.com/.default")
            .build()

        val request = Request.Builder()
            .url(tokenUrl)
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body?.string() ?: return
            val result = json.parseToJsonElement(body).jsonObject
            accessToken = result["access_token"]?.jsonPrimitive?.contentOrNull
            val expiresIn = result["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000)
        }
    }

    // --- Helpers ---

    private fun stripBotMention(text: String, activity: JsonObject): String {
        val entities = activity["entities"]?.jsonArray ?: return text
        var cleaned = text
        for (entity in entities) {
            val entityObj = entity.jsonObject
            val entityType = entityObj["type"]?.jsonPrimitive?.contentOrNull
            if (entityType == "mention") {
                val mentioned = entityObj["mentioned"]?.jsonObject
                val mentionedId = mentioned?.get("id")?.jsonPrimitive?.contentOrNull
                if (mentionedId != null && mentionedId.contains(appId)) {
                    val mentionText = entityObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                    cleaned = cleaned.replace(mentionText, "").trim()
                }
            }
        }
        return cleaned
    }

    private fun callBotFrameworkApi(url: String, payload: JsonObject): Boolean {
        val token = accessToken ?: return false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
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
        const val TEXT_CHUNK_LIMIT = 28000

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
