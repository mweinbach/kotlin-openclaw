package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage

/**
 * Mirrors OpenClaw compaction-timeout signaling helpers.
 */
data class CompactionTimeoutSignal(
    val isTimeout: Boolean,
    val isCompactionPendingOrRetrying: Boolean,
    val isCompactionInFlight: Boolean,
)

fun shouldFlagCompactionTimeout(signal: CompactionTimeoutSignal): Boolean {
    if (!signal.isTimeout) return false
    return signal.isCompactionPendingOrRetrying || signal.isCompactionInFlight
}

data class CompactionTimeoutSnapshotSelectionParams(
    val timedOutDuringCompaction: Boolean,
    val preCompactionSnapshot: List<LlmMessage>?,
    val preCompactionSessionId: String,
    val currentSnapshot: List<LlmMessage>,
    val currentSessionId: String,
)

data class CompactionTimeoutSnapshotSelection(
    val messagesSnapshot: List<LlmMessage>,
    val sessionIdUsed: String,
    val source: Source,
) {
    enum class Source {
        PRE_COMPACTION,
        CURRENT,
    }
}

fun selectCompactionTimeoutSnapshot(
    params: CompactionTimeoutSnapshotSelectionParams,
): CompactionTimeoutSnapshotSelection {
    if (!params.timedOutDuringCompaction) {
        return CompactionTimeoutSnapshotSelection(
            messagesSnapshot = params.currentSnapshot,
            sessionIdUsed = params.currentSessionId,
            source = CompactionTimeoutSnapshotSelection.Source.CURRENT,
        )
    }

    val preCompaction = params.preCompactionSnapshot
    if (preCompaction != null) {
        return CompactionTimeoutSnapshotSelection(
            messagesSnapshot = preCompaction,
            sessionIdUsed = params.preCompactionSessionId,
            source = CompactionTimeoutSnapshotSelection.Source.PRE_COMPACTION,
        )
    }

    return CompactionTimeoutSnapshotSelection(
        messagesSnapshot = params.currentSnapshot,
        sessionIdUsed = params.currentSessionId,
        source = CompactionTimeoutSnapshotSelection.Source.CURRENT,
    )
}
