package ai.openclaw.core.model

import kotlinx.serialization.Serializable

// --- Skill Types (ported from src/agents/skills/types.ts + src/config/types.skills.ts) ---

@Serializable
data class SkillInstallSpec(
    val brew: String? = null,
    val node: String? = null,
    val go: String? = null,
    val uv: String? = null,
    val download: String? = null,
)

@Serializable
data class SkillInvocationPolicy(
    val userInvocation: Boolean? = null,
    val modelInvocation: Boolean? = null,
)

@Serializable
data class SkillCommandDispatchSpec(
    val tool: String? = null,
    val args: Map<String, String>? = null,
)

@Serializable
data class OpenClawSkillMetadata(
    val emoji: String? = null,
    val os: List<String>? = null,
    val requiredBins: List<String>? = null,
    val requiredEnv: List<String>? = null,
    val primaryEnv: String? = null,
    val invocation: SkillInvocationPolicy? = null,
    val dispatch: SkillCommandDispatchSpec? = null,
    val install: SkillInstallSpec? = null,
)

@Serializable
data class SkillEntry(
    val name: String,
    val commandName: String,
    val description: String? = null,
    val body: String,
    val path: String,
    val source: SkillSource,
    val metadata: OpenClawSkillMetadata? = null,
)

@Serializable
enum class SkillSource {
    BUNDLED,
    MANAGED,
    WORKSPACE,
    EXTRA,
    PLUGIN,
}

@Serializable
data class SkillSnapshot(
    val name: String,
    val commandName: String,
    val description: String? = null,
    val primaryEnv: String? = null,
    val requiredEnv: List<String>? = null,
    val blockChars: Int = 0,
)

// --- Skills Config ---

@Serializable
data class SkillConfig(
    val enabled: Boolean? = null,
    val apiKey: SecretInput? = null,
    val env: Map<String, String>? = null,
    val config: Map<String, String>? = null,
)

@Serializable
data class SkillsLoadConfig(
    val extraDirs: List<String>? = null,
    val watch: Boolean? = null,
    val watchDebounceMs: Long? = null,
)

@Serializable
data class SkillsInstallConfig(
    val preferBrew: Boolean? = null,
    val nodeManager: String? = null,
)

@Serializable
data class SkillsLimitsConfig(
    val maxCandidatesPerRoot: Int? = null,
    val maxSkillsLoadedPerSource: Int? = null,
    val maxSkillsInPrompt: Int? = null,
    val maxSkillsPromptChars: Int? = null,
    val maxSkillFileBytes: Int? = null,
)

@Serializable
data class SkillsConfig(
    val allowBundled: List<String>? = null,
    val load: SkillsLoadConfig? = null,
    val install: SkillsInstallConfig? = null,
    val limits: SkillsLimitsConfig? = null,
    val entries: Map<String, SkillConfig>? = null,
)
