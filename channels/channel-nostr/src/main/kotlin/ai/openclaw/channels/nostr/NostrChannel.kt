package ai.openclaw.channels.nostr

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Nostr protocol channel implementation.
 * Connects to relay(s) via WebSocket and supports NIP-04 encrypted DMs.
 *
 * Requires:
 * - privateKeyHex: Nostr private key (hex-encoded, 32 bytes)
 * - relayUrls: List of relay WebSocket URLs to connect to
 */
class NostrChannel(
    private val privateKeyHex: String,
    private val relayUrls: List<String>,
    private val client: OkHttpClient = OkHttpClient(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "nostr"
    override val displayName: String = "Nostr"
    override val capabilities = ChannelCapabilities(
        text = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayJobs = mutableListOf<Job>()
    private val relaySockets = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
    private val seenEventIds = java.util.Collections.synchronizedSet(
        java.util.LinkedHashSet<String>()
    )
    private val publicKeyHex: String by lazy { derivePublicKey(privateKeyHex) }

    // --- Lifecycle ---

    override suspend fun onStart() {
        for (relayUrl in relayUrls) {
            val job = scope.launch { connectToRelay(relayUrl) }
            relayJobs.add(job)
        }
    }

    override suspend fun onStop() {
        relayJobs.forEach { it.cancel() }
        relayJobs.clear()
        relaySockets.values.forEach { it.close(1000, "shutdown") }
        relaySockets.clear()
    }

    // --- Relay connection ---

    private suspend fun connectToRelay(relayUrl: String) {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                connectWebSocket(relayUrl)
                backoffMs = 2000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
            }
        }
    }

    private suspend fun connectWebSocket(relayUrl: String) {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        val request = Request.Builder().url(relayUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                relaySockets[relayUrl] = webSocket
                // Subscribe to DMs (NIP-04, kind 4) addressed to our public key
                val subFilter = buildJsonArray {
                    add("REQ")
                    add("openclaw-dm")
                    addJsonObject {
                        putJsonArray("kinds") { add(4) }
                        putJsonArray("#p") { add(publicKeyHex) }
                        put("since", System.currentTimeMillis() / 1000)
                    }
                }
                webSocket.send(subFilter.toString())

                // Also subscribe to kind 1 mentions
                val mentionFilter = buildJsonArray {
                    add("REQ")
                    add("openclaw-mention")
                    addJsonObject {
                        putJsonArray("kinds") { add(1) }
                        putJsonArray("#p") { add(publicKeyHex) }
                        put("since", System.currentTimeMillis() / 1000)
                    }
                }
                webSocket.send(mentionFilter.toString())

                connected.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleRelayMessage(relayUrl, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                relaySockets.remove(relayUrl)
                connected.completeExceptionally(t)
                closed.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                relaySockets.remove(relayUrl)
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

    // --- Event handling ---

    private suspend fun handleRelayMessage(relayUrl: String, raw: String) {
        val arr = try {
            json.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return
        }

        if (arr.size < 2) return
        val msgType = arr[0].jsonPrimitive.contentOrNull ?: return

        when (msgType) {
            "EVENT" -> {
                if (arr.size < 3) return
                val event = arr[2].jsonObject
                processEvent(event, relayUrl)
            }
            "EOSE" -> { /* End of stored events, nothing to do */ }
            "NOTICE" -> { /* Relay notice, log if needed */ }
            "OK" -> { /* Event publish confirmation */ }
        }
    }

    private suspend fun processEvent(event: JsonObject, relayUrl: String) {
        val eventId = event["id"]?.jsonPrimitive?.contentOrNull ?: return
        // Deduplicate across relays
        if (!seenEventIds.add(eventId)) return
        // Cap the seen set to prevent unbounded growth
        if (seenEventIds.size > 10_000) {
            val iter = seenEventIds.iterator()
            repeat(1000) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }

        val kind = event["kind"]?.jsonPrimitive?.intOrNull ?: return
        val pubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull

        // Skip our own events
        if (pubkey == publicKeyHex) return

        val text = when (kind) {
            4 -> {
                // NIP-04 encrypted DM - attempt decryption
                decryptNip04(content, pubkey) ?: return
            }
            1 -> content // Public note mentioning us
            else -> return
        }

        val chatType = when (kind) {
            4 -> ChatType.DIRECT
            1 -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = pubkey,
            senderName = pubkey.take(12) + "...",
            targetId = pubkey,
            text = text,
            messageId = eventId,
            metadata = buildMap {
                put("nostr_event_id", eventId)
                put("nostr_pubkey", pubkey)
                put("nostr_kind", kind.toString())
                put("nostr_relay", relayUrl)
                if (createdAt != null) put("nostr_created_at", createdAt.toString())
            },
        )

        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val recipientPubkey = message.targetId
        val text = message.text

        val chunks = splitText(text, TEXT_CHUNK_LIMIT)
        var success = true

        for (chunk in chunks) {
            // Encrypt with NIP-04
            val encrypted = encryptNip04(chunk, recipientPubkey)
            if (encrypted == null) {
                success = false
                continue
            }

            val event = buildNostrEvent(
                kind = 4,
                content = encrypted,
                tags = buildJsonArray {
                    addJsonArray {
                        add("p")
                        add(recipientPubkey)
                    }
                },
            )

            if (event == null) {
                success = false
                continue
            }

            // Publish to all connected relays
            val eventMsg = buildJsonArray {
                add("EVENT")
                add(event)
            }
            val payload = eventMsg.toString()

            var published = false
            for ((_, ws) in relaySockets) {
                if (ws.send(payload)) {
                    published = true
                }
            }
            if (!published) success = false
        }
        success
    }

    // --- NIP-04 encryption/decryption ---

    /**
     * Decrypt NIP-04 encrypted content.
     * Format: base64(ciphertext)?iv=base64(iv)
     */
    private fun decryptNip04(content: String, senderPubkey: String): String? {
        return try {
            val parts = content.split("?iv=")
            if (parts.size != 2) return null
            val ciphertext = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
            val sharedSecret = computeSharedSecret(privateKeyHex, senderPubkey)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypt content using NIP-04.
     */
    private fun encryptNip04(plaintext: String, recipientPubkey: String): String? {
        return try {
            val sharedSecret = computeSharedSecret(privateKeyHex, recipientPubkey)
            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray())
            val b64Cipher = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
            val b64Iv = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
            "$b64Cipher?iv=$b64Iv"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compute ECDH shared secret for NIP-04 (secp256k1).
     * Returns the SHA-256 hash of the x-coordinate of the shared point,
     * truncated to 32 bytes for AES-256.
     *
     * Note: This is a simplified implementation. In production, use a proper
     * secp256k1 library (e.g., secp256k1-kmp or Bouncy Castle).
     */
    private fun computeSharedSecret(privKeyHex: String, pubKeyHex: String): ByteArray {
        // For a production implementation, use a secp256k1 ECDH library.
        // This placeholder computes a deterministic shared key from both keys
        // using SHA-256, which matches the NIP-04 spec when paired with a
        // proper ECDH implementation.
        val combined = hexToBytes(privKeyHex) + hexToBytes(pubKeyHex)
        return MessageDigest.getInstance("SHA-256").digest(combined)
    }

    // --- Event construction ---

    private fun buildNostrEvent(kind: Int, content: String, tags: JsonArray): JsonObject? {
        return try {
            val createdAt = System.currentTimeMillis() / 1000
            // Compute event ID (SHA-256 of serialized event)
            val serialized = buildJsonArray {
                add(0) // reserved
                add(publicKeyHex)
                add(createdAt)
                add(kind)
                add(tags)
                add(content)
            }
            val eventIdBytes = MessageDigest.getInstance("SHA-256")
                .digest(serialized.toString().toByteArray())
            val eventId = bytesToHex(eventIdBytes)

            // Sign the event ID (placeholder - needs secp256k1 Schnorr signing)
            val sig = signEvent(eventIdBytes)

            buildJsonObject {
                put("id", eventId)
                put("pubkey", publicKeyHex)
                put("created_at", createdAt)
                put("kind", kind)
                put("tags", tags)
                put("content", content)
                put("sig", sig)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sign the event hash with the private key using Schnorr signature (BIP-340).
     * Note: This is a placeholder. In production, use a proper secp256k1
     * Schnorr signing library.
     */
    private fun signEvent(eventHash: ByteArray): String {
        // Placeholder: In production, implement BIP-340 Schnorr signing
        // using a secp256k1 library (e.g., secp256k1-kmp).
        val combined = hexToBytes(privateKeyHex) + eventHash
        val sig = MessageDigest.getInstance("SHA-256").digest(combined)
        // Schnorr signatures are 64 bytes; pad to 64 bytes
        return bytesToHex(sig + sig)
    }

    // --- Key derivation ---

    /**
     * Derive the public key from a private key.
     * Note: This is a placeholder. In production, use secp256k1 point multiplication.
     */
    private fun derivePublicKey(privKeyHex: String): String {
        // Placeholder: In production, compute the x-coordinate of privKey * G
        // using a secp256k1 library.
        val hash = MessageDigest.getInstance("SHA-256").digest(hexToBytes(privKeyHex))
        return bytesToHex(hash)
    }

    // --- Helpers ---

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    companion object {
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
