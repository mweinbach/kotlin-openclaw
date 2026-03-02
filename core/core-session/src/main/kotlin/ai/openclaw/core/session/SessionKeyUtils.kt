package ai.openclaw.core.session

/**
 * Parsed agent session key: agent:<agentId>:<rest>
 */
data class ParsedAgentSessionKey(
    val agentId: String,
    val rest: String,
)

enum class SessionKeyChatType {
    DIRECT, GROUP, CHANNEL, UNKNOWN
}

/**
 * Parse agent-scoped session keys in a canonical, case-insensitive way.
 * Returned values are normalized to lowercase for stable comparisons/routing.
 */
fun parseAgentSessionKey(sessionKey: String?): ParsedAgentSessionKey? {
    val raw = sessionKey?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return null

    val parts = raw.split(":").filter { it.isNotEmpty() }
    if (parts.size < 3) return null
    if (parts[0] != "agent") return null

    val agentId = parts[1].trim()
    val rest = parts.drop(2).joinToString(":")
    if (agentId.isEmpty() || rest.isEmpty()) return null

    return ParsedAgentSessionKey(agentId, rest)
}

/**
 * Best-effort chat-type extraction from session keys across canonical and legacy formats.
 */
fun deriveSessionChatType(sessionKey: String?): SessionKeyChatType {
    val raw = sessionKey?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return SessionKeyChatType.UNKNOWN

    val scoped = parseAgentSessionKey(raw)?.rest ?: raw
    val tokens = scoped.split(":").filter { it.isNotEmpty() }.toSet()

    return when {
        "group" in tokens -> SessionKeyChatType.GROUP
        "channel" in tokens -> SessionKeyChatType.CHANNEL
        "direct" in tokens || "dm" in tokens -> SessionKeyChatType.DIRECT
        // Legacy Discord keys: discord:<accountId>:guild-<guildId>:channel-<channelId>
        Regex("^discord:(?:[^:]+:)?guild-[^:]+:channel-[^:]+$").matches(scoped) -> SessionKeyChatType.CHANNEL
        else -> SessionKeyChatType.UNKNOWN
    }
}

fun isCronRunSessionKey(sessionKey: String?): Boolean {
    val parsed = parseAgentSessionKey(sessionKey) ?: return false
    return Regex("^cron:[^:]+:run:[^:]+$").matches(parsed.rest)
}

fun isCronSessionKey(sessionKey: String?): Boolean {
    val parsed = parseAgentSessionKey(sessionKey) ?: return false
    return parsed.rest.lowercase().startsWith("cron:")
}

fun isSubagentSessionKey(sessionKey: String?): Boolean {
    val raw = sessionKey?.trim().orEmpty()
    if (raw.isEmpty()) return false
    if (raw.lowercase().startsWith("subagent:")) return true
    val parsed = parseAgentSessionKey(raw)
    return parsed?.rest?.lowercase()?.startsWith("subagent:") == true
}

fun getSubagentDepth(sessionKey: String?): Int {
    val raw = sessionKey?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return 0
    return raw.split(":subagent:").size - 1
}

fun isAcpSessionKey(sessionKey: String?): Boolean {
    val raw = sessionKey?.trim().orEmpty()
    if (raw.isEmpty()) return false
    val normalized = raw.lowercase()
    if (normalized.startsWith("acp:")) return true
    val parsed = parseAgentSessionKey(raw)
    return parsed?.rest?.lowercase()?.startsWith("acp:") == true
}

private val THREAD_SESSION_MARKERS = listOf(":thread:", ":topic:")

fun resolveThreadParentSessionKey(sessionKey: String?): String? {
    val raw = sessionKey?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val normalized = raw.lowercase()
    var idx = -1
    for (marker in THREAD_SESSION_MARKERS) {
        val candidate = normalized.lastIndexOf(marker)
        if (candidate > idx) {
            idx = candidate
        }
    }
    if (idx <= 0) return null
    val parent = raw.substring(0, idx).trim()
    return parent.ifEmpty { null }
}
