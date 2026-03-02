package ai.openclaw.core.config

import ai.openclaw.core.model.OpenClawConfig
import kotlinx.serialization.json.Json

/**
 * Loads and validates OpenClaw configuration from JSON files.
 */
class ConfigLoader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        coerceInputValues = true
    }
) {
    /**
     * Parse a JSON string into an OpenClawConfig.
     */
    fun parse(jsonString: String): OpenClawConfig {
        return json.decodeFromString<OpenClawConfig>(jsonString)
    }

    /**
     * Substitute environment variable references in the format ${VAR_NAME}.
     */
    fun substituteEnvVars(
        input: String,
        env: Map<String, String> = System.getenv(),
    ): String {
        return ENV_VAR_PATTERN.replace(input) { match ->
            val varName = match.groupValues[1]
            env[varName] ?: match.value
        }
    }

    /**
     * Deep-merge two configs, with overlay taking precedence.
     */
    fun merge(base: OpenClawConfig, overlay: OpenClawConfig): OpenClawConfig {
        // Simple shallow merge - overlay fields replace base fields
        return OpenClawConfig(
            auth = overlay.auth ?: base.auth,
            acp = overlay.acp ?: base.acp,
            diagnostics = overlay.diagnostics ?: base.diagnostics,
            logging = overlay.logging ?: base.logging,
            skills = overlay.skills ?: base.skills,
            plugins = overlay.plugins ?: base.plugins,
            models = overlay.models ?: base.models,
            agents = overlay.agents ?: base.agents,
            tools = overlay.tools ?: base.tools,
            bindings = overlay.bindings ?: base.bindings,
            messages = overlay.messages ?: base.messages,
            session = overlay.session ?: base.session,
            channels = overlay.channels ?: base.channels,
            cron = overlay.cron ?: base.cron,
            hooks = overlay.hooks ?: base.hooks,
            gateway = overlay.gateway ?: base.gateway,
            memory = overlay.memory ?: base.memory,
        )
    }

    companion object {
        private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    }
}
