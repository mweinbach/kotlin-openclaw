package ai.openclaw.runtime.engine

import ai.openclaw.core.acp.AcpRuntime
import ai.openclaw.core.agent.*
import ai.openclaw.core.model.*
import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.ToolPolicyEnforcer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

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
) {
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
        sessionKey: String = "",
        agentId: String = DEFAULT_AGENT_ID,
        agentIdentity: IdentityConfig? = null,
        channelContext: SystemPromptBuilder.ChannelContext? = null,
        skills: List<SystemPromptBuilder.SkillSummary> = emptyList(),
        runtimeInfo: SystemPromptBuilder.RuntimeInfo? = null,
        workspaceDir: String? = null,
        maxHistoryTurns: Int? = null,
    ): Flow<AcpRuntimeEvent> = flow {
        // Build system prompt if not provided
        val effectiveSystemPrompt = systemPrompt ?: systemPromptBuilder.build(
            SystemPromptBuilder.PromptConfig(
                agentIdentity = agentIdentity,
                tools = toolRegistry.toSummaries(),
                skills = skills,
                runtimeInfo = runtimeInfo,
                channelContext = channelContext,
                workspaceDir = workspaceDir,
                modelId = model,
                provider = provider.id,
            )
        )

        // Load persisted history if available
        var currentMessages = if (sessionPersistence != null && sessionPersistence.exists(sessionKey)) {
            val (_, history) = sessionPersistence.load(sessionKey)
            val combined = history.toMutableList()
            // Add new messages that aren't already in history.
            // Use structural identity (role + content + toolCallId + toolCall IDs) to avoid
            // dropping legitimately repeated user messages while still preventing true duplicates.
            for (msg in messages) {
                val isDuplicate = combined.any { existing ->
                    existing.role == msg.role &&
                        existing.content == msg.content &&
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

        // Apply history turn limits
        currentMessages = contextGuard.limitHistoryTurns(currentMessages, maxHistoryTurns).toMutableList()

        // Trim to fit context window
        currentMessages = contextGuard.trimToFit(currentMessages).toMutableList()

        var round = 0
        var aborted = false

        emit(AcpRuntimeEvent.Status(text = "Starting turn", tag = "turn_start"))

        while (round < maxToolRounds && !aborted) {
            round++

            val request = LlmRequest(
                model = model,
                messages = currentMessages,
                tools = toolRegistry.toDefinitions(),
                systemPrompt = effectiveSystemPrompt,
            )

            // --- Phase 1: Stream the full LLM response, collecting tool calls ---
            val textBuffer = StringBuilder()
            val pendingToolCalls = mutableListOf<LlmStreamEvent.ToolUse>()
            var streamError: LlmStreamEvent.Error? = null
            var stopReason: String? = null

            provider.streamCompletion(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        textBuffer.append(event.text)
                        emit(AcpRuntimeEvent.TextDelta(text = event.text))
                    }
                    is LlmStreamEvent.ThinkingDelta -> {
                        emit(AcpRuntimeEvent.TextDelta(
                            text = event.text,
                            stream = AcpRuntimeEvent.StreamType.THOUGHT,
                        ))
                    }
                    is LlmStreamEvent.ToolUse -> {
                        pendingToolCalls.add(event)
                        emit(AcpRuntimeEvent.ToolCall(
                            text = "Calling ${event.name}",
                            toolCallId = event.id,
                            title = event.name,
                        ))
                    }
                    is LlmStreamEvent.Usage -> {
                        emit(AcpRuntimeEvent.Status(
                            text = "Tokens: ${event.inputTokens}+${event.outputTokens}",
                            tag = "usage_update",
                            used = event.inputTokens + event.outputTokens,
                        ))
                    }
                    is LlmStreamEvent.Done -> {
                        stopReason = event.stopReason
                    }
                    is LlmStreamEvent.Error -> {
                        streamError = event
                    }
                }
            }

            // Handle stream error
            if (streamError != null) {
                emit(AcpRuntimeEvent.Error(
                    message = streamError!!.message,
                    code = streamError!!.code,
                    retryable = streamError!!.retryable,
                ))
                aborted = true
                return@flow
            }

            // --- Phase 2: Execute tool calls (if any) ---
            if (pendingToolCalls.isNotEmpty()) {
                // Add a single assistant message with ALL tool calls from this response
                val assistantMsg = LlmMessage(
                    role = LlmMessage.Role.ASSISTANT,
                    content = textBuffer.toString(),
                    toolCalls = pendingToolCalls.map {
                        LlmToolCall(it.id, it.name, it.input)
                    },
                )
                currentMessages.add(assistantMsg)
                sessionPersistence?.appendMessage(sessionKey, assistantMsg)

                // Execute each tool and add individual result messages
                for (tc in pendingToolCalls) {
                    val rawResult = executeTool(
                        toolCall = tc,
                        agentId = agentId,
                        sessionKey = sessionKey,
                    )
                    val result = contextGuard.guardToolResult(rawResult, currentMessages)

                    val toolMsg = LlmMessage(
                        role = LlmMessage.Role.TOOL,
                        content = result,
                        toolCallId = tc.id,
                        name = tc.name,
                    )
                    currentMessages.add(toolMsg)
                    sessionPersistence?.appendMessage(sessionKey, toolMsg)
                }
            } else {
                // No tool calls — persist final assistant text and finish
                if (textBuffer.isNotEmpty()) {
                    sessionPersistence?.appendMessage(sessionKey, LlmMessage(
                        role = LlmMessage.Role.ASSISTANT,
                        content = textBuffer.toString(),
                    ))
                }
                emit(AcpRuntimeEvent.Done(stopReason = stopReason ?: "end_turn"))
                return@flow
            }

            // Check context budget between rounds
            if (!contextGuard.fitsInContext(currentMessages)) {
                currentMessages = contextGuard.trimToFit(currentMessages).toMutableList()
                emit(AcpRuntimeEvent.Status(
                    text = "Context compacted",
                    tag = "context_compacted",
                ))
            }
        }

        // Exceeded max tool rounds
        if (!aborted) {
            emit(AcpRuntimeEvent.Error(message = "Exceeded maximum tool call rounds ($maxToolRounds)"))
        }
    }

    /**
     * Execute a single tool call with policy and approval checks.
     */
    private suspend fun executeTool(
        toolCall: LlmStreamEvent.ToolUse,
        agentId: String,
        sessionKey: String,
    ): String {
        // Check tool policy
        val policyResult = toolPolicyEnforcer?.check(toolCall.name, agentId, sessionKey)
        if (policyResult?.allowed == false) {
            return "Error: Tool '${toolCall.name}' denied by policy: ${policyResult.reason}"
        }

        // Check approval
        val approvalDecision = if (approvalManager != null) {
            approvalManager.checkApproval(toolCall.name, toolCall.input, agentId, sessionKey)
        } else {
            ApprovalDecision.APPROVED
        }

        return when (approvalDecision) {
            ApprovalDecision.DENIED ->
                "Error: Tool call '${toolCall.name}' was denied by approval policy."
            ApprovalDecision.TIMED_OUT ->
                "Error: Tool call '${toolCall.name}' timed out waiting for approval."
            ApprovalDecision.APPROVED -> {
                val tool = toolRegistry.get(toolCall.name)
                if (tool != null) {
                    try {
                        tool.execute(toolCall.input, ToolContext(sessionKey, agentId))
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                } else {
                    "Error: Unknown tool '${toolCall.name}'"
                }
            }
        }
    }
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
        var runner: AgentRunner,
        @Volatile var activeJob: kotlinx.coroutines.Job? = null,
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionState>()
    private val runtimeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override suspend fun ensureSession(input: AcpRuntimeEnsureInput): AcpRuntimeHandle {
        val runner = runnerFactory(input.agent)
        sessions[input.sessionKey] = SessionState(
            sessionKey = input.sessionKey,
            agentId = input.agent,
            model = "default",
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

        // Cancel any in-flight turn for this session (supports new message during processing)
        session.activeJob?.cancel()

        val userMessage = LlmMessage(
            role = LlmMessage.Role.USER,
            content = input.text,
        )

        return kotlinx.coroutines.flow.channelFlow {
            val job = runtimeScope.launch {
                session.runner.runTurn(
                    messages = listOf(userMessage),
                    model = session.model,
                    sessionKey = session.sessionKey,
                    agentId = session.agentId,
                ).collect { event ->
                    send(event)
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
