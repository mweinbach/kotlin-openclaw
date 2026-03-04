package ai.openclaw.core.model

import kotlinx.serialization.Serializable

// --- Agent Types (ported from src/config/types.agents.ts + types.agents-shared.ts) ---

@Serializable
data class AgentModelConfig(
    val primary: String? = null,
    val fallbacks: List<String>? = null,
) {
    companion object {
        /** Parse from either a plain string or structured config. */
        fun fromString(model: String): AgentModelConfig =
            AgentModelConfig(primary = model)
    }
}

@Serializable
data class AgentSandboxConfig(
    val mode: String? = null,
    val workspaceAccess: String? = null,
    val sessionToolsVisibility: String? = null,
    val scope: String? = null,
    val perSession: Boolean? = null,
    val workspaceRoot: String? = null,
)

@Serializable
data class MemorySearchConfig(
    val enabled: Boolean? = null,
    val topK: Int? = null,
    val threshold: Double? = null,
)

@Serializable
data class AgentToolsConfig(
    val profile: ToolProfileId? = null,
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
    val byProvider: Map<String, ToolPolicyConfig>? = null,
    val enabled: List<String>? = null,
    val disabled: List<String>? = null,
)

@Serializable
data class GroupChatConfig(
    val activateOn: GroupActivation? = null,
    val names: List<String>? = null,
)

@Serializable
data class SubagentsConfig(
    val allowAgents: List<String>? = null,
    val model: AgentModelConfig? = null,
    val maxSpawnDepth: Int? = null,
)

@Serializable
data class AgentContextPruningConfig(
    val enabled: Boolean? = null,
    val maxTokens: Int? = null,
    val strategy: String? = null,
)

@Serializable
data class AgentCompactionConfig(
    val enabled: Boolean? = null,
    val mode: String? = null,
    val maxTurns: Int? = null,
    val maxTokens: Int? = null,
    val summaryModel: String? = null,
)

@Serializable
data class AgentDefaultsConfig(
    val model: AgentModelConfig? = null,
    val skills: List<String>? = null,
    val memorySearch: MemorySearchConfig? = null,
    val humanDelay: HumanDelayConfig? = null,
    val identity: IdentityConfig? = null,
    val groupChat: GroupChatConfig? = null,
    val sandbox: AgentSandboxConfig? = null,
    val params: Map<String, String>? = null,
    val tools: AgentToolsConfig? = null,
    val heartbeat: HeartbeatConfig? = null,
    val workspace: String? = null,
    val contextTokens: Int? = null,
    val contextPruning: AgentContextPruningConfig? = null,
    val compaction: AgentCompactionConfig? = null,
    val thinkingDefault: Boolean? = null,
    val verboseDefault: Boolean? = null,
    val maxConcurrent: Int? = null,
    val subagents: SubagentsConfig? = null,
    val blockStreamingDefault: Boolean? = null,
    val blockStreamingChunk: BlockStreamingChunkConfig? = null,
    val blockStreamingCoalesce: BlockStreamingCoalesceConfig? = null,
    val mediaMaxMb: Int? = null,
    val imageMaxDimensionPx: Int? = null,
)

@Serializable
data class HeartbeatConfig(
    val enabled: Boolean? = null,
    val intervalSeconds: Int? = null,
    val message: String? = null,
)

@Serializable
data class AgentConfig(
    val id: String,
    val default: Boolean? = null,
    val name: String? = null,
    val workspace: String? = null,
    val agentDir: String? = null,
    val model: AgentModelConfig? = null,
    val skills: List<String>? = null,
    val memorySearch: MemorySearchConfig? = null,
    val humanDelay: HumanDelayConfig? = null,
    val heartbeat: HeartbeatConfig? = null,
    val identity: IdentityConfig? = null,
    val groupChat: GroupChatConfig? = null,
    val subagents: SubagentsConfig? = null,
    val sandbox: AgentSandboxConfig? = null,
    val params: Map<String, String>? = null,
    val tools: AgentToolsConfig? = null,
)

@Serializable
data class AgentsConfig(
    val defaults: AgentDefaultsConfig? = null,
    val list: List<AgentConfig>? = null,
)

@Serializable
data class AgentBinding(
    val agentId: String,
    val comment: String? = null,
    val match: AgentBindingMatch,
)

@Serializable
data class AgentBindingMatch(
    val channel: String,
    val accountId: String? = null,
    val peer: PeerMatch? = null,
    val guildId: String? = null,
    val teamId: String? = null,
    val roles: List<String>? = null,
)

@Serializable
data class PeerMatch(
    val kind: ChatType,
    val id: String,
)

const val DEFAULT_AGENT_ID = "main"
const val DEFAULT_MAIN_KEY = "main"
const val DEFAULT_ACCOUNT_ID = "default"
