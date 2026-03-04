package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunnerRetryTimeoutParityTest {
    private class SequencedProvider(
        private val eventsByAttempt: List<List<LlmStreamEvent>>,
    ) : LlmProvider {
        override val id: String = "sequenced"
        var callCount: Int = 0

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            callCount += 1
            val events = eventsByAttempt.getOrElse(callCount - 1) { eventsByAttempt.lastOrNull().orEmpty() }
            for (event in events) {
                emit(event)
            }
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    private class SlowProvider(
        private val delayMs: Long,
    ) : LlmProvider {
        override val id: String = "slow"
        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            delay(delayMs)
            emit(LlmStreamEvent.TextDelta("late"))
            emit(LlmStreamEvent.Done(stopReason = "stop"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    @Test
    fun `runner retries retryable stream errors and succeeds`() = runTest {
        val provider = SequencedProvider(
            eventsByAttempt = listOf(
                listOf(LlmStreamEvent.Error(message = "temporary outage", retryable = true)),
                listOf(
                    LlmStreamEvent.TextDelta("ok"),
                    LlmStreamEvent.Done(stopReason = "stop"),
                ),
            ),
        )
        val runner = AgentRunner(
            provider = provider,
            maxRunAttempts = 2,
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:retry-success",
        ).toList()

        assertEquals(2, provider.callCount)
        assertTrue(events.any { it is AcpRuntimeEvent.Status && it.tag == "turn_retryable_error" })
        assertTrue(events.any { it is AcpRuntimeEvent.TextDelta && it.text == "ok" })
        assertTrue(events.any { it is AcpRuntimeEvent.Done })
        assertTrue(events.none { it is AcpRuntimeEvent.Error })
    }

    @Test
    fun `runner emits retry_limit when retryable errors exceed attempt budget`() = runTest {
        val provider = SequencedProvider(
            eventsByAttempt = listOf(
                listOf(LlmStreamEvent.Error(message = "upstream unavailable", retryable = true)),
                listOf(LlmStreamEvent.Error(message = "upstream unavailable", retryable = true)),
            ),
        )
        val runner = AgentRunner(
            provider = provider,
            maxRunAttempts = 2,
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:retry-limit",
        ).toList()

        val lastError = events.filterIsInstance<AcpRuntimeEvent.Error>().lastOrNull()
        assertTrue(lastError != null, "Expected retry_limit error")
        assertEquals("retry_limit", lastError.code)
        assertTrue(lastError.message.contains("Exceeded retry limit"))
    }

    @Test
    fun `runner emits timeout retry_limit when stream exceeds turn timeout`() = runTest {
        val runner = AgentRunner(
            provider = SlowProvider(delayMs = 250),
            maxRunAttempts = 1,
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:timeout",
            timeoutMs = 50L,
        ).toList()

        val lastError = events.filterIsInstance<AcpRuntimeEvent.Error>().lastOrNull()
        assertTrue(lastError != null, "Expected timeout/retry_limit error")
        assertEquals("retry_limit", lastError.code)
        assertTrue(lastError.message.contains("Turn timed out"))
    }
}
