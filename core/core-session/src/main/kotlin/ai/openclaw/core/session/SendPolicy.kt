package ai.openclaw.core.session

import ai.openclaw.core.model.*

/**
 * Normalize a raw policy string to a typed decision.
 */
fun normalizeSendPolicy(raw: String?): SendPolicyDecision? {
    return when (raw?.trim()?.lowercase()) {
        "allow" -> SendPolicyDecision.ALLOW
        "deny" -> SendPolicyDecision.DENY
        else -> null
    }
}

private fun normalizeMatchValue(raw: String?): String? {
    val value = raw?.trim()?.lowercase()
    return if (value.isNullOrEmpty()) null else value
}

private fun stripAgentSessionKeyPrefix(key: String?): String? {
    if (key.isNullOrEmpty()) return null
    val parts = key.split(":").filter { it.isNotEmpty() }
    // Canonical agent session keys: agent:<agentId>:<sessionKey...>
    if (parts.size >= 3 && parts[0] == "agent") {
        return parts.drop(2).joinToString(":")
    }
    return key
}

private fun deriveChannelFromKey(key: String?): String? {
    val normalizedKey = stripAgentSessionKeyPrefix(key) ?: return null
    val parts = normalizedKey.split(":").filter { it.isNotEmpty() }
    if (parts.size >= 3 && (parts[1] == "group" || parts[1] == "channel")) {
        return normalizeMatchValue(parts[0])
    }
    return null
}

private fun deriveChatTypeFromKey(key: String?): SessionKeyChatType? {
    val chatType = deriveSessionChatType(key)
    return if (chatType == SessionKeyChatType.UNKNOWN) null else chatType
}

private fun normalizeChatType(chatType: ChatType?): ChatType? = chatType

/**
 * Resolve effective send policy for a session.
 */
fun resolveSendPolicy(
    config: OpenClawConfig,
    entry: SessionEntry? = null,
    sessionKey: String? = null,
    channel: String? = null,
    chatType: ChatType? = null,
): SendPolicyDecision {
    // Session-level override wins
    val override = entry?.sendPolicy
    if (override != null) return override

    val policy = config.session?.sendPolicy ?: return SendPolicyDecision.ALLOW

    val effectiveChannel =
        normalizeMatchValue(channel)
            ?: normalizeMatchValue(entry?.channel)
            ?: normalizeMatchValue(entry?.lastChannel)
            ?: deriveChannelFromKey(sessionKey)

    val effectiveChatType =
        normalizeChatType(chatType ?: entry?.chatType)

    val rawSessionKey = sessionKey.orEmpty()
    val strippedSessionKey = stripAgentSessionKeyPrefix(rawSessionKey).orEmpty()
    val rawSessionKeyNorm = rawSessionKey.lowercase()
    val strippedSessionKeyNorm = strippedSessionKey.lowercase()

    var allowedMatch = false
    for (rule in policy.rules.orEmpty()) {
        val action = rule.action
        val match = rule.match ?: SessionSendPolicyMatch()
        val matchChannel = normalizeMatchValue(match.channel)
        val matchChatType = match.chatType
        val matchPrefix = normalizeMatchValue(match.keyPrefix)
        val matchRawPrefix = normalizeMatchValue(match.rawKeyPrefix)

        if (matchChannel != null && matchChannel != effectiveChannel) continue
        if (matchChatType != null && matchChatType != effectiveChatType) continue
        if (matchRawPrefix != null && !rawSessionKeyNorm.startsWith(matchRawPrefix)) continue
        if (matchPrefix != null &&
            !rawSessionKeyNorm.startsWith(matchPrefix) &&
            !strippedSessionKeyNorm.startsWith(matchPrefix)
        ) continue

        if (action == SendPolicyDecision.DENY) return SendPolicyDecision.DENY
        allowedMatch = true
    }

    if (allowedMatch) return SendPolicyDecision.ALLOW

    return policy.default ?: SendPolicyDecision.ALLOW
}
