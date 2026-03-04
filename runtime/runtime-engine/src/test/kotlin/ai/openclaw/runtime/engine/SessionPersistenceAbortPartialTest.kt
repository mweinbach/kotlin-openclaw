package ai.openclaw.runtime.engine

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPersistenceAbortPartialTest {
    @Test
    fun `append abort assistant partial is idempotent and skips blank text`() {
        val tempDir = Files.createTempDirectory("session-abort-partial-test")
        try {
            val persistence = SessionPersistence(tempDir.toAbsolutePath().toString())
            val sessionKey = "test-session"
            persistence.initSession(
                sessionKey = sessionKey,
                header = SessionPersistence.SessionHeader(
                    sessionId = "s1",
                    agentId = "main",
                ),
            )

            val skipped = persistence.appendAbortAssistantPartial(
                sessionKey = sessionKey,
                runId = "run-1",
                text = "   ",
                origin = "rpc",
            )
            assertEquals(false, skipped)

            val first = persistence.appendAbortAssistantPartial(
                sessionKey = sessionKey,
                runId = "run-1",
                text = "partial output",
                origin = "rpc",
            )
            val second = persistence.appendAbortAssistantPartial(
                sessionKey = sessionKey,
                runId = "run-1",
                text = "partial output",
                origin = "rpc",
            )

            assertEquals(true, first)
            assertEquals(false, second)

            val (_, messages) = persistence.load(sessionKey)
            assertEquals(1, messages.size)
            assertEquals("partial output", messages.single().content)
            assertEquals("stop", messages.single().stopReason)
        } finally {
            File(tempDir.toUri()).deleteRecursively()
        }
    }
}
