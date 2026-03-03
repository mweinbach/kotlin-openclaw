package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.*
import ai.openclaw.core.model.*
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.LlmInputEvent
import ai.openclaw.core.plugins.PluginHookAgentContext
import ai.openclaw.core.plugins.PluginHookName
import ai.openclaw.core.plugins.PluginHookRegistration
import ai.openclaw.core.plugins.PluginRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedAcpRuntimeTest {

    /**
     * A fake LLM provider that emits events with controllable delays.
     */
    private class FakeLlmProvider(
        private val delayMs: Long = 0,
        private val response: String = "Hello",
    ) : LlmProvider {
        override val id = "fake"
        val requestedModels = mutableListOf<String>()

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            requestedModels += request.model
            if (delayMs > 0) delay(delayMs)
            emit(LlmStreamEvent.TextDelta(response))
            emit(LlmStreamEvent.Done("end_turn"))
        }

        override fun supportsModel(modelId: String) = true
    }

    private fun createRuntime(
        delayMs: Long = 0,
        response: String = "Hello",
    ): EmbeddedAcpRuntime {
        return EmbeddedAcpRuntime { agentId ->
            AgentRunner(
                provider = FakeLlmProvider(delayMs, response),
            )
        }
    }

    @Test
    fun `ensureSession creates a session`() = runTest {
        val runtime = createRuntime()
        val handle = runtime.ensureSession(AcpRuntimeEnsureInput(
            sessionKey = "test-session",
            agent = "main",
            mode = AcpRuntimeSessionMode.PERSISTENT,
        ))
        assertEquals("test-session", handle.sessionKey)
        assertEquals("embedded", handle.backend)
    }

    @Test
    fun `runTurn emits text and done events`() = runTest {
        val runtime = createRuntime(response = "Test response")
        val handle = runtime.ensureSession(AcpRuntimeEnsureInput(
            sessionKey = "test-session",
            agent = "main",
            mode = AcpRuntimeSessionMode.PERSISTENT,
        ))

        val events = runtime.runTurn(AcpRuntimeTurnInput(
            handle = handle,
            text = "Hello",
            mode = AcpRuntimePromptMode.PROMPT,
            requestId = "req-1",
        )).toList()

        // Should have: status (turn_start), text_delta, done
        assertTrue(events.any { it is AcpRuntimeEvent.TextDelta })
        assertTrue(events.any { it is AcpRuntimeEvent.Done })
        val textEvent = events.filterIsInstance<AcpRuntimeEvent.TextDelta>().first()
        assertEquals("Test response", textEvent.text)
    }

    @Test
    fun `cancel stops in-flight turn`() = runTest {
        val runtime = createRuntime(delayMs = 5000) // Long delay

        val handle = runtime.ensureSession(AcpRuntimeEnsureInput(
            sessionKey = "cancel-test",
            agent = "main",
            mode = AcpRuntimeSessionMode.PERSISTENT,
        ))

        val events = java.util.concurrent.CopyOnWriteArrayList<AcpRuntimeEvent>()
        val collectJob = launch(Dispatchers.Default) {
            runtime.runTurn(AcpRuntimeTurnInput(
                handle = handle,
                text = "Hello",
                mode = AcpRuntimePromptMode.PROMPT,
                requestId = "req-cancel",
            )).collect { events.add(it) }
        }

        // Allow the turn to start - use real time since EmbeddedAcpRuntime uses Dispatchers.IO
        withContext(Dispatchers.Default) { delay(500) }

        // Cancel the session
        runtime.cancel(handle, "user cancelled")

        // Wait for collection to finish
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { collectJob.join() }
        }

        // Should have a Done event with "cancelled" reason
        val doneEvent = events.filterIsInstance<AcpRuntimeEvent.Done>().lastOrNull()
        assertTrue(doneEvent != null, "Expected a Done event after cancellation, got: $events")
        assertEquals("cancelled", doneEvent.stopReason)
    }

    @Test
    fun `getStatus reflects active job`() = runTest {
        val runtime = createRuntime(delayMs = 2000)

        val handle = runtime.ensureSession(AcpRuntimeEnsureInput(
            sessionKey = "status-test",
            agent = "main",
            mode = AcpRuntimeSessionMode.PERSISTENT,
        ))

        // Before running, status should be "active"
        var status = runtime.getStatus(handle)
        assertEquals("active", status.summary)

        // Start a turn in background (use Default dispatcher since runtime uses IO internally)
        val job = launch(Dispatchers.Default) {
            runtime.runTurn(AcpRuntimeTurnInput(
                handle = handle,
                text = "Hello",
                mode = AcpRuntimePromptMode.PROMPT,
                requestId = "req-status",
            )).collect {}
        }

        // Allow turn to start - use real time since runtime uses Dispatchers.IO
        withContext(Dispatchers.Default) { delay(200) }

        // While running, status should be "running"
        status = runtime.getStatus(handle)
        assertEquals("running", status.summary)

        runtime.cancel(handle)
        withContext(Dispatchers.Default) {
            withTimeout(5000) { job.join() }
        }
    }

    @Test
    fun `close removes session and cancels active job`() = runTest {
        val runtime = createRuntime(delayMs = 2000)

        val handle = runtime.ensureSession(AcpRuntimeEnsureInput(
            sessionKey = "close-test",
            agent = "main",
            mode = AcpRuntimeSessionMode.PERSISTENT,
        ))

        val job = launch(Dispatchers.Default) {
            runtime.runTurn(AcpRuntimeTurnInput(
                handle = handle,
                text = "Hello",
                mode = AcpRuntimePromptMode.PROMPT,
                requestId = "req-close",
            )).collect {}
        }

        // Allow turn to start - use real time
        withContext(Dispatchers.Default) { delay(200) }

        runtime.close(handle, "session ended")
        withContext(Dispatchers.Default) {
            withTimeout(5000) { job.join() }
        }

        // After close, status should be "unknown"
        val status = runtime.getStatus(handle)
        assertEquals("unknown", status.summary)
    }

    @Test
    fun `runTurn on nonexistent session emits error`() = runTest {
        val runtime = createRuntime()
        val handle = AcpRuntimeHandle(
            sessionKey = "nonexistent",
            backend = "embedded",
            runtimeSessionName = "nonexistent",
        )

        val events = runtime.runTurn(AcpRuntimeTurnInput(
            handle = handle,
            text = "Hello",
            mode = AcpRuntimePromptMode.PROMPT,
            requestId = "req-noexist",
        )).toList()

        assertTrue(events.any { it is AcpRuntimeEvent.Error })
        val error = events.filterIsInstance<AcpRuntimeEvent.Error>().first()
        assertTrue(error.message.contains("No session"))
    }

    @Test
    fun `ensureSession uses model from env when provided`() = runTest {
        val provider = FakeLlmProvider(response = "ok")
        val runtime = EmbeddedAcpRuntime {
            AgentRunner(provider = provider)
        }

        val handle = runtime.ensureSession(
            AcpRuntimeEnsureInput(
                sessionKey = "env-model-test",
                agent = "main",
                mode = AcpRuntimeSessionMode.PERSISTENT,
                env = mapOf("model" to "openai/gpt-4o-mini"),
            ),
        )

        runtime.runTurn(
            AcpRuntimeTurnInput(
                handle = handle,
                text = "Hello",
                mode = AcpRuntimePromptMode.PROMPT,
                requestId = "req-env-model",
            ),
        ).toList()

        assertTrue(provider.requestedModels.contains("openai/gpt-4o-mini"))
    }

    @Test
    fun `runTurn forwards requestId as hook runId`() = runTest {
        var capturedRunId: String? = null
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (LlmInputEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-llm-input-run-id",
                    hookName = PluginHookName.LLM_INPUT,
                    handler = { event, _ ->
                        capturedRunId = event.runId
                    },
                ),
            )
        }
        val runtime = EmbeddedAcpRuntime {
            AgentRunner(
                provider = FakeLlmProvider(response = "ok"),
                hookRunner = HookRunner(registry),
            )
        }

        val handle = runtime.ensureSession(
            AcpRuntimeEnsureInput(
                sessionKey = "run-id-forwarding",
                agent = "main",
                mode = AcpRuntimeSessionMode.PERSISTENT,
            ),
        )

        runtime.runTurn(
            AcpRuntimeTurnInput(
                handle = handle,
                text = "Hello",
                mode = AcpRuntimePromptMode.PROMPT,
                requestId = "req-forwarded-123",
            ),
        ).toList()

        repeat(50) {
            if (capturedRunId != null) return@repeat
            delay(20)
        }
        assertEquals("req-forwarded-123", capturedRunId)
    }
}
