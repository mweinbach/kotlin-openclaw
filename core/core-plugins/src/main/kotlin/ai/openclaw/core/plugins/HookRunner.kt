package ai.openclaw.core.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Logger interface for hook runner output.
 */
interface HookRunnerLogger {
    fun debug(message: String) {}
    fun warn(message: String)
    fun error(message: String)
}

/**
 * Options for configuring the HookRunner.
 */
data class HookRunnerOptions(
    val logger: HookRunnerLogger? = null,
    val catchErrors: Boolean = true,
)

/**
 * Hook Runner for executing plugin lifecycle hooks with proper
 * error handling, priority ordering, and async support.
 *
 * Ported from src/plugins/hooks.ts.
 */
class HookRunner(
    private val registry: PluginRegistry,
    private val options: HookRunnerOptions = HookRunnerOptions(),
) {
    private val logger = options.logger
    private val catchErrors = options.catchErrors

    private fun handleHookError(hookName: PluginHookName, pluginId: String, error: Throwable) {
        val msg = "[hooks] $hookName handler from $pluginId failed: ${error.message}"
        if (catchErrors) {
            logger?.error(msg)
        } else {
            throw RuntimeException(msg, error)
        }
    }

    /**
     * Run a hook that doesn't return a value (fire-and-forget style).
     * All handlers are executed in parallel for performance.
     */
    suspend fun <E, C> runVoidHook(
        hookName: PluginHookName,
        event: E,
        ctx: C,
    ) {
        val hooks = registry.getHooksForName<suspend (E, C) -> Unit>(hookName)
        if (hooks.isEmpty()) return

        logger?.debug("[hooks] running $hookName (${hooks.size} handlers)")

        coroutineScope {
            hooks.map { hook ->
                async {
                    try {
                        hook.handler.invoke(event, ctx)
                    } catch (err: Throwable) {
                        handleHookError(hookName, hook.pluginId, err)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Run a hook that can return a modifying result.
     * Handlers are executed sequentially in priority order, and results are merged.
     */
    suspend fun <E, C, R> runModifyingHook(
        hookName: PluginHookName,
        event: E,
        ctx: C,
        mergeResults: ((accumulated: R?, next: R) -> R)? = null,
    ): R? {
        val hooks = registry.getHooksForName<suspend (E, C) -> R?>(hookName)
        if (hooks.isEmpty()) return null

        logger?.debug("[hooks] running $hookName (${hooks.size} handlers, sequential)")

        var result: R? = null

        for (hook in hooks) {
            try {
                val handlerResult = hook.handler.invoke(event, ctx)

                if (handlerResult != null) {
                    if (mergeResults != null && result != null) {
                        result = mergeResults(result, handlerResult)
                    } else {
                        result = handlerResult
                    }
                }
            } catch (err: Throwable) {
                handleHookError(hookName, hook.pluginId, err)
            }
        }

        return result
    }

    // =========================================================================
    // Agent Hooks
    // =========================================================================

    suspend fun runBeforeModelResolve(
        event: BeforeModelResolveEvent,
        ctx: PluginHookAgentContext,
    ): BeforeModelResolveResult? {
        return runModifyingHook<BeforeModelResolveEvent, PluginHookAgentContext, BeforeModelResolveResult>(
            PluginHookName.BEFORE_MODEL_RESOLVE,
            event,
            ctx,
            mergeResults = { acc: BeforeModelResolveResult?, next: BeforeModelResolveResult ->
                BeforeModelResolveResult(
                    modelOverride = acc?.modelOverride ?: next.modelOverride,
                    providerOverride = acc?.providerOverride ?: next.providerOverride,
                )
            },
        )
    }

    suspend fun runBeforePromptBuild(
        event: BeforePromptBuildEvent,
        ctx: PluginHookAgentContext,
    ): BeforePromptBuildResult? {
        return runModifyingHook<BeforePromptBuildEvent, PluginHookAgentContext, BeforePromptBuildResult>(
            PluginHookName.BEFORE_PROMPT_BUILD,
            event,
            ctx,
            mergeResults = { acc: BeforePromptBuildResult?, next: BeforePromptBuildResult ->
                BeforePromptBuildResult(
                    systemPrompt = next.systemPrompt ?: acc?.systemPrompt,
                    prependContext = when {
                        acc?.prependContext != null && next.prependContext != null ->
                            "${acc.prependContext}\n\n${next.prependContext}"
                        else -> next.prependContext ?: acc?.prependContext
                    },
                )
            },
        )
    }

    suspend fun runBeforeAgentStart(
        event: BeforeAgentStartEvent,
        ctx: PluginHookAgentContext,
    ): BeforeAgentStartResult? {
        return runModifyingHook<BeforeAgentStartEvent, PluginHookAgentContext, BeforeAgentStartResult>(
            PluginHookName.BEFORE_AGENT_START,
            event,
            ctx,
            mergeResults = { acc: BeforeAgentStartResult?, next: BeforeAgentStartResult ->
                BeforeAgentStartResult(
                    systemPrompt = next.systemPrompt ?: acc?.systemPrompt,
                    prependContext = when {
                        acc?.prependContext != null && next.prependContext != null ->
                            "${acc.prependContext}\n\n${next.prependContext}"
                        else -> next.prependContext ?: acc?.prependContext
                    },
                    modelOverride = acc?.modelOverride ?: next.modelOverride,
                    providerOverride = acc?.providerOverride ?: next.providerOverride,
                )
            },
        )
    }

    suspend fun runLlmInput(event: LlmInputEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.LLM_INPUT, event, ctx)
    }

    suspend fun runLlmOutput(event: LlmOutputEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.LLM_OUTPUT, event, ctx)
    }

    suspend fun runAgentEnd(event: AgentEndEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.AGENT_END, event, ctx)
    }

    suspend fun runBeforeCompaction(event: BeforeCompactionEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.BEFORE_COMPACTION, event, ctx)
    }

    suspend fun runAfterCompaction(event: AfterCompactionEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.AFTER_COMPACTION, event, ctx)
    }

    suspend fun runBeforeReset(event: BeforeResetEvent, ctx: PluginHookAgentContext) {
        runVoidHook(PluginHookName.BEFORE_RESET, event, ctx)
    }

    // =========================================================================
    // Message Hooks
    // =========================================================================

    suspend fun runMessageReceived(event: MessageReceivedEvent, ctx: PluginHookMessageContext) {
        runVoidHook(PluginHookName.MESSAGE_RECEIVED, event, ctx)
    }

    suspend fun runMessageSending(
        event: MessageSendingEvent,
        ctx: PluginHookMessageContext,
    ): MessageSendingResult? {
        return runModifyingHook<MessageSendingEvent, PluginHookMessageContext, MessageSendingResult>(
            PluginHookName.MESSAGE_SENDING,
            event,
            ctx,
            mergeResults = { acc: MessageSendingResult?, next: MessageSendingResult ->
                MessageSendingResult(
                    content = next.content ?: acc?.content,
                    cancel = next.cancel ?: acc?.cancel,
                )
            },
        )
    }

    suspend fun runMessageSent(event: MessageSentEvent, ctx: PluginHookMessageContext) {
        runVoidHook(PluginHookName.MESSAGE_SENT, event, ctx)
    }

    // =========================================================================
    // Tool Hooks
    // =========================================================================

    suspend fun runBeforeToolCall(
        event: BeforeToolCallEvent,
        ctx: PluginHookToolContext,
    ): BeforeToolCallResult? {
        return runModifyingHook<BeforeToolCallEvent, PluginHookToolContext, BeforeToolCallResult>(
            PluginHookName.BEFORE_TOOL_CALL,
            event,
            ctx,
            mergeResults = { acc: BeforeToolCallResult?, next: BeforeToolCallResult ->
                BeforeToolCallResult(
                    params = next.params ?: acc?.params,
                    block = next.block ?: acc?.block,
                    blockReason = next.blockReason ?: acc?.blockReason,
                )
            },
        )
    }

    suspend fun runAfterToolCall(event: AfterToolCallEvent, ctx: PluginHookToolContext) {
        runVoidHook(PluginHookName.AFTER_TOOL_CALL, event, ctx)
    }

    suspend fun runToolResultPersist(
        event: ToolResultPersistEvent,
        ctx: PluginHookToolContext,
    ): ToolResultPersistResult? {
        val hooks = registry.getHooksForName<suspend (ToolResultPersistEvent, PluginHookToolContext) -> ToolResultPersistResult?>(
            PluginHookName.TOOL_RESULT_PERSIST,
        )
        if (hooks.isEmpty()) return null

        var currentMessage = event.message
        for (hook in hooks) {
            try {
                val result = hook.handler.invoke(
                    event.copy(message = currentMessage),
                    ctx,
                )
                if (result?.message != null) {
                    currentMessage = result.message
                }
            } catch (err: Throwable) {
                handleHookError(PluginHookName.TOOL_RESULT_PERSIST, hook.pluginId, err)
            }
        }

        return ToolResultPersistResult(message = currentMessage)
    }

    // =========================================================================
    // Session Hooks
    // =========================================================================

    suspend fun runSessionStart(event: SessionStartEvent, ctx: PluginHookSessionContext) {
        runVoidHook(PluginHookName.SESSION_START, event, ctx)
    }

    suspend fun runSessionEnd(event: SessionEndEvent, ctx: PluginHookSessionContext) {
        runVoidHook(PluginHookName.SESSION_END, event, ctx)
    }

    // =========================================================================
    // Gateway Hooks
    // =========================================================================

    suspend fun runGatewayStart(event: GatewayStartEvent, ctx: PluginHookGatewayContext) {
        runVoidHook(PluginHookName.GATEWAY_START, event, ctx)
    }

    suspend fun runGatewayStop(event: GatewayStopEvent, ctx: PluginHookGatewayContext) {
        runVoidHook(PluginHookName.GATEWAY_STOP, event, ctx)
    }

    // =========================================================================
    // Utility
    // =========================================================================

    fun hasHooks(hookName: PluginHookName): Boolean = registry.hasHooks(hookName)

    fun getHookCount(hookName: PluginHookName): Int = registry.getHookCount(hookName)
}
