package ai.openclaw.runtime.engine

import ai.openclaw.core.acp.AcpRuntime
import ai.openclaw.core.agent.*
import ai.openclaw.core.model.*
import ai.openclaw.core.plugins.AfterToolCallEvent
import ai.openclaw.core.plugins.AgentEndEvent
import ai.openclaw.core.plugins.BeforeAgentStartEvent
import ai.openclaw.core.plugins.BeforeAgentStartResult
import ai.openclaw.core.plugins.BeforePromptBuildEvent
import ai.openclaw.core.plugins.BeforeToolCallEvent
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.LlmInputEvent
import ai.openclaw.core.plugins.LlmOutputEvent
import ai.openclaw.core.plugins.LlmUsage
import ai.openclaw.core.plugins.PluginHookAgentContext
import ai.openclaw.core.plugins.PluginHookToolContext
import ai.openclaw.core.plugins.ToolResultPersistEvent
import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.ToolPolicyEnforcer
import ai.openclaw.core.security.ToolPolicyContext
import ai.openclaw.core.session.isSubagentSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Tool registry for managing available tools.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()
    private val schemaJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        explicitNulls = false
    }

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    fun clear() {
        tools.clear()
    }

    fun toDefinitions(allowedToolNames: Set<String>? = null): List<LlmToolDefinition> = filteredTools(allowedToolNames).map {
        LlmToolDefinition(
            name = it.name,
            description = it.description,
            parameters = normalizeToolParametersSchema(it.parametersSchema),
        )
    }

    fun toSummaries(allowedToolNames: Set<String>? = null): List<SystemPromptBuilder.ToolSummary> = filteredTools(allowedToolNames).map {
        SystemPromptBuilder.ToolSummary(name = it.name, description = it.description)
    }

    private fun filteredTools(allowedToolNames: Set<String>?): List<AgentTool> {
        if (allowedToolNames.isNullOrEmpty()) return tools.values.toList()
        val allowed = allowedToolNames.map { it.trim().lowercase() }.toSet()
        return tools.values.filter { tool -> tool.name.trim().lowercase() in allowed }
    }

    private fun normalizeToolParametersSchema(schema: String): String {
        val parsed = runCatching { schemaJson.parseToJsonElement(schema) }.getOrNull() as? JsonObject
            ?: return schema

        val normalized = when {
            parsed["type"] == null &&
                (parsed["properties"] is JsonObject || parsed["required"] is JsonArray) &&
                parsed["anyOf"] !is JsonArray &&
                parsed["oneOf"] !is JsonArray -> JsonObject(parsed + ("type" to JsonPrimitive("object")))

            parsed["anyOf"] is JsonArray || parsed["oneOf"] is JsonArray -> flattenUnionSchema(parsed)

            else -> parsed
        }

        return runCatching {
            schemaJson.encodeToString(JsonObject.serializer(), normalized)
        }.getOrElse { schema }
    }

    private fun flattenUnionSchema(schema: JsonObject): JsonObject {
        val variants = when {
            schema["anyOf"] is JsonArray -> schema["anyOf"]!!.jsonArray
            schema["oneOf"] is JsonArray -> schema["oneOf"]!!.jsonArray
            else -> return schema
        }

        val mergedProperties = mutableMapOf<String, JsonElement>()
        val requiredCounts = mutableMapOf<String, Int>()
        var objectVariants = 0

        for (variant in variants) {
            val variantObj = variant as? JsonObject ?: continue
            val properties = variantObj["properties"] as? JsonObject ?: continue
            objectVariants += 1
            for ((key, value) in properties) {
                val existing = mergedProperties[key]
                mergedProperties[key] = if (existing == null) {
                    value
                } else {
                    mergePropertySchemas(existing, value)
                }
            }

            val required = variantObj["required"] as? JsonArray ?: JsonArray(emptyList())
            required.forEach { requiredEntry ->
                val name = requiredEntry.jsonPrimitive.contentOrNull ?: return@forEach
                requiredCounts[name] = (requiredCounts[name] ?: 0) + 1
            }
        }

        val baseRequired = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
        val mergedRequired = baseRequired ?: if (objectVariants > 0) {
            requiredCounts.entries
                .filter { (_, count) -> count == objectVariants }
                .map { (key, _) -> key }
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val flattened = buildJsonObject {
            put("type", JsonPrimitive("object"))
            schema["title"]?.let { put("title", it) }
            schema["description"]?.let { put("description", it) }
            putJsonObject("properties") {
                val source = if (mergedProperties.isNotEmpty()) {
                    JsonObject(mergedProperties)
                } else {
                    schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                }
                for ((key, value) in source) {
                    put(key, value)
                }
            }
            if (!mergedRequired.isNullOrEmpty()) {
                putJsonArray("required") {
                    mergedRequired.forEach { add(JsonPrimitive(it)) }
                }
            }
            put(
                "additionalProperties",
                schema["additionalProperties"] ?: JsonPrimitive(true),
            )
        }

        return flattened
    }

    private fun mergePropertySchemas(existing: JsonElement, incoming: JsonElement): JsonElement {
        val existingEnums = extractEnumValues(existing)
        val incomingEnums = extractEnumValues(incoming)
        if (existingEnums.isEmpty() && incomingEnums.isEmpty()) {
            return existing
        }

        val uniqueValues = linkedMapOf<String, JsonElement>()
        (existingEnums + incomingEnums).forEach { enumValue ->
            val key = enumValue.toString()
            if (key !in uniqueValues) {
                uniqueValues[key] = enumValue
            }
        }

        val existingObj = existing as? JsonObject
        val incomingObj = incoming as? JsonObject
        val title = existingObj?.get("title") ?: incomingObj?.get("title")
        val description = existingObj?.get("description") ?: incomingObj?.get("description")
        val defaultValue = existingObj?.get("default") ?: incomingObj?.get("default")
        val merged = buildJsonObject {
            if (title != null) put("title", title)
            if (description != null) put("description", description)
            if (defaultValue != null) put("default", defaultValue)

            val enumType = inferEnumType(uniqueValues.values.toList())
            if (enumType != null) {
                put("type", JsonPrimitive(enumType))
            }
            putJsonArray("enum") {
                uniqueValues.values.forEach { add(it) }
            }
        }

        return merged
    }

    private fun extractEnumValues(schema: JsonElement): List<JsonElement> {
        val obj = schema as? JsonObject ?: return emptyList()

        val directEnum = obj["enum"] as? JsonArray
        if (directEnum != null) {
            return directEnum.toList()
        }

        obj["const"]?.let { return listOf(it) }

        val variants = (obj["anyOf"] as? JsonArray) ?: (obj["oneOf"] as? JsonArray) ?: return emptyList()
        return variants.flatMap { extractEnumValues(it) }
    }

    private fun inferEnumType(values: List<JsonElement>): String? {
        if (values.isEmpty()) return null
        val types = values.mapNotNull { value ->
            when (value) {
                is JsonPrimitive -> when {
                    value.isString -> "string"
                    value.booleanOrNull != null -> "boolean"
                    value.longOrNull != null || value.doubleOrNull != null -> "number"
                    else -> null
                }
                else -> null
            }
        }.toSet()
        return if (types.size == 1) types.first() else null
    }
}

/**
 * The embedded agent runner - the core LLM orchestration loop.
 * Receives messages, builds prompts, calls LLM, executes tools, loops until done.
 *
 * Ported from src/agents/pi-embedded-runner/
 */
