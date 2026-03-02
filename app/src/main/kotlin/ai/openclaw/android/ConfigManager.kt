package ai.openclaw.android

import android.content.Context
import ai.openclaw.core.config.ConfigLoader
import ai.openclaw.core.config.ConfigValidator
import ai.openclaw.core.model.OpenClawConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads/writes openclaw.json from context.filesDir/config/.
 */
class ConfigManager(private val context: Context) {
    private val configDir: File get() = context.filesDir.resolve("config")
    private val configFile: File get() = configDir.resolve("openclaw.json")

    private val loader = ConfigLoader()
    private val validator = ConfigValidator()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val _config = MutableStateFlow(OpenClawConfig())
    val config: StateFlow<OpenClawConfig> = _config.asStateFlow()

    fun load(): OpenClawConfig {
        val cfg = runCatching {
            if (configFile.exists()) {
                loader.parse(configFile.readText())
            } else {
                OpenClawConfig()
            }
        }.getOrElse {
            OpenClawConfig()
        }
        _config.value = cfg
        return cfg
    }

    fun save(config: OpenClawConfig) {
        configDir.mkdirs()
        val text = json.encodeToString(OpenClawConfig.serializer(), config)
        val tmp = File(configFile.parentFile, ".openclaw.json.tmp")
        tmp.writeText(text)
        tmp.renameTo(configFile)
        _config.value = config
    }

    fun update(block: OpenClawConfig.() -> OpenClawConfig) {
        val updated = _config.value.block()
        save(updated)
    }

    fun observe(): StateFlow<OpenClawConfig> = config

    fun validate(): List<String> {
        val results = validator.validate(_config.value)
        return results.map { it.toString() }
    }
}
