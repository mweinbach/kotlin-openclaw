package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunnerSessionPersistenceTest {

    private class CapturingProvider(
        private val responseText: String = "ok",
    ) : LlmProvider {
        override val id: String = "fake"
        var lastRequest: LlmRequest? = null

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            lastRequest = request
            emit(LlmStreamEvent.TextDelta(responseText))
            emit(LlmStreamEvent.Done("end_turn"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    @Test
    fun `blank session key does not load persisted history`() = runTest {
        val tempDir = Files.createTempDirectory("agent-runner-session-test")
        try {
            val persistence = SessionPersistence(tempDir.toAbsolutePath().toString())
            persistence.initSession(
                sessionKey = "",
                header = SessionPersistence.SessionHeader(
                    sessionId = "blank",
                    agentId = "main",
                ),
            )
            persistence.appendMessage(
                sessionKey = "",
                message = LlmMessage(role = LlmMessage.Role.USER, content = "persisted-old-message"),
            )

            val provider = CapturingProvider()
            val runner = AgentRunner(
                provider = provider,
                sessionPersistence = persistence,
            )

            runner.runTurn(
                messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "fresh-message")),
                model = "test-model",
                sessionKey = "",
            ).toList()

            val sentContents = provider.lastRequest?.messages?.map { it.content }.orEmpty()
            assertEquals(listOf("fresh-message"), sentContents)
        } finally {
            File(tempDir.toUri()).deleteRecursively()
        }
    }

    @Test
    fun `blank session key does not write assistant messages`() = runTest {
        val tempDir = Files.createTempDirectory("agent-runner-session-write-test")
        try {
            val persistence = SessionPersistence(tempDir.toAbsolutePath().toString())
            val provider = CapturingProvider()
            val runner = AgentRunner(
                provider = provider,
                sessionPersistence = persistence,
            )

            runner.runTurn(
                messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
                model = "test-model",
                sessionKey = "",
            ).toList()

            assertTrue(persistence.listSessionKeys().isEmpty())
        } finally {
            File(tempDir.toUri()).deleteRecursively()
        }
    }
}
