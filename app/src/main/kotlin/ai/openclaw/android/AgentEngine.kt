package ai.openclaw.android

import android.content.Context
import android.net.Uri
import ai.openclaw.android.auth.CodexOauthManager
import ai.openclaw.android.security.SharedPreferencesSecretStore
import ai.openclaw.channels.core.ChannelPlugin
import ai.openclaw.channels.discord.DiscordChannel
import ai.openclaw.channels.irc.IrcChannel
import ai.openclaw.channels.line.LineChannel
import ai.openclaw.channels.mattermost.MattermostChannel
import ai.openclaw.channels.matrix.MatrixChannel
import ai.openclaw.channels.msteams.MSTeamsChannel
import ai.openclaw.channels.nostr.NostrChannel
import ai.openclaw.channels.signal.SignalChannel
import ai.openclaw.channels.slack.SlackChannel
import ai.openclaw.channels.telegram.TelegramChannel
import ai.openclaw.channels.webchat.WebChatChannel
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.AppRuntimeConfig
import ai.openclaw.core.model.AgentConfig
import ai.openclaw.core.model.AgentDefaultsConfig
import ai.openclaw.core.model.AgentModelConfig
import ai.openclaw.core.model.AgentsConfig
import ai.openclaw.core.model.AuthProfileConfig
import ai.openclaw.core.model.ChannelId
import ai.openclaw.core.model.ChannelsConfig
import ai.openclaw.core.model.ChatType
import ai.openclaw.core.model.DiscordAccountConfig
import ai.openclaw.core.model.DiscordConfig
import ai.openclaw.core.model.GoogleChatAccountConfig
import ai.openclaw.core.model.GoogleChatConfig
import ai.openclaw.core.model.IMessageConfig
import ai.openclaw.core.model.InboundMessage
import ai.openclaw.core.model.IrcAccountConfig
import ai.openclaw.core.model.IrcConfig
import ai.openclaw.core.model.LineAccountConfig
import ai.openclaw.core.model.LineConfig
import ai.openclaw.core.model.MatrixConfig
import ai.openclaw.core.model.MSTeamsConfig
import ai.openclaw.core.model.MattermostAccountConfig
import ai.openclaw.core.model.MattermostConfig
import ai.openclaw.core.model.ModelProviderAuthMode
import ai.openclaw.core.model.ModelDefinitionConfig
import ai.openclaw.core.model.ModelProviderConfig
import ai.openclaw.core.model.NostrAccountConfig
import ai.openclaw.core.model.NostrConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.model.OutboundMessage
import ai.openclaw.core.model.SecretInput
import ai.openclaw.core.model.SignalAccountConfig
import ai.openclaw.core.model.SignalConfig
import ai.openclaw.core.model.SlackAccountConfig
import ai.openclaw.core.model.SlackConfig
import ai.openclaw.core.model.TelegramAccountConfig
import ai.openclaw.core.model.TelegramConfig
import ai.openclaw.core.model.WebChatConfig
import ai.openclaw.core.model.WhatsAppConfig
import ai.openclaw.core.model.DEFAULT_AGENT_ID
import ai.openclaw.core.plugins.BeforeAgentStartEvent
import ai.openclaw.core.plugins.BeforeModelResolveEvent
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.PluginHookAgentContext
import ai.openclaw.core.plugins.PluginRegistry
import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.AuditLog
import ai.openclaw.core.security.ConfigBasedApprovalPolicy
import ai.openclaw.core.security.SecretCategory
import ai.openclaw.core.security.SecretStore
import ai.openclaw.core.security.ToolAuditor
import ai.openclaw.core.security.ToolPolicyEnforcer
import ai.openclaw.core.storage.OpenClawDatabase
import ai.openclaw.runtime.cron.CronPayload
import ai.openclaw.runtime.cron.CronScheduler
import ai.openclaw.runtime.cron.CronStore
import ai.openclaw.runtime.cron.CronTool
import ai.openclaw.runtime.devices.DeviceToolsConfig
import ai.openclaw.runtime.devices.DeviceToolsModule
import ai.openclaw.runtime.engine.AgentRunner
import ai.openclaw.runtime.engine.ContextGuard
import ai.openclaw.runtime.engine.ClientToolDefinition
import ai.openclaw.runtime.engine.EmbeddedRunParams
import ai.openclaw.runtime.engine.EmbeddedTurnContext
import ai.openclaw.runtime.engine.SessionPersistence
import ai.openclaw.runtime.engine.SkillDefinition
import ai.openclaw.runtime.engine.SkillExecutor
import ai.openclaw.runtime.engine.SkillResolver
import ai.openclaw.runtime.engine.SkillsTool
import ai.openclaw.runtime.engine.SystemPromptBuilder
import ai.openclaw.runtime.engine.TranscriptRepairPolicy
import ai.openclaw.runtime.engine.ToolLoopDetector
import ai.openclaw.runtime.engine.ToolRegistry
import ai.openclaw.runtime.engine.tools.CodingToolsModule
import ai.openclaw.runtime.engine.tools.runtime.ProcessRegistry
import ai.openclaw.runtime.gateway.ChannelManager
import ai.openclaw.runtime.gateway.GatewayServer
import ai.openclaw.runtime.memory.MemoryManager
import ai.openclaw.runtime.providers.AnthropicProvider
import ai.openclaw.runtime.providers.GeminiProvider
import ai.openclaw.runtime.providers.OllamaProvider
import ai.openclaw.runtime.providers.OpenAiProvider
import ai.openclaw.runtime.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Central engine wiring all OpenClaw subsystems together.
 */
