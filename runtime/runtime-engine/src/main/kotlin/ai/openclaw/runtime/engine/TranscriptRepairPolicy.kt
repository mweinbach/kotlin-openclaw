package ai.openclaw.runtime.engine

import ai.openclaw.core.model.SessionTranscriptRepairConfig

/**
 * Runtime policy for transcript repair stages.
 */
data class TranscriptRepairPolicy(
    val fileRepairEnabled: Boolean = true,
    val toolCallInputRepairEnabled: Boolean = true,
    val toolResultPairRepairEnabled: Boolean = true,
    val allowSyntheticToolResults: Boolean = true,
) {
    companion object {
        fun fromConfig(config: SessionTranscriptRepairConfig?): TranscriptRepairPolicy {
            return TranscriptRepairPolicy(
                fileRepairEnabled = config?.fileRepairEnabled ?: true,
                toolCallInputRepairEnabled = config?.toolCallInputRepairEnabled ?: true,
                toolResultPairRepairEnabled = config?.toolResultPairRepairEnabled ?: true,
                allowSyntheticToolResults = config?.allowSyntheticToolResults ?: true,
            )
        }
    }
}
