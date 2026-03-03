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
import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.ToolPolicyEnforcer
import ai.openclaw.core.session.isSubagentSessionKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Tool registry for managing available tools.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    fun clear() {
        tools.clear()
    }

    fun toDefinitions(): List<LlmToolDefinition> = tools.values.map {
        LlmToolDefinition(
            name = it.name,
            description = it.description,
            parameters = it.parametersSchema,
        )
    }

    fun toSummaries(): List<SystemPromptBuilder.ToolSummary> = tools.values.map {
        SystemPromptBuilder.ToolSummary(name = it.name, description = it.description)
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
    private val maxToolRounds: Int = 30,
    private val contextGuard: ContextGuard = ContextGuard(),
    private val sessionPersistence: SessionPersistence? = null,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val approvalManager: ApprovalManager? = null,
    private val toolPolicyEnforcer: ToolPolicyEnforcer? = null,
    private val toolLoopDetector: ToolLoopDetector? = null,
    private val hookRunner: HookRunner? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val hookDispatchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

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
            hookSessionId = params.hookSessionId,
            legacyBeforeAgentStartResult = params.legacyBeforeAgentStartResult,
            trigger = params.turnContext.trigger,
            messageProvider = params.turnContext.messageProvider,
            hookChannelId = params.turnContext.channelId,
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
        hookSessionId: String? = null,
        legacyBeforeAgentStartResult: BeforeAgentStartResult? = null,
        trigger: String? = null,
        messageProvider: String? = null,
        hookChannelId: String? = null,
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

        try {
            // Load persisted history if available.
            currentMessages = if (persistence != null && persistence.exists(sessionKey)) {
                val (_, history) = persistence.load(sessionKey)
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
            var effectiveSystemPrompt = systemPrompt ?: systemPromptBuilder.buildEmbedded(
                SystemPromptBuilder.EmbeddedPromptConfig(
                    agentIdentity = agentIdentity,
                    tools = toolRegistry.toSummaries(),
                    skills = skills,
                    runtimeInfo = runtimeInfo,
                    promptMode = resolvePromptModeForSession(sessionKey),
                    channelContext = channelContext,
                    workspaceDir = workspaceDir ?: ".",
                    modelId = model,
                    provider = provider.id,
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

            val latestUserMessage = currentMessages.lastOrNull { it.role == LlmMessage.Role.USER }
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
                            historyMessages = currentMessages.map { it.toHookMessagePayload() },
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

            var round = 0
            var aborted = false
            emit(AcpRuntimeEvent.Status(text = "Starting turn", tag = "turn_start"))

            while (round < maxToolRounds && !aborted) {
                round++
                finalModelForHooks = effectiveModel
                val request = LlmRequest(
                    model = effectiveModel,
                    messages = currentMessages,
                    tools = toolRegistry.toDefinitions(),
                    systemPrompt = effectiveSystemPrompt,
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

                provider.streamCompletion(request).collect { event ->
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
                            emit(
                                AcpRuntimeEvent.ToolCall(
                                    text = "Calling ${event.name}",
                                    toolCallId = event.id,
                                    title = event.name,
                                ),
                            )
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
                    emit(
                        AcpRuntimeEvent.Error(
                            message = streamError!!.message,
                            code = streamError!!.code,
                            retryable = streamError!!.retryable,
                        ),
                    )
                    terminalError = streamError!!.message
                    aborted = true
                    return@flow
                }

                if (pendingToolCalls.isNotEmpty()) {
                    val assistantMsg = LlmMessage(
                        role = LlmMessage.Role.ASSISTANT,
                        content = assistantText,
                        toolCalls = pendingToolCalls.map {
                            LlmToolCall(it.id, normalizeToolName(it.name), it.input)
                        },
                    )
                    currentMessages.add(assistantMsg)
                    persistence?.appendMessage(sessionKey, assistantMsg)

                    for (tc in pendingToolCalls) {
                        val normalizedToolName = normalizeToolName(tc.name)
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
                            val blockedResult = contextGuard.guardToolResult(loopError, currentMessages)
                            val blockedToolMsg = LlmMessage(
                                role = LlmMessage.Role.TOOL,
                                content = blockedResult,
                                toolCallId = tc.id,
                                name = normalizedToolName,
                            )
                            currentMessages.add(blockedToolMsg)
                            persistence?.appendMessage(sessionKey, blockedToolMsg)
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

                        val execution = executeTool(
                            toolCall = tc,
                            agentId = agentId,
                            sessionKey = sessionKey,
                            sessionId = effectiveHookSessionId,
                            runId = effectiveRunId,
                        )
                        if (!execution.skipOutcomeRecord) {
                            toolLoopDetector?.recordToolCallOutcome(
                                toolName = normalizedToolName,
                                input = tc.input,
                                toolCallId = tc.id,
                                result = if (execution.error == null) execution.result else null,
                                error = execution.error,
                            )
                        }
                        val result = contextGuard.guardToolResult(execution.result, currentMessages)
                        val toolMsg = LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = result,
                            toolCallId = tc.id,
                            name = normalizedToolName,
                        )
                        currentMessages.add(toolMsg)
                        persistence?.appendMessage(sessionKey, toolMsg)
                    }
                } else {
                    if (assistantText.isNotEmpty()) {
                        persistence?.appendMessage(
                            sessionKey,
                            LlmMessage(
                                role = LlmMessage.Role.ASSISTANT,
                                content = assistantText,
                            ),
                        )
                    }
                    completedSuccessfully = true
                    emit(AcpRuntimeEvent.Done(stopReason = stopReason ?: "end_turn"))
                    return@flow
                }

                if (!contextGuard.fitsInContext(currentMessages)) {
                    currentMessages = contextGuard.trimToFit(currentMessages).toMutableList()
                    emit(
                        AcpRuntimeEvent.Status(
                            text = "Context compacted",
                            tag = "context_compacted",
                        ),
                    )
                }
            }

            if (!aborted) {
                val errorMessage = "Exceeded maximum tool call rounds ($maxToolRounds)"
                terminalError = errorMessage
                emit(AcpRuntimeEvent.Error(message = errorMessage))
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
    private suspend fun executeTool(
        toolCall: LlmStreamEvent.ToolUse,
        agentId: String,
        sessionKey: String,
        sessionId: String,
        runId: String,
    ): ToolExecutionOutcome {
        val startedAt = System.currentTimeMillis()
        val toolName = normalizeToolName(toolCall.name)
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

        val policyResult = toolPolicyEnforcer?.check(toolName, agentId, sessionKey)
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
            return ToolExecutionOutcome(result = result, error = policyError)
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
                        val output = tool.execute(effectiveInput, ToolContext(sessionKey, agentId))
                        extractErrorMessage(output)?.let { executionError = it }
                        output
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
        return ToolExecutionOutcome(result = result, error = executionError)
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

    private fun logHookWarning(message: String) {
        System.err.println("[AgentRunner] $message")
    }

    private fun resolvePromptModeForSession(sessionKey: String): SystemPromptBuilder.PromptMode {
        return if (isSubagentSessionKey(sessionKey)) {
            SystemPromptBuilder.PromptMode.MINIMAL
        } else {
            SystemPromptBuilder.PromptMode.FULL
        }
    }

    private data class ToolExecutionOutcome(
        val result: String,
        val error: String? = null,
        val skipOutcomeRecord: Boolean = false,
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
                            hookSessionId = input.context?.sessionId ?: session.hookSessionId,
                            turnContext = EmbeddedTurnContext(
                                trigger = input.context?.trigger,
                                messageProvider = input.context?.messageProvider,
                                channelId = input.context?.channelId,
                                sessionId = input.context?.sessionId,
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
