package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionTimeoutParityTest {
    @Test
    fun `should flag compaction timeout only when timeout and compaction state is active`() {
        assertFalse(
            shouldFlagCompactionTimeout(
                CompactionTimeoutSignal(
                    isTimeout = false,
                    isCompactionPendingOrRetrying = true,
                    isCompactionInFlight = true,
                ),
            ),
        )
        assertFalse(
            shouldFlagCompactionTimeout(
                CompactionTimeoutSignal(
                    isTimeout = true,
                    isCompactionPendingOrRetrying = false,
                    isCompactionInFlight = false,
                ),
            ),
        )
        assertTrue(
            shouldFlagCompactionTimeout(
                CompactionTimeoutSignal(
                    isTimeout = true,
                    isCompactionPendingOrRetrying = true,
                    isCompactionInFlight = false,
                ),
            ),
        )
        assertTrue(
            shouldFlagCompactionTimeout(
                CompactionTimeoutSignal(
                    isTimeout = true,
                    isCompactionPendingOrRetrying = false,
                    isCompactionInFlight = true,
                ),
            ),
        )
    }

    @Test
    fun `selects pre compaction snapshot when timeout happened during compaction`() {
        val pre = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "before"))
        val current = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "after"))
        val selected = selectCompactionTimeoutSnapshot(
            CompactionTimeoutSnapshotSelectionParams(
                timedOutDuringCompaction = true,
                preCompactionSnapshot = pre,
                preCompactionSessionId = "pre-session",
                currentSnapshot = current,
                currentSessionId = "current-session",
            ),
        )
        assertEquals(pre, selected.messagesSnapshot)
        assertEquals("pre-session", selected.sessionIdUsed)
        assertEquals(CompactionTimeoutSnapshotSelection.Source.PRE_COMPACTION, selected.source)
    }
}
