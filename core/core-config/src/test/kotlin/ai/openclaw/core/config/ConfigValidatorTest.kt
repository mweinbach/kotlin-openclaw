package ai.openclaw.core.config

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private val validator = ConfigValidator()

    @Test
    fun `empty config produces agent error`() {
        val config = OpenClawConfig()
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "agents.list"
        })
    }

    @Test
    fun `config with agent but no default produces warning`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(AgentConfig(id = "main"))
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.WARNING && it.issue.message.contains("No default agent")
        })
    }

    @Test
    fun `config with default agent produces no agent errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(AgentConfig(id = "main", default = true))
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "agents.list"
        })
    }

    @Test
    fun `duplicate agent ids produce error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(
                    AgentConfig(id = "dup", default = true),
                    AgentConfig(id = "dup"),
                )
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.message.contains("Duplicate agent id")
        })
    }

    @Test
    fun `no auth profiles produces warning`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.WARNING && it.issue.path == "auth.profiles"
        })
    }

    @Test
    fun `valid auth profiles produce no auth warnings`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            auth = AuthConfig(
                profiles = mapOf("default" to AuthProfileConfig(provider = "anthropic", mode = "api-key")),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none { it.issue.path == "auth.profiles" })
    }

    @Test
    fun `invalid gateway port produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            gateway = GatewayConfig(port = 99999),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "gateway.port"
        })
    }

    @Test
    fun `zero port produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            gateway = GatewayConfig(port = 0),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "gateway.port"
        })
    }

    @Test
    fun `valid gateway port produces no port errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            gateway = GatewayConfig(port = 8080),
        )
        val results = validator.validate(config)
        assertTrue(results.none { it.issue.path == "gateway.port" })
    }

    @Test
    fun `enabled telegram without accounts produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                telegram = TelegramConfig(enabled = true),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR &&
                it.issue.path == "channels.telegram.accounts"
        })
    }

    @Test
    fun `telegram account without bot token produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                telegram = TelegramConfig(
                    enabled = true,
                    accounts = mapOf("bot1" to TelegramAccountConfig()),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR &&
                it.issue.path.contains("botToken")
        })
    }

    @Test
    fun `valid telegram config produces no channel errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                telegram = TelegramConfig(
                    enabled = true,
                    accounts = mapOf("bot1" to TelegramAccountConfig(
                        botToken = SecretInput.ofEnv("TELEGRAM_TOKEN"),
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("telegram")
        })
    }

    @Test
    fun `negative idle minutes produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            session = SessionConfig(idleMinutes = -5),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "session.idleMinutes"
        })
    }

    @Test
    fun `negative queue cap produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            messages = ExpandedMessagesConfig(
                queue = QueueConfig(mode = QueueMode.STEER, cap = -1),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "messages.queue.cap"
        })
    }

    @Test
    fun `qmd backend without qmd config produces warning`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            memory = ExpandedMemoryConfig(backend = MemoryBackend.QMD),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.WARNING && it.issue.path == "memory.qmd"
        })
    }

    @Test
    fun `api key format validation for anthropic`() {
        assertNull(ConfigValidator.validateApiKeyFormat("anthropic", "sk-ant-test123"))
        assertTrue(ConfigValidator.validateApiKeyFormat("anthropic", "wrong-prefix")!!.contains("doesn't match"))
    }

    @Test
    fun `api key format validation for openai`() {
        assertNull(ConfigValidator.validateApiKeyFormat("openai", "sk-test123"))
        assertTrue(ConfigValidator.validateApiKeyFormat("openai", "wrong-prefix")!!.contains("doesn't match"))
    }

    @Test
    fun `api key format validation for unknown provider returns null`() {
        assertNull(ConfigValidator.validateApiKeyFormat("custom-provider", "anything"))
    }

    @Test
    fun `slack account missing app token produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                slack = SlackConfig(
                    enabled = true,
                    accounts = mapOf("ws" to SlackAccountConfig(
                        botToken = SecretInput.ofEnv("SLACK_BOT_TOKEN"),
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("appToken")
        })
    }

    @Test
    fun `matrix account missing homeserver url produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                matrix = MatrixConfig(
                    enabled = true,
                    accounts = mapOf("mx" to MatrixAccountConfig(
                        accessToken = SecretInput.ofEnv("MATRIX_TOKEN"),
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("homeserverUrl")
        })
    }

    @Test
    fun `fully valid config produces minimal issues`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(AgentConfig(id = "main", default = true)),
            ),
            auth = AuthConfig(
                profiles = mapOf("default" to AuthProfileConfig(provider = "anthropic", mode = "api-key")),
            ),
            gateway = GatewayConfig(port = 3000),
            session = SessionConfig(idleMinutes = 60),
        )
        val results = validator.validate(config)
        assertTrue(results.none { it.severity == ValidationSeverity.ERROR })
    }

    @Test
    fun `discord enabled without accounts produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                discord = DiscordConfig(enabled = true),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR &&
                it.issue.path == "channels.discord.accounts"
        })
    }

    @Test
    fun `discord account without bot token produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                discord = DiscordConfig(
                    enabled = true,
                    accounts = mapOf("bot1" to DiscordAccountConfig()),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR &&
                it.issue.path.contains("discord") && it.issue.path.contains("botToken")
        })
    }

    @Test
    fun `valid discord config produces no discord errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                discord = DiscordConfig(
                    enabled = true,
                    accounts = mapOf("bot1" to DiscordAccountConfig(
                        botToken = SecretInput.ofEnv("DISCORD_TOKEN"),
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("discord")
        })
    }

    @Test
    fun `negative debounce produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            messages = ExpandedMessagesConfig(
                queue = QueueConfig(mode = QueueMode.STEER, debounceMs = -100),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "messages.queue.debounceMs"
        })
    }

    @Test
    fun `model with zero context window produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            models = ModelsConfig(
                providers = mapOf("test" to ModelProviderConfig(
                    baseUrl = "https://api.example.com",
                    models = listOf(ModelDefinitionConfig(
                        id = "test-model",
                        name = "Test Model",
                        reasoning = false,
                        input = listOf("text"),
                        cost = ModelCost(input = 0.01, output = 0.02, cacheRead = 0.0, cacheWrite = 0.0),
                        contextWindow = 0,
                        maxTokens = 4096,
                    )),
                )),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.message.contains("contextWindow")
        })
    }

    @Test
    fun `model with negative max tokens produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            models = ModelsConfig(
                providers = mapOf("test" to ModelProviderConfig(
                    baseUrl = "https://api.example.com",
                    models = listOf(ModelDefinitionConfig(
                        id = "test-model",
                        name = "Test Model",
                        reasoning = false,
                        input = listOf("text"),
                        cost = ModelCost(input = 0.01, output = 0.02, cacheRead = 0.0, cacheWrite = 0.0),
                        contextWindow = 200000,
                        maxTokens = -1,
                    )),
                )),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.message.contains("maxTokens")
        })
    }

    @Test
    fun `provider with no models produces warning`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            models = ModelsConfig(
                providers = mapOf("empty" to ModelProviderConfig(
                    baseUrl = "https://api.example.com",
                    models = emptyList(),
                )),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.WARNING && it.issue.message.contains("no models defined")
        })
    }

    @Test
    fun `api key format validation for google`() {
        assertNull(ConfigValidator.validateApiKeyFormat("google", "AIzaSomeKey123"))
        assertTrue(ConfigValidator.validateApiKeyFormat("google", "wrong-key")!!.contains("doesn't match"))
    }

    @Test
    fun `api key format is case insensitive for provider name`() {
        assertNull(ConfigValidator.validateApiKeyFormat("Anthropic", "sk-ant-test123"))
        assertNull(ConfigValidator.validateApiKeyFormat("OPENAI", "sk-test123"))
        assertNull(ConfigValidator.validateApiKeyFormat("Google", "AIzaKey"))
    }

    @Test
    fun `qmd negative max results produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            memory = ExpandedMemoryConfig(
                backend = MemoryBackend.QMD,
                qmd = MemoryQmdConfig(
                    limits = MemoryQmdLimitsConfig(maxResults = -5),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("maxResults")
        })
    }

    @Test
    fun `matrix account missing access token produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                matrix = MatrixConfig(
                    enabled = true,
                    accounts = mapOf("mx" to MatrixAccountConfig(
                        homeserverUrl = "https://matrix.example.com",
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("accessToken")
        })
    }

    @Test
    fun `multiple unique agents with one default produces no errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(
                list = listOf(
                    AgentConfig(id = "main", default = true),
                    AgentConfig(id = "helper"),
                    AgentConfig(id = "coder"),
                ),
            ),
            auth = AuthConfig(
                profiles = mapOf("default" to AuthProfileConfig(provider = "anthropic", mode = "api-key")),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none { it.severity == ValidationSeverity.ERROR })
    }

    @Test
    fun `slack enabled without accounts produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                slack = SlackConfig(enabled = true),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR &&
                it.issue.path == "channels.slack.accounts"
        })
    }

    @Test
    fun `valid slack config with both tokens produces no errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                slack = SlackConfig(
                    enabled = true,
                    accounts = mapOf("ws" to SlackAccountConfig(
                        botToken = SecretInput.ofEnv("SLACK_BOT_TOKEN"),
                        appToken = SecretInput.ofEnv("SLACK_APP_TOKEN"),
                    )),
                ),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("slack")
        })
    }

    @Test
    fun `canvas host invalid port produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            canvasHost = CanvasHostConfig(port = 70000),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "canvasHost.port"
        })
    }

    @Test
    fun `disabled channel is not validated`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            channels = ChannelsConfig(
                telegram = TelegramConfig(enabled = false),
                discord = DiscordConfig(enabled = false),
                slack = SlackConfig(enabled = false),
                matrix = MatrixConfig(enabled = false),
            ),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("channels")
        })
    }

    @Test
    fun `null channels config produces no channel errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path.contains("channels")
        })
    }

    @Test
    fun `zero idle minutes produces error`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            session = SessionConfig(idleMinutes = 0),
        )
        val results = validator.validate(config)
        assertTrue(results.any {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "session.idleMinutes"
        })
    }

    @Test
    fun `positive idle minutes produces no session errors`() {
        val config = OpenClawConfig(
            agents = AgentsConfig(list = listOf(AgentConfig(id = "main", default = true))),
            session = SessionConfig(idleMinutes = 30),
        )
        val results = validator.validate(config)
        assertTrue(results.none {
            it.severity == ValidationSeverity.ERROR && it.issue.path == "session.idleMinutes"
        })
    }
}
