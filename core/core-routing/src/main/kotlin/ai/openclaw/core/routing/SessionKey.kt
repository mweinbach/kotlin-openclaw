package ai.openclaw.core.routing

import ai.openclaw.core.model.*
import ai.openclaw.core.session.parseAgentSessionKey

/**
 * Session key shape classification.
 */
enum class SessionKeyShape {
    MISSING, AGENT, LEGACY_OR_ALIAS, MALFORMED_AGENT
}

private val VALID_ID_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$", RegexOption.IGNORE_CASE)
private val INVALID_CHARS_RE = Regex("[^a-z0-9_-]+")
private val LEADING_DASH_RE = Regex("^-+")
private val TRAILING_DASH_RE = Regex("-+$")

/**
 * Normalize an agent ID to a safe, lowercase format.
 */
fun normalizeAgentId(value: String?): String {
    val trimmed = (value ?: "").trim()
    if (trimmed.isEmpty()) return DEFAULT_AGENT_ID
    if (VALID_ID_RE.matches(trimmed)) return trimmed.lowercase()
    val normalized = trimmed.lowercase()
        .replace(INVALID_CHARS_RE, "-")
        .replace(LEADING_DASH_RE, "")
        .replace(TRAILING_DASH_RE, "")
        .take(64)
    return normalized.ifEmpty { DEFAULT_AGENT_ID }
}

/**
 * Normalize a main key, defaulting to DEFAULT_MAIN_KEY.
 */
fun normalizeMainKey(value: String?): String {
    val trimmed = (value ?: "").trim()
    return if (trimmed.isNotEmpty()) trimmed.lowercase() else DEFAULT_MAIN_KEY
}

/**
 * Convert a stored agent session key to a request key (strips agent:<id>: prefix).
 */
fun toAgentRequestSessionKey(storeKey: String?): String? {
    val raw = (storeKey ?: "").trim()
    if (raw.isEmpty()) return null
    return parseAgentSessionKey(raw)?.rest ?: raw
}

/**
 * Convert a request key to an agent-scoped store key.
 */
fun toAgentStoreSessionKey(agentId: String, requestKey: String?, mainKey: String? = null): String {
    val raw = (requestKey ?: "").trim()
    if (raw.isEmpty() || raw.lowercase() == DEFAULT_MAIN_KEY) {
        return buildAgentMainSessionKey(agentId, mainKey)
    }
    val parsed = parseAgentSessionKey(raw)
    if (parsed != null) {
        return "agent:${parsed.agentId}:${parsed.rest}"
    }
    val lowered = raw.lowercase()
    if (lowered.startsWith("agent:")) return lowered
    return "agent:${normalizeAgentId(agentId)}:$lowered"
}

/**
 * Resolve the agent ID from a session key.
 */
fun resolveAgentIdFromSessionKey(sessionKey: String?): String {
    val parsed = parseAgentSessionKey(sessionKey)
    return normalizeAgentId(parsed?.agentId ?: DEFAULT_AGENT_ID)
}

/**
 * Classify the shape of a session key.
 */
fun classifySessionKeyShape(sessionKey: String?): SessionKeyShape {
    val raw = (sessionKey ?: "").trim()
    if (raw.isEmpty()) return SessionKeyShape.MISSING
    if (parseAgentSessionKey(raw) != null) return SessionKeyShape.AGENT
    return if (raw.lowercase().startsWith("agent:")) SessionKeyShape.MALFORMED_AGENT
    else SessionKeyShape.LEGACY_OR_ALIAS
}

/**
 * Build a main session key: agent:<agentId>:<mainKey>
 */
fun buildAgentMainSessionKey(agentId: String, mainKey: String? = null): String {
    val nAgentId = normalizeAgentId(agentId)
    val nMainKey = normalizeMainKey(mainKey)
    return "agent:$nAgentId:$nMainKey"
}

/**
 * Build a peer session key with DM scope support.
 */
