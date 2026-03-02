package ai.openclaw.core.config

import ai.openclaw.core.model.ConfigValidationIssue
import ai.openclaw.core.model.MemoryBackend
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.model.QueueMode

/**
 * Severity levels for configuration validation issues.
 */
enum class ValidationSeverity {
    ERROR,
    WARNING,
    INFO,
}

/**
 * Extended validation issue carrying severity alongside the base model.
 */
data class ConfigValidationResult(
    val issue: ConfigValidationIssue,
    val severity: ValidationSeverity,
)

/**
 * Validates OpenClaw configuration for completeness and correctness.
 */
class ConfigValidator {

    fun validate(config: OpenClawConfig): List<ConfigValidationResult> {
        val results = mutableListOf<ConfigValidationResult>()
        validateAgents(config, results)
        validateAuth(config, results)
        validateGateway(config, results)
        validateChannels(config, results)
        validateModels(config, results)
        validateSession(config, results)
        validateMemory(config, results)
        return results
    }

    private fun validateAgents(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val agents = config.agents?.list
        if (agents.isNullOrEmpty()) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "agents.list",
                    message = "At least one agent must be defined",
                ),
                severity = ValidationSeverity.ERROR,
            ))
            return
        }

        val hasDefault = agents.any { it.default == true }
        if (!hasDefault) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "agents.list",
                    message = "No default agent defined; the first agent will be used as default",
                ),
                severity = ValidationSeverity.WARNING,
            ))
        }

        val ids = agents.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        for (dup in duplicates) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "agents.list",
                    message = "Duplicate agent id: $dup",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }
    }

    private fun validateAuth(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val profiles = config.auth?.profiles
        if (profiles.isNullOrEmpty()) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "auth.profiles",
                    message = "At least one auth profile should be defined",
                ),
                severity = ValidationSeverity.WARNING,
            ))
        }
    }

    private fun validateGateway(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val port = config.gateway?.port
        if (port != null && (port < 1 || port > 65535)) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "gateway.port",
                    message = "Port must be between 1 and 65535, got: $port",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }

        val canvasPort = config.canvasHost?.port
        if (canvasPort != null && (canvasPort < 1 || canvasPort > 65535)) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "canvasHost.port",
                    message = "Canvas host port must be between 1 and 65535, got: $canvasPort",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }
    }

    private fun validateChannels(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val channels = config.channels ?: return

        // Telegram: require bot token per enabled account
        val telegram = channels.telegram
        if (telegram?.enabled == true) {
            val accounts = telegram.accounts
            if (accounts.isNullOrEmpty()) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "channels.telegram.accounts",
                        message = "Telegram is enabled but no accounts are configured",
                    ),
                    severity = ValidationSeverity.ERROR,
                ))
            } else {
                for ((id, account) in accounts) {
                    if (account.botToken == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.telegram.accounts.$id.botToken",
                                message = "Telegram account '$id' is missing botToken",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                }
            }
        }

        // Discord: require bot token per enabled account
        val discord = channels.discord
        if (discord?.enabled == true) {
            val accounts = discord.accounts
            if (accounts.isNullOrEmpty()) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "channels.discord.accounts",
                        message = "Discord is enabled but no accounts are configured",
                    ),
                    severity = ValidationSeverity.ERROR,
                ))
            } else {
                for ((id, account) in accounts) {
                    if (account.botToken == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.discord.accounts.$id.botToken",
                                message = "Discord account '$id' is missing botToken",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                }
            }
        }

        // Slack: require bot token and app token per enabled account
        val slack = channels.slack
        if (slack?.enabled == true) {
            val accounts = slack.accounts
            if (accounts.isNullOrEmpty()) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "channels.slack.accounts",
                        message = "Slack is enabled but no accounts are configured",
                    ),
                    severity = ValidationSeverity.ERROR,
                ))
            } else {
                for ((id, account) in accounts) {
                    if (account.botToken == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.slack.accounts.$id.botToken",
                                message = "Slack account '$id' is missing botToken",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                    if (account.appToken == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.slack.accounts.$id.appToken",
                                message = "Slack account '$id' is missing appToken",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                }
            }
        }

        // Matrix: require homeserver URL and access token
        val matrix = channels.matrix
        if (matrix?.enabled == true) {
            val accounts = matrix.accounts
            if (accounts.isNullOrEmpty()) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "channels.matrix.accounts",
                        message = "Matrix is enabled but no accounts are configured",
                    ),
                    severity = ValidationSeverity.ERROR,
                ))
            } else {
                for ((id, account) in accounts) {
                    if (account.homeserverUrl == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.matrix.accounts.$id.homeserverUrl",
                                message = "Matrix account '$id' is missing homeserverUrl",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                    if (account.accessToken == null) {
                        results.add(ConfigValidationResult(
                            issue = ConfigValidationIssue(
                                path = "channels.matrix.accounts.$id.accessToken",
                                message = "Matrix account '$id' is missing accessToken",
                            ),
                            severity = ValidationSeverity.ERROR,
                        ))
                    }
                }
            }
        }
    }

    private fun validateModels(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val providers = config.models?.providers ?: return

        for ((providerName, providerConfig) in providers) {
            if (providerConfig.models.isEmpty()) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "models.providers.$providerName.models",
                        message = "Model provider '$providerName' has no models defined",
                    ),
                    severity = ValidationSeverity.WARNING,
                ))
            }

            for (model in providerConfig.models) {
                if (model.contextWindow <= 0) {
                    results.add(ConfigValidationResult(
                        issue = ConfigValidationIssue(
                            path = "models.providers.$providerName.models.${model.id}.contextWindow",
                            message = "Model '${model.id}' has invalid contextWindow: ${model.contextWindow}",
                        ),
                        severity = ValidationSeverity.ERROR,
                    ))
                }
                if (model.maxTokens <= 0) {
                    results.add(ConfigValidationResult(
                        issue = ConfigValidationIssue(
                            path = "models.providers.$providerName.models.${model.id}.maxTokens",
                            message = "Model '${model.id}' has invalid maxTokens: ${model.maxTokens}",
                        ),
                        severity = ValidationSeverity.ERROR,
                    ))
                }
            }
        }
    }

    private fun validateSession(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val session = config.session
        val idleMinutes = session?.idleMinutes
        if (idleMinutes != null && idleMinutes <= 0) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "session.idleMinutes",
                    message = "Session idle timeout must be positive, got: $idleMinutes",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }

        val queueConfig = config.messages?.queue
        val cap = queueConfig?.cap
        if (cap != null && cap <= 0) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "messages.queue.cap",
                    message = "Queue cap must be positive, got: $cap",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }

        val debounceMs = queueConfig?.debounceMs
        if (debounceMs != null && debounceMs < 0) {
            results.add(ConfigValidationResult(
                issue = ConfigValidationIssue(
                    path = "messages.queue.debounceMs",
                    message = "Queue debounce must be non-negative, got: $debounceMs",
                ),
                severity = ValidationSeverity.ERROR,
            ))
        }
    }

    private fun validateMemory(config: OpenClawConfig, results: MutableList<ConfigValidationResult>) {
        val memory = config.memory ?: return

        // Backend validation - already an enum so it's always valid if parsed
        // Check QMD-specific config if QMD backend is selected
        if (memory.backend == MemoryBackend.QMD) {
            val qmd = memory.qmd
            if (qmd == null) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "memory.qmd",
                        message = "Memory backend is 'qmd' but no qmd config is provided",
                    ),
                    severity = ValidationSeverity.WARNING,
                ))
            }

            val maxResults = qmd?.limits?.maxResults
            if (maxResults != null && maxResults <= 0) {
                results.add(ConfigValidationResult(
                    issue = ConfigValidationIssue(
                        path = "memory.qmd.limits.maxResults",
                        message = "QMD maxResults must be positive, got: $maxResults",
                    ),
                    severity = ValidationSeverity.ERROR,
                ))
            }
        }
    }

    companion object {
        /** Known API key prefixes for format validation. */
        val KNOWN_KEY_PREFIXES = mapOf(
            "anthropic" to listOf("sk-ant-"),
            "openai" to listOf("sk-"),
            "google" to listOf("AIza"),
        )

        /**
         * Validate an API key format against known provider prefixes.
         * Returns null if valid or unknown, or an error message if the format is wrong.
         */
        fun validateApiKeyFormat(provider: String, apiKey: String): String? {
            val prefixes = KNOWN_KEY_PREFIXES[provider.lowercase()] ?: return null
            val matchesAny = prefixes.any { apiKey.startsWith(it) }
            return if (matchesAny) null
            else "API key for provider '$provider' doesn't match expected format (expected prefix: ${prefixes.joinToString(" or ")})"
        }
    }
}
