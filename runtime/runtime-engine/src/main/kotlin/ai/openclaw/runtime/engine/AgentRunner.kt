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
     * Run a single turn: message → LLM → tool calls → loop → done.
     * Returns a Flow of AcpRuntimeEvents for streaming.
     */
    fun runTurn(
        messages: List<LlmMessage>,
        model: String,
        systemPrompt: String? = null,
        sessionKey: String = "",
        agentId: String = DEFAULT_AGENT_ID,
        agentIdentity: IdentityConfig? = null,
        channelContext: SystemPromptBuilder.ChannelContext? = null,
        maxHistoryTurns: Int? = null,
    ): Flow<AcpRuntimeEvent> = flow {
        // Build system prompt if not provided
        val effectiveSystemPrompt = systemPrompt ?: systemPromptBuilder.build(
            SystemPromptBuilder.PromptConfig(
                agentIdentity = agentIdentity,
                tools = toolRegistry.toSummaries(),
                channelContext = channelContext,
                modelId = model,
                provider = provider.id,
            )
        )

        // Load persisted history if available
        var currentMessages = if (sessionPersistence != null && sessionPersistence.exists(sessionKey)) {
            val (_, history) = sessionPersistence.load(sessionKey)
            val combined = history.toMutableList()
            // Add new messages that aren't already in history
            for (msg in messages) {
                if (combined.none { it.content == msg.content && it.role == msg.role }) {
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

            var hasToolUse = false
            val textBuffer = StringBuilder()

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
                        hasToolUse = true
                        emit(AcpRuntimeEvent.ToolCall(
                            text = "Calling ${event.name}",
                            toolCallId = event.id,
                            title = event.name,
                        ))

                        // Check tool policy enforcement first
                        val policyResult = toolPolicyEnforcer?.check(event.name, agentId, sessionKey)
                        val policyAllowed = policyResult?.allowed != false

                        // Check approval before execution
                        val approvalDecision = if (policyAllowed && approvalManager != null) {
                            emit(AcpRuntimeEvent.Status(
                                text = "Checking approval for ${event.name}",
                                tag = "approval_check",
                            ))
                            approvalManager.checkApproval(event.name, event.input, agentId, sessionKey)
                        } else {
                            ApprovalDecision.APPROVED
                        }

                        // Execute tool with context guard (only if policy + approval pass)
                        val tool = toolRegistry.get(event.name)
                        val rawResult = when {
                            !policyAllowed ->
                                "Error: Tool '${event.name}' denied by policy: ${policyResult?.reason}"
                            approvalDecision == ApprovalDecision.DENIED ->
                                "Error: Tool call '${event.name}' was denied by approval policy."
                            approvalDecision == ApprovalDecision.TIMED_OUT ->
                                "Error: Tool call '${event.name}' timed out waiting for approval."
                            tool != null -> {
                                try {
                                    tool.execute(event.input, ToolContext(sessionKey, agentId))
                                } catch (e: Exception) {
                                    "Error: ${e.message}"
                                }
                            }
                            else -> "Error: Unknown tool '${event.name}'"
                        }
                        // Guard tool result size
                        val result = contextGuard.guardToolResult(rawResult, currentMessages)

                        // Add assistant message with tool call and tool result
                        currentMessages.add(LlmMessage(
                            role = LlmMessage.Role.ASSISTANT,
                            content = textBuffer.toString(),
                            toolCalls = listOf(LlmToolCall(event.id, event.name, event.input)),
                        ))
                        currentMessages.add(LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = result,
                            toolCallId = event.id,
                        ))
                        textBuffer.clear()

                        // Persist tool interactions
                        if (sessionPersistence != null) {
                            sessionPersistence.appendMessage(sessionKey, currentMessages[currentMessages.size - 2])
                            sessionPersistence.appendMessage(sessionKey, currentMessages[currentMessages.size - 1])
                        }
                    }
                    is LlmStreamEvent.Usage -> {
                        emit(AcpRuntimeEvent.Status(
                            text = "Tokens: ${event.inputTokens}+${event.outputTokens}",
                            tag = "usage_update",
                            used = event.inputTokens + event.outputTokens,
                        ))
                    }
                    is LlmStreamEvent.Done -> {
                        if (!hasToolUse) {
                            // Persist final assistant response
                            if (sessionPersistence != null && textBuffer.isNotEmpty()) {
                                sessionPersistence.appendMessage(sessionKey, LlmMessage(
                                    role = LlmMessage.Role.ASSISTANT,
                                    content = textBuffer.toString(),
                                ))
                            }
                            emit(AcpRuntimeEvent.Done(stopReason = event.stopReason))
                        }
                    }
                    is LlmStreamEvent.Error -> {
                        emit(AcpRuntimeEvent.Error(
                            message = event.message,
                            code = event.code,
                            retryable = event.retryable,
                        ))
                        aborted = true
                    }
                }
            }

            // If no tool use or error, we're done
            if (!hasToolUse || aborted) return@flow

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