class AgentRunner(
    private val provider: LlmProvider,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val maxToolRounds: Int = 160,
    private val maxRunAttempts: Int = 32,
    private val contextGuard: ContextGuard = ContextGuard(),
    private val sessionPersistence: SessionPersistence? = null,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val approvalManager: ApprovalManager? = null,
    private val toolPolicyEnforcer: ToolPolicyEnforcer? = null,
    private val toolLoopDetector: ToolLoopDetector? = null,
    private val hookRunner: HookRunner? = null,
    private val transcriptRepairPolicy: TranscriptRepairPolicy = TranscriptRepairPolicy(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val hookDispatchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    private val transcriptRepair = SessionTranscriptRepair()

    /**
     * Structured entrypoint mirroring the reference runner architecture.
     */
    fun runTurn(params: EmbeddedRunParams): Flow<AcpRuntimeEvent> {
        return runTurn(
            messages = params.messages,
            model = params.model,
            systemPrompt = params.systemPrompt,
            runId = params.runId,
            sessionKey = params.sessionKey,
            agentId = params.agentId,
            agentIdentity = params.agentIdentity,
            channelContext = params.channelContext,
            skills = params.skills,
            runtimeInfo = params.runtimeInfo,
            workspaceDir = params.workspaceDir,
            maxHistoryTurns = params.maxHistoryTurns,
            timeoutMs = params.timeoutMs,
            maxRunAttempts = params.maxRunAttempts,
            hookSessionId = params.hookSessionId,
            legacyBeforeAgentStartResult = params.legacyBeforeAgentStartResult,
            modelAliasLines = params.modelAliasLines,
            workspaceNotes = params.workspaceNotes,
            docsPath = params.docsPath,
            ownerNumbers = params.ownerNumbers,
            ownerDisplay = params.ownerDisplay,
            ownerDisplaySecret = params.ownerDisplaySecret,
            reasoningTagHint = params.reasoningTagHint,
            reasoningEffort = params.reasoningEffort,
            extraSystemPrompt = params.extraSystemPrompt,
            contextFiles = params.contextFiles,
            bootstrapTruncationWarningLines = params.bootstrapTruncationWarningLines,
            memoryCitationsMode = params.memoryCitationsMode,
            ttsHint = params.ttsHint,
            reactionGuidance = params.reactionGuidance,
            messageToolHints = params.messageToolHints,
            heartbeatPrompt = params.heartbeatPrompt,
            trigger = params.turnContext.trigger,
            messageProvider = params.turnContext.messageProvider,
            hookChannelId = params.turnContext.channelId,
            senderIsOwner = params.turnContext.senderIsOwner,
            turnSandboxed = params.turnContext.sandboxed,
            groupToolPolicy = params.turnContext.groupToolPolicy,
            clientTools = params.clientTools,
        )
    }

    /**
     * Run a single turn: message -> LLM -> tool calls -> loop -> done.
     * Returns a Flow of AcpRuntimeEvents for streaming.
     *
     * Tool calls from a single LLM response are accumulated into one assistant message,
     * then executed sequentially with all results appended as individual tool messages.
     */
    fun runTurn(
        messages: List<LlmMessage>,
        model: String,
        systemPrompt: String? = null,
        runId: String? = null,
        sessionKey: String = "",
        agentId: String = DEFAULT_AGENT_ID,
        agentIdentity: IdentityConfig? = null,
        channelContext: SystemPromptBuilder.ChannelContext? = null,
        skills: List<SystemPromptBuilder.SkillSummary> = emptyList(),
        runtimeInfo: SystemPromptBuilder.RuntimeInfo? = null,
        workspaceDir: String? = null,
        maxHistoryTurns: Int? = null,
        timeoutMs: Long? = null,
        maxRunAttempts: Int? = null,
        hookSessionId: String? = null,
        legacyBeforeAgentStartResult: BeforeAgentStartResult? = null,
        modelAliasLines: List<String> = emptyList(),
        workspaceNotes: List<String> = emptyList(),
        docsPath: String? = null,
        ownerNumbers: List<String> = emptyList(),
        ownerDisplay: SystemPromptBuilder.OwnerDisplay = SystemPromptBuilder.OwnerDisplay.RAW,
        ownerDisplaySecret: String? = null,
        reasoningTagHint: Boolean = false,
        reasoningEffort: String? = null,
        extraSystemPrompt: String? = null,
        contextFiles: List<SystemPromptBuilder.ContextFile> = emptyList(),
        bootstrapTruncationWarningLines: List<String> = emptyList(),
        memoryCitationsMode: MemoryCitationsMode? = null,
        ttsHint: String? = null,
        reactionGuidance: SystemPromptBuilder.ReactionGuidance? = null,
        messageToolHints: List<String> = emptyList(),
        heartbeatPrompt: String? = null,
        trigger: String? = null,
        messageProvider: String? = null,
        hookChannelId: String? = null,
        senderIsOwner: Boolean? = null,
        turnSandboxed: Boolean? = null,
        groupToolPolicy: GroupToolPolicyConfig? = null,
        clientTools: List<ClientToolDefinition> = emptyList(),
    ): Flow<AcpRuntimeEvent> = flow {
        val effectiveRunId = runId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val effectiveHookSessionId = hookSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        val effectiveTrigger = trigger?.trim()?.takeIf { it.isNotEmpty() } ?: "user"
        val effectiveMessageProvider = messageProvider?.trim()?.takeIf { it.isNotEmpty() }
            ?: channelContext?.channelId
        val effectiveHookChannelId = hookChannelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: channelContext?.channelId
        val turnStartedAt = System.currentTimeMillis()
        val persistence = sessionPersistence?.takeIf { sessionKey.isNotBlank() }
        var currentMessages = messages.toMutableList()
        var terminalError: String? = null
        var completedSuccessfully = false
        val llmAssistantTexts = mutableListOf<String>()
        var llmLastAssistantPayload: Map<String, Any?>? = null
        var usageInputTotal = 0
        var usageOutputTotal = 0
        var usageCacheReadTotal = 0
            var usageCacheWriteTotal = 0
            var usageSeenAny = false
            var finalModelForHooks = model
            val startedToolCallIds = mutableSetOf<String>()

        try {
            // Load persisted history if available.
            currentMessages = if (persistence != null && persistence.exists(sessionKey)) {
                val loadWarnings = mutableListOf<String>()
                val loadResult = persistence.loadWithReport(sessionKey) { warning ->
                    loadWarnings += warning
                }
                loadWarnings.forEach { warning ->
                    emit(AcpRuntimeEvent.Status(text = warning, tag = "session_repair"))
                }
                val history = loadResult.messages
                val combined = history.toMutableList()
                for (msg in messages) {
                    val isDuplicate = combined.any { existing ->
                        existing.role == msg.role &&
                            existing.plainTextContent() == msg.plainTextContent() &&
                            existing.toolCallId == msg.toolCallId &&
                            existing.toolCalls?.map { it.id } == msg.toolCalls?.map { it.id }
                    }
                    if (!isDuplicate) {
                        combined.add(msg)
                    }
                }
                combined
            } else {
                messages.toMutableList()
            }

            var effectiveModel = model
            finalModelForHooks = effectiveModel
            var policyContext = buildToolPolicyContext(
                agentId = agentId,
                sessionKey = sessionKey,
                modelProvider = provider.id,
                modelId = effectiveModel,
                messageProvider = effectiveMessageProvider,
                senderIsOwner = senderIsOwner,
                runtimeInfo = runtimeInfo,
                turnSandboxed = turnSandboxed,
                groupToolPolicy = groupToolPolicy,
            )
            var allowedToolNames = resolveAllowedToolNames(policyContext)
            val clientToolNames = clientTools.map { normalizeToolName(it.name) }.toSet()
            val mergedExtraSystemPrompt = listOfNotNull(
                extraSystemPrompt?.trim()?.takeIf { it.isNotEmpty() },
                subagentPromptContract(sessionKey)?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString("\n\n").ifBlank { null }
            var effectiveSystemPrompt = systemPrompt ?: systemPromptBuilder.buildEmbedded(
                SystemPromptBuilder.EmbeddedPromptConfig(
                    agentIdentity = agentIdentity,
                    tools = toolRegistry.toSummaries(allowedToolNames),
                    skills = skills,
                    runtimeInfo = runtimeInfo,
                    promptMode = resolvePromptModeForSession(sessionKey),
                    channelContext = channelContext,
                    workspaceDir = workspaceDir ?: ".",
                    modelId = model,
                    provider = provider.id,
                    modelAliasLines = modelAliasLines,
                    workspaceNotes = workspaceNotes,
                    extraSystemPrompt = mergedExtraSystemPrompt,
                    docsPath = docsPath,
                    ownerNumbers = ownerNumbers,
                    ownerDisplay = ownerDisplay,
                    ownerDisplaySecret = ownerDisplaySecret,
                    reasoningTagHint = reasoningTagHint,
                    contextFiles = contextFiles,
                    bootstrapTruncationWarningLines = bootstrapTruncationWarningLines,
                    memoryCitationsMode = memoryCitationsMode,
                    ttsHint = ttsHint,
                    reactionGuidance = reactionGuidance,
                    messageToolHints = messageToolHints,
                    heartbeatPrompt = heartbeatPrompt,
                ),
            )

            val latestUserPrompt = messages.lastOrNull { it.role == LlmMessage.Role.USER }?.plainTextContent() ?: ""
            val hookResult = resolvePromptBuildHookResult(
                prompt = latestUserPrompt,
                messages = currentMessages,
                agentId = agentId,
                sessionKey = sessionKey,
                sessionId = effectiveHookSessionId,
                workspaceDir = workspaceDir,
                messageProvider = effectiveMessageProvider,
                channelId = effectiveHookChannelId,
                legacyBeforeAgentStartResult = legacyBeforeAgentStartResult,
                trigger = effectiveTrigger,
            )
            if (!hookResult.prependContext.isNullOrBlank()) {
                val userIndex = currentMessages.indexOfLast { it.role == LlmMessage.Role.USER }
                if (userIndex >= 0) {
                    val msg = currentMessages[userIndex]
                    val mergedContent = "${hookResult.prependContext}\n\n${msg.plainTextContent()}"
                    currentMessages[userIndex] = msg.copy(
                        content = mergedContent,
                        contentBlocks = listOf(LlmContentBlock.Text(mergedContent)),
                    )
                }
            }
            if (!hookResult.systemPrompt.isNullOrBlank()) {
                effectiveSystemPrompt = hookResult.systemPrompt.trim()
            }

            currentMessages = contextGuard.limitHistoryTurns(currentMessages, maxHistoryTurns).toMutableList()
            currentMessages = contextGuard.trimToFit(currentMessages).toMutableList()
            currentMessages = applyTranscriptRepairs(
                messages = currentMessages,
                allowedToolNames = allowedToolNames + clientToolNames,
            ).toMutableList()

            val latestUserMessage = currentMessages.lastOrNull { it.role == LlmMessage.Role.USER }
            val historyMessagesForHook = if (latestUserMessage != null) {
                val lastUserIndex = currentMessages.indexOfLast { it.role == LlmMessage.Role.USER }
                if (lastUserIndex > 0) currentMessages.subList(0, lastUserIndex) else emptyList()
            } else {
                currentMessages
            }
            val promptImageCount = latestUserMessage
                ?.normalizedContentBlocks()
                ?.count { it is LlmContentBlock.ImageUrl }
                ?: 0

            hookDispatchScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    hookRunner?.runLlmInput(
                        event = LlmInputEvent(
                            runId = effectiveRunId,
                            sessionId = effectiveHookSessionId,
                            provider = provider.id,
                            model = effectiveModel,
                            systemPrompt = effectiveSystemPrompt,
                            prompt = latestUserMessage?.plainTextContent().orEmpty(),
                            historyMessages = historyMessagesForHook.map { it.toHookMessagePayload() },
                            imagesCount = promptImageCount,
                        ),
                        ctx = PluginHookAgentContext(
                            agentId = agentId,
                            sessionKey = sessionKey.takeIf { it.isNotBlank() },
                            sessionId = effectiveHookSessionId,
                            workspaceDir = workspaceDir,
                            messageProvider = effectiveMessageProvider,
                            trigger = effectiveTrigger,
                            channelId = effectiveHookChannelId,
                        ),
                    )
                }.onFailure { err ->
                    logHookWarning("llm_input hook failed: ${err.message}")
                }
            }

            val effectiveMaxRunAttempts = (maxRunAttempts ?: this@AgentRunner.maxRunAttempts).coerceAtLeast(1)
            val turnTimeoutMs = timeoutMs?.takeIf { it > 0L }
            val maxOverflowCompactionAttempts = 3
            var overflowCompactionAttempts = 0
            var toolResultTruncationAttempted = false
            var runAttempt = 0
            var lastRetryableError: LlmStreamEvent.Error? = null
            var aborted = false
            emit(AcpRuntimeEvent.Status(text = "Starting turn", tag = "turn_start"))

            attemptLoop@ while (runAttempt < effectiveMaxRunAttempts && !aborted) {
                runAttempt += 1
                val attemptStartedAt = System.currentTimeMillis()
                var round = 0
                if (runAttempt > 1) {
                    emit(
                        AcpRuntimeEvent.Status(
                            text = "Retrying turn attempt $runAttempt/$effectiveMaxRunAttempts",
                            tag = "turn_retry",
                        ),
                    )
                }

                while (round < maxToolRounds && !aborted) {
                    round++
                    finalModelForHooks = effectiveModel
                    policyContext = policyContext.copy(modelId = effectiveModel)
                    allowedToolNames = resolveAllowedToolNames(policyContext)
                    val remainingAttemptTimeoutMs = turnTimeoutMs?.let { timeout ->
                        (timeout - (System.currentTimeMillis() - attemptStartedAt)).coerceAtLeast(0L)
                    }
                    if (remainingAttemptTimeoutMs != null && remainingAttemptTimeoutMs <= 0L) {
                        val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                        val timeoutError = LlmStreamEvent.Error(
                            message = timeoutMessage,
                            code = "timeout",
                            retryable = runAttempt < effectiveMaxRunAttempts,
                        )
                        if (runAttempt < effectiveMaxRunAttempts) {
                            lastRetryableError = timeoutError
                            emit(
                                AcpRuntimeEvent.Status(
                                    text = "$timeoutMessage; retrying",
                                    tag = "turn_timeout_retry",
                                ),
                            )
                            continue@attemptLoop
                        }
                        val retryLimitMessage =
                            "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                        emit(
                            AcpRuntimeEvent.Error(
                                message = retryLimitMessage,
                                code = "retry_limit",
                                retryable = true,
                            ),
                        )
                        terminalError = retryLimitMessage
                        aborted = true
                        return@flow
                    }
                    currentMessages = applyTranscriptRepairs(
                        messages = currentMessages,
                        allowedToolNames = allowedToolNames + clientToolNames,
                    ).toMutableList()
                    val request = LlmRequest(
                        model = effectiveModel,
                        messages = currentMessages,
                        tools = buildRequestTools(allowedToolNames, clientTools),
                        systemPrompt = effectiveSystemPrompt,
                        reasoningEffort = reasoningEffort,
                    )

                    val textBuffer = StringBuilder()
                    val pendingToolCalls = mutableListOf<LlmStreamEvent.ToolUse>()
                    var streamError: LlmStreamEvent.Error? = null
                    var stopReason: String? = null
                    var usageInputTokens = 0
                    var usageOutputTokens = 0
                    var usageCacheRead = 0
                    var usageCacheWrite = 0
                    var usageSeen = false

                    val streamCollection = collectProviderStream(
                        request = request,
                        timeoutMs = remainingAttemptTimeoutMs,
                    ) { event ->
                        when (event) {
                            is LlmStreamEvent.TextDelta -> {
                                textBuffer.append(event.text)
                                emit(AcpRuntimeEvent.TextDelta(text = event.text))
                            }

                            is LlmStreamEvent.ThinkingDelta -> {
                                emit(
                                    AcpRuntimeEvent.TextDelta(
                                        text = event.text,
                                        stream = AcpRuntimeEvent.StreamType.THOUGHT,
                                    ),
                                )
                            }

                            is LlmStreamEvent.ToolUse -> {
                                pendingToolCalls.add(event)
                                val normalizedToolName = normalizeToolName(event.name)
                                if (startedToolCallIds.add(event.id)) {
                                    val parsedInput = parseToolParams(event.input)
                                    emit(
                                        AcpRuntimeEvent.ToolCall(
                                            text = "Calling $normalizedToolName",
                                            tag = "tool_call",
                                            toolCallId = event.id,
                                            status = "in_progress",
                                            title = formatToolCallTitle(normalizedToolName, parsedInput),
                                            detail = "status=in_progress",
                                            kind = inferToolKind(normalizedToolName),
                                            rawInput = event.input,
                                        ),
                                    )
                                }
                            }

                            is LlmStreamEvent.Usage -> {
                                usageSeen = true
                                usageSeenAny = true
                                usageInputTokens += event.inputTokens
                                usageOutputTokens += event.outputTokens
                                usageCacheRead += event.cacheRead
                                usageCacheWrite += event.cacheWrite
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "Tokens: ${event.inputTokens}+${event.outputTokens}",
                                        tag = "usage_update",
                                        used = event.inputTokens + event.outputTokens,
                                    ),
                                )
                            }

                            is LlmStreamEvent.Done -> {
                                stopReason = event.stopReason
                            }

                            is LlmStreamEvent.Error -> {
                                streamError = event
                            }
                        }
                    }

                    if (streamCollection.timedOut) {
                        val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                        val timeoutError = LlmStreamEvent.Error(
                            message = timeoutMessage,
                            code = "timeout",
                            retryable = runAttempt < effectiveMaxRunAttempts,
                        )
                        if (runAttempt < effectiveMaxRunAttempts) {
                            lastRetryableError = timeoutError
                            emit(
                                AcpRuntimeEvent.Status(
                                    text = "$timeoutMessage; retrying",
                                    tag = "turn_timeout_retry",
                                ),
                            )
                            continue@attemptLoop
                        }
                        val retryLimitMessage =
                            "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                        emit(
                            AcpRuntimeEvent.Error(
                                message = retryLimitMessage,
                                code = "retry_limit",
                                retryable = true,
                            ),
                        )
                        terminalError = retryLimitMessage
                        aborted = true
                        return@flow
                    }

                    streamCollection.throwable?.let { thrown ->
                        if (thrown is CancellationException) throw thrown
                        streamError = LlmStreamEvent.Error(
                            message = thrown.message ?: "Provider stream failed",
                            code = "stream_exception",
                            retryable = true,
                        )
                    }

                    val llmUsage = if (usageSeen) {
                        LlmUsage(
                            input = usageInputTokens,
                            output = usageOutputTokens,
                            cacheRead = usageCacheRead,
                            cacheWrite = usageCacheWrite,
                            total = usageInputTokens + usageOutputTokens + usageCacheRead + usageCacheWrite,
                        )
                    } else {
                        null
                    }
                    val assistantText = textBuffer.toString()
                    if (assistantText.isNotBlank()) {
                        llmAssistantTexts += assistantText
                    }
                    llmLastAssistantPayload = if (assistantText.isNotEmpty() || pendingToolCalls.isNotEmpty()) {
                        buildMap {
                            put("role", "assistant")
                            put("content", assistantText)
                            stopReason?.let { put("stopReason", it) }
                            if (pendingToolCalls.isNotEmpty()) {
                                put(
                                    "toolCalls",
                                    pendingToolCalls.map {
                                        mapOf(
                                            "id" to it.id,
                                            "name" to normalizeToolName(it.name),
                                            "arguments" to it.input,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        null
                    }
                    if (llmUsage != null) {
                        usageInputTotal += llmUsage.input ?: 0
                        usageOutputTotal += llmUsage.output ?: 0
                        usageCacheReadTotal += llmUsage.cacheRead ?: 0
                        usageCacheWriteTotal += llmUsage.cacheWrite ?: 0
                    }

                    if (streamError != null) {
                        val error = streamError!!
                        val overflowCode = classifyContextOverflowError(error.message)
                        if (overflowCode != null) {
                            val overflowMessage =
                                "Context overflow: prompt too large for the model context window."
                            if (!toolResultTruncationAttempted && contextGuard.sessionLikelyHasOversizedToolResults(currentMessages)) {
                                toolResultTruncationAttempted = true
                                val truncation = contextGuard.truncateOversizedToolResults(currentMessages)
                                if (truncation.truncated) {
                                    currentMessages = applyTranscriptRepairs(
                                        messages = truncation.messages,
                                        allowedToolNames = allowedToolNames + clientToolNames,
                                    ).toMutableList()
                                    persistCompactedTranscript(
                                        persistence = persistence,
                                        sessionKey = sessionKey,
                                        model = effectiveModel,
                                        agentId = agentId,
                                        workspaceDir = workspaceDir,
                                        messages = currentMessages,
                                    )
                                    emit(
                                        AcpRuntimeEvent.Status(
                                            text = "Recovered context overflow by truncating oversized tool results; retrying",
                                            tag = "tool_result_truncation_retry",
                                        ),
                                    )
                                    continue@attemptLoop
                                }
                            }
                            if (runAttempt < effectiveMaxRunAttempts) {
                                lastRetryableError = LlmStreamEvent.Error(
                                    message = overflowMessage,
                                    code = overflowCode,
                                    retryable = true,
                                )
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "$overflowMessage Retrying attempt ${runAttempt + 1}/$effectiveMaxRunAttempts",
                                        tag = if (overflowCode == "compaction_failure") {
                                            "compaction_retry"
                                        } else {
                                            "context_overflow_retry"
                                        },
                                    ),
                                )
                                continue@attemptLoop
                            }
                            emit(
                                AcpRuntimeEvent.Error(
                                    message = overflowMessage,
                                    code = overflowCode,
                                    retryable = false,
                                ),
                            )
                            terminalError = overflowMessage
                            aborted = true
                            return@flow
                        }
                        if (error.retryable) {
                            if (runAttempt < effectiveMaxRunAttempts) {
                                lastRetryableError = error
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "Provider error: ${error.message}. Retrying attempt ${runAttempt + 1}/$effectiveMaxRunAttempts",
                                        tag = "turn_retryable_error",
                                    ),
                                )
                                continue@attemptLoop
                            }
                            val retryLimitMessage =
                                "Exceeded retry limit after $runAttempt attempts (last error: ${error.message})"
                            emit(
                                AcpRuntimeEvent.Error(
                                    message = retryLimitMessage,
                                    code = "retry_limit",
                                    retryable = true,
                                ),
                            )
                            terminalError = retryLimitMessage
                            aborted = true
                            return@flow
                        }
                        emit(
                            AcpRuntimeEvent.Error(
                                message = error.message,
                                code = error.code,
                                retryable = error.retryable,
                            ),
                        )
                        terminalError = error.message
                        aborted = true
                        return@flow
                    }

                    if (pendingToolCalls.isNotEmpty()) {
                        val assistantMsg = LlmMessage(
                            role = LlmMessage.Role.ASSISTANT,
                            content = assistantText,
                            stopReason = stopReason,
                            toolCalls = pendingToolCalls.map {
                                LlmToolCall(it.id, normalizeToolName(it.name), it.input)
                            },
                        )
                        currentMessages.add(assistantMsg)
                        persistMessage(
                            persistence = persistence,
                            sessionKey = sessionKey,
                            message = assistantMsg,
                            toolContext = null,
                        )
                        val pendingClientToolCalls = mutableListOf<AcpPendingToolCall>()

                        for (tc in pendingToolCalls) {
                            val normalizedToolName = normalizeToolName(tc.name)
                            if (normalizedToolName in clientToolNames) {
                                pendingClientToolCalls += AcpPendingToolCall(
                                    id = tc.id,
                                    name = normalizedToolName,
                                    arguments = tc.input,
                                )
                                emit(
                                    AcpRuntimeEvent.ToolCall(
                                        text = "Delegated $normalizedToolName to client",
                                        tag = "tool_call_update",
                                        toolCallId = tc.id,
                                        status = "pending",
                                        title = normalizedToolName,
                                        detail = "status=pending",
                                        kind = inferToolKind(normalizedToolName),
                                        rawInput = tc.input,
                                    ),
                                )
                                continue
                            }
                            val toolContext = PluginHookToolContext(
                                agentId = agentId,
                                sessionKey = sessionKey.takeIf { it.isNotBlank() },
                                sessionId = effectiveHookSessionId,
                                runId = effectiveRunId,
                                toolName = normalizedToolName,
                                toolCallId = tc.id,
                            )
                            val loopCheck = toolLoopDetector?.checkBeforeToolCall(
                                toolName = normalizedToolName,
                                input = tc.input,
                            )
                            if (loopCheck?.critical == true) {
                                val loopErrorReason =
                                    loopCheck.message ?: "Detected repetitive tool loop for '$normalizedToolName'."
                                val loopError = formatToolErrorResult(
                                    toolName = normalizedToolName,
                                    error = loopErrorReason,
                                )
                                emitAfterToolCall(
                                    toolContext = PluginHookToolContext(
                                        agentId = agentId,
                                        sessionKey = sessionKey.takeIf { it.isNotBlank() },
                                        sessionId = effectiveHookSessionId,
                                        runId = effectiveRunId,
                                        toolName = normalizedToolName,
                                        toolCallId = tc.id,
                                    ),
                                    params = parseToolParams(tc.input),
                                    result = loopError,
                                    error = loopErrorReason,
                                    durationMs = 0L,
                                )
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = loopCheck.message
                                            ?: "Blocked tool call '$normalizedToolName' due to critical loop detection.",
                                        tag = "tool_loop_blocked",
                                    ),
                                )
                                emit(
                                    AcpRuntimeEvent.ToolCall(
                                        text = loopErrorReason,
                                        tag = "tool_call_update",
                                        toolCallId = tc.id,
                                        status = "failed",
                                        title = normalizedToolName,
                                        detail = loopErrorReason,
                                        kind = inferToolKind(normalizedToolName),
                                        rawOutput = loopError,
                                    ),
                                )
                                val blockedResult = contextGuard.guardToolResult(loopError, currentMessages)
                                val blockedToolMsg = LlmMessage(
                                    role = LlmMessage.Role.TOOL,
                                    content = blockedResult,
                                    toolCallId = tc.id,
                                    name = normalizedToolName,
                                )
                                currentMessages.add(blockedToolMsg)
                                persistMessage(
                                    persistence = persistence,
                                    sessionKey = sessionKey,
                                    message = blockedToolMsg,
                                    toolContext = toolContext,
                                )
                                continue
                            }
                            if (loopCheck?.warning == true && loopCheck.shouldEmitWarning) {
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = loopCheck.message
                                            ?: "Potential tool loop detected for '$normalizedToolName' (${loopCheck.count} repeated calls)",
                                        tag = "tool_loop_warning",
                                    ),
                                )
                            }

                            toolLoopDetector?.recordToolCall(
                                toolName = normalizedToolName,
                                input = tc.input,
                                toolCallId = tc.id,
                            )

                            val remainingToolTimeoutMs = turnTimeoutMs?.let { timeout ->
                                (timeout - (System.currentTimeMillis() - attemptStartedAt)).coerceAtLeast(0L)
                            }
                            if (remainingToolTimeoutMs != null && remainingToolTimeoutMs <= 0L) {
                                val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                                val timeoutError = LlmStreamEvent.Error(
                                    message = timeoutMessage,
                                    code = "timeout",
                                    retryable = runAttempt < effectiveMaxRunAttempts,
                                )
                                if (runAttempt < effectiveMaxRunAttempts) {
                                    lastRetryableError = timeoutError
                                    emit(
                                        AcpRuntimeEvent.Status(
                                            text = "$timeoutMessage; retrying",
                                            tag = "turn_timeout_retry",
                                        ),
                                    )
                                    continue@attemptLoop
                                }
                                val retryLimitMessage =
                                    "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                                emit(
                                    AcpRuntimeEvent.Error(
                                        message = retryLimitMessage,
                                        code = "retry_limit",
                                        retryable = true,
                                    ),
                                )
                                terminalError = retryLimitMessage
                                aborted = true
                                return@flow
                            }

                            val execution = if (remainingToolTimeoutMs != null && remainingToolTimeoutMs > 0L) {
                                withTimeoutOrNull(remainingToolTimeoutMs) {
                                    executeTool(
                                        toolCall = tc,
                                        agentId = agentId,
                                        sessionKey = sessionKey,
                                        sessionId = effectiveHookSessionId,
                                        runId = effectiveRunId,
                                        workspaceDir = workspaceDir,
                                        policyContext = policyContext,
                                        clientToolNames = clientToolNames,
                                    )
                                }
                            } else {
                                executeTool(
                                    toolCall = tc,
                                    agentId = agentId,
                                    sessionKey = sessionKey,
                                    sessionId = effectiveHookSessionId,
                                    runId = effectiveRunId,
                                    workspaceDir = workspaceDir,
                                    policyContext = policyContext,
                                    clientToolNames = clientToolNames,
                                )
                            }
                            if (execution == null) {
                                val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                                val timeoutError = LlmStreamEvent.Error(
                                    message = timeoutMessage,
                                    code = "timeout",
                                    retryable = runAttempt < effectiveMaxRunAttempts,
                                )
                                if (runAttempt < effectiveMaxRunAttempts) {
                                    lastRetryableError = timeoutError
                                    emit(
                                        AcpRuntimeEvent.Status(
                                            text = "$timeoutMessage; retrying",
                                            tag = "turn_timeout_retry",
                                        ),
                                    )
                                    continue@attemptLoop
                                }
                                val retryLimitMessage =
                                    "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                                emit(
                                    AcpRuntimeEvent.Error(
                                        message = retryLimitMessage,
                                        code = "retry_limit",
                                        retryable = true,
                                    ),
                                )
                                terminalError = retryLimitMessage
                                aborted = true
                                return@flow
                            }
                            val remainingAfterToolTimeoutMs = turnTimeoutMs?.let { timeout ->
                                (timeout - (System.currentTimeMillis() - attemptStartedAt)).coerceAtLeast(0L)
                            }
                            if (remainingAfterToolTimeoutMs != null && remainingAfterToolTimeoutMs <= 0L) {
                                val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                                val timeoutError = LlmStreamEvent.Error(
                                    message = timeoutMessage,
                                    code = "timeout",
                                    retryable = runAttempt < effectiveMaxRunAttempts,
                                )
                                if (runAttempt < effectiveMaxRunAttempts) {
                                    lastRetryableError = timeoutError
                                    emit(
                                        AcpRuntimeEvent.Status(
                                            text = "$timeoutMessage; retrying",
                                            tag = "turn_timeout_retry",
                                        ),
                                    )
                                    continue@attemptLoop
                                }
                                val retryLimitMessage =
                                    "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                                emit(
                                    AcpRuntimeEvent.Error(
                                        message = retryLimitMessage,
                                        code = "retry_limit",
                                        retryable = true,
                                    ),
                                )
                                terminalError = retryLimitMessage
                                aborted = true
                                return@flow
                            }
                            if (!execution.skipOutcomeRecord) {
                                toolLoopDetector?.recordToolCallOutcome(
                                    toolName = normalizedToolName,
                                    input = tc.input,
                                    toolCallId = tc.id,
                                    result = if (execution.error == null) execution.result else null,
                                    error = execution.error,
                                )
                            }
                            emit(
                                AcpRuntimeEvent.ToolCall(
                                    text = buildToolCallLifecycleText(
                                        toolName = normalizedToolName,
                                        status = execution.status,
                                        error = execution.error,
                                    ),
                                    tag = "tool_call_update",
                                    toolCallId = tc.id,
                                    status = execution.status,
                                    title = normalizedToolName,
                                    detail = buildToolCallLifecycleText(
                                        toolName = normalizedToolName,
                                        status = execution.status,
                                        error = execution.error,
                                    ),
                                    kind = inferToolKind(normalizedToolName),
                                    rawOutput = execution.result,
                                ),
                            )
                            val result = contextGuard.guardToolResult(execution.result, currentMessages)
                            val toolMsg = LlmMessage(
                                role = LlmMessage.Role.TOOL,
                                content = result,
                                toolCallId = tc.id,
                                name = normalizedToolName,
                            )
                            currentMessages.add(toolMsg)
                            persistMessage(
                                persistence = persistence,
                                sessionKey = sessionKey,
                                message = toolMsg,
                                toolContext = toolContext,
                            )
                        }
                        if (pendingClientToolCalls.isNotEmpty()) {
                            completedSuccessfully = true
                            emit(
                                AcpRuntimeEvent.Done(
                                    stopReason = "tool_calls",
                                    pendingToolCalls = pendingClientToolCalls,
                                ),
                            )
                            return@flow
                        }
                    } else {
                        if (assistantText.isNotEmpty()) {
                            persistMessage(
                                persistence = persistence,
                                sessionKey = sessionKey,
                                message = LlmMessage(
                                    role = LlmMessage.Role.ASSISTANT,
                                    content = assistantText,
                                    stopReason = stopReason,
                                ),
                                toolContext = null,
                            )
                        }
                        completedSuccessfully = true
                        emit(AcpRuntimeEvent.Done(stopReason = toAcpStopReason(stopReason)))
                        return@flow
                    }

                    if (!contextGuard.fitsInContext(currentMessages)) {
                        val preCompactionSnapshot = currentMessages.toList()
                        val preCompactionSessionId = sessionKey
                        var timedOutDuringCompaction = false
                        val remainingCompactionTimeoutMs = turnTimeoutMs?.let { timeout ->
                            (timeout - (System.currentTimeMillis() - attemptStartedAt)).coerceAtLeast(0L)
                        }
                        if (remainingCompactionTimeoutMs != null && remainingCompactionTimeoutMs <= 0L) {
                            timedOutDuringCompaction = shouldFlagCompactionTimeout(
                                CompactionTimeoutSignal(
                                    isTimeout = true,
                                    isCompactionPendingOrRetrying = true,
                                    isCompactionInFlight = false,
                                ),
                            )
                            val snapshotSelection = selectCompactionTimeoutSnapshot(
                                CompactionTimeoutSnapshotSelectionParams(
                                    timedOutDuringCompaction = timedOutDuringCompaction,
                                    preCompactionSnapshot = preCompactionSnapshot,
                                    preCompactionSessionId = preCompactionSessionId,
                                    currentSnapshot = currentMessages.toList(),
                                    currentSessionId = sessionKey,
                                ),
                            )
                            currentMessages = snapshotSelection.messagesSnapshot.toMutableList()
                            val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                            val timeoutError = LlmStreamEvent.Error(
                                message = timeoutMessage,
                                code = "timeout",
                                retryable = runAttempt < effectiveMaxRunAttempts,
                            )
                            if (runAttempt < effectiveMaxRunAttempts) {
                                lastRetryableError = timeoutError
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "$timeoutMessage during compaction; retrying",
                                        tag = "turn_timeout_retry_compaction",
                                    ),
                                )
                                continue@attemptLoop
                            }
                            val retryLimitMessage =
                                "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                            emit(
                                AcpRuntimeEvent.Error(
                                    message = retryLimitMessage,
                                    code = "retry_limit",
                                    retryable = true,
                                ),
                            )
                            terminalError = retryLimitMessage
                            aborted = true
                            return@flow
                        }

                        val beforeCompaction = currentMessages.toList()
                        val compacted = contextGuard.trimToFit(currentMessages)
                        val compactionChanged = compacted != beforeCompaction
                        currentMessages = applyTranscriptRepairs(
                            messages = compacted,
                            allowedToolNames = allowedToolNames + clientToolNames,
                        ).toMutableList()
                        val remainingAfterCompactionTimeoutMs = turnTimeoutMs?.let { timeout ->
                            (timeout - (System.currentTimeMillis() - attemptStartedAt)).coerceAtLeast(0L)
                        }
                        if (remainingAfterCompactionTimeoutMs != null && remainingAfterCompactionTimeoutMs <= 0L) {
                            timedOutDuringCompaction = shouldFlagCompactionTimeout(
                                CompactionTimeoutSignal(
                                    isTimeout = true,
                                    isCompactionPendingOrRetrying = true,
                                    isCompactionInFlight = true,
                                ),
                            )
                            val snapshotSelection = selectCompactionTimeoutSnapshot(
                                CompactionTimeoutSnapshotSelectionParams(
                                    timedOutDuringCompaction = timedOutDuringCompaction,
                                    preCompactionSnapshot = preCompactionSnapshot,
                                    preCompactionSessionId = preCompactionSessionId,
                                    currentSnapshot = currentMessages.toList(),
                                    currentSessionId = sessionKey,
                                ),
                            )
                            currentMessages = snapshotSelection.messagesSnapshot.toMutableList()
                            val timeoutMessage = "Turn timed out after ${turnTimeoutMs ?: 0L}ms"
                            val timeoutError = LlmStreamEvent.Error(
                                message = timeoutMessage,
                                code = "timeout",
                                retryable = runAttempt < effectiveMaxRunAttempts,
                            )
                            if (runAttempt < effectiveMaxRunAttempts) {
                                lastRetryableError = timeoutError
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "$timeoutMessage during compaction; retrying",
                                        tag = "turn_timeout_retry_compaction",
                                    ),
                                )
                                continue@attemptLoop
                            }
                            val retryLimitMessage =
                                "Exceeded retry limit after $runAttempt attempts (last error: $timeoutMessage)"
                            emit(
                                AcpRuntimeEvent.Error(
                                    message = retryLimitMessage,
                                    code = "retry_limit",
                                    retryable = true,
                                ),
                            )
                            terminalError = retryLimitMessage
                            aborted = true
                            return@flow
                        }
                        if (!contextGuard.fitsInContext(currentMessages)) {
                            overflowCompactionAttempts += 1
                            val overflowCode = if (compactionChanged) {
                                "compaction_failure"
                            } else {
                                "context_overflow"
                            }
                            val overflowMessage =
                                "Context overflow: prompt too large for the model context window."
                            if (!toolResultTruncationAttempted && contextGuard.sessionLikelyHasOversizedToolResults(currentMessages)) {
                                toolResultTruncationAttempted = true
                                val truncation = contextGuard.truncateOversizedToolResults(currentMessages)
                                if (truncation.truncated) {
                                    currentMessages = applyTranscriptRepairs(
                                        messages = truncation.messages,
                                        allowedToolNames = allowedToolNames + clientToolNames,
                                    ).toMutableList()
                                    persistCompactedTranscript(
                                        persistence = persistence,
                                        sessionKey = sessionKey,
                                        model = effectiveModel,
                                        agentId = agentId,
                                        workspaceDir = workspaceDir,
                                        messages = currentMessages,
                                    )
                                    emit(
                                        AcpRuntimeEvent.Status(
                                            text = "Recovered context overflow by truncating oversized tool results; retrying",
                                            tag = "tool_result_truncation_retry",
                                        ),
                                    )
                                    continue@attemptLoop
                                }
                            }
                            if (
                                runAttempt < effectiveMaxRunAttempts &&
                                overflowCompactionAttempts < maxOverflowCompactionAttempts
                            ) {
                                lastRetryableError = LlmStreamEvent.Error(
                                    message = overflowMessage,
                                    code = overflowCode,
                                    retryable = true,
                                )
                                emit(
                                    AcpRuntimeEvent.Status(
                                        text = "$overflowMessage Retrying attempt ${runAttempt + 1}/$effectiveMaxRunAttempts",
                                        tag = if (overflowCode == "compaction_failure") {
                                            "compaction_retry"
                                        } else {
                                            "context_overflow_retry"
                                        },
                                    ),
                                )
                                continue@attemptLoop
                            }
                            emit(
                                AcpRuntimeEvent.Error(
                                    message = overflowMessage,
                                    code = overflowCode,
                                    retryable = false,
                                ),
                            )
                            terminalError = overflowMessage
                            aborted = true
                            return@flow
                        }
                        overflowCompactionAttempts = 0
                        persistCompactedTranscript(
                            persistence = persistence,
                            sessionKey = sessionKey,
                            model = effectiveModel,
                            agentId = agentId,
                            workspaceDir = workspaceDir,
                            messages = currentMessages,
                        )
                        emit(
                            AcpRuntimeEvent.Status(
                                text = "Context compacted",
                                tag = "context_compacted",
                            ),
                        )
                    }
                }

                if (aborted) {
                    break
                }
                val roundLimitError = LlmStreamEvent.Error(
                    message = "Exceeded maximum tool call rounds ($maxToolRounds)",
                    code = "tool_round_limit",
                    retryable = runAttempt < effectiveMaxRunAttempts,
                )
                if (runAttempt < effectiveMaxRunAttempts) {
                    lastRetryableError = roundLimitError
                    emit(
                        AcpRuntimeEvent.Status(
                            text = "${roundLimitError.message}; retrying",
                            tag = "tool_round_limit_retry",
                        ),
                    )
                    continue
                }
                val retryLimitMessage =
                    "Exceeded retry limit after $runAttempt attempts (last error: ${roundLimitError.message})"
                terminalError = retryLimitMessage
                emit(
                    AcpRuntimeEvent.Error(
                        message = retryLimitMessage,
                        code = "retry_limit",
                        retryable = true,
                    ),
                )
                return@flow
            }

            if (!aborted && terminalError == null && !completedSuccessfully) {
                val fallbackError = lastRetryableError
                val retryLimitMessage = if (fallbackError != null) {
                    "Exceeded retry limit after $effectiveMaxRunAttempts attempts (last error: ${fallbackError.message})"
                } else {
                    "Exceeded retry limit after $effectiveMaxRunAttempts attempts"
                }
                terminalError = retryLimitMessage
                emit(
                    AcpRuntimeEvent.Error(
                        message = retryLimitMessage,
                        code = "retry_limit",
                        retryable = true,
                    ),
                )
            }
        } finally {
            hookDispatchScope.launch {
                runCatching {
                    hookRunner?.runAgentEnd(
                        event = AgentEndEvent(
                            success = completedSuccessfully && terminalError == null,
                            error = terminalError,
                            durationMs = System.currentTimeMillis() - turnStartedAt,
                            messages = currentMessages.map { it.toHookMessagePayload() },
                        ),
                        ctx = PluginHookAgentContext(
                            agentId = agentId,
                            sessionKey = sessionKey.takeIf { it.isNotBlank() },
                            sessionId = effectiveHookSessionId,
                            workspaceDir = workspaceDir,
                            messageProvider = effectiveMessageProvider,
                            trigger = effectiveTrigger,
                            channelId = effectiveHookChannelId,
                        ),
                    )
                }.onFailure { err ->
                    logHookWarning("agent_end hook failed: ${err.message}")
                }
                runCatching {
                    hookRunner?.runLlmOutput(
                        event = LlmOutputEvent(
                            runId = effectiveRunId,
                            sessionId = effectiveHookSessionId,
                            provider = provider.id,
                            model = finalModelForHooks,
                            assistantTexts = llmAssistantTexts.toList(),
                            lastAssistant = llmLastAssistantPayload,
                            usage = if (usageSeenAny) {
                                LlmUsage(
                                    input = usageInputTotal,
                                    output = usageOutputTotal,
                                    cacheRead = usageCacheReadTotal,
                                    cacheWrite = usageCacheWriteTotal,
                                    total = usageInputTotal + usageOutputTotal + usageCacheReadTotal + usageCacheWriteTotal,
                                )
                            } else {
                                null
                            },
                        ),
                        ctx = PluginHookAgentContext(
                            agentId = agentId,
                            sessionKey = sessionKey.takeIf { it.isNotBlank() },
                            sessionId = effectiveHookSessionId,
                            workspaceDir = workspaceDir,
                            messageProvider = effectiveMessageProvider,
                            trigger = effectiveTrigger,
                            channelId = effectiveHookChannelId,
                        ),
                    )
                }.onFailure { err ->
                    logHookWarning("llm_output hook failed: ${err.message}")
                }
            }
        }
    }

    /**
     * Execute a single tool call with policy and approval checks.
     */
    private suspend fun collectProviderStream(
        request: LlmRequest,
        timeoutMs: Long?,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): StreamCollectionResult {
        return if (timeoutMs != null && timeoutMs > 0L) {
            val runResult = withTimeoutOrNull(timeoutMs) {
                runCatching {
                    provider.streamCompletion(request).collect { event -> onEvent(event) }
                }
            }
            when {
                runResult == null -> StreamCollectionResult(timedOut = true)
                runResult.isFailure -> StreamCollectionResult(throwable = runResult.exceptionOrNull())
                else -> StreamCollectionResult()
            }
        } else {
            runCatching {
                provider.streamCompletion(request).collect { event -> onEvent(event) }
            }.fold(
                onSuccess = { StreamCollectionResult() },
                onFailure = { StreamCollectionResult(throwable = it) },
            )
        }
    }

    private suspend fun executeTool(
        toolCall: LlmStreamEvent.ToolUse,
        agentId: String,
        sessionKey: String,
        sessionId: String,
        runId: String,
        workspaceDir: String?,
        policyContext: ToolPolicyContext,
        clientToolNames: Set<String>,
    ): ToolExecutionOutcome {
        val startedAt = System.currentTimeMillis()
        val toolName = normalizeToolName(toolCall.name)
        if (toolName in clientToolNames) {
            return ToolExecutionOutcome(
                result = """{"status":"pending","tool":"$toolName","message":"Tool execution delegated to client"}""",
                error = null,
                skipOutcomeRecord = true,
                status = "pending",
            )
        }
        val toolContext = PluginHookToolContext(
            agentId = agentId,
            sessionKey = sessionKey.takeIf { it.isNotBlank() },
            sessionId = sessionId,
            runId = runId,
            toolName = toolName,
            toolCallId = toolCall.id,
        )
        val baseParams = parseToolParams(toolCall.input)
        var effectiveParams = baseParams
        var effectiveInput = toolCall.input

        val beforeToolCall = try {
            hookRunner?.runBeforeToolCall(
                event = BeforeToolCallEvent(
                    toolName = toolName,
                    params = baseParams,
                    runId = runId,
                    toolCallId = toolCall.id,
                ),
                ctx = toolContext,
            )
        } catch (err: Throwable) {
            logHookWarning("before_tool_call hook failed: ${err.message}")
            null
        }
        if (beforeToolCall?.block == true) {
            val blockedReason = beforeToolCall.blockReason?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "Tool call blocked by plugin hook"
            val result = formatToolErrorResult(toolName = toolName, error = blockedReason)
            emitAfterToolCall(
                toolContext = toolContext,
                params = effectiveParams,
                result = result,
                error = blockedReason,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            return ToolExecutionOutcome(
                result = result,
                error = blockedReason,
                skipOutcomeRecord = true,
                status = "failed",
            )
        }
        val overriddenParams = beforeToolCall?.params
        if (overriddenParams != null) {
            effectiveParams = if (baseParams.isNotEmpty()) {
                baseParams + overriddenParams
            } else {
                overriddenParams
            }
            effectiveInput = encodeToolParams(effectiveParams)
        }

        val policyResult = toolPolicyEnforcer?.check(
            toolName = toolName,
            context = policyContext.copy(
                agentId = agentId,
                sessionKey = sessionKey,
            ),
            includeRateLimit = true,
            recordAudit = true,
        )
        if (policyResult?.allowed == false) {
            val policyError = "Tool '$toolName' denied by policy: ${policyResult.reason}"
            val result = formatToolErrorResult(toolName = toolName, error = policyError)
            emitAfterToolCall(
                toolContext = toolContext,
                params = effectiveParams,
                result = result,
                error = policyError,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            return ToolExecutionOutcome(result = result, error = policyError, status = "failed")
        }

        val approvalDecision = if (approvalManager != null) {
            approvalManager.checkApproval(toolName, effectiveInput, agentId, sessionKey)
        } else {
            ApprovalDecision.APPROVED
        }

        var executionError: String? = null
        val result = when (approvalDecision) {
            ApprovalDecision.DENIED -> {
                val message = "Tool call '$toolName' was denied by approval policy."
                executionError = message
                formatToolErrorResult(toolName = toolName, error = message)
            }
            ApprovalDecision.TIMED_OUT -> {
                val message = "Tool call '$toolName' timed out waiting for approval."
                executionError = message
                formatToolErrorResult(toolName = toolName, error = message)
            }
            ApprovalDecision.APPROVED -> {
                val tool = toolRegistry.get(toolName) ?: toolRegistry.get(toolCall.name.trim())
                if (tool != null) {
                    try {
                        val output = tool.execute(effectiveInput, ToolContext(sessionKey, agentId, workspaceDir))
                        extractErrorMessage(output)?.let { executionError = it }
                        output
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: e::class.simpleName ?: "Tool execution failed"
                        executionError = message
                        formatToolErrorResult(toolName = toolName, error = message)
                    }
                } else {
                    val message = "Unknown tool '$toolName'"
                    executionError = message
                    formatToolErrorResult(toolName = toolName, error = message)
                }
            }
        }

        emitAfterToolCall(
            toolContext = toolContext,
            params = effectiveParams,
            result = result,
            error = executionError,
            durationMs = System.currentTimeMillis() - startedAt,
        )
        return ToolExecutionOutcome(
            result = result,
            error = executionError,
            status = classifyToolCallStatus(executionError),
        )
    }

    private suspend fun resolvePromptBuildHookResult(
        prompt: String,
        messages: List<LlmMessage>,
        agentId: String,
        sessionKey: String,
        sessionId: String,
        workspaceDir: String?,
        messageProvider: String?,
        channelId: String?,
        legacyBeforeAgentStartResult: BeforeAgentStartResult? = null,
        trigger: String = "user",
    ): PromptHookResult {
        val runner = hookRunner ?: return PromptHookResult()
        val hookContext = PluginHookAgentContext(
            agentId = agentId,
            sessionKey = sessionKey.takeIf { it.isNotBlank() },
            sessionId = sessionId,
            workspaceDir = workspaceDir,
            messageProvider = messageProvider,
            trigger = trigger,
            channelId = channelId,
        )
        val messagesForHook = if (
            messages.lastOrNull()?.role == LlmMessage.Role.USER &&
            messages.lastOrNull()?.plainTextContent() == prompt
        ) {
            messages.dropLast(1)
        } else {
            messages
        }
        val hookMessages = messagesForHook.map { it.toHookMessagePayload() }

        val promptBuild = try {
            runner.runBeforePromptBuild(
                event = BeforePromptBuildEvent(prompt = prompt, messages = hookMessages),
                ctx = hookContext,
            )
        } catch (err: Throwable) {
            logHookWarning("before_prompt_build hook failed: ${err.message}")
            null
        }

        val legacy = legacyBeforeAgentStartResult ?: try {
            runner.runBeforeAgentStart(
                event = BeforeAgentStartEvent(prompt = prompt, messages = hookMessages),
                ctx = hookContext,
            )
        } catch (err: Throwable) {
            logHookWarning("before_agent_start hook (legacy prompt build path) failed: ${err.message}")
            null
        }

        val prepend = listOfNotNull(
            promptBuild?.prependContext?.trim().takeUnless { it.isNullOrEmpty() },
            legacy?.prependContext?.trim().takeUnless { it.isNullOrEmpty() },
        ).joinToString("\n\n").ifBlank { null }

        return PromptHookResult(
            systemPrompt = promptBuild?.systemPrompt?.trim().takeUnless { it.isNullOrEmpty() }
                ?: legacy?.systemPrompt?.trim().takeUnless { it.isNullOrEmpty() },
            prependContext = prepend,
        )
    }

    private fun emitAfterToolCall(
        toolContext: PluginHookToolContext,
        params: Map<String, Any?>,
        result: Any?,
        error: String?,
        durationMs: Long,
    ) {
        hookDispatchScope.launch {
            runCatching {
                hookRunner?.runAfterToolCall(
                    event = AfterToolCallEvent(
                        toolName = toolContext.toolName,
                        params = params,
                        runId = toolContext.runId,
                        toolCallId = toolContext.toolCallId,
                        result = result,
                        error = error,
                        durationMs = durationMs,
                    ),
                    ctx = toolContext,
                )
            }.onFailure { err ->
                logHookWarning("after_tool_call hook failed: ${err.message}")
            }
        }
    }

    private fun extractErrorMessage(result: String): String? {
        val trimmed = result.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("Error:", ignoreCase = true)) {
            return trimmed.substringAfter(":", "").trim().ifEmpty { trimmed }
        }
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
        val status = parsed["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (status != "error") return null
        return parsed["error"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: trimmed
    }

    private fun formatToolErrorResult(toolName: String, error: String): String {
        val payload = buildJsonObject {
            put("status", JsonPrimitive("error"))
            put("tool", JsonPrimitive(normalizeToolName(toolName)))
            put("error", JsonPrimitive(error))
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun classifyToolCallStatus(error: String?): String {
        if (error.isNullOrBlank()) return "completed"
        return "failed"
    }

    private fun isBlockedToolError(error: String): Boolean {
        val normalized = error.trim().lowercase()
        if (normalized.isEmpty()) return false
        return normalized.contains("denied by policy") ||
            normalized.contains("denied by approval policy") ||
            normalized.contains("timed out waiting for approval") ||
            normalized.contains("blocked by plugin hook") ||
            normalized.contains("tool loop blocked") ||
            normalized.contains("critical loop detection")
    }

    private fun buildToolCallLifecycleText(
        toolName: String,
        status: String,
        error: String?,
    ): String {
        val normalizedStatus = status.trim().lowercase().ifEmpty { "completed" }
        return when (normalizedStatus) {
            "completed" -> "Completed $toolName"
            "failed" -> if (error != null && isBlockedToolError(error)) {
                error.trim().ifEmpty { "Blocked $toolName" }
            } else {
                error?.trim().takeUnless { it.isNullOrEmpty() } ?: "Tool '$toolName' failed"
            }
            else -> error?.trim().takeUnless { it.isNullOrEmpty() } ?: "Tool '$toolName' failed"
        }
    }
    private fun parseToolParams(input: String): Map<String, Any?> {
        val parsed = runCatching { json.parseToJsonElement(input) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return parsed.mapValues { (_, value) -> value.toAnyValue() }
    }

    private fun encodeToolParams(params: Map<String, Any?>): String {
        val jsonObject = buildJsonObject {
            for ((key, value) in params) {
                put(key, value.toJsonElement())
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun JsonElement.toAnyValue(): Any? = when (this) {
        JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            longOrNull != null -> long
            doubleOrNull != null -> double
            else -> content
        }
        is JsonArray -> this.map { it.toAnyValue() }
        is JsonObject -> this.mapValues { (_, value) -> value.toAnyValue() }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this.toDouble())
        is Map<*, *> -> buildJsonObject {
            for ((key, value) in this@toJsonElement) {
                if (key is String) put(key, value.toJsonElement())
            }
        }
        is Iterable<*> -> buildJsonArray {
            for (value in this@toJsonElement) add(value.toJsonElement())
        }
        else -> JsonPrimitive(this.toString())
    }

    private fun LlmMessage.toHookMessagePayload(): Map<String, Any?> = buildMap {
        put("role", role.name.lowercase())
        put("content", plainTextContent())
        val blocks = normalizedContentBlocks()
        if (blocks.isNotEmpty()) {
            put(
                "contentBlocks",
                blocks.map { block ->
                    when (block) {
                        is LlmContentBlock.Text -> mapOf(
                            "type" to "text",
                            "text" to block.text,
                        )
                        is LlmContentBlock.ImageUrl -> mapOf(
                            "type" to "image_url",
                            "url" to block.url,
                            "mimeType" to block.mimeType,
                        )
                    }
                },
            )
        }
        name?.let { put("name", it) }
        toolCallId?.let { put("toolCallId", it) }
        stopReason?.let { put("stopReason", it) }
        toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            put(
                "toolCalls",
                calls.map { call ->
                    mapOf(
                        "id" to call.id,
                        "name" to normalizeToolName(call.name),
                        "arguments" to call.arguments,
                    )
                },
            )
        }
    }

    private fun normalizeToolName(name: String): String {
        val normalized = name.trim().lowercase()
        return when (normalized) {
            "bash" -> "exec"
            "apply-patch" -> "apply_patch"
            else -> normalized
        }
    }

    private fun normalizeStopReason(stopReason: String?): String? {
        return stopReason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private fun classifyContextOverflowError(message: String?): String? {
        val normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return null
        val overflowSignals = listOf(
            "context overflow",
            "context length",
            "maximum context length",
            "token limit",
            "prompt is too long",
            "too many tokens",
        )
        if (overflowSignals.any { normalized.contains(it) }) {
            return "context_overflow"
        }
        if (normalized.contains("compaction")) {
            return "compaction_failure"
        }
        return null
    }

    private fun toAcpStopReason(stopReason: String?): String {
        return when (normalizeStopReason(stopReason)) {
            "max_tokens" -> "max_tokens"
            "tool_calls" -> "tool_calls"
            "error" -> "error"
            "aborted", "cancelled", "canceled" -> "aborted"
            else -> "end_turn"
        }
    }

    private fun formatToolCallTitle(name: String, args: Map<String, Any?>): String {
        if (args.isEmpty()) return name
        val argSummary = args.entries.joinToString(", ") { (key, value) ->
            val raw = when (value) {
                null -> "null"
                is String -> value
                else -> value.toString()
            }
            val safe = if (raw.length > 100) "${raw.take(100)}..." else raw
            "$key: $safe"
        }
        return "$name: $argSummary"
    }

    private fun inferToolKind(name: String?): String {
        val normalized = name?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return "other"
        return when {
            "read" in normalized -> "read"
            "write" in normalized || "edit" in normalized -> "edit"
            "delete" in normalized || "remove" in normalized -> "delete"
            "move" in normalized || "rename" in normalized -> "move"
            "search" in normalized || "find" in normalized -> "search"
            "exec" in normalized || "run" in normalized || "bash" in normalized -> "execute"
            "fetch" in normalized || "http" in normalized -> "fetch"
            else -> "other"
        }
    }

    private fun shouldSkipSyntheticToolResult(stopReason: String?): Boolean {
        return when (normalizeStopReason(stopReason)) {
            "error", "aborted", "cancelled", "canceled" -> true
            else -> false
        }
    }

    /**
     * Repairs transcript parity for providers that require strict tool_call/tool_result adjacency.
     * - Moves matching tool results directly after assistant tool calls
     * - Drops orphan and duplicate tool results
     * - Inserts synthetic error results for missing tool call ids
     */
    private fun sanitizeToolCallResultPairing(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.isEmpty()) return messages

        val sanitized = mutableListOf<LlmMessage>()
        val emittedToolResultIds = mutableSetOf<String>()
        var changed = false

        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            if (message.role != LlmMessage.Role.ASSISTANT) {
                if (message.role == LlmMessage.Role.TOOL) {
                    changed = true
                } else {
                    sanitized += message
                }
                i += 1
                continue
            }

            val assistantToolCalls = message.toolCalls
                ?.mapNotNull { call ->
                    val trimmedId = call.id.trim()
                    if (trimmedId.isEmpty()) {
                        changed = true
                        null
                    } else {
                        val normalizedName = normalizeToolName(call.name)
                        if (normalizedName != call.name) {
                            changed = true
                        }
                        call.copy(id = trimmedId, name = normalizedName)
                    }
                }
                ?: emptyList()

            val normalizedAssistantMessage = if (assistantToolCalls.isNotEmpty()) {
                val normalizedStopReason = normalizeStopReason(message.stopReason)
                if (normalizedStopReason != message.stopReason) {
                    changed = true
                }
                message.copy(
                    stopReason = normalizedStopReason,
                    toolCalls = assistantToolCalls,
                )
            } else if (!message.toolCalls.isNullOrEmpty()) {
                changed = true
                val normalizedStopReason = normalizeStopReason(message.stopReason)
                if (normalizedStopReason != message.stopReason) {
                    changed = true
                }
                message.copy(
                    stopReason = normalizedStopReason,
                    toolCalls = null,
                )
            } else {
                val normalizedStopReason = normalizeStopReason(message.stopReason)
                if (normalizedStopReason != message.stopReason) {
                    changed = true
                }
                message.copy(stopReason = normalizedStopReason)
            }

            if (assistantToolCalls.isEmpty()) {
                sanitized += normalizedAssistantMessage
                i += 1
                continue
            }

            if (shouldSkipSyntheticToolResult(normalizedAssistantMessage.stopReason)) {
                // Match reference repair behavior: do not attempt tool-result pairing when
                // assistant stopReason indicates an aborted/error turn.
                sanitized += normalizedAssistantMessage
                i += 1
                continue
            }

            sanitized += normalizedAssistantMessage

            val callIds = assistantToolCalls.map { it.id }.toSet()
            val callNamesById = assistantToolCalls.associate { it.id to it.name }
            val spanResultsById = mutableMapOf<String, LlmMessage>()
            val remainder = mutableListOf<LlmMessage>()

            var j = i + 1
            while (j < messages.size) {
                val next = messages[j]
                if (next.role == LlmMessage.Role.ASSISTANT) {
                    break
                }
                if (next.role == LlmMessage.Role.TOOL) {
                    val toolCallId = next.toolCallId?.trim().takeUnless { it.isNullOrEmpty() }
                    if (toolCallId != null && callIds.contains(toolCallId)) {
                        if (emittedToolResultIds.contains(toolCallId) || spanResultsById.containsKey(toolCallId)) {
                            changed = true
                        } else {
                            val normalizedToolResult = normalizeToolResultMessage(
                                message = next,
                                fallbackName = callNamesById[toolCallId],
                            )
                            if (normalizedToolResult != next) {
                                changed = true
                            }
                            spanResultsById[toolCallId] = normalizedToolResult
                            emittedToolResultIds.add(toolCallId)
                        }
                    } else {
                        changed = true
                    }
                } else {
                    remainder += next
                }
                j += 1
            }

            if (spanResultsById.isNotEmpty() && remainder.isNotEmpty()) {
                changed = true
            }

            for (call in assistantToolCalls) {
                val existingResult = spanResultsById[call.id]
                if (existingResult != null) {
                    sanitized += existingResult
                } else {
                    changed = true
                    val synthetic = createSyntheticMissingToolResult(call.id, call.name)
                    emittedToolResultIds.add(call.id)
                    sanitized += synthetic
                }
            }

            sanitized += remainder
            i = j
        }

        return if (changed) sanitized else messages
    }

    private fun normalizeToolResultMessage(message: LlmMessage, fallbackName: String?): LlmMessage {
        val normalizedToolCallId = message.toolCallId?.trim()
        val normalizedToolName = message.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::normalizeToolName)
            ?: fallbackName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::normalizeToolName)
            ?: "unknown"
        return message.copy(
            toolCallId = normalizedToolCallId,
            name = normalizedToolName,
        )
    }

    private fun createSyntheticMissingToolResult(toolCallId: String, toolName: String): LlmMessage {
        val normalizedToolName = normalizeToolName(toolName)
        val error = "Missing tool result in transcript history; inserted synthetic error result."
        return LlmMessage(
            role = LlmMessage.Role.TOOL,
            content = formatToolErrorResult(normalizedToolName, error),
            toolCallId = toolCallId,
            name = normalizedToolName,
        )
    }

    private suspend fun persistMessage(
        persistence: SessionPersistence?,
        sessionKey: String,
        message: LlmMessage,
        toolContext: PluginHookToolContext?,
    ) {
        if (persistence == null || sessionKey.isBlank()) return
        var messageToPersist = message
        if (message.role == LlmMessage.Role.TOOL && toolContext != null) {
            val transformed = try {
                hookRunner?.runToolResultPersist(
                    event = ToolResultPersistEvent(
                        message = message,
                        toolName = toolContext.toolName,
                        toolCallId = toolContext.toolCallId,
                        runId = toolContext.runId,
                    ),
                    ctx = toolContext,
                )?.message
            } catch (err: Throwable) {
                logHookWarning("tool_result_persist hook failed: ${err.message}")
                null
            }
            if (transformed != null) {
                messageToPersist = transformed
            }
        }
        persistence.appendMessage(sessionKey, messageToPersist)
    }

    private fun logHookWarning(message: String) {
        System.err.println("[AgentRunner] $message")
    }

    private fun buildToolPolicyContext(
        agentId: String,
        sessionKey: String,
        modelProvider: String?,
        modelId: String?,
        messageProvider: String?,
        senderIsOwner: Boolean?,
        runtimeInfo: SystemPromptBuilder.RuntimeInfo?,
        turnSandboxed: Boolean?,
        groupToolPolicy: GroupToolPolicyConfig?,
    ): ToolPolicyContext {
        return ToolPolicyContext(
            agentId = agentId,
            sessionKey = sessionKey,
            modelProvider = modelProvider,
            modelId = modelId,
            messageProvider = messageProvider,
            senderIsOwner = senderIsOwner,
            sandboxed = turnSandboxed ?: runtimeInfo?.sandboxed,
            groupPolicy = groupToolPolicy,
        )
    }

    private fun resolveAllowedToolNames(policyContext: ToolPolicyContext): Set<String> {
        val enforcer = toolPolicyEnforcer ?: return toolRegistry.names()
        return toolRegistry.names().filter { toolName ->
            enforcer.check(
                toolName = toolName,
                context = policyContext,
                includeRateLimit = false,
                recordAudit = false,
            ).allowed
        }.toSet()
    }

    private fun buildRequestTools(
        allowedToolNames: Set<String>,
        clientTools: List<ClientToolDefinition>,
    ): List<LlmToolDefinition> {
        val builtIn = toolRegistry.toDefinitions(allowedToolNames)
        if (clientTools.isEmpty()) return builtIn

        val existing = builtIn.map { normalizeToolName(it.name) }.toMutableSet()
        val clientDefs = mutableListOf<LlmToolDefinition>()
        for (clientTool in clientTools) {
            val normalized = normalizeToolName(clientTool.name)
            if (normalized.isBlank() || existing.contains(normalized)) {
                continue
            }
            existing += normalized
            clientDefs += LlmToolDefinition(
                name = normalized,
                description = clientTool.description,
                parameters = clientTool.parameters,
            )
        }
        return builtIn + clientDefs
    }

    private fun applyTranscriptRepairs(
        messages: List<LlmMessage>,
        allowedToolNames: Set<String>,
    ): List<LlmMessage> {
        var repaired = messages
        if (transcriptRepairPolicy.toolCallInputRepairEnabled) {
            repaired = transcriptRepair.repairToolCallInputs(
                messages = repaired,
                allowedToolNames = allowedToolNames,
            ).messages
        }
        if (transcriptRepairPolicy.toolResultPairRepairEnabled) {
            repaired = transcriptRepair.repairToolUseResultPairing(
                messages = repaired,
                allowSyntheticToolResults = transcriptRepairPolicy.allowSyntheticToolResults,
            ).messages
        }
        return repaired
    }

    private fun persistCompactedTranscript(
        persistence: SessionPersistence?,
        sessionKey: String,
        model: String,
        agentId: String,
        workspaceDir: String?,
        messages: List<LlmMessage>,
    ) {
        if (persistence == null || sessionKey.isBlank()) return
        val existingHeader = persistence.load(sessionKey).first
        val header = existingHeader ?: SessionPersistence.SessionHeader(
            sessionId = sessionKey,
            agentId = agentId,
            model = model,
            cwd = workspaceDir,
        )
        persistence.compact(sessionKey, header, messages)
    }

    private fun resolvePromptModeForSession(sessionKey: String): SystemPromptBuilder.PromptMode {
        return if (isSubagentSessionKey(sessionKey)) {
            SystemPromptBuilder.PromptMode.MINIMAL
        } else {
            SystemPromptBuilder.PromptMode.FULL
        }
    }

    private fun subagentPromptContract(sessionKey: String): String? {
        if (!isSubagentSessionKey(sessionKey)) return null
        return buildString {
            append("## Subagent Contract\n")
            append("Focus on delegated execution and return concise outcomes to the parent agent.\n")
            append("Do not poll subagent/session lists in loops; rely on push completion and on-demand checks.\n")
        }
    }

    private data class ToolExecutionOutcome(
        val result: String,
        val error: String? = null,
        val skipOutcomeRecord: Boolean = false,
        val status: String = "completed",
    )

    private data class StreamCollectionResult(
        val timedOut: Boolean = false,
        val throwable: Throwable? = null,
    )

    private data class PromptHookResult(
        val systemPrompt: String? = null,
        val prependContext: String? = null,
    )
}

/**
 * ACP runtime implementation backed by AgentRunner.
 * Bridges the AcpRuntime interface to the embedded engine.
 */
class EmbeddedAcpRuntime(
    private val runnerFactory: (String) -> AgentRunner,
) : AcpRuntime {

    private class SessionState(
        val sessionKey: String,
        val agentId: String,
        val model: String,
        val hookSessionId: String = UUID.randomUUID().toString(),
        var runner: AgentRunner,
        val turnMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
        @Volatile var activeJob: kotlinx.coroutines.Job? = null,
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionState>()
    private val runtimeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override suspend fun ensureSession(input: AcpRuntimeEnsureInput): AcpRuntimeHandle {
        val runner = runnerFactory(input.agent)
        val resolvedModel = input.env?.get("model")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "default"
        sessions[input.sessionKey] = SessionState(
            sessionKey = input.sessionKey,
            agentId = input.agent,
            model = resolvedModel,
            runner = runner,
        )
        return AcpRuntimeHandle(
            sessionKey = input.sessionKey,
            backend = "embedded",
            runtimeSessionName = input.sessionKey,
            cwd = input.cwd,
        )
    }

    override fun runTurn(input: AcpRuntimeTurnInput): Flow<AcpRuntimeEvent> {
        val session = sessions[input.handle.sessionKey]
            ?: return flow {
                emit(AcpRuntimeEvent.Error(message = "No session: ${input.handle.sessionKey}"))
            }

        val userMessage = LlmMessage(
            role = LlmMessage.Role.USER,
            content = input.text,
        )

        return kotlinx.coroutines.flow.channelFlow {
            val job = runtimeScope.launch {
                session.turnMutex.withLock {
                    session.runner.runTurn(
                        EmbeddedRunParams(
                            messages = listOf(userMessage),
                            model = session.model,
                            runId = input.requestId,
                            sessionKey = session.sessionKey,
                            agentId = session.agentId,
                            timeoutMs = input.timeoutMs,
                            hookSessionId = input.context?.sessionId ?: session.hookSessionId,
                            turnContext = EmbeddedTurnContext(
                                trigger = input.context?.trigger,
                                messageProvider = input.context?.messageProvider,
                                channelId = input.context?.channelId,
                                sessionId = input.context?.sessionId,
                                senderIsOwner = input.context?.senderIsOwner,
                                sandboxed = input.context?.sandboxed,
                                groupToolPolicy = input.context?.groupToolPolicy,
                            ),
                        ),
                    ).collect { event ->
                        send(event)
                    }
                }
            }
            session.activeJob = job
            try {
                job.join()
                // join() returns normally even if the joined job was cancelled;
                // check its completion status to detect cancellation.
                if (job.isCancelled) {
                    send(AcpRuntimeEvent.Done(stopReason = "cancelled"))
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                send(AcpRuntimeEvent.Done(stopReason = "cancelled"))
            } finally {
                if (session.activeJob == job) {
                    session.activeJob = null
                }
            }
        }
    }

    override suspend fun getCapabilities(handle: AcpRuntimeHandle?): AcpRuntimeCapabilities {
        return AcpRuntimeCapabilities(
            controls = listOf(
                AcpRuntimeControl.SESSION_SET_MODE,
                AcpRuntimeControl.SESSION_STATUS,
            ),
        )
    }

    override suspend fun getStatus(handle: AcpRuntimeHandle): AcpRuntimeStatus {
        val session = sessions[handle.sessionKey]
        return AcpRuntimeStatus(
            summary = if (session != null) {
                if (session.activeJob?.isActive == true) "running" else "active"
            } else "unknown",
            agentSessionId = session?.sessionKey,
        )
    }

    override suspend fun setMode(handle: AcpRuntimeHandle, mode: String) {
        // Embedded runtime doesn't have mode switching
    }

    override suspend fun setConfigOption(handle: AcpRuntimeHandle, key: String, value: String) {
        // Embedded runtime config options are not yet supported
    }

    override suspend fun doctor(): AcpRuntimeDoctorReport {
        return AcpRuntimeDoctorReport(
            ok = true,
            message = "Embedded runtime is healthy",
        )
    }

    override suspend fun cancel(handle: AcpRuntimeHandle, reason: String?) {
        val session = sessions[handle.sessionKey] ?: return
        val job = session.activeJob ?: return
        job.cancel(kotlinx.coroutines.CancellationException(reason ?: "Cancelled by user"))
        session.activeJob = null
    }

    override suspend fun close(handle: AcpRuntimeHandle, reason: String) {
        val session = sessions.remove(handle.sessionKey)
        session?.activeJob?.cancel()
    }
}