class AgentEngine(
    private val context: Context,
    managedToolchainManagerFactory: (Context, OkHttpClient) -> ManagedToolchainManager = { ctx, client ->
        ManagedToolchainManager(ctx, client = client)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()

    private var initialized = false
    private var pluginsStarted = false
    private var cronStarted = false
    private var backgroundRuntimeActive = false

    private var gatewayHost: String = DEFAULT_GATEWAY_HOST
    private var gatewayPort: Int = DEFAULT_GATEWAY_PORT
    private var gatewayServerInternal: GatewayServer? = null
    private val codexOauthManager by lazy { CodexOauthManager(context.applicationContext) }
    private val _codexOauthEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val sharedHttpClient = OkHttpClient()
    private val gatewayHttpClient = sharedHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val managedToolchainManager = managedToolchainManagerFactory(context, sharedHttpClient)
    private var processRegistry: ProcessRegistry? = null
    private var defaultStoppedChannelKeys: Set<String> = emptySet()
    private val execEnvironmentResolver = ExecEnvironmentResolver()
    private var execEnvironment = ResolvedExecEnvironment.fromSystem()
    private var managedToolchainActivation = ManagedToolchainActivation()
    private var managedToolchainStatus = ManagedToolchainStatus()

    val providerRegistry = ProviderRegistry()
    val pluginRegistry = PluginRegistry()
    private val hookRunner: HookRunner by lazy { HookRunner(pluginRegistry) }
    private val sessionToolLoopDetectors = ConcurrentHashMap<String, ToolLoopDetector>()
    private val sessionHookSessionIds = ConcurrentHashMap<String, String>()
    val toolRegistry = ToolRegistry()
    val codexOauthEvents: SharedFlow<String> = _codexOauthEvents.asSharedFlow()

    var config: OpenClawConfig = OpenClawConfig()
        private set

    private var configLoaded = false

    val configManager: ConfigManager by lazy { ConfigManager(context) }

    val sessionPersistence: SessionPersistence by lazy {
        SessionPersistence(
            context.filesDir.resolve("sessions").absolutePath,
            transcriptRepairPolicy = TranscriptRepairPolicy.fromConfig(config.session?.transcriptRepair),
        )
    }

    val channelManager: ChannelManager by lazy { ChannelManager(scope) }

    val gatewayServer: GatewayServer
        get() = gatewayServerInternal ?: buildGatewayServer().also { gatewayServerInternal = it }

    val secretStore: SecretStore by lazy {
        SecretStore(
            delegate = SharedPreferencesSecretStore(context.applicationContext),
            auditLog = AuditLog(),
        )
    }

    // Approval policy reads the latest config at check-time.
    val approvalManager: ApprovalManager by lazy {
        ApprovalManager(
            policy = object : ai.openclaw.core.security.ApprovalPolicy {
                override fun requiresApproval(
                    toolName: String,
                    toolInput: String,
                    agentId: String,
                    sessionKey: String,
                ): Boolean {
                    return ConfigBasedApprovalPolicy(config.approvals, config.tools)
                        .requiresApproval(toolName, toolInput, agentId, sessionKey)
                }
            },
            metadataProvider = { toolName, toolInput ->
                ConfigBasedApprovalPolicy(config.approvals, config.tools)
                    .approvalMetadata(toolName, toolInput)
            },
        )
    }

    val memoryManager: MemoryManager by lazy { MemoryManager() }

    private val toolAuditor = ToolAuditor()

    val cronScheduler: CronScheduler by lazy {
        val cronDir = context.filesDir.resolve("cron")
        cronDir.mkdirs()
        CronScheduler(
            store = CronStore(cronDir.resolve("cron-store.json").absolutePath),
            executor = { job ->
                val message = when (val payload = job.payload) {
                    is CronPayload.AgentTurn -> payload.message
                    is CronPayload.SystemEvent -> payload.text
                }
                sendMessage(
                    userMessage = message,
                    model = (job.payload as? CronPayload.AgentTurn)?.model,
                    agentId = job.agentId ?: defaultAgentId(),
                    sessionKey = job.sessionKey ?: "cron:${job.id}",
                ).collect { /* fire-and-forget */ }
            },
            scope = scope,
        )
    }

    /**
     * Load configuration from disk with parse-failure fallback.
     */
    fun loadConfig(): OpenClawConfig {
        config = configManager.load()
        configLoaded = true
        return config
    }

    /**
     * Initialize the foreground engine state without implicitly enabling
     * long-lived background services.
     */
    suspend fun initialize() {
        initMutex.withLock {
            ensureStorageStructure()
            ensureConfigLoaded()
            applyRuntimeConfiguration(notifyPluginReload = initialized && pluginsStarted)
            initialized = true
        }
    }

    suspend fun startBackgroundRuntime() {
        initMutex.withLock {
            ensureStorageStructure()
            ensureConfigLoaded()
            backgroundRuntimeActive = true
            applyRuntimeConfiguration(notifyPluginReload = initialized && pluginsStarted)
            initialized = true
        }
    }

    suspend fun stopBackgroundRuntime() {
        initMutex.withLock {
            backgroundRuntimeActive = false
            syncBackgroundRuntimeState()
        }
    }

    fun isBackgroundRuntimeActive(): Boolean = backgroundRuntimeActive

    fun isGatewayRunning(): Boolean = gatewayServerInternal?.isRunning == true

    fun currentShellEnvironment(): Map<String, String> = execEnvironment.environment

    fun currentShellPath(): String = execEnvironment.shellPath

    suspend fun currentToolchainStatus(): ManagedToolchainStatus {
        initMutex.withLock {
            ensureStorageStructure()
            ensureConfigLoaded()
            refreshExecEnvironment()
            return managedToolchainStatus
        }
    }

    suspend fun installManagedNode(): ManagedToolchainStatus {
        initMutex.withLock {
            ensureStorageStructure()
            ensureConfigLoaded()
            managedToolchainActivation = managedToolchainManager.installNode(config)
            refreshExecEnvironment()
            return managedToolchainStatus
        }
    }

    fun keepAliveInBackgroundEnabled(): Boolean {
        ensureConfigLoaded()
        return config.appRuntime?.keepAliveInBackground == true
    }

    suspend fun setKeepAliveInBackground(enabled: Boolean) {
        ensureConfigLoaded()
        val updatedConfig = config.copy(
            appRuntime = (config.appRuntime ?: AppRuntimeConfig()).copy(
                keepAliveInBackground = enabled,
            ),
        )
        initMutex.withLock {
            if (!enabled) {
                backgroundRuntimeActive = false
            }
            saveConfigInternal(updatedConfig)
        }
    }

    /**
     * Re-read config and notify all subsystems.
     */
    fun reloadConfig() {
        scope.launch {
            initMutex.withLock {
                ensureStorageStructure()
                loadConfig()
                applyRuntimeConfiguration(notifyPluginReload = initialized && pluginsStarted)
                initialized = true
            }
        }
    }

    /**
     * Serialize and write config file.
     */
    fun saveConfig(newConfig: OpenClawConfig) {
        configManager.save(newConfig)
        config = newConfig
        configLoaded = true
        scope.launch {
            initMutex.withLock {
                applyRuntimeConfiguration(notifyPluginReload = initialized && pluginsStarted)
                initialized = true
            }
        }
    }

    private suspend fun saveConfigInternal(newConfig: OpenClawConfig) {
        configManager.save(newConfig)
        config = newConfig
        configLoaded = true
        applyRuntimeConfiguration(notifyPluginReload = initialized && pluginsStarted)
        initialized = true
    }

    data class CodexOauthStatus(
        val tokenSet: Boolean,
        val apiAccessReady: Boolean,
        val accountId: String?,
        val email: String?,
        val expiresAtMs: Long?,
    )

    data class InstallStorageSnapshot(
        val configFileExists: Boolean,
        val sessionCount: Int,
        val cronStoreExists: Boolean,
        val secretCount: Int,
    )

    fun beginCodexOauthLogin(): String {
        return codexOauthManager.buildAuthorizationUrl()
    }

    fun isCodexOauthRedirect(uri: Uri): Boolean {
        return codexOauthManager.isOauthRedirect(uri)
    }

    suspend fun completeCodexOauthRedirect(uri: Uri): Boolean {
        if (!isCodexOauthRedirect(uri)) return false
        return runCatching {
            val session = codexOauthManager.exchangeFromRedirect(uri)
            setCodexOauth(
                accessToken = session.accessToken,
                accountId = session.accountId,
                refreshToken = session.refreshToken,
                idToken = session.idToken,
                expiresAtMs = session.expiresAtMs,
                email = session.email,
                apiKey = session.apiKey,
            )
            _codexOauthEvents.tryEmit(
                if (session.apiKey.isNullOrBlank()) {
                    "Codex OAuth connected, but API credential exchange was unavailable."
                } else {
                    "Codex OAuth connected."
                },
            )
            true
        }.getOrElse { error ->
            _codexOauthEvents.tryEmit(error.message ?: "Codex OAuth failed.")
            true
        }
    }

    suspend fun setApiKey(providerId: String, apiKey: String) {
        ensureConfigLoaded()
        val canonical = canonicalProvider(providerId)
        secretStore.storeSecret(secretKeyForProvider(canonical), apiKey, SecretCategory.LLM_API_KEY)
        if (canonical == "openai") {
            secretStore.deleteSecret(CODEX_OAUTH_TOKEN_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_ID_TOKEN_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_API_KEY_KEY)
            secretStore.deleteSecret(CODEX_ACCOUNT_ID_KEY)
            secretStore.deleteSecret(CODEX_EMAIL_KEY)
        }

        val updatedProfiles = (config.auth?.profiles.orEmpty()).toMutableMap()
        val existingProfileKey = updatedProfiles.entries
            .firstOrNull { canonicalProvider(it.value.provider) == canonical }
            ?.key
        if (existingProfileKey != null) {
            updatedProfiles[existingProfileKey] = updatedProfiles.getValue(existingProfileKey).copy(
                provider = canonical,
                mode = "api-key",
            )
        } else {
            updatedProfiles[canonical] = AuthProfileConfig(provider = canonical, mode = "api-key")
        }

        val updatedConfig = config.copy(
            auth = (config.auth ?: ai.openclaw.core.model.AuthConfig()).copy(
                profiles = updatedProfiles,
            ),
        )
        initMutex.withLock {
            saveConfigInternal(updatedConfig)
            initialized = true
        }
    }

    suspend fun setCodexOauth(
        accessToken: String,
        accountId: String?,
        refreshToken: String? = null,
        idToken: String? = null,
        expiresAtMs: Long? = null,
        email: String? = null,
        apiKey: String? = null,
    ) {
        ensureConfigLoaded()
        val token = accessToken.trim()
        require(token.isNotBlank()) { "Codex OAuth access token cannot be blank" }

        secretStore.storeSecret(CODEX_OAUTH_TOKEN_KEY, token, SecretCategory.LLM_API_KEY)
        if (!refreshToken.isNullOrBlank()) {
            secretStore.storeSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY, refreshToken.trim(), SecretCategory.LLM_API_KEY)
        } else {
            secretStore.deleteSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
        }
        val normalizedIdToken = idToken?.trim().orEmpty()
        if (normalizedIdToken.isBlank()) {
            secretStore.deleteSecret(CODEX_OAUTH_ID_TOKEN_KEY)
        } else {
            secretStore.storeSecret(CODEX_OAUTH_ID_TOKEN_KEY, normalizedIdToken, SecretCategory.LLM_API_KEY)
        }
        if (expiresAtMs != null && expiresAtMs > 0L) {
            secretStore.storeSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY, expiresAtMs.toString(), SecretCategory.LLM_API_KEY)
        } else {
            secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
        }
        val normalizedApiKey = apiKey?.trim().orEmpty()
        if (normalizedApiKey.isBlank()) {
            secretStore.deleteSecret(CODEX_OAUTH_API_KEY_KEY)
        } else {
            secretStore.storeSecret(CODEX_OAUTH_API_KEY_KEY, normalizedApiKey, SecretCategory.LLM_API_KEY)
        }
        val normalizedAccountId = accountId?.trim().orEmpty()
        if (normalizedAccountId.isBlank()) {
            secretStore.deleteSecret(CODEX_ACCOUNT_ID_KEY)
        } else {
            secretStore.storeSecret(CODEX_ACCOUNT_ID_KEY, normalizedAccountId, SecretCategory.LLM_API_KEY)
        }
        val normalizedEmail = email?.trim().orEmpty()
        if (normalizedEmail.isBlank()) {
            secretStore.deleteSecret(CODEX_EMAIL_KEY)
        } else {
            secretStore.storeSecret(CODEX_EMAIL_KEY, normalizedEmail, SecretCategory.LLM_API_KEY)
        }

        val updatedProfiles = (config.auth?.profiles.orEmpty()).toMutableMap()
        val existingOpenAiProfileKey = updatedProfiles.entries
            .firstOrNull { canonicalProvider(it.value.provider) == "openai" }
            ?.key
        if (existingOpenAiProfileKey != null) {
            updatedProfiles[existingOpenAiProfileKey] = updatedProfiles.getValue(existingOpenAiProfileKey).copy(
                provider = "openai",
                mode = "oauth",
            )
        } else {
            updatedProfiles["openai"] = AuthProfileConfig(provider = "openai", mode = "oauth")
        }

        val updatedConfig = config.copy(
            auth = (config.auth ?: ai.openclaw.core.model.AuthConfig()).copy(
                profiles = updatedProfiles,
            ),
        )
        initMutex.withLock {
            saveConfigInternal(updatedConfig)
            initialized = true
        }
    }

    suspend fun clearCodexOauth() {
        ensureConfigLoaded()
        secretStore.deleteSecret(CODEX_OAUTH_TOKEN_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_ID_TOKEN_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_API_KEY_KEY)
        secretStore.deleteSecret(CODEX_ACCOUNT_ID_KEY)
        secretStore.deleteSecret(CODEX_EMAIL_KEY)

        val updatedProfiles = (config.auth?.profiles.orEmpty())
            .filterValues { !(canonicalProvider(it.provider) == "openai" && it.mode.lowercase() == "oauth") }
        val updatedAuth = if (updatedProfiles.isEmpty()) null else {
            (config.auth ?: ai.openclaw.core.model.AuthConfig()).copy(profiles = updatedProfiles)
        }
        val updatedConfig = config.copy(auth = updatedAuth)

        initMutex.withLock {
            saveConfigInternal(updatedConfig)
            initialized = true
        }
    }

    suspend fun getCodexOauthStatus(): CodexOauthStatus {
        val expiresAtMs = secretStore.getSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)?.toLongOrNull()
        return CodexOauthStatus(
            tokenSet = secretStore.hasSecret(CODEX_OAUTH_TOKEN_KEY),
            apiAccessReady = secretStore.hasSecret(CODEX_OAUTH_API_KEY_KEY),
            accountId = secretStore.getSecret(CODEX_ACCOUNT_ID_KEY),
            email = secretStore.getSecret(CODEX_EMAIL_KEY),
            expiresAtMs = expiresAtMs,
        )
    }

    suspend fun clearApiKey(providerId: String) {
        ensureConfigLoaded()
        val canonical = canonicalProvider(providerId)
        for (key in secretKeysForProvider(canonical)) {
            secretStore.deleteSecret(key)
        }

        val updatedProfiles = (config.auth?.profiles.orEmpty())
            .filterValues { canonicalProvider(it.provider) != canonical }
        val updatedAuth = if (updatedProfiles.isEmpty()) null else {
            (config.auth ?: ai.openclaw.core.model.AuthConfig()).copy(profiles = updatedProfiles)
        }
        val updatedConfig = config.copy(auth = updatedAuth)

        initMutex.withLock {
            saveConfigInternal(updatedConfig)
            initialized = true
        }
    }

    suspend fun storageSnapshot(): InstallStorageSnapshot {
        val configFileExists = context.filesDir.resolve("config/openclaw.json").exists()
        val sessionCount = sessionPersistence.listSessionKeys().size
        val cronStoreExists = context.filesDir.resolve("cron/cron-store.json").exists()
        val secretCount = secretStore.listSecretKeys().size
        return InstallStorageSnapshot(
            configFileExists = configFileExists,
            sessionCount = sessionCount,
            cronStoreExists = cronStoreExists,
            secretCount = secretCount,
        )
    }

    suspend fun wipeInstallStorage() {
        initMutex.withLock {
            runCatching { gatewayServerInternal?.stop() }
            gatewayServerInternal = null

            runCatching { cronScheduler.stop() }
            runCatching { channelManager.stopAll() }
            runCatching { pluginRegistry.stopAll() }

            pluginsStarted = false
            cronStarted = false
            initialized = false
            backgroundRuntimeActive = false

            providerRegistry.clear()
            toolRegistry.clear()
            sessionToolLoopDetectors.clear()
            sessionHookSessionIds.clear()
            memoryManager.clear()
            processRegistry?.close()
            processRegistry = null

            runCatching {
                secretStore.listSecretKeys().forEach { key ->
                    secretStore.deleteSecret(key)
                }
            }
            runCatching { codexOauthManager.clearLocalState() }

            clearFilesDirChildren()
            OpenClawDatabase.wipe(context.applicationContext)

            ensureStorageStructure()
            config = OpenClawConfig()
            configManager.save(config)
            configLoaded = true

            applyRuntimeConfiguration(notifyPluginReload = false)
            initialized = true
        }
    }

    fun defaultAgentId(): String {
        ensureConfigLoaded()
        return config.agents?.list
            ?.firstOrNull { it.default == true }
            ?.id
            ?: config.agents?.list?.firstOrNull()?.id
            ?: DEFAULT_AGENT_ID
    }

    fun preferredModelForAgent(agentId: String = defaultAgentId()): String? {
        ensureConfigLoaded()
        val agent = resolveAgentConfig(agentId)
        return agent?.model?.primary
            ?: config.agents?.defaults?.model?.primary
            ?: resolveConfiguredDefaultModel()
    }

    fun preferredReasoningEffortForAgent(
        agentId: String = defaultAgentId(),
        modelId: String? = preferredModelForAgent(agentId),
    ): String? {
        ensureConfigLoaded()
        val agent = resolveAgentConfig(agentId)
        val configured = agent?.model?.reasoningEffort
            ?: config.agents?.defaults?.model?.reasoningEffort
        return normalizeReasoningEffort(modelId, configured)
    }

    fun availableReasoningEffortsForModel(modelId: String): List<String> {
        ensureConfigLoaded()
        return supportedReasoningEfforts(modelId)
    }

    fun availableModelIdsForEnabledProviders(): List<String> {
        ensureConfigLoaded()
        val configuredProviders = config.models?.providers.orEmpty()
        val enabledProviders = resolveEnabledProviders(configuredProviders.keys)
        val available = mutableListOf<String>()

        for ((providerId, providerConfig) in configuredProviders) {
            val canonical = canonicalProvider(providerId)
            if (canonical !in enabledProviders) continue
            for (model in providerConfig.models) {
                val modelId = model.id.trim()
                if (modelId.isEmpty()) continue
                available += routeModelToProvider(
                    if ('/' in modelId) modelId else "$canonical/$modelId",
                )
            }
        }

        for (provider in enabledProviders) {
            val hasConfiguredModels = configuredProviders.entries.any { (providerId, providerConfig) ->
                canonicalProvider(providerId) == provider && providerConfig.models.isNotEmpty()
            }
            if (!hasConfiguredModels) {
                available += suggestedModelsForProvider(provider)
            }
        }

        if (available.isEmpty()) {
            val defaults = enabledProviders.flatMap(::suggestedModelsForProvider)
            if (defaults.isNotEmpty()) {
                available += defaults
            } else {
                available += DEFAULT_MODEL_CANDIDATES
            }
        }

        return available
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun setDefaultModel(modelId: String, reasoningEffort: String? = preferredReasoningEffortForAgent()) {
        val normalized = modelId.trim()
        require(normalized.isNotEmpty()) { "Model ID cannot be blank" }

        val currentAgents = config.agents ?: AgentsConfig()
        val currentDefaults = currentAgents.defaults ?: AgentDefaultsConfig()
        val currentModel = currentDefaults.model ?: AgentModelConfig()
        val normalizedReasoningEffort = normalizeReasoningEffort(normalized, reasoningEffort)

        val updatedConfig = config.copy(
            agents = currentAgents.copy(
                defaults = currentDefaults.copy(
                    model = currentModel.copy(
                        primary = normalized,
                        reasoningEffort = normalizedReasoningEffort,
                    ),
                ),
            ),
        )
        saveConfig(updatedConfig)
    }

    fun setDefaultReasoningEffort(reasoningEffort: String?) {
        val currentModel = preferredModelForAgent() ?: defaultModelForProvider("openai") ?: return
        setDefaultModel(currentModel, reasoningEffort)
    }

    fun currentToolNames(): List<String> = toolRegistry.names().sorted()

    fun terminalWorkingDirectory(): String = context.filesDir.absolutePath

    suspend fun authorizeToolInvocation(
        toolName: String,
        input: String,
        agentId: String = defaultAgentId(),
        sessionKey: String = "local:terminal",
    ): String? {
        ensureInitialized()
        val enforcer = ToolPolicyEnforcer(config, toolAuditor)
        val policy = enforcer.check(toolName = toolName, agentId = agentId, sessionKey = sessionKey)
        if (!policy.allowed) {
            return "Blocked by tool policy: ${policy.reason ?: "not allowed"}"
        }

        return when (approvalManager.checkApproval(toolName, input, agentId, sessionKey)) {
            ApprovalDecision.APPROVED -> null
            ApprovalDecision.DENIED -> "Blocked: shell command was denied by approval policy"
            ApprovalDecision.TIMED_OUT -> "Blocked: shell command timed out waiting for approval"
        }
    }

    /**
     * Send a message and get streamed response events.
     */
    fun sendMessage(
        userMessage: String,
        conversationHistory: List<LlmMessage> = emptyList(),
        prependMessages: List<LlmMessage> = emptyList(),
        model: String? = null,
        systemPrompt: String? = null,
        extraSystemPrompt: String? = null,
        agentId: String? = null,
        sessionKey: String = "",
        messageProvider: String? = null,
        messageChannelId: String? = null,
        messageAccountId: String? = null,
        clientTools: List<ClientToolDefinition> = emptyList(),
    ): Flow<AcpRuntimeEvent> = channelFlow {
        ensureInitialized()
        val resolvedAgentId = agentId ?: defaultAgentId()
        val effectiveMessageProvider = messageProvider?.trim()?.takeIf { it.isNotEmpty() }
        val effectiveChannelId = messageChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: effectiveMessageProvider
        val effectiveAccountId = messageAccountId?.trim()?.takeIf { it.isNotEmpty() }
        val channelContext = buildChannelContext(
            channelId = effectiveChannelId,
            accountId = effectiveAccountId,
        )
        val promptSkills = buildPromptSkillSummaries()
        val hookSessionKey = sessionKey.trim().ifEmpty { "__default__" }
        val hookSessionId = sessionHookSessionIds.computeIfAbsent(hookSessionKey) {
            java.util.UUID.randomUUID().toString()
        }
        val hookContext = PluginHookAgentContext(
            agentId = resolvedAgentId,
            sessionKey = sessionKey.takeIf { it.isNotBlank() },
            sessionId = hookSessionId,
            workspaceDir = terminalWorkingDirectory(),
            messageProvider = effectiveMessageProvider,
            trigger = "user",
            channelId = effectiveChannelId,
        )
        val modelResolve = try {
            hookRunner.runBeforeModelResolve(
                event = BeforeModelResolveEvent(prompt = userMessage),
                ctx = hookContext,
            )
        } catch (err: Throwable) {
            logHookWarning("before_model_resolve hook failed: ${err.message}")
            null
        }
        val legacyModelResolve = try {
            hookRunner.runBeforeAgentStart(
                event = BeforeAgentStartEvent(prompt = userMessage),
                ctx = hookContext,
            )
        } catch (err: Throwable) {
            logHookWarning("before_agent_start hook (legacy model resolve path) failed: ${err.message}")
            null
        }
        val mergedModelOverride = if (modelResolve?.modelOverride != null) {
            modelResolve.modelOverride
        } else {
            legacyModelResolve?.modelOverride
        }
        val mergedProviderOverride = if (modelResolve?.providerOverride != null) {
            modelResolve.providerOverride
        } else {
            legacyModelResolve?.providerOverride
        }
        val hookModelOverride = mergedModelOverride?.takeIf { it.isNotEmpty() }
        val hookProviderOverride = mergedProviderOverride?.takeIf { it.isNotEmpty() }
        val requestedModel = applyModelOverride(
            baseModel = applyProviderOverride(model, hookProviderOverride) ?: model,
            modelOverride = hookModelOverride,
        )
        val modelChain = resolveModelChain(
            agentId = resolvedAgentId,
            requestedModel = requestedModel,
        )

        val messages = conversationHistory + prependMessages + LlmMessage(
            role = LlmMessage.Role.USER,
            content = userMessage,
        )
        val turnRunId = java.util.UUID.randomUUID().toString()

        var lastRetryableError: AcpRuntimeEvent.Error? = null

        for ((index, candidateModel) in modelChain.withIndex()) {
            val routedModel = routeModelToProvider(candidateModel)
            val provider = providerRegistry.resolveProvider(routedModel)
            if (provider == null) {
                lastRetryableError = AcpRuntimeEvent.Error(
                    message = "No provider found for model: $routedModel",
                    retryable = index < modelChain.lastIndex,
                )
                continue
            }

            val runner = AgentRunner(
                provider = provider,
                toolRegistry = toolRegistry,
                sessionPersistence = sessionPersistence,
                contextGuard = ContextGuard(),
                approvalManager = approvalManager,
                toolPolicyEnforcer = ToolPolicyEnforcer(config, toolAuditor),
                toolLoopDetector = resolveToolLoopDetector(sessionKey),
                hookRunner = hookRunner,
                transcriptRepairPolicy = TranscriptRepairPolicy.fromConfig(config.session?.transcriptRepair),
            )

            var retryableFailure: AcpRuntimeEvent.Error? = null
            var completed = false

            val runtimeInfoForTurn = buildRuntimeInfo(
                agentId = resolvedAgentId,
                modelId = routedModel,
                channelContext = channelContext,
            )
            val promptContext = buildPromptContextFilesWithWarnings()
            val contextFiles = promptContext.files
            val bootstrapTruncationWarningLines = promptContext.warningLines
            val workspaceNotes = buildWorkspaceNotes(contextFiles)
            val reactionGuidance = resolveReactionGuidance(channelContext)
            val ttsHint = resolveTtsPromptHint()
            val messageToolHints = buildMessageToolHints(channelContext)
            val ownerDisplay = resolveOwnerDisplayMode()
            val ownerDisplaySecret = config.commands?.ownerDisplaySecret
            val heartbeatPrompt = resolveHeartbeatPrompt(resolvedAgentId)
            val reasoningEffort = preferredReasoningEffortForAgent(
                agentId = resolvedAgentId,
                modelId = routedModel,
            )
            runner.runTurn(
                EmbeddedRunParams(
                    messages = messages,
                    model = routedModel,
                    runId = turnRunId,
                    systemPrompt = systemPrompt,
                    sessionKey = sessionKey,
                    agentId = resolvedAgentId,
                    agentIdentity = resolveAgentConfig(resolvedAgentId)?.identity,
                    channelContext = channelContext,
                    skills = promptSkills,
                    runtimeInfo = runtimeInfoForTurn,
                    workspaceDir = terminalWorkingDirectory(),
                    hookSessionId = hookSessionId,
                    legacyBeforeAgentStartResult = legacyModelResolve,
                    modelAliasLines = buildModelAliasLines(),
                    workspaceNotes = workspaceNotes,
                    docsPath = resolveDocsPath(),
                    ownerNumbers = config.commands?.ownerAllowFrom.orEmpty(),
                    ownerDisplay = ownerDisplay,
                    ownerDisplaySecret = ownerDisplaySecret,
                    reasoningTagHint = resolveReasoningTagHint(routedModel),
                    reasoningEffort = reasoningEffort,
                    extraSystemPrompt = extraSystemPrompt,
                    contextFiles = contextFiles,
                    bootstrapTruncationWarningLines = bootstrapTruncationWarningLines,
                    memoryCitationsMode = config.memory?.citations,
                    ttsHint = ttsHint,
                    reactionGuidance = reactionGuidance,
                    messageToolHints = messageToolHints,
                    heartbeatPrompt = heartbeatPrompt,
                    turnContext = EmbeddedTurnContext(
                        trigger = "user",
                        messageProvider = effectiveMessageProvider,
                        channelId = effectiveChannelId,
                        sessionId = hookSessionId,
                        senderIsOwner = null,
                        sandboxed = runtimeInfoForTurn.sandboxed,
                        groupToolPolicy = null,
                    ),
                    clientTools = clientTools,
                ),
            ).collect { event ->
                when (event) {
                    is AcpRuntimeEvent.Error -> {
                        val canRetry = (event.retryable == true) && index < modelChain.lastIndex
                        if (canRetry) {
                            retryableFailure = event
                        } else {
                            send(event)
                        }
                    }
                    is AcpRuntimeEvent.Done -> {
                        completed = true
                        send(event)
                    }
                    else -> {
                        if (retryableFailure == null) {
                            send(event)
                        }
                    }
                }
            }

            if (completed) {
                return@channelFlow
            }

            if (retryableFailure != null) {
                lastRetryableError = retryableFailure
                if (index < modelChain.lastIndex) {
                    send(
                        AcpRuntimeEvent.Status(
                            text = "Model '$routedModel' failed, retrying fallback model",
                            tag = "model_fallback",
                        ),
                    )
                    continue
                }
                send(retryableFailure)
                return@channelFlow
            }

            return@channelFlow
        }

        send(
            lastRetryableError ?: AcpRuntimeEvent.Error(
                message = "No provider found for model chain: ${modelChain.joinToString(" -> ")}",
            ),
        )
    }

    /**
     * Shut down the engine gracefully.
     */
    suspend fun shutdown() {
        backgroundRuntimeActive = false
        syncBackgroundRuntimeState()
        pluginRegistry.stopAll()
        processRegistry?.close()
        processRegistry = null
        gatewayHttpClient.connectionPool.evictAll()
        sharedHttpClient.connectionPool.evictAll()
        gatewayHttpClient.dispatcher.executorService.shutdown()
        sharedHttpClient.dispatcher.executorService.shutdown()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun applyRuntimeConfiguration(notifyPluginReload: Boolean) {
        sessionPersistence.setTranscriptRepairPolicy(
            TranscriptRepairPolicy.fromConfig(config.session?.transcriptRepair),
        )
        sessionToolLoopDetectors.clear()
        refreshExecEnvironment()
        registerProviders()
        registerTools()
        registerChannels()

        if (!pluginsStarted) {
            runCatching { pluginRegistry.startAll() }
            pluginsStarted = true
        } else if (notifyPluginReload) {
            runCatching { pluginRegistry.notifyConfigReload() }
        }

        syncBackgroundRuntimeState()
    }

    private suspend fun registerProviders() {
        providerRegistry.clear()

        val configured = config.models?.providers.orEmpty()
        val covered = mutableSetOf<String>()

        for ((providerId, providerConfig) in configured) {
            val canonical = canonicalProvider(providerId)
            registerProvider(canonical, providerConfig)
            covered += canonical
        }

        // Always consider built-ins even when models.providers is empty.
        val defaults = listOf("anthropic", "openai", "gemini", "ollama")
        for (providerId in defaults) {
            if (providerId !in covered) {
                registerProvider(providerId, null)
            }
        }
    }

    private suspend fun registerProvider(
        providerId: String,
        providerConfig: ModelProviderConfig?,
    ) {
        when (providerId) {
            "anthropic" -> {
                val apiKey = resolveProviderApiKey(
                    aliases = listOf("anthropic"),
                    providerConfig = providerConfig,
                ) ?: return
                providerRegistry.register(
                    AnthropicProvider(
                        apiKey = apiKey,
                        baseUrl = providerConfig?.baseUrl ?: "https://api.anthropic.com",
                        client = sharedHttpClient,
                    ),
                )
            }

            "openai" -> {
                val apiKey = resolveProviderApiKey(
                    aliases = listOf("openai"),
                    providerConfig = providerConfig,
                ) ?: return
                providerRegistry.register(
                    OpenAiProvider(
                        apiKey = apiKey,
                        baseUrl = providerConfig?.baseUrl ?: "https://api.openai.com/v1",
                        client = sharedHttpClient,
                        extraHeaders = resolveOpenAiHeaders(providerConfig),
                    ),
                )
            }

            "gemini" -> {
                val apiKey = resolveProviderApiKey(
                    aliases = listOf("gemini", "google"),
                    providerConfig = providerConfig,
                ) ?: return
                providerRegistry.register(
                    GeminiProvider(
                        apiKey = apiKey,
                        baseUrl = providerConfig?.baseUrl
                            ?: "https://generativelanguage.googleapis.com/v1beta",
                        client = sharedHttpClient,
                    ),
                )
            }

            "ollama" -> {
                val apiKey = resolveProviderApiKey(
                    aliases = listOf("ollama"),
                    providerConfig = providerConfig,
                )
                providerRegistry.register(
                    OllamaProvider(
                        baseUrl = providerConfig?.baseUrl ?: "http://localhost:11434",
                        apiKey = apiKey,
                        client = sharedHttpClient,
                    ),
                )
            }
        }
    }

    private suspend fun resolveProviderApiKey(
        aliases: List<String>,
        providerConfig: ModelProviderConfig?,
    ): String? {
        resolveSecretInput(providerConfig?.apiKey)?.let { return it }

        if (aliases.any { canonicalProvider(it) == "openai" } && shouldUseCodexOauth(providerConfig)) {
            resolveCodexOauthApiKey()?.let { return it }
        }

        for (provider in aliases) {
            for (secretKey in secretKeysForProvider(provider)) {
                secretStore.getSecret(secretKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }

        val profiles = config.auth?.profiles.orEmpty()
        for ((_, profile) in profiles) {
            if (canonicalProvider(profile.provider) in aliases.map { canonicalProvider(it) }) {
                resolveProfileApiKey(profile)?.let { return it }
            }
        }

        for (provider in aliases) {
            providerEnvVars(provider)
                .firstNotNullOfOrNull { key -> System.getenv(key)?.takeIf { it.isNotBlank() } }
                ?.let { return it }
        }

        return null
    }

    private suspend fun resolveCodexOauthApiKey(): String? {
        val currentApiKey = secretStore.getSecret(CODEX_OAUTH_API_KEY_KEY)
            ?.takeIf { it.isNotBlank() }
        val currentIdToken = secretStore.getSecret(CODEX_OAUTH_ID_TOKEN_KEY)
            ?.takeIf { it.isNotBlank() }
        val currentToken = secretStore.getSecret(CODEX_OAUTH_TOKEN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return currentApiKey
        val expiresAtMs = secretStore.getSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)?.toLongOrNull()
        val now = System.currentTimeMillis()
        val tokenFresh = expiresAtMs == null || expiresAtMs > now + CODEX_EXPIRY_MARGIN_MS
        if (currentApiKey != null && tokenFresh) {
            return currentApiKey
        }
        if (currentApiKey == null && currentIdToken != null && tokenFresh) {
            return runCatching {
                codexOauthManager.exchangeApiKeyFromIdToken(currentIdToken).also { apiKey ->
                    secretStore.storeSecret(CODEX_OAUTH_API_KEY_KEY, apiKey, SecretCategory.LLM_API_KEY)
                }
            }.getOrElse {
                currentApiKey
            }
        }

        val refreshToken = secretStore.getSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return currentApiKey

        return runCatching {
            val previousIdToken = currentIdToken
            val refreshed = codexOauthManager.refreshFromRefreshToken(refreshToken)
            secretStore.storeSecret(CODEX_OAUTH_TOKEN_KEY, refreshed.accessToken, SecretCategory.LLM_API_KEY)
            secretStore.storeSecret(
                CODEX_OAUTH_REFRESH_TOKEN_KEY,
                (refreshed.refreshToken ?: refreshToken),
                SecretCategory.LLM_API_KEY,
            )
            val nextIdToken = refreshed.idToken?.trim().orEmpty().ifBlank { previousIdToken.orEmpty() }
            if (nextIdToken.isBlank()) {
                secretStore.deleteSecret(CODEX_OAUTH_ID_TOKEN_KEY)
            } else {
                secretStore.storeSecret(CODEX_OAUTH_ID_TOKEN_KEY, nextIdToken, SecretCategory.LLM_API_KEY)
            }
            if (refreshed.expiresAtMs != null && refreshed.expiresAtMs > 0L) {
                secretStore.storeSecret(
                    CODEX_OAUTH_EXPIRES_AT_MS_KEY,
                    refreshed.expiresAtMs.toString(),
                    SecretCategory.LLM_API_KEY,
                )
            } else {
                secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
            }
            val refreshedApiKey = refreshed.apiKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: nextIdToken.takeIf { it.isNotBlank() }?.let { codexOauthManager.exchangeApiKeyFromIdToken(it) }
                ?: currentApiKey
            if (refreshedApiKey.isNullOrBlank()) {
                secretStore.deleteSecret(CODEX_OAUTH_API_KEY_KEY)
            } else {
                secretStore.storeSecret(CODEX_OAUTH_API_KEY_KEY, refreshedApiKey, SecretCategory.LLM_API_KEY)
            }
            val refreshedAccountId = refreshed.accountId?.trim().orEmpty()
            if (refreshedAccountId.isBlank()) {
                secretStore.deleteSecret(CODEX_ACCOUNT_ID_KEY)
            } else {
                secretStore.storeSecret(CODEX_ACCOUNT_ID_KEY, refreshedAccountId, SecretCategory.LLM_API_KEY)
            }
            val refreshedEmail = refreshed.email?.trim().orEmpty()
            if (refreshedEmail.isBlank()) {
                secretStore.deleteSecret(CODEX_EMAIL_KEY)
            } else {
                secretStore.storeSecret(CODEX_EMAIL_KEY, refreshedEmail, SecretCategory.LLM_API_KEY)
            }
            _codexOauthEvents.tryEmit("Codex OAuth token refreshed.")
            refreshedApiKey
        }.getOrElse {
            currentApiKey
        }
    }

    private fun shouldUseCodexOauth(providerConfig: ModelProviderConfig?): Boolean {
        if (providerConfig?.auth == ModelProviderAuthMode.OAUTH) {
            return true
        }
        return config.auth?.profiles
            .orEmpty()
            .values
            .any { canonicalProvider(it.provider) == "openai" && it.mode.equals("oauth", ignoreCase = true) }
    }

    private suspend fun resolveOpenAiHeaders(providerConfig: ModelProviderConfig?): Map<String, String> {
        return providerConfig?.headers
            .orEmpty()
            .filterValues { it.isNotBlank() }
            .toMutableMap()
    }

    private suspend fun resolveProfileApiKey(profile: AuthProfileConfig): String? {
        val canonical = canonicalProvider(profile.provider)
        for (key in secretKeysForProvider(canonical) + "api_key_${profile.provider}") {
            secretStore.getSecret(key)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private suspend fun resolveSecretInput(input: SecretInput?): String? {
        if (input == null) return null
        if (!input.value.isNullOrBlank()) return input.value
        val envName = input.env
        if (!envName.isNullOrBlank()) {
            System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun registerTools() {
        toolRegistry.clear()

        val toolsConfig = config.tools
        val execConfig = toolsConfig?.exec
        val workspaceOnly = toolsConfig?.fs?.workspaceOnly != false
        val applyPatchConfig = execConfig?.applyPatch
        val applyPatchEnabled = applyPatchConfig?.enabled == true
        val applyPatchWorkspaceOnly = workspaceOnly || applyPatchConfig?.workspaceOnly != false
        val registry = processRegistry ?: ProcessRegistry(
            cleanupMs = execConfig?.cleanupMs?.toLong()?.coerceAtLeast(1L) ?: 300_000L,
        ).also { processRegistry = it }

        CodingToolsModule.registerAll(
            registry = toolRegistry,
            config = CodingToolsModule.Config(
                workspaceDir = terminalWorkingDirectory(),
                workspaceOnly = workspaceOnly,
                applyPatchEnabled = applyPatchEnabled,
                applyPatchWorkspaceOnly = applyPatchWorkspaceOnly,
                execTimeoutSec = execConfig?.timeoutSec?.coerceAtLeast(1) ?: 120,
                execYieldMs = execConfig?.backgroundMs?.toLong()?.coerceAtLeast(0L) ?: 10_000L,
                processCleanupMs = execConfig?.cleanupMs?.toLong()?.coerceAtLeast(1L) ?: 300_000L,
                shellPath = execEnvironment.shellPath,
                baseEnv = execEnvironment.environment,
            ),
            processRegistry = registry,
        )

        DeviceToolsModule.registerAll(
            registry = toolRegistry,
            context = context,
            config = DeviceToolsConfig(
                launchActivityClass = MainActivity::class.java,
            ),
        )

        toolRegistry.register(CronTool(cronScheduler))

        val skillExecutor = SkillExecutor()
        toolRegistry.register(
            SkillsTool(
                skillExecutor = skillExecutor,
                availableSkills = {
                    SkillResolver(config = config.skills).resolveSkills()
                },
            ),
        )
    }

    private suspend fun registerChannels() {
        channelManager.setInboundHandler(::handleInboundChannelMessage)

        val registrations = mutableListOf<Pair<ChannelManager.ChannelAdapter, String>>()
        val initiallyStopped = mutableSetOf<String>()
        val channels = config.channels

        fun markAutoStart(channelId: String, accountId: String, enabled: Boolean?) {
            if (enabled == false) {
                initiallyStopped += "$channelId:$accountId"
            }
        }

        suspend fun registerTelegram(cfg: TelegramConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to TelegramAccountConfig()) }
            for ((accountId, account) in accounts) {
                val adapter: ChannelManager.ChannelAdapter = resolveSecretInput(account.botToken)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { token ->
                        pluginChannelAdapter(
                            TelegramChannel(
                                botToken = token,
                                client = sharedHttpClient,
                            ),
                        )
                    } 
                    ?: unsupportedChannelAdapter(
                        channelId = "telegram",
                        displayName = "Telegram",
                        reason = "Missing bot token.",
                    )
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("telegram", accountId, cfg.enabled)
            }
        }

        suspend fun registerDiscord(cfg: DiscordConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to DiscordAccountConfig()) }
            for ((accountId, account) in accounts) {
                val adapter: ChannelManager.ChannelAdapter = resolveSecretInput(account.botToken)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { token ->
                        pluginChannelAdapter(
                            DiscordChannel(
                                botToken = token,
                                client = gatewayHttpClient,
                                intents = resolveDiscordIntents(account),
                            ),
                        )
                    } 
                    ?: unsupportedChannelAdapter(
                        channelId = "discord",
                        displayName = "Discord",
                        reason = "Missing bot token.",
                    )
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("discord", accountId, cfg.enabled)
            }
        }

        suspend fun registerSlack(cfg: SlackConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to SlackAccountConfig()) }
            for ((accountId, account) in accounts) {
                val botToken = resolveSecretInput(account.botToken)
                val appToken = resolveSecretInput(account.appToken)
                val adapter: ChannelManager.ChannelAdapter = if (!botToken.isNullOrBlank() && !appToken.isNullOrBlank()) {
                    pluginChannelAdapter(
                        SlackChannel(
                            botToken = botToken,
                            appToken = appToken,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "slack",
                        displayName = "Slack",
                        reason = "Missing bot token or app token.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("slack", accountId, cfg.enabled)
            }
        }

        fun registerSignal(cfg: SignalConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to SignalAccountConfig()) }
            for ((accountId, account) in accounts) {
                val apiUrl = account.apiUrl?.takeIf { it.isNotBlank() }
                val number = account.number?.takeIf { it.isNotBlank() }
                val adapter: ChannelManager.ChannelAdapter = if (apiUrl != null && number != null) {
                    pluginChannelAdapter(
                        SignalChannel(
                            apiUrl = apiUrl,
                            number = number,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "signal",
                        displayName = "Signal",
                        reason = "Missing API URL or phone number.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("signal", accountId, cfg.enabled)
            }
        }

        suspend fun registerMatrix(cfg: MatrixConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to ai.openclaw.core.model.MatrixAccountConfig()) }
            for ((accountId, account) in accounts) {
                val homeserverUrl = account.homeserverUrl?.takeIf { it.isNotBlank() }
                val accessToken = resolveSecretInput(account.accessToken)
                val userId = account.userId?.takeIf { it.isNotBlank() }
                val adapter: ChannelManager.ChannelAdapter = if (
                    homeserverUrl != null &&
                    !accessToken.isNullOrBlank() &&
                    userId != null
                ) {
                    pluginChannelAdapter(
                        MatrixChannel(
                            homeserverUrl = homeserverUrl,
                            accessToken = accessToken,
                            userId = userId,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "matrix",
                        displayName = "Matrix",
                        reason = "Missing homeserver URL, access token, or user ID.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("matrix", accountId, cfg.enabled)
            }
        }

        suspend fun registerIrc(cfg: IrcConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to IrcAccountConfig()) }
            for ((accountId, account) in accounts) {
                val server = account.server?.takeIf { it.isNotBlank() }
                val nick = account.nick?.takeIf { it.isNotBlank() }
                val password = resolveSecretInput(account.password)
                val adapter: ChannelManager.ChannelAdapter = if (server != null && nick != null) {
                    pluginChannelAdapter(
                        IrcChannel(
                            server = server,
                            port = account.port ?: 6667,
                            nick = nick,
                            password = password,
                            channels = account.channels.orEmpty(),
                            useTls = account.useTls == true,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "irc",
                        displayName = "IRC",
                        reason = "Missing server or nickname.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("irc", accountId, cfg.enabled)
            }
        }

        suspend fun registerLine(cfg: LineConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to LineAccountConfig()) }
            for ((accountId, account) in accounts) {
                val accessToken = resolveSecretInput(account.channelAccessToken)
                val secret = resolveSecretInput(account.channelSecret)
                val adapter: ChannelManager.ChannelAdapter = if (!accessToken.isNullOrBlank() && !secret.isNullOrBlank()) {
                    pluginChannelAdapter(
                        LineChannel(
                            channelAccessToken = accessToken,
                            channelSecret = secret,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "line",
                        displayName = "LINE",
                        reason = "Missing channel access token or channel secret.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("line", accountId, cfg.enabled)
            }
        }

        suspend fun registerMattermost(cfg: MattermostConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to MattermostAccountConfig()) }
            for ((accountId, account) in accounts) {
                val serverUrl = account.serverUrl?.takeIf { it.isNotBlank() }
                val accessToken = resolveSecretInput(account.accessToken)
                val adapter: ChannelManager.ChannelAdapter = if (serverUrl != null && !accessToken.isNullOrBlank()) {
                    pluginChannelAdapter(
                        MattermostChannel(
                            serverUrl = serverUrl,
                            accessToken = accessToken,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "mattermost",
                        displayName = "Mattermost",
                        reason = "Missing server URL or access token.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("mattermost", accountId, cfg.enabled)
            }
        }

        suspend fun registerNostr(cfg: NostrConfig) {
            val accounts = cfg.accounts.orEmpty().ifEmpty { mapOf("default" to NostrAccountConfig()) }
            for ((accountId, account) in accounts) {
                val privateKey = resolveSecretInput(account.privateKey)
                val relayUrls = account.relayUrls?.takeIf { it.isNotEmpty() }
                val adapter: ChannelManager.ChannelAdapter = if (!privateKey.isNullOrBlank() && relayUrls != null) {
                    pluginChannelAdapter(
                        NostrChannel(
                            privateKeyHex = privateKey,
                            relayUrls = relayUrls,
                            client = sharedHttpClient,
                        ),
                    )
                } else {
                    unsupportedChannelAdapter(
                        channelId = "nostr",
                        displayName = "Nostr",
                        reason = "Missing private key or relay URLs.",
                    )
                }
                registrations += wrapChannelAdapter(accountId, adapter) to accountId
                markAutoStart("nostr", accountId, cfg.enabled)
            }
        }

        suspend fun registerMSTeams(cfg: MSTeamsConfig) {
            val appId = cfg.appId?.takeIf { it.isNotBlank() }
            val appPassword = cfg.appPassword?.takeIf { it.isNotBlank() }
            val adapter: ChannelManager.ChannelAdapter = if (appId != null && appPassword != null) {
                pluginChannelAdapter(
                    MSTeamsChannel(
                        appId = appId,
                        appPassword = appPassword,
                        tenantId = cfg.tenantId,
                        client = sharedHttpClient,
                    ),
                )
            } else {
                unsupportedChannelAdapter(
                    channelId = "msteams",
                    displayName = "Microsoft Teams",
                    reason = "Missing app ID or app password.",
                )
            }
            registrations += wrapChannelAdapter("default", adapter) to "default"
            markAutoStart("msteams", "default", cfg.enabled)
        }

        fun registerUnsupportedAccountChannel(
            channelId: String,
            displayName: String,
            enabled: Boolean?,
            accounts: Map<String, Any?>,
            reason: String,
        ) {
            val resolvedAccounts = accounts.ifEmpty { mapOf("default" to null) }
            for ((accountId, _) in resolvedAccounts) {
                registrations += wrapChannelAdapter(
                    accountId,
                    unsupportedChannelAdapter(
                        channelId = channelId,
                        displayName = displayName,
                        reason = reason,
                    ),
                ) to accountId
                markAutoStart(channelId, accountId, enabled)
            }
        }

        channels?.telegram?.let { registerTelegram(it) }
        channels?.discord?.let { registerDiscord(it) }
        channels?.slack?.let { registerSlack(it) }
        channels?.signal?.let { registerSignal(it) }
        channels?.matrix?.let { registerMatrix(it) }
        channels?.irc?.let { registerIrc(it) }
        channels?.line?.let { registerLine(it) }
        channels?.mattermost?.let { registerMattermost(it) }
        channels?.nostr?.let { registerNostr(it) }
        channels?.msteams?.let { registerMSTeams(it) }
        channels?.googlechat?.let {
            registerUnsupportedAccountChannel(
                channelId = "googlechat",
                displayName = "Google Chat",
                enabled = it.enabled,
                accounts = it.accounts.orEmpty(),
                reason = "Google Chat service-account token exchange is not wired yet.",
            )
        }
        channels?.whatsapp?.let {
            registerUnsupportedAccountChannel(
                channelId = "whatsapp",
                displayName = "WhatsApp",
                enabled = it.enabled,
                accounts = it.accounts.orEmpty(),
                reason = "WhatsApp Cloud API credentials are not represented in the current config model.",
            )
        }
        channels?.imessage?.let {
            registerUnsupportedAccountChannel(
                channelId = "imessage",
                displayName = "iMessage",
                enabled = it.enabled,
                accounts = it.accounts.orEmpty(),
                reason = "The iMessage runtime module is not included in this Android build.",
            )
        }
        channels?.webchat?.let { cfg ->
            registrations += wrapChannelAdapter(
                "default",
                gatewayBackedChannelAdapter(),
            ) to "default"
            markAutoStart("webchat", "default", cfg.enabled)
        }

        defaultStoppedChannelKeys = initiallyStopped
        channelManager.replaceChannels(
            registrations = registrations,
            defaultStoppedKeys = defaultStoppedChannelKeys,
        )
    }

    private suspend fun syncBackgroundRuntimeState() {
        if (!backgroundRuntimeActive) {
            runCatching { channelManager.stopAll() }
            runCatching { gatewayServerInternal?.stop() }
            runCatching { cronScheduler.stop() }
            cronStarted = false
            return
        }

        ensureGatewayServer(startGateway = true)

        if (config.cron?.enabled == false) {
            if (cronStarted) {
                runCatching { cronScheduler.stop() }
                cronStarted = false
            }
        } else if (!cronStarted) {
            runCatching { cronScheduler.start() }
            cronStarted = true
        }

        runCatching { channelManager.startAll() }
    }

    private fun ensureGatewayServer(startGateway: Boolean) {
        val desiredHost = config.gateway?.customBindHost ?: DEFAULT_GATEWAY_HOST
        val desiredPort = config.gateway?.port ?: DEFAULT_GATEWAY_PORT

        val shouldReplace = gatewayServerInternal == null || gatewayHost != desiredHost || gatewayPort != desiredPort
        if (shouldReplace) {
            gatewayServerInternal?.stop()
            gatewayHost = desiredHost
            gatewayPort = desiredPort
            gatewayServerInternal = buildGatewayServer()
        }

        val server = gatewayServerInternal ?: return
        server.onChatSend = { params ->
            val prependMessages = params.functionCallOutputs.map { output ->
                LlmMessage(
                    role = LlmMessage.Role.TOOL,
                    content = output.output,
                    toolCallId = output.callId,
                    name = output.name ?: "client",
                )
            }
            val clientTools = params.clientTools.map { tool ->
                ClientToolDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters,
                )
            }
            sendMessage(
                userMessage = params.text,
                model = params.model,
                agentId = params.agentId ?: defaultAgentId(),
                sessionKey = params.sessionKey,
                messageProvider = params.channel,
                messageChannelId = params.channel,
                messageAccountId = params.accountId,
                prependMessages = prependMessages,
                clientTools = clientTools,
            )
        }
        server.onAbortPersistPartial = { abort ->
            sessionPersistence.appendAbortAssistantPartial(
                sessionKey = abort.sessionKey,
                runId = abort.runId,
                text = abort.text,
                origin = abort.origin,
            )
        }

        if (startGateway && !server.isRunning) {
            runCatching { server.start() }
        }
    }

    private fun buildGatewayServer(): GatewayServer {
        return GatewayServer(
            port = gatewayPort,
            host = gatewayHost,
            config = config,
        )
    }

    private suspend fun handleInboundChannelMessage(message: InboundMessage) {
        if (message.text.isBlank()) return
        val responseText = StringBuilder()
        runCatching {
            sendMessage(
                userMessage = message.text,
                agentId = defaultAgentId(),
                sessionKey = channelSessionKey(message),
                messageProvider = message.channelId,
                messageChannelId = message.channelId,
                messageAccountId = message.accountId,
            ).collect { event ->
                when (event) {
                    is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                    is AcpRuntimeEvent.Done -> {
                        val text = responseText.toString().trim()
                        if (text.isNotEmpty()) {
                            channelManager.send(
                                channelId = message.channelId,
                                accountId = message.accountId,
                                message = OutboundMessage(
                                    channelId = message.channelId,
                                    accountId = message.accountId,
                                    targetId = message.targetId,
                                    text = text,
                                    threadId = message.threadId,
                                    replyToMessageId = message.messageId,
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun channelSessionKey(message: InboundMessage): String {
        val threadPart = message.threadId?.takeIf { it.isNotBlank() } ?: "root"
        val targetPart = message.targetId.ifBlank { "unknown" }
        val chatScope = when (message.chatType) {
            ChatType.DIRECT -> "dm"
            ChatType.GROUP -> "group"
            ChatType.CHANNEL -> "channel"
        }
        return "channel:${message.channelId}:${message.accountId}:$chatScope:$targetPart:$threadPart"
    }

    private fun wrapChannelAdapter(
        accountId: String,
        delegate: ChannelManager.ChannelAdapter,
    ): ChannelManager.ChannelAdapter {
        return object : ChannelManager.ChannelAdapter {
            override val channelId: ChannelId = delegate.channelId
            override val displayName: String = delegate.displayName

            override suspend fun start(handler: suspend (InboundMessage) -> Unit) {
                delegate.start { inbound ->
                    handler(
                        inbound.copy(
                            channelId = channelId,
                            accountId = accountId,
                        ),
                    )
                }
            }

            override suspend fun stop() {
                delegate.stop()
            }

            override suspend fun send(message: OutboundMessage): Boolean {
                return delegate.send(
                    message.copy(
                        channelId = channelId,
                        accountId = accountId,
                    ),
                )
            }
        }
    }

    private fun pluginChannelAdapter(plugin: ChannelPlugin): ChannelManager.ChannelAdapter {
        return object : ChannelManager.ChannelAdapter {
            override val channelId: ChannelId = plugin.channelId
            override val displayName: String = plugin.displayName

            override suspend fun start(handler: suspend (InboundMessage) -> Unit) {
                plugin.start(handler)
            }

            override suspend fun stop() {
                plugin.stop()
            }

            override suspend fun send(message: OutboundMessage): Boolean {
                return plugin.send(message)
            }
        }
    }

    private fun unsupportedChannelAdapter(
        channelId: String,
        displayName: String,
        reason: String,
    ): ChannelManager.ChannelAdapter {
        return object : ChannelManager.ChannelAdapter {
            override val channelId: ChannelId = channelId
            override val displayName: String = displayName

            override suspend fun start(handler: suspend (InboundMessage) -> Unit) {
                throw ChannelManager.NonRetryableChannelException(reason)
            }

            override suspend fun stop() = Unit

            override suspend fun send(message: OutboundMessage): Boolean = false
        }
    }

    private fun gatewayBackedChannelAdapter(): ChannelManager.ChannelAdapter {
        return object : ChannelManager.ChannelAdapter {
            override val channelId: ChannelId = "webchat"
            override val displayName: String = "Web Chat"

            override suspend fun start(handler: suspend (InboundMessage) -> Unit) = Unit

            override suspend fun stop() = Unit

            override suspend fun send(message: OutboundMessage): Boolean = false
        }
    }

    private fun resolveDiscordIntents(account: DiscordAccountConfig): Int {
        if (account.intents.isNullOrEmpty()) return DiscordChannel.DEFAULT_INTENTS
        val mapping = mapOf(
            "guilds" to DiscordChannel.INTENT_GUILDS,
            "guild_members" to DiscordChannel.INTENT_GUILD_MEMBERS,
            "guild_messages" to DiscordChannel.INTENT_GUILD_MESSAGES,
            "guild_message_reactions" to DiscordChannel.INTENT_GUILD_MESSAGE_REACTIONS,
            "direct_messages" to DiscordChannel.INTENT_DIRECT_MESSAGES,
            "direct_message_reactions" to DiscordChannel.INTENT_DIRECT_MESSAGE_REACTIONS,
            "message_content" to DiscordChannel.INTENT_MESSAGE_CONTENT,
            "guild_voice_states" to DiscordChannel.INTENT_GUILD_VOICE_STATES,
        )
        val resolved = account.intents.orEmpty()
            .mapNotNull { mapping[it.trim().lowercase()] }
            .fold(0) { acc, intent -> acc or intent }
        return if (resolved == 0) DiscordChannel.DEFAULT_INTENTS else resolved
    }

    private fun resolveModelChain(
        agentId: String,
        requestedModel: String?,
    ): List<String> {
        val agentConfig = resolveAgentConfig(agentId)
        val defaultsModel = config.agents?.defaults?.model
        val configuredPrimary = agentConfig?.model?.primary
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultsModel?.primary
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: resolveConfiguredDefaultModel()
        val agentFallbackOverride = agentConfig?.model?.fallbacks?.let { it.toList() }
        val configuredFallbacks = agentFallbackOverride ?: defaultsModel?.fallbacks.orEmpty()
        val requested = requestedModel?.trim()?.takeIf { it.isNotEmpty() }

        val chain = mutableListOf<String>()

        if (requested != null) {
            chain += requested
            if (agentFallbackOverride != null) {
                chain += configuredFallbacks
            } else {
                val sameProviderAsConfigured = configuredPrimary?.let {
                    sharesProvider(requested, it)
                } ?: false
                val requestedInFallbacks = configuredFallbacks.any {
                    sameResolvedModel(requested, it)
                }
                if (sameProviderAsConfigured || requestedInFallbacks || configuredPrimary == null) {
                    chain += configuredFallbacks
                }
                configuredPrimary?.let(chain::add)
            }
        } else {
            configuredPrimary?.let(chain::add)
            chain += configuredFallbacks
        }

        if (chain.isEmpty()) {
            chain += availableModelIdsForEnabledProviders()
        }

        return chain
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun resolveConfiguredDefaultModel(): String? {
        return availableModelIdsForEnabledProviders().firstOrNull()
    }

    private fun buildPromptSkillSummaries(): List<SystemPromptBuilder.SkillSummary> {
        val resolver = SkillResolver(config = config.skills)
        val resolved = resolver.resolveSkills()
        return resolver.buildSkillSummaries(resolved)
    }

    private fun buildChannelContext(
        channelId: String?,
        accountId: String? = null,
    ): SystemPromptBuilder.ChannelContext? {
        val resolvedChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SystemPromptBuilder.ChannelContext(
            channelId = resolvedChannelId,
            accountId = accountId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun applyProviderOverride(requestedModel: String?, providerOverride: String?): String? {
        val provider = providerOverride?.takeIf { it.isNotEmpty() } ?: return requestedModel
        val normalizedProvider = canonicalProvider(provider)
        val current = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
        if (current != null) {
            val modelName = current.substringAfter("/", current)
            return "$normalizedProvider/$modelName"
        }
        return availableModelIdsForEnabledProviders()
            .firstOrNull { modelId ->
                canonicalProvider(modelId.substringBefore("/", missingDelimiterValue = modelId)) == normalizedProvider
            }
    }

    private fun applyModelOverride(baseModel: String?, modelOverride: String?): String? {
        val override = modelOverride?.takeIf { it.isNotEmpty() } ?: return baseModel
        if (override.contains("/")) {
            return override
        }
        val current = baseModel?.trim()?.takeIf { it.isNotEmpty() } ?: return override
        val providerPrefix = current.substringBefore("/", missingDelimiterValue = "").trim()
        return if (providerPrefix.isNotEmpty()) {
            "${canonicalProvider(providerPrefix)}/$override"
        } else {
            override
        }
    }

    private fun resolveToolLoopDetector(sessionKey: String): ToolLoopDetector {
        val loopConfig = config.tools?.loopDetection
        val key = sessionKey.trim().ifEmpty { "__default__" }
        return sessionToolLoopDetectors.computeIfAbsent(key) {
            ToolLoopDetector(loopConfig)
        }
    }

    private fun logHookWarning(message: String) {
        System.err.println("[AgentEngine] $message")
    }

    private fun routeModelToProvider(modelId: String): String {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return trimmed
        val slash = trimmed.indexOf('/')
        if (slash > 0) {
            val provider = canonicalProvider(trimmed.substring(0, slash))
            val model = trimmed.substring(slash + 1).trim()
            return if (model.isEmpty()) provider else "$provider/$model"
        }

        val providers = config.models?.providers.orEmpty()
        for ((providerId, providerConfig) in providers) {
            if (providerConfig.models.any { it.id.equals(trimmed, ignoreCase = true) }) {
                return "${canonicalProvider(providerId)}/$trimmed"
            }
        }
        return trimmed
    }

    private fun resolveEnabledProviders(configuredProviderIds: Set<String>): List<String> {
        val configured = configuredProviderIds.map(::canonicalProvider).toSet()
        val registered = providerRegistry.ids().map(::canonicalProvider).toSet()
        val enabled = when {
            registered.isNotEmpty() -> registered
            configured.isNotEmpty() -> configured
            else -> DEFAULT_PROVIDER_ORDER.toSet()
        }
        return enabled.sortedWith(compareBy({ providerSortKey(it) }, { it }))
    }

    private fun defaultModelForProvider(providerId: String): String? {
        return DEFAULT_MODELS_BY_PROVIDER[canonicalProvider(providerId)]
    }

    private fun suggestedModelsForProvider(providerId: String): List<String> {
        val canonical = canonicalProvider(providerId)
        val suggested = SUGGESTED_MODELS_BY_PROVIDER[canonical].orEmpty()
        return if (suggested.isNotEmpty()) suggested else listOfNotNull(defaultModelForProvider(canonical))
    }

    private fun providerSortKey(providerId: String): Int {
        val idx = DEFAULT_PROVIDER_ORDER.indexOf(providerId)
        return if (idx == -1) Int.MAX_VALUE else idx
    }

    private fun resolveProviderFromModel(modelId: String): String? {
        val routed = routeModelToProvider(modelId)
        val slash = routed.indexOf('/')
        if (slash <= 0) return null
        return canonicalProvider(routed.substring(0, slash))
    }

    private fun sharesProvider(modelA: String, modelB: String): Boolean {
        val providerA = resolveProviderFromModel(modelA) ?: return false
        val providerB = resolveProviderFromModel(modelB) ?: return false
        return providerA == providerB
    }

    private fun sameResolvedModel(modelA: String, modelB: String): Boolean {
        return routeModelToProvider(modelA).equals(
            routeModelToProvider(modelB),
            ignoreCase = true,
        )
    }

    private fun resolveModelDefinition(modelId: String?): ModelDefinitionConfig? {
        val routed = modelId?.trim()?.takeIf { it.isNotEmpty() }?.let(::routeModelToProvider) ?: return null
        val providerId = resolveProviderFromModel(routed) ?: return null
        val requestedModelId = routed.substringAfter("/", missingDelimiterValue = routed)
        val providerConfig = config.models?.providers
            ?.entries
            ?.firstOrNull { canonicalProvider(it.key) == providerId }
            ?.value
            ?: return null
        return providerConfig.models.firstOrNull { definition ->
            val routedDefinition = routeModelToProvider(
                if ('/' in definition.id) definition.id else "$providerId/${definition.id}",
            )
            routedDefinition.equals(routed, ignoreCase = true) ||
                definition.id.equals(requestedModelId, ignoreCase = true)
        }
    }

    private fun supportsReasoningEffort(modelId: String?): Boolean {
        val definitionSupport = resolveModelDefinition(modelId)
            ?.compat
            ?.supportsReasoningEffort
        if (definitionSupport != null) return definitionSupport
        val normalized = modelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::routeModelToProvider)
            ?.substringAfter("/", missingDelimiterValue = "")
            ?.lowercase()
            ?: return false
        return normalized.startsWith("gpt-5")
    }

    private fun supportedReasoningEfforts(modelId: String?): List<String> {
        if (!supportsReasoningEffort(modelId)) return emptyList()
        val normalized = modelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::routeModelToProvider)
            ?.substringAfter("/", missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()
        return when {
            normalized.startsWith("gpt-5.4-pro") -> listOf("medium", "high", "xhigh")
            normalized.startsWith("gpt-5.4") -> listOf("none", "low", "medium", "high", "xhigh")
            normalized.startsWith("gpt-5.1") -> listOf("none", "low", "medium", "high")
            normalized.startsWith("gpt-5") -> listOf("minimal", "low", "medium", "high")
            else -> listOf("low", "medium", "high")
        }
    }

    private fun normalizeReasoningEffort(modelId: String?, reasoningEffort: String?): String? {
        val normalized = reasoningEffort
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val supported = supportedReasoningEfforts(modelId)
        return normalized.takeIf { it in supported }
    }

    private fun resolveAgentConfig(agentId: String): AgentConfig? {
        return config.agents?.list?.firstOrNull { it.id == agentId }
    }

    private fun canonicalProvider(providerId: String): String {
        return when (providerId.lowercase()) {
            "google" -> "gemini"
            else -> providerId.lowercase()
        }
    }

    private fun providerEnvVars(providerId: String): List<String> {
        return when (canonicalProvider(providerId)) {
            "anthropic" -> listOf("ANTHROPIC_API_KEY")
            "openai" -> listOf("OPENAI_API_KEY")
            "gemini" -> listOf("GEMINI_API_KEY", "GOOGLE_API_KEY")
            else -> emptyList()
        }
    }

    private fun secretKeyForProvider(providerId: String): String {
        return when (canonicalProvider(providerId)) {
            "gemini" -> "api_key_gemini"
            else -> "api_key_${canonicalProvider(providerId)}"
        }
    }

    private fun secretKeysForProvider(providerId: String): List<String> {
        return when (canonicalProvider(providerId)) {
            "gemini" -> listOf("api_key_gemini", "api_key_google")
            else -> listOf("api_key_${canonicalProvider(providerId)}")
        }
    }

    private fun buildRuntimeInfo(
        agentId: String,
        modelId: String? = null,
        channelContext: SystemPromptBuilder.ChannelContext? = null,
    ): SystemPromptBuilder.RuntimeInfo {
        val agentConfig = resolveAgentConfig(agentId)
        val sandboxMode = agentConfig?.sandbox?.mode ?: config.agents?.defaults?.sandbox?.mode
        val sandboxed = isSandboxedMode(sandboxMode)
        val acpEnabled = config.acp?.enabled != false
        val elevatedEnabled = config.tools?.elevated?.enabled == true
        val elevatedAllowed = sandboxed && elevatedEnabled
        val sandboxContainerWorkspaceDir = System.getenv("OPENCLAW_SANDBOX_WORKDIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val capabilities = buildList {
            if (channelContext?.supportsReactions == true) add("reactions")
            if (channelContext?.supportsThreads == true) add("threads")
            if (channelContext?.supportsRichText == true) add("rich_text")
        }
        val preferredDefaultModel = preferredModelForAgent(agentId)
        val reasoningEffort = preferredReasoningEffortForAgent(
            agentId = agentId,
            modelId = modelId ?: preferredDefaultModel,
        )
        val reasoningState = when {
            reasoningEffort != null -> reasoningEffort
            supportsReasoningEffort(modelId ?: preferredDefaultModel) -> "default"
            else -> "off"
        }
        return SystemPromptBuilder.RuntimeInfo(
            os = "Android ${android.os.Build.VERSION.RELEASE}",
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            host = android.os.Build.MODEL,
            agentId = agentId,
            repoRoot = terminalWorkingDirectory(),
            model = modelId,
            defaultModel = preferredDefaultModel,
            node = execEnvironment.nodeVersion,
            shell = execEnvironment.shellPath,
            channel = channelContext?.channelId,
            capabilities = capabilities,
            thinking = "off",
            reasoning = reasoningState,
            timezone = java.util.TimeZone.getDefault().id,
            currentTime = java.time.Instant.now().toString(),
            sandboxed = sandboxed,
            acpEnabled = acpEnabled,
            elevatedEnabled = elevatedEnabled,
            elevatedAllowed = elevatedAllowed,
            sandboxContainerWorkspaceDir = sandboxContainerWorkspaceDir,
        )
    }

    private data class PromptContextBundle(
        val files: List<SystemPromptBuilder.ContextFile>,
        val warningLines: List<String>,
    )

    private fun buildModelAliasLines(): List<String> {
        val providers = config.models?.providers.orEmpty()
        if (providers.isEmpty()) return emptyList()
        return providers.entries
            .sortedBy { canonicalProvider(it.key) }
            .mapNotNull { (providerId, providerConfig) ->
                val alias = canonicalProvider(providerId)
                val preferredModel = providerConfig.models
                    .firstOrNull()
                    ?.id
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: defaultModelForProvider(alias)
                if (preferredModel != null) {
                    "- $alias -> $alias/$preferredModel"
                } else {
                    null
                }
            }
            .distinct()
    }

    private fun resolveDocsPath(): String? {
        val workspace = terminalWorkingDirectory()
        if (workspace.isBlank()) return null
        val docsDir = File(workspace, "docs")
        return if (docsDir.isDirectory) docsDir.absolutePath else null
    }

    private fun buildPromptContextFilesWithWarnings(): PromptContextBundle {
        val workspace = terminalWorkingDirectory()
        if (workspace.isBlank()) return PromptContextBundle(emptyList(), emptyList())
        val warningLines = mutableListOf<String>()
        val maxFileChars = 16_000
        val maxTotalChars = 64_000
        var totalChars = 0
        val files = listOf(
            "AGENTS.md",
            "SOUL.md",
            "CLAUDE.md",
            "README.md",
        )
        val contextFiles = files.mapNotNull { name ->
            val file = File(workspace, name)
            if (!file.isFile) return@mapNotNull null
            val content = runCatching { file.readText() }.getOrNull()?.trim() ?: return@mapNotNull null
            if (content.isEmpty()) return@mapNotNull null
            val perFileTrimmed = if (content.length > maxFileChars) {
                warningLines += "$name exceeded $maxFileChars chars and was truncated."
                content.take(maxFileChars)
            } else {
                content
            }
            if (totalChars >= maxTotalChars) {
                warningLines += "$name omitted because total bootstrap context exceeded $maxTotalChars chars."
                return@mapNotNull null
            }
            val remaining = maxTotalChars - totalChars
            val finalContent = if (perFileTrimmed.length > remaining) {
                warningLines += "$name partially truncated to fit total bootstrap context budget ($maxTotalChars chars)."
                perFileTrimmed.take(remaining)
            } else {
                perFileTrimmed
            }.trim()
            if (finalContent.isEmpty()) return@mapNotNull null
            totalChars += finalContent.length
            SystemPromptBuilder.ContextFile(
                path = name,
                content = finalContent,
            )
        }
        return PromptContextBundle(
            files = contextFiles,
            warningLines = warningLines.distinct(),
        )
    }

    private fun buildWorkspaceNotes(
        contextFiles: List<SystemPromptBuilder.ContextFile>,
    ): List<String> {
        val hasAgentsFile = contextFiles.any { it.path.equals("AGENTS.md", ignoreCase = true) }
        if (!hasAgentsFile) return emptyList()
        return listOf("Reminder: commit your changes in this workspace after edits.")
    }

    private fun resolveReactionGuidance(
        channelContext: SystemPromptBuilder.ChannelContext?,
    ): SystemPromptBuilder.ReactionGuidance? {
        if (channelContext?.supportsReactions != true) return null
        return SystemPromptBuilder.ReactionGuidance(
            level = "minimal",
            channel = channelContext.channelId,
        )
    }

    private fun resolveReasoningTagHint(modelId: String?): Boolean {
        val provider = modelId
            ?.let(::resolveProviderFromModel)
            ?.trim()
            ?.lowercase()
            ?: return false
        return provider in setOf("qwen", "deepseek")
    }

    private fun resolveTtsPromptHint(): String? {
        val tts = config.messages?.tts ?: return null
        if (tts.enabled == false) return null
        val provider = tts.provider?.name?.lowercase() ?: "configured provider"
        return "Text-to-speech is enabled (provider: $provider). Use voice output only when the user asks."
    }

    private fun resolveOwnerDisplayMode(): SystemPromptBuilder.OwnerDisplay {
        return when (config.commands?.ownerDisplay?.trim()?.lowercase()) {
            "hash" -> SystemPromptBuilder.OwnerDisplay.HASH
            else -> SystemPromptBuilder.OwnerDisplay.RAW
        }
    }

    private fun resolveHeartbeatPrompt(agentId: String): String? {
        val agentPrompt = resolveAgentConfig(agentId)?.heartbeat?.message?.trim()
        if (!agentPrompt.isNullOrEmpty()) return agentPrompt
        val defaultPrompt = config.agents?.defaults?.heartbeat?.message?.trim()
        return defaultPrompt?.takeIf { it.isNotEmpty() }
    }

    private fun buildMessageToolHints(
        channelContext: SystemPromptBuilder.ChannelContext?,
    ): List<String> {
        if (channelContext == null) return emptyList()
        return buildList {
            if (channelContext.supportsThreads) {
                add("Threads are supported on this channel.")
            }
            if (channelContext.supportsRichText) {
                add("Rich text formatting is supported on this channel.")
            }
        }
    }

    private fun isSandboxedMode(mode: String?): Boolean {
        val normalized = mode?.trim()?.lowercase() ?: return false
        return normalized !in setOf(
            "off",
            "none",
            "host",
            "disabled",
            "false",
        )
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initialize()
    }

    private suspend fun refreshExecEnvironment() {
        managedToolchainActivation = managedToolchainManager.prepare(config)
        execEnvironment = execEnvironmentResolver.resolve(
            config = config,
            workspaceDir = terminalWorkingDirectory(),
            managedNodePath = managedToolchainActivation.nodePath,
            managedPathPrepend = managedToolchainActivation.prependPaths,
        )
        managedToolchainStatus = managedToolchainManager.buildStatus(
            activation = managedToolchainActivation,
            execEnvironment = execEnvironment,
        )
    }

    private fun ensureConfigLoaded() {
        if (configLoaded) return
        ensureStorageStructure()
        loadConfig()
    }

    private fun ensureStorageStructure() {
        STORAGE_DIRS.forEach { relativePath ->
            context.filesDir.resolve(relativePath).mkdirs()
        }
    }

    private fun clearFilesDirChildren() {
        context.filesDir.listFiles()?.forEach { entry ->
            entry.deleteRecursively()
        }
    }

    companion object {
        private const val DEFAULT_GATEWAY_PORT = 18789
        private const val DEFAULT_GATEWAY_HOST = "127.0.0.1"
        private const val CODEX_OAUTH_TOKEN_KEY = "codex_oauth_access_token"
        private const val CODEX_OAUTH_REFRESH_TOKEN_KEY = "codex_oauth_refresh_token"
        private const val CODEX_OAUTH_ID_TOKEN_KEY = "codex_oauth_id_token"
        private const val CODEX_OAUTH_EXPIRES_AT_MS_KEY = "codex_oauth_expires_at_ms"
        private const val CODEX_OAUTH_API_KEY_KEY = "codex_oauth_openai_api_key"
        private const val CODEX_ACCOUNT_ID_KEY = "codex_account_id"
        private const val CODEX_EMAIL_KEY = "codex_email"
        private const val CODEX_EXPIRY_MARGIN_MS = 60_000L
        private val DEFAULT_PROVIDER_ORDER = listOf(
            "anthropic",
            "openai",
            "gemini",
            "ollama",
        )
        private val DEFAULT_MODELS_BY_PROVIDER = mapOf(
            "anthropic" to "anthropic/claude-sonnet-4-5-20250514",
            "openai" to "openai/gpt-4o-mini",
            "gemini" to "gemini/gemini-2.0-flash",
            "ollama" to "ollama/llama3",
        )
        private val SUGGESTED_MODELS_BY_PROVIDER = mapOf(
            "anthropic" to listOf("anthropic/claude-sonnet-4-5-20250514"),
            "openai" to listOf("openai/gpt-5.4", "openai/gpt-4o-mini"),
            "gemini" to listOf("gemini/gemini-2.0-flash"),
            "ollama" to listOf("ollama/llama3"),
        )

        private val DEFAULT_MODEL_CANDIDATES = listOf(
            "anthropic/claude-sonnet-4-5-20250514",
            "openai/gpt-5.4",
            "openai/gpt-4o-mini",
            "gemini/gemini-2.0-flash",
            "ollama/llama3",
        )
        private val STORAGE_DIRS = listOf(
            "config",
            "sessions",
            "cron",
            "toolchains",
            "camera_output",
            "screen_captures",
            "tts_output",
        )
    }
}
