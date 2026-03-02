package ai.openclaw.android

import android.content.Context
import android.net.Uri
import ai.openclaw.android.auth.CodexOauthManager
import ai.openclaw.android.security.SharedPreferencesSecretStore
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.AgentConfig
import ai.openclaw.core.model.AuthProfileConfig
import ai.openclaw.core.model.ModelProviderAuthMode
import ai.openclaw.core.model.ModelProviderConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.model.SecretInput
import ai.openclaw.core.model.DEFAULT_AGENT_ID
import ai.openclaw.core.plugins.PluginRegistry
import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.AuditLog
import ai.openclaw.core.security.ConfigBasedApprovalPolicy
import ai.openclaw.core.security.SecretCategory
import ai.openclaw.core.security.SecretStore
import ai.openclaw.core.security.ToolAuditor
import ai.openclaw.core.security.ToolPolicyEnforcer
import ai.openclaw.runtime.cron.CronPayload
import ai.openclaw.runtime.cron.CronScheduler
import ai.openclaw.runtime.cron.CronStore
import ai.openclaw.runtime.cron.CronTool
import ai.openclaw.runtime.devices.DeviceToolsConfig
import ai.openclaw.runtime.devices.DeviceToolsModule
import ai.openclaw.runtime.engine.AgentRunner
import ai.openclaw.runtime.engine.SessionPersistence
import ai.openclaw.runtime.engine.SkillDefinition
import ai.openclaw.runtime.engine.SkillExecutor
import ai.openclaw.runtime.engine.SkillsTool
import ai.openclaw.runtime.engine.ToolRegistry
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

/**
 * Central engine wiring all OpenClaw subsystems together.
 */
class AgentEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()

    private var initialized = false
    private var pluginsStarted = false
    private var cronStarted = false

    private var gatewayHost: String = DEFAULT_GATEWAY_HOST
    private var gatewayPort: Int = DEFAULT_GATEWAY_PORT
    private var gatewayServerInternal: GatewayServer? = null
    private val codexOauthManager by lazy { CodexOauthManager(context.applicationContext) }
    private val _codexOauthEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val providerRegistry = ProviderRegistry()
    val pluginRegistry = PluginRegistry()
    val toolRegistry = ToolRegistry()
    val codexOauthEvents: SharedFlow<String> = _codexOauthEvents.asSharedFlow()

    var config: OpenClawConfig = OpenClawConfig()
        private set

    val configManager: ConfigManager by lazy { ConfigManager(context) }

    val sessionPersistence: SessionPersistence by lazy {
        SessionPersistence(context.filesDir.resolve("sessions").absolutePath)
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
                override fun requiresApproval(toolName: String, agentId: String, sessionKey: String): Boolean {
                    return ConfigBasedApprovalPolicy(config.approvals)
                        .requiresApproval(toolName, agentId, sessionKey)
                }
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
        return config
    }

    /**
     * Initialize the engine and background subsystems.
     */
    suspend fun initialize() {
        initMutex.withLock {
            if (initialized) return
            loadConfig()
            applyRuntimeConfiguration(notifyPluginReload = false, startGateway = true)
            initialized = true
        }
    }

    /**
     * Re-read config and notify all subsystems.
     */
    fun reloadConfig() {
        scope.launch {
            initMutex.withLock {
                loadConfig()
                applyRuntimeConfiguration(notifyPluginReload = pluginsStarted, startGateway = true)
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
        scope.launch {
            initMutex.withLock {
                applyRuntimeConfiguration(notifyPluginReload = pluginsStarted, startGateway = true)
                initialized = true
            }
        }
    }

    data class CodexOauthStatus(
        val tokenSet: Boolean,
        val accountId: String?,
        val email: String?,
        val expiresAtMs: Long?,
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
                expiresAtMs = session.expiresAtMs,
                email = session.email,
            )
            _codexOauthEvents.tryEmit("Codex OAuth connected.")
            true
        }.getOrElse { error ->
            _codexOauthEvents.tryEmit(error.message ?: "Codex OAuth failed.")
            true
        }
    }

    suspend fun setApiKey(providerId: String, apiKey: String) {
        val canonical = canonicalProvider(providerId)
        secretStore.storeSecret(secretKeyForProvider(canonical), apiKey, SecretCategory.LLM_API_KEY)
        if (canonical == "openai") {
            secretStore.deleteSecret(CODEX_OAUTH_TOKEN_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
            secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
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
        configManager.save(updatedConfig)
        config = updatedConfig

        initMutex.withLock {
            registerProviders()
            ensureGatewayServer(startGateway = true)
            initialized = true
        }
    }

    suspend fun setCodexOauth(
        accessToken: String,
        accountId: String?,
        refreshToken: String? = null,
        expiresAtMs: Long? = null,
        email: String? = null,
    ) {
        val token = accessToken.trim()
        require(token.isNotBlank()) { "Codex OAuth access token cannot be blank" }

        secretStore.storeSecret(CODEX_OAUTH_TOKEN_KEY, token, SecretCategory.LLM_API_KEY)
        if (!refreshToken.isNullOrBlank()) {
            secretStore.storeSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY, refreshToken.trim(), SecretCategory.LLM_API_KEY)
        }
        if (expiresAtMs != null && expiresAtMs > 0L) {
            secretStore.storeSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY, expiresAtMs.toString(), SecretCategory.LLM_API_KEY)
        } else {
            secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
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
        configManager.save(updatedConfig)
        config = updatedConfig

        initMutex.withLock {
            registerProviders()
            ensureGatewayServer(startGateway = true)
            initialized = true
        }
    }

    suspend fun clearCodexOauth() {
        secretStore.deleteSecret(CODEX_OAUTH_TOKEN_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
        secretStore.deleteSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)
        secretStore.deleteSecret(CODEX_ACCOUNT_ID_KEY)
        secretStore.deleteSecret(CODEX_EMAIL_KEY)

        val updatedProfiles = (config.auth?.profiles.orEmpty())
            .filterValues { !(canonicalProvider(it.provider) == "openai" && it.mode.lowercase() == "oauth") }
        val updatedAuth = if (updatedProfiles.isEmpty()) null else {
            (config.auth ?: ai.openclaw.core.model.AuthConfig()).copy(profiles = updatedProfiles)
        }
        val updatedConfig = config.copy(auth = updatedAuth)

        configManager.save(updatedConfig)
        config = updatedConfig

        initMutex.withLock {
            registerProviders()
            ensureGatewayServer(startGateway = true)
            initialized = true
        }
    }

    suspend fun getCodexOauthStatus(): CodexOauthStatus {
        val expiresAtMs = secretStore.getSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)?.toLongOrNull()
        return CodexOauthStatus(
            tokenSet = secretStore.hasSecret(CODEX_OAUTH_TOKEN_KEY),
            accountId = secretStore.getSecret(CODEX_ACCOUNT_ID_KEY),
            email = secretStore.getSecret(CODEX_EMAIL_KEY),
            expiresAtMs = expiresAtMs,
        )
    }

    suspend fun clearApiKey(providerId: String) {
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

        configManager.save(updatedConfig)
        config = updatedConfig

        initMutex.withLock {
            registerProviders()
            ensureGatewayServer(startGateway = true)
            initialized = true
        }
    }

    fun defaultAgentId(): String {
        return config.agents?.list
            ?.firstOrNull { it.default == true }
            ?.id
            ?: config.agents?.list?.firstOrNull()?.id
            ?: DEFAULT_AGENT_ID
    }

    fun preferredModelForAgent(agentId: String = defaultAgentId()): String? {
        val agent = resolveAgentConfig(agentId)
        return agent?.model?.primary
            ?: config.agents?.defaults?.model?.primary
            ?: resolveConfiguredDefaultModel()
    }

    fun currentToolNames(): List<String> = toolRegistry.names().sorted()

    fun terminalWorkingDirectory(): String = context.filesDir.absolutePath

    suspend fun authorizeToolInvocation(
        toolName: String,
        input: String,
        agentId: String = defaultAgentId(),
        sessionKey: String = "local:terminal",
    ): String? {
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
        model: String? = null,
        systemPrompt: String? = null,
        agentId: String? = null,
        sessionKey: String = "",
    ): Flow<AcpRuntimeEvent> = channelFlow {
        val resolvedAgentId = agentId ?: defaultAgentId()
        val modelChain = resolveModelChain(
            agentId = resolvedAgentId,
            requestedModel = model,
        )

        val messages = conversationHistory + LlmMessage(
            role = LlmMessage.Role.USER,
            content = userMessage,
        )

        val effectiveSystemPrompt = systemPrompt
            ?: resolveSystemPrompt(resolvedAgentId)

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
                approvalManager = approvalManager,
                toolPolicyEnforcer = ToolPolicyEnforcer(config, toolAuditor),
            )

            var retryableFailure: AcpRuntimeEvent.Error? = null
            var completed = false

            runner.runTurn(
                messages = messages,
                model = routedModel,
                systemPrompt = effectiveSystemPrompt,
                sessionKey = sessionKey,
                agentId = resolvedAgentId,
                agentIdentity = resolveAgentConfig(resolvedAgentId)?.identity,
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
        cronScheduler.stop()
        channelManager.stopAll()
        pluginRegistry.stopAll()
        gatewayServerInternal?.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun applyRuntimeConfiguration(
        notifyPluginReload: Boolean,
        startGateway: Boolean,
    ) {
        registerProviders()
        registerTools()

        if (!pluginsStarted) {
            runCatching { pluginRegistry.startAll() }
            pluginsStarted = true
        } else if (notifyPluginReload) {
            runCatching { pluginRegistry.notifyConfigReload() }
        }

        ensureGatewayServer(startGateway = startGateway)

        if (!cronStarted) {
            runCatching { cronScheduler.start() }
            cronStarted = true
        }
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
                    ),
                )
            }

            "ollama" -> {
                providerRegistry.register(
                    OllamaProvider(
                        baseUrl = providerConfig?.baseUrl ?: "http://localhost:11434",
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
            resolveCodexOauthAccessToken()?.let { return it }
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

    private suspend fun resolveCodexOauthAccessToken(): String? {
        val currentToken = secretStore.getSecret(CODEX_OAUTH_TOKEN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val expiresAtMs = secretStore.getSecret(CODEX_OAUTH_EXPIRES_AT_MS_KEY)?.toLongOrNull()
        val now = System.currentTimeMillis()
        if (expiresAtMs == null || expiresAtMs > now + CODEX_EXPIRY_MARGIN_MS) {
            return currentToken
        }

        val refreshToken = secretStore.getSecret(CODEX_OAUTH_REFRESH_TOKEN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return currentToken

        return runCatching {
            val refreshed = codexOauthManager.refreshFromRefreshToken(refreshToken)
            secretStore.storeSecret(CODEX_OAUTH_TOKEN_KEY, refreshed.accessToken, SecretCategory.LLM_API_KEY)
            secretStore.storeSecret(
                CODEX_OAUTH_REFRESH_TOKEN_KEY,
                (refreshed.refreshToken ?: refreshToken),
                SecretCategory.LLM_API_KEY,
            )
            if (refreshed.expiresAtMs != null && refreshed.expiresAtMs > 0L) {
                secretStore.storeSecret(
                    CODEX_OAUTH_EXPIRES_AT_MS_KEY,
                    refreshed.expiresAtMs.toString(),
                    SecretCategory.LLM_API_KEY,
                )
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
            refreshed.accessToken
        }.getOrElse {
            currentToken
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
        val headers = providerConfig?.headers
            .orEmpty()
            .filterValues { it.isNotBlank() }
            .toMutableMap()

        secretStore.getSecret(CODEX_ACCOUNT_ID_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { accountId ->
                headers.putIfAbsent("ChatGPT-Account-ID", accountId)
            }

        return headers
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
                availableSkills = { emptyList<SkillDefinition>() },
            ),
        )
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
            sendMessage(
                userMessage = params.text,
                model = params.model,
                agentId = params.agentId ?: defaultAgentId(),
                sessionKey = params.sessionKey,
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

    private fun resolveSystemPrompt(agentId: String): String {
        val agentIdentityName = resolveAgentConfig(agentId)?.identity?.name
        return when {
            !agentIdentityName.isNullOrBlank() -> "You are $agentIdentityName."
            else -> "You are a helpful assistant."
        }
    }

    private fun resolveModelChain(
        agentId: String,
        requestedModel: String?,
    ): List<String> {
        val agentConfig = resolveAgentConfig(agentId)
        val defaults = config.agents?.defaults?.model

        val chain = mutableListOf<String>()

        if (!requestedModel.isNullOrBlank()) {
            chain += requestedModel
        } else {
            agentConfig?.model?.primary?.let(chain::add)
            defaults?.primary?.let(chain::add)
            resolveConfiguredDefaultModel()?.let(chain::add)
            chain += DEFAULT_MODEL_CANDIDATES
        }

        chain += agentConfig?.model?.fallbacks.orEmpty()
        chain += defaults?.fallbacks.orEmpty()

        return chain
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun resolveConfiguredDefaultModel(): String? {
        val providers = config.models?.providers.orEmpty()
        for ((providerId, providerConfig) in providers) {
            val firstModel = providerConfig.models.firstOrNull()?.id ?: continue
            val canonical = canonicalProvider(providerId)
            return if ('/' in firstModel) firstModel else "$canonical/$firstModel"
        }
        return null
    }

    private fun routeModelToProvider(modelId: String): String {
        if ('/' in modelId) return modelId
        val providers = config.models?.providers.orEmpty()
        for ((providerId, providerConfig) in providers) {
            if (providerConfig.models.any { it.id == modelId }) {
                return "${canonicalProvider(providerId)}/$modelId"
            }
        }
        return modelId
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

    companion object {
        private const val DEFAULT_GATEWAY_PORT = 18789
        private const val DEFAULT_GATEWAY_HOST = "127.0.0.1"
        private const val CODEX_OAUTH_TOKEN_KEY = "codex_oauth_access_token"
        private const val CODEX_OAUTH_REFRESH_TOKEN_KEY = "codex_oauth_refresh_token"
        private const val CODEX_OAUTH_EXPIRES_AT_MS_KEY = "codex_oauth_expires_at_ms"
        private const val CODEX_ACCOUNT_ID_KEY = "codex_account_id"
        private const val CODEX_EMAIL_KEY = "codex_email"
        private const val CODEX_EXPIRY_MARGIN_MS = 60_000L

        private val DEFAULT_MODEL_CANDIDATES = listOf(
            "anthropic/claude-sonnet-4-5-20250514",
            "openai/gpt-4o-mini",
            "gemini/gemini-2.0-flash",
            "ollama/llama3",
        )
    }
}