fun buildAgentPeerSessionKey(
    agentId: String,
    channel: String,
    mainKey: String? = null,
    accountId: String? = null,
    peerKind: ChatType? = null,
    peerId: String? = null,
    identityLinks: Map<String, List<String>>? = null,
    dmScope: DmScope? = null,
): String {
    val kind = peerKind ?: ChatType.DIRECT
    val nAgentId = normalizeAgentId(agentId)

    if (kind == ChatType.DIRECT) {
        val scope = dmScope ?: DmScope.MAIN
        var resolvedPeerId = (peerId ?: "").trim()
        if (scope != DmScope.MAIN) {
            val linked = resolveLinkedPeerId(identityLinks, channel, resolvedPeerId)
            if (linked != null) resolvedPeerId = linked
        }
        resolvedPeerId = resolvedPeerId.lowercase()

        return when {
            scope == DmScope.PER_ACCOUNT_CHANNEL_PEER && resolvedPeerId.isNotEmpty() -> {
                val nChannel = channel.trim().lowercase().ifEmpty { "unknown" }
                val nAccountId = normalizeAccountId(accountId)
                "agent:$nAgentId:$nChannel:$nAccountId:direct:$resolvedPeerId"
            }
            scope == DmScope.PER_CHANNEL_PEER && resolvedPeerId.isNotEmpty() -> {
                val nChannel = channel.trim().lowercase().ifEmpty { "unknown" }
                "agent:$nAgentId:$nChannel:direct:$resolvedPeerId"
            }
            scope == DmScope.PER_PEER && resolvedPeerId.isNotEmpty() -> {
                "agent:$nAgentId:direct:$resolvedPeerId"
            }
            else -> buildAgentMainSessionKey(agentId, mainKey)
        }
    }

    // Group/channel keys
    val nChannel = channel.trim().lowercase().ifEmpty { "unknown" }
    val nPeerId = (peerId ?: "").trim().lowercase().ifEmpty { "unknown" }
    val kindStr = when (kind) {
        ChatType.GROUP -> "group"
        ChatType.CHANNEL -> "channel"
        else -> "direct"
    }
    return "agent:$nAgentId:$nChannel:$kindStr:$nPeerId"
}

/**
 * Resolve a canonical peer ID via identity links.
 */
private fun resolveLinkedPeerId(
    identityLinks: Map<String, List<String>>?,
    channel: String,
    peerId: String,
): String? {
    if (identityLinks == null) return null
    val trimmedPeerId = peerId.trim()
    if (trimmedPeerId.isEmpty()) return null

    val candidates = mutableSetOf<String>()
    val rawCandidate = trimmedPeerId.trim().lowercase()
    if (rawCandidate.isNotEmpty()) candidates.add(rawCandidate)
    val nChannel = channel.trim().lowercase()
    if (nChannel.isNotEmpty()) {
        val scopedCandidate = "$nChannel:$trimmedPeerId".trim().lowercase()
        if (scopedCandidate.isNotEmpty()) candidates.add(scopedCandidate)
    }
    if (candidates.isEmpty()) return null

    for ((canonical, ids) in identityLinks) {
        val canonicalName = canonical.trim()
        if (canonicalName.isEmpty()) continue
        for (id in ids) {
            val normalized = id.trim().lowercase()
            if (normalized.isNotEmpty() && normalized in candidates) {
                return canonicalName
            }
        }
    }
    return null
}

/**
 * Build a group history key.
 */
fun buildGroupHistoryKey(
    channel: String,
    accountId: String?,
    peerKind: String,
    peerId: String,
): String {
    val nChannel = channel.trim().lowercase().ifEmpty { "unknown" }
    val nAccountId = normalizeAccountId(accountId)
    val nPeerId = peerId.trim().lowercase().ifEmpty { "unknown" }
    return "$nChannel:$nAccountId:$peerKind:$nPeerId"
}

/**
 * Resolve thread session keys by appending :thread:<threadId> suffix.
 */
fun resolveThreadSessionKeys(
    baseSessionKey: String,
    threadId: String? = null,
    parentSessionKey: String? = null,
    useSuffix: Boolean = true,
    normalizeThreadId: ((String) -> String)? = null,
): Pair<String, String?> {
    val tid = (threadId ?: "").trim()
    if (tid.isEmpty()) return baseSessionKey to null
    val normalized = (normalizeThreadId ?: { it.lowercase() })(tid)
    val sessionKey = if (useSuffix) "$baseSessionKey:thread:$normalized" else baseSessionKey
    return sessionKey to parentSessionKey
}

// --- Account ID normalization ---

private val BLOCKED_KEYS = setOf(
    "__proto__", "constructor", "prototype", "hasownproperty",
    "isprototypeof", "tostring", "valueof", "tolocalestring",
)

/**
 * Normalize an account ID to a safe format.
 */
fun normalizeAccountId(value: String?): String {
    val trimmed = (value ?: "").trim()
    if (trimmed.isEmpty()) return DEFAULT_ACCOUNT_ID
    return normalizeCanonicalAccountId(trimmed) ?: DEFAULT_ACCOUNT_ID
}

fun normalizeOptionalAccountId(value: String?): String? {
    val trimmed = (value ?: "").trim()
    if (trimmed.isEmpty()) return null
    return normalizeCanonicalAccountId(trimmed)
}

private fun normalizeCanonicalAccountId(value: String): String? {
    val canonical = canonicalizeAccountId(value)
    if (canonical.isEmpty() || canonical.lowercase() in BLOCKED_KEYS) return null
    return canonical
}

private fun canonicalizeAccountId(value: String): String {
    if (VALID_ID_RE.matches(value)) return value.lowercase()
    return value.lowercase()
        .replace(INVALID_CHARS_RE, "-")
        .replace(LEADING_DASH_RE, "")
        .replace(TRAILING_DASH_RE, "")
        .take(64)
}
