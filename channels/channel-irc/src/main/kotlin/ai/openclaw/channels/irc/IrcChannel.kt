package ai.openclaw.channels.irc

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * IRC channel implementation using raw TCP sockets.
 * Handles PING/PONG, JOIN, PRIVMSG, and parses IRC protocol lines.
 */
class IrcChannel(
    private val server: String,
    private val port: Int = 6667,
    private val nick: String,
    private val password: String? = null,
    private val channels: List<String> = emptyList(),
    private val useTls: Boolean = false,
    private val realName: String = "OpenClaw Bot",
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "irc"
    override val displayName: String = "IRC"
    override val capabilities = ChannelCapabilities(
        text = true,
        groups = true,
    )

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Lifecycle ---

    override suspend fun onStart() {
        withContext(Dispatchers.IO) {
            connect()
        }
        readJob = scope.launch { readLoop() }
    }

    override suspend fun onStop() {
        readJob?.cancel()
        readJob = null
        withContext(Dispatchers.IO) {
            try {
                // Part all channels
                for (channel in channels) {
                    sendRaw("PART $channel :Goodbye")
                }
                sendRaw("QUIT :Shutting down")
            } catch (_: Exception) {
                // Best effort
            }
            closeConnection()
        }
    }

    // --- Connection ---

    private fun connect() {
        socket = if (useTls) {
            SSLSocketFactory.getDefault().createSocket(server, port)
        } else {
            Socket(server, port)
        }
        val s = socket ?: return
        writer = PrintWriter(s.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(s.getInputStream()))

        // Authenticate if password is provided
        if (password != null) {
            sendRaw("PASS $password")
        }

        // Register
        sendRaw("NICK $nick")
        sendRaw("USER $nick 0 * :$realName")
    }

    private fun closeConnection() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        reader = null
        socket = null
    }

    // --- Read loop ---

    private suspend fun readLoop() {
        var backoffMs = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                val line = withContext(Dispatchers.IO) {
                    reader?.readLine()
                } ?: break // Connection closed

                backoffMs = 2000L
                processLine(line)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Attempt reconnect
                delay(backoffMs)
                backoffMs = (backoffMs * 1.8).toLong().coerceAtMost(30_000)
                try {
                    withContext(Dispatchers.IO) {
                        closeConnection()
                        connect()
                    }
                } catch (_: Exception) {
                    // Will retry
                }
            }
        }
    }

    // --- IRC protocol parsing ---

    private suspend fun processLine(line: String) {
        // Handle PING
        if (line.startsWith("PING")) {
            val payload = line.substringAfter("PING ")
            sendRaw("PONG $payload")
            return
        }

        val parsed = parseLine(line) ?: return

        when (parsed.command) {
            "001" -> {
                // RPL_WELCOME - join channels after registration
                for (channel in channels) {
                    sendRaw("JOIN $channel")
                }
            }
            "PRIVMSG" -> processPrivMsg(parsed)
        }
    }

    private suspend fun processPrivMsg(parsed: IrcMessage) {
        val target = parsed.params.firstOrNull() ?: return
        val text = parsed.trailing ?: return
        val senderNick = parsed.prefix?.substringBefore('!') ?: return

        // Skip own messages
        if (senderNick == nick) return

        val isChannel = target.startsWith("#") || target.startsWith("&")
        val targetId = if (isChannel) target else senderNick
        val chatType = if (isChannel) ChatType.GROUP else ChatType.DIRECT

        // Handle CTCP ACTION (/me)
        val resolvedText = if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            "* $senderNick ${text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")}"
        } else {
            text
        }

        val inbound = InboundMessage(
            channelId = channelId,
            chatType = chatType,
            senderId = senderNick,
            senderName = senderNick,
            targetId = targetId,
            text = resolvedText,
            metadata = buildMap {
                put("irc_target", target)
                parsed.prefix?.let { put("irc_prefix", it) }
            },
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val target = message.targetId
        val text = message.text

        // IRC messages are limited to 512 bytes total; split long messages
        val maxLen = 400 // conservative limit after PRIVMSG header
        val lines = text.split('\n')
        var success = true
        for (line in lines) {
            if (line.isEmpty()) continue
            val chunks = splitLine(line, maxLen)
            for (chunk in chunks) {
                if (!sendRaw("PRIVMSG $target :$chunk")) {
                    success = false
                }
            }
        }
        success
    }

    // --- Helpers ---

    private fun sendRaw(line: String): Boolean {
        return try {
            writer?.println(line)
            writer?.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class IrcMessage(
        val prefix: String?,
        val command: String,
        val params: List<String>,
        val trailing: String?,
    )

    private fun parseLine(line: String): IrcMessage? {
        var remaining = line
        val prefix = if (remaining.startsWith(':')) {
            val spaceIdx = remaining.indexOf(' ')
            if (spaceIdx == -1) return null
            val p = remaining.substring(1, spaceIdx)
            remaining = remaining.substring(spaceIdx + 1).trimStart()
            p
        } else null

        val trailing: String?
        val trailingIdx = remaining.indexOf(" :")
        if (trailingIdx >= 0) {
            trailing = remaining.substring(trailingIdx + 2)
            remaining = remaining.substring(0, trailingIdx)
        } else {
            trailing = null
        }

        val parts = remaining.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        return IrcMessage(
            prefix = prefix,
            command = parts[0],
            params = parts.drop(1),
            trailing = trailing,
        )
    }

    companion object {
        fun splitLine(text: String, maxLen: Int): List<String> {
            if (text.length <= maxLen) return listOf(text)
            val chunks = mutableListOf<String>()
            var remaining = text
            while (remaining.length > maxLen) {
                var splitIdx = remaining.lastIndexOf(' ', maxLen)
                if (splitIdx <= 0) splitIdx = maxLen
                chunks.add(remaining.substring(0, splitIdx))
                remaining = remaining.substring(splitIdx).trimStart()
            }
            if (remaining.isNotEmpty()) chunks.add(remaining)
            return chunks
        }
    }
}
